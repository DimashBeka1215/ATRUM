package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Обмен профилями через зашифрованный JSON в profiles.txt в gist'е чата.
 *
 * Формат:
 *   { userId: { name, avatar, updatedAt, lastReadIndex } }
 *
 * Конфликты разрешаются по updatedAt — свежее побеждает.
 *
 * Защита от "застрявшего своего профиля":
 *   если пользователь когда-то сбросил Account и получил новый myUserId,
 *   старый профиль может остаться в gist'е. findPartner отбрасывает профили
 *   с моим текущим именем, чтобы старый "я" не маскировался под собеседника.
 *   pushMyProfile дополнительно вычищает таких "клонов" из gist'а.
 */
object ProfileSync {

    private const val FILE_NAME = "profiles.txt"

    /**
     * Мьютекс для сериализации всех write-операций с profiles.txt.
     *
     * pushPresence и pushMyProfile оба делают read-modify-write: GET → изменение → PATCH.
     * Без сериализации они могут перезаписать изменения друг друга (lost update):
     *   - pushPresence читает {A: ts_old, B: typing} → пишет {A: ts_new, B: typing}
     *   - pushMyProfile читает {A: ts_old, B: typing} (до PATCH выше) → пишет {A: ts_old!, B: read}
     * Мьютекс гарантирует что только один write идёт в каждый момент.
     *
     * Примечание: мьютекс локален устройству. Cross-device race (два телефона одновременно)
     * по-прежнему возможен, но следующий тик (каждые 4с) восстановит актуальное состояние.
     */
    private val profilesMutex = Mutex()

    /**
     * Кэш «известных участников» на процесс: chatId → (userId → Profile).
     * Через нестабильный Tor чтение profiles.txt может вернуть пусто/без партнёра,
     * и тогда read-modify-write затёр бы профиль собеседника. Поэтому при записи
     * объединяем прочитанное с этим кэшем — однажды увиденный участник не теряется.
     */
    private val known = ConcurrentHashMap<String, MutableMap<String, Profile>>()

    /** Возвращает (кэш ∪ read), где read свежее (выигрывает по ключам, что в нём есть). */
    private fun unionWithKnown(chatId: String, read: Map<String, Profile>): MutableMap<String, Profile> {
        val result = LinkedHashMap<String, Profile>()
        known[chatId]?.let { result.putAll(it) }   // ранее виденные (в т.ч. партнёр)
        // Монотонное слияние: поля профиля (имя/аватар/ключи) берём по большему updatedAt,
        // чтобы УСТАРЕВШИЙ опрос (реле отдало старую копию слота при флаки-Tor) НЕ откатывал
        // уже показанный свежий аватар. Presence — из свежего чтения (быстрый «не в сети»),
        // lastReadIndex — монотонно (галочки не едут назад).
        for ((uid, p) in read) {
            val cur = result[uid]
            if (cur == null) { result[uid] = p; continue }
            val profileBase = if (p.updatedAt >= cur.updatedAt) p else cur
            result[uid] = profileBase.copy(
                onlineTs      = p.onlineTs,
                typingTs      = p.typingTs,
                recordingTs   = p.recordingTs,
                lastReadIndex = maxOf(cur.lastReadIndex, p.lastReadIndex)
            )
        }
        return result
    }

    /** Запоминает финальное состояние карты как «известное» для chatId. */
    private fun rememberKnown(chatId: String, map: Map<String, Profile>) {
        known[chatId] = LinkedHashMap(map)
    }

    /**
     * Для ЧТЕНИЯ/отображения: возвращает union(известные ∪ parsed) и пополняет кэш.
     * Делает партнёра «липким» — если отдельное чтение profiles.txt на миг вернуло
     * только мой профиль (гонка перезаписи replaceable-файла / флаки-Tor), партнёр
     * (его ава/ник и эфемерный ключ для forward secrecy) НЕ теряется. На запись это
     * не влияет — пишущие пути используют свой сырой снимок.
     */
    fun unionAndRemember(chatId: String, parsed: Map<String, Profile>): Map<String, Profile> {
        val merged = unionWithKnown(chatId, parsed)
        if (parsed.isNotEmpty()) rememberKnown(chatId, merged)
        return merged
    }

    suspend fun pullProfiles(api: ChatTransport, password: String): Map<String, Profile> {
        val rawEncrypted = api.loadFileOrNull(FILE_NAME)?.trim() ?: return emptyMap()
        if (rawEncrypted.isEmpty()) return emptyMap()
        val parsed = parseProfiles(rawEncrypted, password, api.chatId)
        // Пополняем кэш известных участников всем, что реально прочитали с реле.
        if (parsed.isNotEmpty()) {
            val merged = unionWithKnown(api.chatId, parsed)
            rememberKnown(api.chatId, merged)
        }
        return parsed
    }

