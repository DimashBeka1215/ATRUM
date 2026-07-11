package com.atrum.chat

import android.content.Context
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.transport.LocalTransport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom

/**
 * Системный чат «Уведомления» (одобренный мокап этой сессии): локальный read-only чат,
 * куда приходят все новости модерации — мут (группа, срок, причина), досрочное снятие
 * мута, бан. Писать туда нельзя (строка ввода скрыта в ChatActivity).
 *
 * ⚡ Скорость (требование пользователя: «вся инфа за 3 секунды»): уведомление пишется
 * ЛОКАЛЬНО в тот самый момент, когда устройство ПРИМЕНЯЕТ свежий members.txt
 * (MembersSync.applyIncoming — единственная точка входа для всех путей: тик открытого
 * чата ~1с, опрос списка ~8с, фоновый сервис). Сеть для самого уведомления не нужна
 * вовсе — оно появляется одновременно с плашкой мута/вылетом по бану.
 *
 * Дедуп «из коробки»: applyIncoming применяет каждую версию members.txt ровно один раз
 * на устройство (анти-откат по версии), а уведомление создаётся только при РЕАЛЬНОМ
 * изменении статуса (старая запись существовала и отличалась) — ни повторов на каждый
 * тик, ни спама при свежем входе/переустановке (старой записи нет → тишина).
 *
 * Устройство чата: isFavorites = true — намеренно! Это переиспользует ВСЕ проверенные
 * гварды «Избранного» (LocalTransport в TransportFactory, никаких profiles/presence/FS,
 * пропуск в сетевых циклах списка и фонового сервиса). Отличия от «Избранного»
 * (иконка-колокольчик, имя, read-only) ветвятся по [Chat.isSystemNotifications].
 * getFavoritesChat() уточнён по chatId — коллизий нет.
 */
object SystemNotifications {

    /** Зарезервированный chatId системного чата (см. [Chat.isSystemNotifications]). */
    const val CHAT_ID = "system_notifications"

    /** userId системного отправителя — по нему MessageAdapter ставит иконку приложения на аву. */
    const val SENDER_USER_ID = "atrum_system"

    /** Сериализация записей: пишут три независимых пути (чат/список/фон). */
    private val writeMutex = Mutex()

    /** Атомарный claim версии members.txt: гарантирует «одна версия — одно уведомление». */
    private val claimLock = Any()

    /** Сериализация ensureChat — против дубля строки чата (см. ensureChat). */
    private val ensureMutex = Mutex()

    /**
     * Пытается «занять» версию members.txt под уведомление о моём статусе. true — этот
     * вызывающий ПЕРВЫЙ и должен записать уведомление; false — версию уже обработал
     * другой гонщик (applyIncoming вызывается из чата/списка/фона одновременно), молчим.
     * Персистентный (Prefs) — дедуп переживает и перезапуск процесса.
     */
    fun claimMembersVersion(context: Context, networkChatId: String, version: Int): Boolean =
        synchronized(claimLock) {
            val prefs = Prefs(context)
            if (version <= prefs.getNotifiedMembersVersion(networkChatId)) return false
            prefs.setNotifiedMembersVersion(networkChatId, version)
            true
        }

    /**
     * Как [claimMembersVersion], но для мультиподписи (Этап 2): слитое состояние членства
     * может меняться БЕЗ роста числовой версии главного members.txt (мут/бан делегата),
     * поэтому дедуп по СТРОКОВОМУ токену состояния. true — этот гонщик первый и должен
     * записать уведомление; false — токен уже обработан. Персистентный (Prefs).
     */
    fun claimMembersToken(context: Context, networkChatId: String, token: String): Boolean =
        synchronized(claimLock) {
            val prefs = Prefs(context)
            if (token == prefs.getNotifiedMembersToken(networkChatId)) return false
            prefs.setNotifiedMembersToken(networkChatId, token)
            true
        }

