package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Обмен профилями через зашифрованный JSON в profiles.txt в источнике чата.
 *
 * Формат:
 *   { userId: { name, avatar, updatedAt, lastReadIndex } }
 *
 * Конфликты разрешаются по updatedAt — свежее побеждает.
 *
 * Защита от "застрявшего своего профиля":
 *   если пользователь когда-то сбросил Account и получил новый myUserId,
 *   старый профиль может остаться в источнике. findPartner отбрасывает профили
 *   с моим текущим именем, чтобы старый "я" не маскировался под собеседника.
 *   pushMyProfile дополнительно вычищает таких "клонов" из источника.
 */
object ProfileSync {

    private const val FILE_NAME = "profiles.txt"

    /**
     * Ограниченный по параллелизму дочерний диспетчер поверх Dispatchers.Default — только
     * для параллельной расшифровки слотов profiles.txt в [unionProfileSlots].
     *
     * ⚠️ Фикс (репорт: «сообщения-текст стали дольше висеть в отправке» — регрессия от
     * первой версии этой оптимизации). Отправка текста тоже шифруется на Dispatchers.Default
     * (см. ChatActivity.sendMessage → withContext(Dispatchers.Default) { encryptChatLine }),
     * и для ГРУППОВЫХ чатов это ТЯЖЁЛЫЙ Argon2id (encryptGroupMessage). Если одновременно с
     * отправкой сообщения активная группа из N участников гоняла N параллельных Argon2id
     * расшифровок profiles.txt на ТОМ ЖЕ пуле потоков (Dispatchers.Default размером с число
     * ядер CPU) — шифрование сообщения вставало в очередь позади них, и «часики» висели
     * дольше. Ограничение до 2 одновременных слотов оставляет пулу свободные потоки для
     * шифрования сообщений почти при любом числе ядер, сохраняя при этом выигрыш от
     * параллелизма (в разы быстрее строго последовательного варианта на больших группах).
     */
    private val profileSlotDecryptDispatcher = Dispatchers.Default.limitedParallelism(2)

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

    /**
     * Последнее исключение, проглоченное [pushMyProfile]/[pushPresence] (их catch-блоки
     * возвращают false вместо проброса — большинство вызывающих мест использует результат
     * просто как Boolean, менять сигнатуру ради этого не стали). ТОЛЬКО для диагностики
     * (см. TorSyncWatchdog.kt — иначе «pushMyProfile вернул false» ничего не говорит о
     * РЕАЛЬНОЙ причине). Безопасно читать сразу после `false`-результата: обе функции
     * держат один и тот же [profilesMutex] на всё тело try/catch, так что запись сюда и
     * возврат false происходят под одним и тем же логическим удержанием лока — гонки с
     * другим одновременным push той же функции нет.
     */
    @Volatile var lastError: Throwable? = null
        private set

    /** Возвращает (кэш ∪ read), где read свежее (выигрывает по ключам, что в нём есть). */
    private fun unionWithKnown(chatId: String, read: Map<String, Profile>): MutableMap<String, Profile> =
        mergeReadOverKnown(known[chatId], read)