    /**
     * Дешифрует и парсит уже загруженный зашифрованный контент profiles.txt.
     * Используется в doRefreshPartnerReadIndex когда сырой контент уже есть
     * (загружен для hash-проверки) — избегаем повторного сетевого запроса.
     */
    /**
     * UNION-чтение (Фаза 1): объединяет ВСЕ слоты profiles.txt (по одному событию на
     * участника). Для каждого uid берёт запись с наибольшим updatedAt (имя/аватар/ключи),
     * а presence-таймстампы — максимумом по слотам. Убирает lost-update: свежая правка
     * одного участника физически не может быть затёрта устаревшей копией из чужого слота.
     * Обратносовместимо: старый общий блоб — это просто слот с несколькими uid.
     */
    fun unionProfileSlots(slots: List<String>, password: String, chatId: String): Map<String, Profile> {
        val best = LinkedHashMap<String, Profile>()
        for (slotEnc in slots) {
            val parsed = parseProfiles(slotEnc, password, chatId)
            for ((uid, p) in parsed) {
                val cur = best[uid]
                if (cur == null) { best[uid] = p; continue }
                val base = if (p.updatedAt >= cur.updatedAt) p else cur
                best[uid] = base.copy(
                    onlineTs      = maxOf(cur.onlineTs, p.onlineTs),
                    typingTs      = maxOf(cur.typingTs, p.typingTs),
                    recordingTs   = maxOf(cur.recordingTs, p.recordingTs),
                    lastReadIndex = maxOf(cur.lastReadIndex, p.lastReadIndex)
                )
            }
        }
        return best
    }