    /** Мут: группа, срок, причина (если указана). */
    suspend fun notifyMuted(context: Context, groupName: String, untilMs: Long, reason: String?) {
        val untilFmt = java.text.SimpleDateFormat("dd.MM.yy, HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(untilMs))
        val text = buildString {
            append(context.getString(R.string.notif_muted_fmt, groupName, untilFmt))
            if (!reason.isNullOrBlank()) {
                append('\n')
                append(context.getString(R.string.notif_muted_reason_fmt, reason))
            }
        }
        append(context, text)
    }

    /** Досрочное снятие мута (админ снял до истечения срока). */
    suspend fun notifyUnmuted(context: Context, groupName: String) {
        append(context, context.getString(R.string.notif_unmuted_fmt, groupName))
    }

    /** Истечение срока мута по времени (см. checkMuteExpiry — нет события members.txt). */
    suspend fun notifyMuteExpired(context: Context, groupName: String) {
        append(context, context.getString(R.string.notif_mute_expired_fmt, groupName))
    }

    /** Запомнить срок МОЕГО мута (для уведомления об истечении). Вызывается из applyIncoming. */
    fun rememberMyMute(context: Context, chatId: String, untilMs: Long) {
        Prefs(context).setMyMuteUntil(chatId, untilMs)
    }

    /**
     * Абсолютное время БЛИЖАЙШЕГО будущего истечения моего мута (или Long.MAX_VALUE, если
     * активных мутов нет). Фоновый сервис по нему просыпается ровно к сроку — уведомление
     * «мут истёк» приходит точно вовремя, а не с задержкой опроса (скорость важна).
     */
    suspend fun nearestFutureMuteExpiry(context: Context): Long {
        return runCatching {
            val prefs = Prefs(context)
            val db = AppDatabase.get(context)
            val now = System.currentTimeMillis()
            var nearest = Long.MAX_VALUE
            for (chat in db.chatDao().getAll()) {
                if (!chat.isGroup) continue
                val until = prefs.getMyMuteUntil(chat.chatId)
                if (until in (now + 1)..nearest) nearest = until
            }
            nearest
        }.getOrDefault(Long.MAX_VALUE)
    }

    /** Сбросить трекинг мута (досрочное снятие/бан — уведомление уже пойдёт своим путём). */
    fun clearMyMute(context: Context, chatId: String) {
        Prefs(context).setMyMuteUntil(chatId, 0L)
    }

    /**
     * Проверка истечения МОЕГО мута по времени — источник уведомления «срок мута истёк»
     * (его нет в members.txt). Вызывается периодически (MessageWatchService, ChatActivity).
     * Срабатывает ровно один раз на мут: при наступлении срока сбрасывает трекер в 0.
     */
    suspend fun checkMuteExpiry(context: Context) {
        runCatching {
            val prefs = Prefs(context)
            val db = AppDatabase.get(context)
            val now = System.currentTimeMillis()
            val myUserId = prefs.myUserId
            for (chat in db.chatDao().getAll()) {
                if (!chat.isGroup) continue
                val until = prefs.getMyMuteUntil(chat.chatId)
                if (until in 1L..now) { // until > 0 и срок уже наступил
                    prefs.setMyMuteUntil(chat.chatId, 0L) // consume — ровно один раз
                    // Точность: не шлём «истёк», если меня уже нет в группе или забанили
                    // (тогда актуально другое событие, а не окончание мута).
                    val me = db.chatParticipantDao().getOne(chat.id, myUserId)
                    if (me == null || me.banned) continue
                    val name = chat.groupName?.takeIf { it.isNotBlank() } ?: chat.partnerName
                    notifyMuteExpired(context, name)
                }
            }
        }
    }

    /** Бан. Чат больше НЕ удаляется — прячется (см. ChatActivity/MembersSync), поэтому
     *  разбан наблюдаем и тоже уведомляется. */
    suspend fun notifyBanned(context: Context, groupName: String) {
        append(context, context.getString(R.string.notif_banned_fmt, groupName))
    }

    /** Разбан (возврат в группу) — стал наблюдаемым, т.к. бан теперь не удаляет чат. */
    suspend fun notifyUnbanned(context: Context, groupName: String) {
        append(context, context.getString(R.string.notif_unbanned_fmt, groupName))
    }

    /** Назначение админом / изменение прав — с перечислением выданных прав. */
    suspend fun notifyRoleGranted(context: Context, groupName: String, permissions: Int) {
        val labels = AdminPermissions.names(permissions).map {
            context.getString(
                when (it) { // индексы = порядок в AdminPermissions.names()
                    0 -> R.string.perm_edit; 1 -> R.string.perm_moderate; 2 -> R.string.perm_stats
                    3 -> R.string.perm_pin; else -> R.string.perm_delete
                }
            )
        }
        val text = context.getString(R.string.notif_role_granted_fmt, groupName) +
            (if (labels.isNotEmpty()) "\n" + context.getString(R.string.notif_role_perms_fmt, labels.joinToString(", ")) else "")
        append(context, text)
    }

    /** Снятие прав (разжалование). */
    suspend fun notifyRoleRevoked(context: Context, groupName: String) {
        append(context, context.getString(R.string.notif_role_revoked_fmt, groupName))
    }

    /**
     * Строка чата «Уведомления»: возвращает существующую или создаёт на месте.
     * Также вызывается из ChatsListActivity при старте — чат виден сразу, не дожидаясь
     * первого события.
     */
    suspend fun ensureChat(context: Context): Chat? = ensureMutex.withLock {
        // Mutex — против дубля строки: ensureChat зовётся из ChatsListActivity и из
        // append() (три пути) одновременно; без сериализации оба могли пройти проверку
        // getByChatId=null и вставить по строке, дав два «Уведомления» в списке.
        val db = AppDatabase.get(context)
        db.chatDao().getByChatId(CHAT_ID)?.let { return@withLock it }
        val prefs = Prefs(context)
        if (prefs.getChatPassword(CHAT_ID).isEmpty()) {
            val bytes = ByteArray(24).also { SecureRandom().nextBytes(it) }
            prefs.saveChatSecrets(CHAT_ID, "", bytes.joinToString("") { "%02x".format(it) })
        }
        @Suppress("DEPRECATION")
        db.chatDao().insert(
            Chat(
                chatId = CHAT_ID,
                transportToken = "",
                chatPassword = "",
                partnerName = context.getString(R.string.notif_chat_name),
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                isFavorites = true // намеренно — см. докстринг объекта
            )
        )
        db.chatDao().getByChatId(CHAT_ID)
    }

    private suspend fun append(context: Context, text: String) {
        // «Должны приходить в любом случае» (требование пользователя): 2 попытки с
        // паузой — транзиентный сбой диска/БД не теряет уведомление. Всё локально (без
        // сети), так что это доли секунды. Запись в отдельной от применения мута
        // корутине (см. MembersSync.applyIncoming → AppScope.launch) — критический путь
        // мута НЕ ждёт этот диск и не «заедает».
        for (attempt in 0 until 2) {
            val ok = runCatching {
                writeMutex.withLock {
                    val db = AppDatabase.get(context)
                    val prefs = Prefs(context)
                    val chat = ensureChat(context) ?: return@withLock false
                    val password = prefs.getChatPassword(CHAT_ID)
                    // encryptMetadata (V4, детерминированная соль, тёплый кэш ключа) — а не
                    // generic encrypt: у системного чата нет FS-сессии, generic упал бы на V5
                    // (Argon2id 64МБ со случайной солью на КАЖДУЮ строку) — и запись, и КАЖДОЕ
                    // открытие чата декодировали бы каждую строку по полсекунды. decrypt()
                    // на чтении автодетектит формат по префиксу — ничего менять не нужно.
                    val line = CryptoHelper.encryptMetadata(
                        Message.composePlaintext(
                            senderName = context.getString(R.string.notif_sender_name),
                            senderUserId = SENDER_USER_ID,
                            text = text
                        ),
                        password, CHAT_ID
                    )
                    LocalTransport(chat.id, db.chatDao(), context).appendLine(line)
                    db.chatDao().updatePreview(chat.id, text.replace('\n', ' ').take(80), System.currentTimeMillis())
                    db.chatDao().updateUnread(chat.id, chat.unreadCount + 1)
                    true
                }
            }.getOrDefault(false)
            if (ok) return
            kotlinx.coroutines.delay(500L)
        }
    }
}

/** Системный чат «Уведомления»? (read-only, локальный — см. [SystemNotifications]). */
val Chat.isSystemNotifications: Boolean
    get() = chatId == SystemNotifications.CHAT_ID