    /**
     * Чистая часть [unionWithKnown] — слияние прочитанного поверх ранее известного.
     *
     * Вынесено отдельно и помечено `internal` РАДИ ЮНИТ-ТЕСТОВ: сам [unionWithKnown] завязан на
     * глобальный кэш [known], а эта функция — чистая (вход → выход), её можно проверять без
     * Android, сети и криптографии. Логика при выносе не менялась — только перемещена.
     * См. ProfileSyncMergeTest.
     */
    internal fun mergeReadOverKnown(
        knownForChat: Map<String, Profile>?,
        read: Map<String, Profile>
    ): MutableMap<String, Profile> {
        val result = LinkedHashMap<String, Profile>()
        knownForChat?.let { result.putAll(it) }   // ранее виденные (в т.ч. партнёр)
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
                lastReadIndex = maxOf(cur.lastReadIndex, p.lastReadIndex),
                // ⚠️ Аватар/имя «липкие» (репорт: «ава пропадает, если участник не в сети»).
                // При РАВНОМ updatedAt побеждает свежее чтение (>=); если оффлайн-участник
                // пришёл из чужого блоба БЕЗ авы, известная ава затиралась. Не даём копии
                // без авы/имени перетереть уже известную — берём непустое из любой стороны.
                avatarBase64  = profileBase.avatarBase64 ?: cur.avatarBase64 ?: p.avatarBase64,
                name          = profileBase.name.ifBlank { cur.name.ifBlank { p.name } },
                // ⚠️ Identity/подписи ТОЖЕ «липкие» (репорт: «галочка пропадает, когда я не
                // в сети»). Галочка считается по identitySig/identityPubKey; частичный/
                // устаревший слот с updatedAt>=known, но БЕЗ подписи, обнулял её на тик —
                // тот же класс бага, что был у авы. Не даём копии без подписи перетереть
                // уже известную. БЕЗОПАСНО: подлинность всё равно перепроверяется при
                // отрисовке (VerifiedBadge.isVerifiedProfile сверяет подпись каждый раз),
                // так что «залипает» лишь ГЕНУИННАЯ подпись — подделать нельзя (§1).
                // ephemeral* тоже липкие — не терять ключ FS/1:1-галочку из slim-чтения
                // (ротация выключена, поэтому свежий непустой ключ всегда побеждает через ?:).
                identityPubKey  = profileBase.identityPubKey  ?: cur.identityPubKey  ?: p.identityPubKey,
                identitySig     = profileBase.identitySig     ?: cur.identitySig     ?: p.identitySig,
                ephemeralPubKey = profileBase.ephemeralPubKey ?: cur.ephemeralPubKey ?: p.ephemeralPubKey,
                ephemeralSig    = profileBase.ephemeralSig    ?: cur.ephemeralSig    ?: p.ephemeralSig
            )
        }
        return result
    }

    /** Запоминает финальное состояние карты как «известное» для chatId. */
    private fun rememberKnown(chatId: String, map: Map<String, Profile>) {
        known[chatId] = LinkedHashMap(map)
    }

    /**
     * МЕЖЧАТОВЫЙ (глобальный) кэш «последний известный профиль» по userId — БЕЗ привязки
     * к конкретному чату. `Prefs.myUserId` — это один и тот же UUID для одного и того же
     * человека во ВСЕХ чатах (личных и групповых), поэтому если имя/аватар этого userId уже
     * увидены в одном чате (например, в личной переписке), их можно использовать как
     * fallback в другом чате (например, в группе), пока СВОЙ profiles.txt этой группы ещё
     * не догрузился — не нужно ждать отдельной синхронизации с нуля для каждого чата.
     *
     * ТОЛЬКО fallback для отображения (имя/тег/аватар) — presence (onlineTs/typingTs/
     * recordingTs/lastReadIndex) сюда не подмешивается и не читается из этого кэша: онлайн-
     * статус легитимно относится к конкретному чату/сессии и не должен «одалживаться» из
     * другого чата (иначе человек будет ошибочно показан «в сети», если он активен в другом
     * чате, но не открывал этот).
     */
    private val globalKnownProfiles = ConcurrentHashMap<String, Profile>()

    /** Пополняет глобальный кэш всем, что реально прочитано (по любому чату), по большему updatedAt. */
    private fun rememberGlobal(profiles: Map<String, Profile>) {
        for ((uid, p) in profiles) {
            if (p.name.isBlank() && p.avatarBase64.isNullOrBlank()) continue // нечего запоминать
            val existing = globalKnownProfiles[uid]
            if (existing == null) {
                globalKnownProfiles[uid] = p
            } else if (p.updatedAt >= existing.updatedAt) {
                // ⚠️ ФИКС (репорт: «ава человека исчезает в чате, и в беседах, и в 1:1»):
                // раньше более свежий профиль ЦЕЛИКОМ заменял известный. parseProfiles зовёт
                // rememberGlobal с СЫРЫМ снимком блоба — если блоб пришёл с именем, но БЕЗ
                // аватара (presence/частичный слот/флаки-Tor) и updatedAt новее, глобальный
                // кэш терял аватар → getGlobalKnown().avatarBase64 = null → аватарка отправителя
                // в чате пропадала (default-путь refreshMessageAvatars на onCreate/onResume).
                // Делаем поля «липкими» на уровне поля, как уже сделано в unionWithKnown: не
                // понижаем уже известные аватар/имя/тег до пустого, если новый пришёл без них.
                globalKnownProfiles[uid] = p.copy(
                    avatarBase64 = p.avatarBase64?.takeIf { it.isNotBlank() } ?: existing.avatarBase64,
                    name = p.name.ifBlank { existing.name },
                    tag = p.tag?.takeIf { it.isNotBlank() } ?: existing.tag
                )
            }
        }
    }

    /**
     * Публичный доступ к межчатовому fallback-профилю по userId (см. [globalKnownProfiles]).
     * Использовать ТОЛЬКО для имени/тега/аватара — presence из результата не читать.
     */
    fun getGlobalKnown(userId: String): Profile? = globalKnownProfiles[userId]

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

    /**
     * Читает профили чата.
     *
     * ⚠️ ФИКС (§16, аудит синка профилей): раньше здесь стоял `api.loadFileOrNull(FILE_NAME)` —
     * ОДИН, самый свежий слот («последний записавший выигрывает»). Все читающие экраны давно
     * переведены на унию слотов (ChatActivity/ChatsListActivity/PartnerProfileActivity), а вот
     * сам pullProfiles остался на одном блобе — и именно на нём построена ЗАПИСЬ:
     * [pushMyProfile] и [pushPresence] берут отсюда карту участников, дописывают себя и
     * публикуют её целиком в свой слот. То есть мой слот мог нести устаревшие копии чужих
     * профилей, а в беседе — вовсе потерять участника, чей профиль в прочитанный блоб не попал.
     *
     * Теперь читаем ВСЕ слоты и сливаем их так же, как читающая сторона. Дополнительного
     * трафика это не создаёт: [ChatTransport.loadFileSlots] выполняет ТОТ ЖЕ запрос, что и
     * loadFileOrNull, и просто не выбрасывает лишние события (см. NostrTransport).
     * Обратная совместимость (§17): старый общий блоб — это просто слот с несколькими uid.
     */
    suspend fun pullProfiles(api: ChatTransport, password: String): Map<String, Profile> {
        val slots = api.loadFileSlots(FILE_NAME)
        if (slots.isEmpty()) return emptyMap()
        val parsed = if (slots.size == 1) {
            parseProfiles(slots[0].trim(), password, api.chatId)
        } else {
            unionProfileSlots(slots, password, api.chatId)
        }
        // Пополняем кэш известных участников всем, что реально прочитали с реле.
        if (parsed.isNotEmpty()) {
            val merged = unionWithKnown(api.chatId, parsed)
            rememberKnown(api.chatId, merged)
        }
        return parsed
    }

    /**
     * UNION-чтение (Фаза 1): объединяет ВСЕ слоты profiles.txt (по одному событию на
     * участника). Для каждого uid берёт запись с наибольшим updatedAt (имя/аватар/ключи),
     * а presence-таймстампы — максимумом по слотам. Убирает lost-update: свежая правка
     * одного участника физически не может быть затёрта устаревшей копией из чужого слота.
     * Обратносовместимо: старый общий блоб — это просто слот с несколькими uid.
     *
     * ⚠️ Оптимизация (репорт §16: «групповые чаты грузятся долго»): расшифровка КАЖДОГО
     * слота — тяжёлый Argon2id, а слотов у группы столько же, сколько участников. Раньше
     * цикл шёл строго последовательно на вызывающем потоке (в ChatActivity — это основной
     * поток UI!), поэтому активная группа из N участников на КАЖДЫЙ тик, где меняется
     * presence (у любого из них — раз в ~5с), платила N последовательных тяжёлых
     * расшифровок ПОДРЯД, включая блокировку главного потока. Теперь независимые слоты
     * расшифровываются ПАРАЛЛЕЛЬНО (см. [profileSlotDecryptDispatcher] — ограничено 2
     * одновременными, не весь Dispatchers.Default, иначе конкурирует с шифрованием
     * отправляемых сообщений на том же пуле, см. докстринг диспетчера). Сам мердж —
     * дешёвое сравнение полей — остаётся последовательным ПОСЛЕ того, как все расшифровки
     * завершились, никакой гонки на общем состоянии. Результат идентичен прежнему.
     */
    /**
     * Кэш разбора ОДНОГО слота: ключ — чат + отпечаток шифртекста, значение — уже разобранные
     * профили этого слота.
     *
     * ⚠️ Добавлено по репорту «синк стал очень долгим». unionProfileSlots вызывается и на каждом
     * тике опроса, и на КАЖДОМ presence-пуше (раз в ~5 секунд, через pullProfiles), а слотов
     * столько же, сколько участников. При этом реально меняется обычно один слот — тот, чей
     * владелец обновил presence; остальные разбирались заново впустую. Теперь неизменившийся
     * слот берётся из памяти.
     *
     * Размер намеренно маленький: в значениях лежат аватары в base64, и большой кэш съел бы
     * заметную память. Восьми записей хватает на 1:1 и небольшие беседы, а для крупных групп
     * это просто ограничивает выигрыш, но ничего не ломает.
     */
    private const val SLOT_PARSE_CACHE_MAX = 8
    private val slotParseCache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Map<String, Profile>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Profile>>) =
                size > SLOT_PARSE_CACHE_MAX
        }
    )

    /** Отпечаток слота: длина + хэш строки. String.hashCode в JVM кэшируется, поэтому дёшево. */
    private fun slotCacheKey(chatId: String, slotEnc: String) =
        chatId + '#' + slotEnc.length + '#' + slotEnc.hashCode()

    suspend fun unionProfileSlots(slots: List<String>, password: String, chatId: String): Map<String, Profile> = coroutineScope {
        val parsedPerSlot = slots.map { slotEnc ->
            val key = slotCacheKey(chatId, slotEnc)
            val cached = slotParseCache[key]
            if (cached != null) {
                kotlinx.coroutines.CompletableDeferred(cached)
            } else {
                async(profileSlotDecryptDispatcher) {
                    parseProfiles(slotEnc, password, chatId).also { parsed ->
                        if (parsed.isNotEmpty()) slotParseCache[key] = parsed
                    }
                }
            }
        }.awaitAll()
        mergeParsedSlots(parsedPerSlot)
    }

    /**
     * Чистая часть [unionProfileSlots] — слияние УЖЕ расшифрованных слотов.
     *
     * Вынесено отдельно и помечено `internal` РАДИ ЮНИТ-ТЕСТОВ: расшифровка требует Android и
     * реального ключа, а само слияние — чистая логика, и именно в ней исторически заводились
     * баги (липкость авы/имени/подписей, тай-брейк по updatedAt). Логика при выносе не
     * менялась — только перемещена. См. ProfileSyncMergeTest.
     *
     * ⚠️ ПОРЯДОК ВАЖЕН: ожидается, что слоты идут по created_at УБЫВАЮЩЕ (как их отдаёт
     * NostrTransport.splitAll) — от этого зависит тай-брейк при равном updatedAt (см. ниже).
     */
    internal fun mergeParsedSlots(parsedPerSlot: List<Map<String, Profile>>): Map<String, Profile> {
        val best = LinkedHashMap<String, Profile>()
        for (parsed in parsedPerSlot) {
            for ((uid, p) in parsed) {
                val cur = best[uid]
                if (cur == null) { best[uid] = p; continue }
                // ⚠️ ФИКС тай-брейка (§16, найден при аудите синка профилей; обкатан в песочнице).
                // Слоты приходят отсортированными по created_at УБЫВАЮЩЕ (NostrTransport.splitAll),
                // то есть первым обрабатывается САМОЕ СВЕЖЕЕ событие. При нестрогом `>=` копия с
                // РАВНЫМ updatedAt из более СТАРОГО слота перезаписывала уже взятую свежую запись.
                // А равный updatedAt — штатная ситуация, а не редкость: pushPresence сохраняет
                // прежний updatedAt (делает gist.copy, не обновляя его), поэтому мой профиль и его
                // устаревшая копия в чужом слоте почти всегда несут ОДИН И ТОТ ЖЕ updatedAt.
                // Строгий `>` оставляет победителем запись из более НОВОГО события.
                val base = if (p.updatedAt > cur.updatedAt) p else cur
                best[uid] = base.copy(
                    onlineTs      = maxOf(cur.onlineTs, p.onlineTs),
                    typingTs      = maxOf(cur.typingTs, p.typingTs),
                    recordingTs   = maxOf(cur.recordingTs, p.recordingTs),
                    lastReadIndex = maxOf(cur.lastReadIndex, p.lastReadIndex),
                    // Аватар/имя «липкие» — см. unionWithKnown: копия без авы/имени не
                    // должна перетирать уже известную (иначе ава пропадает у оффлайн-участника).
                    avatarBase64  = base.avatarBase64 ?: cur.avatarBase64 ?: p.avatarBase64,
                    name          = base.name.ifBlank { cur.name.ifBlank { p.name } },
                    // Identity/подписи «липкие» — тот же принцип, что и для авы: slim-слот
                    // без подписи не гасит галочку (подлинность перепроверяется при отрисовке).
                    identityPubKey  = base.identityPubKey  ?: cur.identityPubKey  ?: p.identityPubKey,
                    identitySig     = base.identitySig     ?: cur.identitySig     ?: p.identitySig,
                    ephemeralPubKey = base.ephemeralPubKey ?: cur.ephemeralPubKey ?: p.ephemeralPubKey,
                    ephemeralSig    = base.ephemeralSig    ?: cur.ephemeralSig    ?: p.ephemeralSig
                )
            }
        }
        return best
    }

    /** Дешифрует и парсит ОДИН уже загруженный блоб profiles.txt (без сетевого вызова). */
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
            // Любой успешно распарсенный профиль (из ЛЮБОГО чата — личного или группового)
            // пополняет межчатовый fallback-кэш (см. globalKnownProfiles) — единая точка,
            // через которую проходят и pullProfiles(), и unionProfileSlots().
            if (result.isNotEmpty()) rememberGlobal(result)
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Пушит мой профиль в источник, заодно подчищая старых "клонов меня"
     * (профили с моим именем но другим userId) — это лечит проблему когда
     * после Reset Account старый профиль остаётся в источнике.
     */
    suspend fun pushMyProfile(
        api: ChatTransport,
        password: String,
        myProfile: Profile,
        identityPriv: ByteArray? = null
    ): Boolean = profilesMutex.withLock {
        try {
        // Подпись СОДЕРЖИМОГО профиля (имя/тег/аватар/статус) личным identity-ключом
        // (ADR_GROUP_CHATS.md §«Осталось укрепить», п.4). Домен = api.chatId (== Chat.chatId,
        // тот же что у identitySig/VerifiedBadge) → получатель может проверить против
        // закреплённого за userId ключа. Не-блокирующая детекция подмены имени/аватара.
        // Приватник затираем сразу после подписи (§1), даже если подпись не требовалась.
        val profileToPush = try {
            if (identityPriv != null && myProfile.identityPubKey != null && myProfile.contentSig == null) {
                val csig = CryptoHelper.signProfileContent(
                    identityPriv, api.chatId, myProfile.userId,
                    myProfile.name, myProfile.tag, myProfile.avatarBase64, myProfile.status
                )
                myProfile.copy(contentSig = csig)
            } else myProfile
        } finally {
            identityPriv?.fill(0)
        }
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

        // Записать мой профиль (с подписью содержимого, если была вычислена)
        existing[profileToPush.userId] = profileToPush
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
            lastError = e
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
     * профиля ещё нет в источнике (например, стартовый тик до первого syncProfiles). Без этого
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
        myIdentitySig: String? = null,
        myVerifiedPartnerIdk: String? = null,
        // Рукопожатие профилей: отпечаток эфемерного ключа партнёра, который я реально держу.
        // ОБЯЗАТЕЛЬНО передавать здесь, а не только в pushMyProfile: presence переписывает мой
        // слот каждые ~5 секунд, и без этого поля подтверждение стиралось бы почти сразу после
        // публикации — партнёр никогда бы не включил forward secrecy. null = сохранить как в base.
        myEphAck: String? = null,
        /** Версия протокола профиля (см. ProfileHandshake.PROTOCOL_VERSION). 0 = не менять. */
        myPv: Int = 0,
        // Read receipt: если задан — монотонно продвигаем lastReadIndex этим пушем (нужно для
        // офлайн-флаша «прочитано» при быстром выходе из чата). null = сохранить как в base.
        lastReadIndex: Int? = null
    ): Boolean = profilesMutex.withLock {
        try {
            val existing = unionWithKnown(api.chatId, pullProfiles(api, password))
            // Берём наш профиль из источника; если его там нет — создаём с реальными данными,
            // а не с пустым именем. Так же восстанавливаем имя если оно там было пустым.
            val gist = existing[myUserId]
            val base = gist?.copy(
                name         = gist.name.ifBlank { myName },
                tag          = gist.tag ?: myTag,
                avatarBase64 = gist.avatarBase64 ?: myAvatarBase64
            ) ?: Profile(userId = myUserId, name = myName, tag = myTag, avatarBase64 = myAvatarBase64)

            val updated = base.copy(
                typingTs           = typingTs,
                onlineTs           = onlineTs,
                recordingTs        = recordingTs,
                // Read receipt: монотонно (не откатываем назад); null-параметр = как в base.
                lastReadIndex      = maxOf(base.lastReadIndex, lastReadIndex ?: base.lastReadIndex),
                ephemeralPubKey    = myEphemeralPubKey ?: base.ephemeralPubKey,
                // identity-поля всегда заново вставляем — чтобы presence-пуш их не терял
                identityPubKey     = myIdentityPubKey ?: base.identityPubKey,
                ephemeralSig       = myEphemeralSig ?: base.ephemeralSig,
                identitySig        = myIdentitySig ?: base.identitySig,
                verifiedPartnerIdk = myVerifiedPartnerIdk ?: base.verifiedPartnerIdk,
                // Рукопожатие: свежее подтверждение перекрывает прежнее (партнёр мог сменить
                // ключ), а если подтверждать пока нечего — сохраняем уже опубликованное.
                ephAck             = myEphAck ?: base.ephAck,
                pv                 = if (myPv > 0) myPv else base.pv
            )

            // ⚠️ ФИКС (§16, аудит синка): presence-пуш НИКОГДА не двигал updatedAt — писал профиль
            // через gist.copy(), сохраняя прежнее значение. А ведь он же и ВОССТАНАВЛИВАЕТ
            // содержимое: подставляет имя/тег/аватар из fallback-параметров, если в источнике они
            // пустые, и заново вставляет ключи с подписями. Восстановленные данные выходили
            // «несвежими» и при слиянии проигрывали (или в лучшем случае сравнивались на равных)
            // устаревшей копии меня из чужого слота — из-за чего правка могла не доехать вообще.
            //
            // Двигаем updatedAt ТОЛЬКО когда реально изменилось СОДЕРЖИМОЕ профиля. Делать это на
            // каждом тике нельзя: presence идёт раз в ~5 секунд, и профиль тогда считался бы
            // изменившимся постоянно — лишние перерисовки у получателей и бессмысленная «свежесть».
            // Presence-поля (онлайн/печатает/записывает/прочитано) содержимым НЕ считаются.
            val contentChanged = gist == null ||
                updated.name               != gist.name ||
                updated.tag                != gist.tag ||
                updated.avatarBase64       != gist.avatarBase64 ||
                updated.ephemeralPubKey    != gist.ephemeralPubKey ||
                updated.identityPubKey     != gist.identityPubKey ||
                updated.ephemeralSig       != gist.ephemeralSig ||
                updated.identitySig        != gist.identitySig ||
                updated.verifiedPartnerIdk != gist.verifiedPartnerIdk ||
                // Появление/смена подтверждения рукопожатия — тоже изменение содержимого:
                // партнёр ждёт именно его, чтобы включить forward secrecy, поэтому свежая
                // отметка обязана выигрывать слияние у устаревших копий.
                updated.ephAck             != gist.ephAck
            existing[myUserId] =
                if (contentChanged) updated.copy(updatedAt = System.currentTimeMillis()) else updated
            rememberKnown(api.chatId, existing) // не теряем партнёра при флаки-чтениях
            val json = JSONObject().apply {
                for ((uid, p) in existing) put(uid, p.toJsonObject())
            }
            val encrypted = CryptoHelper.encryptMetadata(json.toString(), password, api.chatId)
            api.saveFile(FILE_NAME, encrypted)
            true
        } catch (e: Exception) {
            lastError = e
            false
        }
    }

    // ⚠️ Удалена pushPresenceWriteOnly() (мёртвый код — ни одного вызывающего по всему
    // проекту не найдено, см. аудит по репорту пользователя "авы/ники везде"). Писала
    // typingTs/onlineTs поверх ПЕРЕДАННОГО кэша партнёрских данных без свежего чтения —
    // именно этот паттерн ниже прямо назван причиной бага "onlineTs партнёра зависает"
    // (см. комментарий pushPresenceCached), из-за которого от него уже отказались в
    // пользу pushPresence (полный GET+PATCH). Саму функцию тогда забыли удалить —
    // теперь удалена, чтобы её нельзя было случайно снова подключить.

    // ⚠️ Удалена pushPresenceCached() (мёртвый код — тоже ни одного вызывающего по всему
    // проекту не найдено). Была оставлена как "алиас для совместимости", делегирующий в
    // pushPresence(), но раз вызывающих не осталось вообще — алиас ничему не совместим,
    // это просто ещё один хвост той же незавершённой уборки после отказа от write-only
    // presence-пушей (см. удаление pushPresenceWriteOnly выше). Если понадобится дешёвый
    // (без GET) presence-пуш — писать заново с нуля поверх текущей per-слотовой архитектуры
    // (см. unionProfileSlots), а не реанимировать этот путь: он основан на общем блобе.

    // ⚠️ Удалены pushTypingTs()/pushOnlineTs() (мёртвый код — ни одного вызывающего по
    // всему проекту не найдено, см. аудит по репорту пользователя "авы/ники везде").
    // Были задуманы как write-only PATCH поверх кэша из уже удалённой doRefreshPartnerReadIndex()
    // (см. её же удаление в ChatActivity.kt), и писали ОДИН общий блоб profiles.txt
    // (api.saveFile(FILE_NAME, ...) поверх всей карты участников) — несовместимо с текущей
    // архитектурой per-участника слотов (см. unionProfileSlots выше): реактивация сейчас
    // не просто не нужна, а вредна — переписала бы общий блоб поверх чужих слотов.

    /**
     * Помечает наш профиль как удалённый (deleted=true) в источнике.
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
        myName: String = "",
        myIdentityPubKey: String? = null
    ): Profile? {
        // ⚠️ ФИКС (репорт: «в шапке чата стоит МОЁ ИМЯ и моя заглушка вместо собеседника»).
        // Жёсткий отсев «это точно я»: не только по userId, но и по identity-ключу. После
        // сброса аккаунта в канале остаётся мой старый профиль с ДРУГИМ userId — по userId он
        // не отсеивался, и дальше срабатывал фолбэк ниже, из-за чего партнёром назначался
        // мой же старый профиль. Он попадал в Room (updatePartnerProfile) и намертво
        // прописывался в шапке: собеседник при этом писал и его сообщения приходили, но
        // имя и аватар в шапке были моими. Identity-ключ подделать нельзя, поэтому это
        // однозначный признак «этот профиль — я», даже если имя успело смениться.
        val others = profiles.values.filter { p ->
            p.userId != myUserId &&
                !(myIdentityPubKey != null && p.identityPubKey != null && p.identityPubKey == myIdentityPubKey)
        }
        if (others.isEmpty()) return null
        // Отбрасываем "клонов меня" (моё имя, чужой userId).
        val nonClones = if (myName.isBlank()) others else others.filter { it.name != myName }
        if (nonClones.isNotEmpty()) return nonClones.maxByOrNull { it.updatedAt }
        // Остались только тёзки. Раньше здесь безусловно брался любой из них — именно этот
        // фолбэк и назначал партнёром мой старый профиль. Берём тёзку, ТОЛЬКО если он
        // доказанно не я: у него есть СВОЙ identity-ключ, отличный от моего. Легитимный
        // однофамилец такой ключ публикует всегда (ChatActivity кладёт его в каждый профиль),
        // а вот у старого «меня» его обычно нет — и назначать его собеседником нельзя.
        return others
            .filter { it.identityPubKey != null && it.identityPubKey != myIdentityPubKey }
            .maxByOrNull { it.updatedAt }
    }
}