    fun parseProfiles(rawEncrypted: String, password: String, chatId: String): Map<String, Profile> {
        if (rawEncrypted.isBlank()) return emptyMap()
        val decrypted = CryptoHelper.decrypt(rawEncrypted, password, chatId) ?: return emptyMap()
        return try {
            val json = JSONObject(decrypted)
            val result = mutableMapOf<String, Profile>()
            for (key in json.keys()) {
                val obj = json.optJSONObject(key) ?: continue
                result[key] = Profile.fromJsonObject(key, obj)
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Пушит мой профиль в gist, заодно подчищая старых "клонов меня"
     * (профили с моим именем но другим userId) — это лечит проблему когда
     * после Reset Account старый профиль остаётся в gist.
     */
    suspend fun pushMyProfile(
        api: ChatTransport,
        password: String,
        myProfile: Profile
    ): Boolean = profilesMutex.withLock {
        try {
        val existing = unionWithKnown(api.chatId, pullProfiles(api, password))

        // Удалить все "старые я" — профили с моим именем но не моим userId.
        // Имя не пустое — пустые имена не считаем "моим клоном".
        if (myProfile.name.isNotBlank()) {
            val staleKeys = existing.filterKeys { uid ->
                uid != myProfile.userId &&
                    existing[uid]?.name?.equals(myProfile.name, ignoreCase = false) == true
            }.keys
            staleKeys.forEach { existing.remove(it) }
        }

        // Записать мой профиль
        existing[myProfile.userId] = myProfile
        rememberKnown(api.chatId, existing) // не теряем партнёра при будущих флаки-чтениях

        val json = JSONObject().apply {
            for ((userId, profile) in existing) {
                put(userId, profile.toJsonObject())
            }
        }

        val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
        api.saveFile(FILE_NAME, encrypted)
        true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Обновляет typingTs И onlineTs одним PATCH-запросом.
     *
     * Делаем свежий pull перед записью — это критически важно для корректности ephemeralPubKey:
     * стейл-кэш мог содержать устаревшую (или пустую) копию профиля партнёра и затирал бы
     * его ephemeralPubKey каждые 3 сек, из-за чего партнёр никогда не мог установить V3-сессию.
     *
     * Да, это +1 GET каждые 3 сек, но корректность > оптимизация.
     * Лимит: 60 вызовов/мин (40 до + 20 добавили) при квоте 5000/ч — безопасно.
     *
     * myName / myAvatarBase64 / myEphemeralPubKey — используются как fallback когда нашего
     * профиля ещё нет в gist (например, стартовый тик до первого syncProfiles). Без этого
     * первый вызов записывал Profile с пустым именем и партнёр терял наше имя/аватар.
     */
    suspend fun pushPresence(
        api: ChatTransport,
        password: String,
        myUserId: String,
        typingTs: Long,
        onlineTs: Long,
        recordingTs: Long = 0L,
        myEphemeralPubKey: String? = null,
        myName: String = "",
        myTag: String? = null,
        myAvatarBase64: String? = null,
        myIdentityPubKey: String? = null,
        myEphemeralSig: String? = null,
        myVerifiedPartnerIdk: String? = null
    ): Boolean = profilesMutex.withLock {
        try {
            val existing = unionWithKnown(api.chatId, pullProfiles(api, password))
            // Берём наш профиль из gist; если его там нет — создаём с реальными данными,
            // а не с пустым именем. Так же восстанавливаем имя если оно там было пустым.
            val gist = existing[myUserId]
            val base = gist?.copy(
                name         = gist.name.ifBlank { myName },
                tag          = gist.tag ?: myTag,
                avatarBase64 = gist.avatarBase64 ?: myAvatarBase64
            ) ?: Profile(userId = myUserId, name = myName, tag = myTag, avatarBase64 = myAvatarBase64)

            existing[myUserId] = base.copy(
                typingTs           = typingTs,
                onlineTs           = onlineTs,
                recordingTs        = recordingTs,
                ephemeralPubKey    = myEphemeralPubKey ?: base.ephemeralPubKey,
                // identity-поля всегда заново вставляем — чтобы presence-пуш их не терял
                identityPubKey     = myIdentityPubKey ?: base.identityPubKey,
                ephemeralSig       = myEphemeralSig ?: base.ephemeralSig,
                verifiedPartnerIdk = myVerifiedPartnerIdk ?: base.verifiedPartnerIdk
            )
            rememberKnown(api.chatId, existing) // не теряем партнёра при флаки-чтениях
            val json = JSONObject().apply {
                for ((uid, p) in existing) put(uid, p.toJsonObject())
            }
            val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
            api.saveFile(FILE_NAME, encrypted)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Обновляет presence (typingTs + onlineTs) БЕЗ GET-запроса.
     *
     * Использует переданный кэш как базу. Партнёрские данные (включая ephemeralPubKey)
     * остаются нетронутыми — записываем весь снимок профилей обратно с обновлёнными
     * полями только своего профиля.
     *
     * @return true если PATCH отправлен успешно,
     *         false если нашего профиля нет в кэше (нужен fallback на полный GET+PATCH).
     */
    suspend fun pushPresenceWriteOnly(
        api: ChatTransport,
        password: String,
        cachedProfiles: Map<String, Profile>,
        myUserId: String,
        typingTs: Long,
        onlineTs: Long,
        myEphemeralPubKey: String? = null,
        myIdentityPubKey: String? = null,
        myEphemeralSig: String? = null,
        myVerifiedPartnerIdk: String? = null
    ): Boolean {
        val myProfile = cachedProfiles[myUserId] ?: return false
        return try {
            val updated = cachedProfiles.toMutableMap()
            updated[myUserId] = myProfile.copy(
                typingTs           = typingTs,
                onlineTs           = onlineTs,
                ephemeralPubKey    = myEphemeralPubKey ?: myProfile.ephemeralPubKey,
                // identity-поля всегда заново вставляем — иначе write-only пуш из
                // устаревшего кэша затирает identityPubKey, и партнёр не видит щит.
                identityPubKey     = myIdentityPubKey ?: myProfile.identityPubKey,
                ephemeralSig       = myEphemeralSig ?: myProfile.ephemeralSig,
                verifiedPartnerIdk = myVerifiedPartnerIdk ?: myProfile.verifiedPartnerIdk
            )
            val json = JSONObject().apply {
                for ((uid, p) in updated) put(uid, p.toJsonObject())
            }
            val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
            api.saveFile(FILE_NAME, encrypted)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Алиас для совместимости — делегирует в [pushPresence] с полным GET+PATCH.
     *
     * Кэш-версия (write-only на основе снимка) была удалена: она записывала устаревшие
     * данные партнёра обратно в gist, из-за чего onlineTs партнёра «зависал» даже после
     * того как собеседник покинул чат. Любая запись profiles.txt должна основываться на
     * свежем чтении (GET), иначе мы перезаписываем изменения партнёра.
     *
     * Доп. расход: +1 GET/3 сек = +20 GET/мин. При квоте 5000/ч (83/мин) и суммарных
     * ~60 GET/мин (poll профилей + сообщения + sync) — хорошо в пределах лимита.
     */
    suspend fun pushPresenceCached(
        api: ChatTransport,
        password: String,
        cachedProfiles: Map<String, Profile>,   // больше не используется для записи
        myUserId: String,
        typingTs: Long,
        onlineTs: Long,
        myEphemeralPubKey: String? = null,
        myName: String = "",
        myTag: String? = null,
        myAvatarBase64: String? = null
    ): Boolean = pushPresence(
        api               = api,
        password          = password,
        myUserId          = myUserId,
        typingTs          = typingTs,
        onlineTs          = onlineTs,
        myEphemeralPubKey = myEphemeralPubKey,
        myName            = myName,
        myTag             = myTag,
        myAvatarBase64    = myAvatarBase64
    )

    /**
     * Обновляет typingTs в profiles.txt — write-only на основе кэша Activity.
     *
     * Используем кэш (последний успешно прочитанный снимок профилей) вместо
     * read-modify-write, чтобы обойтись одним PATCH вместо двух (GET + PATCH).
     * Это снижает задержку typing-индикатора с ~1.5 сек до ~0.5 сек.
     *
     * Кэш обновляется каждые ~1.5 сек в doRefreshPartnerReadIndex, поэтому
     * максимальное «протухание» данных партнёра — 1.5 сек. При этом pushMyProfile
     * (read receipts) всегда делает read-modify-write, что гарантирует корректность
     * галочек прочтения.
     *
     * @param cachedProfiles  последний снимок из doRefreshPartnerReadIndex
     * @param typingTs        текущий timestamp или 0 (перестал печатать)
     */
    suspend fun pushTypingTs(
        api: ChatTransport,
        password: String,
        cachedProfiles: Map<String, Profile>,
        myUserId: String,
        typingTs: Long
    ): Boolean {
        val myProfile = cachedProfiles[myUserId] ?: return false
        return try {
            val updated = cachedProfiles.toMutableMap()
            updated[myUserId] = myProfile.copy(typingTs = typingTs)
            val json = JSONObject().apply {
                for ((uid, p) in updated) put(uid, p.toJsonObject())
            }
            val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
            api.saveFile(FILE_NAME, encrypted)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Обновляет onlineTs в profiles.txt — write-only на основе кэша Activity.
     * Та же логика что и pushTypingTs: 1 PATCH вместо GET+PATCH для скорости.
     *
     * @param cachedProfiles  последний снимок из doRefreshPartnerReadIndex
     * @param onlineTs        текущий timestamp или 0 (ушли в фон)
     */
    suspend fun pushOnlineTs(
        api: ChatTransport,
        password: String,
        cachedProfiles: Map<String, Profile>,
        myUserId: String,
        onlineTs: Long
    ): Boolean {
        val myProfile = cachedProfiles[myUserId] ?: return false
        return try {
            val updated = cachedProfiles.toMutableMap()
            updated[myUserId] = myProfile.copy(onlineTs = onlineTs)
            val json = JSONObject().apply {
                for ((uid, p) in updated) put(uid, p.toJsonObject())
            }
            val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
            api.saveFile(FILE_NAME, encrypted)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Помечает наш профиль как удалённый (deleted=true) в gist.
     * Read-modify-write — профиль партнёра сохраняется нетронутым.
     * Вызывается перед полным сбросом аккаунта: собеседник увидит заглушку.
     */
    suspend fun pushDeletedMarker(
        api: ChatTransport,
        password: String,
        myUserId: String
    ): Boolean = try {
        val existing = pullProfiles(api, password).toMutableMap()
        val old = existing[myUserId]
        // Сохраняем имя/аватар — собеседник должен видеть ЧЬЕЙ профиль удалён.
        // Presence-поля (typingTs, onlineTs, lastReadIndex) обнуляем — не нужны.
        existing[myUserId] = Profile(
            userId = myUserId,
            name = old?.name ?: "",
            avatarBase64 = old?.avatarBase64,
            updatedAt = System.currentTimeMillis(),
            deleted = true
        )
        val json = JSONObject().apply {
            for ((uid, profile) in existing) put(uid, profile.toJsonObject())
        }
        val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
        api.saveFile(FILE_NAME, encrypted)
        true
    } catch (_: Exception) {
        false
    }

    /**
     * Возвращает партнёра (не меня).
     *  - Отбрасываем профили с моим userId
     *  - Отбрасываем "клонов" с моим именем (старые я после Reset)
     *  - Если осталось несколько — берём самого свежего по updatedAt
     */
    fun findPartner(
        profiles: Map<String, Profile>,
        myUserId: String,
        myName: String = ""
    ): Profile? {
        val others = profiles.values.filter { it.userId != myUserId }
        if (others.isEmpty()) return null
        // Отбрасываем "клонов меня" (моё имя, чужой userId) — но если кроме них
        // никого нет, значит это легитимный партнёр с таким же именем: берём его.
        val nonClones = if (myName.isBlank()) others else others.filter { it.name != myName }
        return (if (nonClones.isNotEmpty()) nonClones else others).maxByOrNull { it.updatedAt }
    }
}
