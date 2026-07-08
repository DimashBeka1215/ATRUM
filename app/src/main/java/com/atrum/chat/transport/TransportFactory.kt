package com.atrum.chat.transport

import android.content.Context

/**
 * Создаёт транспорт для одного чата. Проект работает на Nostr (публичные реле).
 *
 * Поле токена чата (transportToken) зарезервировано для будущего выбора пути
 * (например, Nostr напрямую vs Nostr через Tor). Сейчас всё → NostrTransport,
 * а "Избранное" → локальный LocalTransport.
 */
class TransportFactory(
    private val chatId: String,
    @Suppress("unused") private val transportToken: String,
    private val chatPassword: String,
    private val myUserId: String,
    private val isFavorites: Boolean = false,
    private val chatIdLong: Long = -1L,
    private val chatDao: com.atrum.chat.data.ChatDao? = null,
    private val context: Context? = null,
    /**
     * userId администратора ГРУППОВОГО чата (ADR-001, Chat.adminUserId). null для
     * 1:1-чатов — путь 1:1 не меняется ни на бит (см. NostrTransport.adminUserId).
     * Без этого поля NostrTransport.adminPubkeyHex всегда null → members.txt никогда
     * не проходит проверку подписи → MembersSync.applyIncoming всегда no-op даже в
     * открытом чате/списке чатов (баг: счётчик участников и имя/аватар группы не
     * обновляются после джойна собеседника).
     */
    private val adminUserId: String? = null
) {
    @Volatile
    private var cached: ChatTransport? = null

    /** Возвращает актуальный транспорт. */
    suspend fun get(): ChatTransport = cached ?: resolve().also { cached = it }

    /** Принудительно перепроверяет транспорт (сбрасывает кэш). */
    suspend fun refresh(): ChatTransport {
        cached = null
        return get()
    }

    /** Транспорт для мгновенного старта UI без сетевой инициализации. */
    fun instant(): ChatTransport {
        // Local и BT — финальные транспорты без сетевого ресолва. Кешируем сразу, чтобы
        // последующий get() вернул ТОТ ЖЕ экземпляр (для BT критично: иначе слушатель BLE
        // и SyncEngine окажутся на разных объектах и доставка сломается).
        cached?.let { if (it is LocalTransport || it is BluetoothTransport) return it }
        if (isFavorites && chatDao != null && context != null)
            return LocalTransport(chatIdLong, chatDao, context).also { cached = it }
        // BT-локальный чат: доставка по живому BLE-каналу, история на диске.
        if (transportToken == BluetoothTransport.BT_TOKEN && context != null)
            return BluetoothTransport(chatId, context).also { cached = it }
        // Через Tor по умолчанию; напрямую — только если токен явно "nostrdirect".
        // Фактический режим в NostrTransport динамический: если Tor не поднимется
        // (заблокирован) — автопереход на прямое подключение, чтобы Nostr работал без VPN.
        val preferTor = transportToken != NostrTransport.NOSTR_DIRECT_TOKEN
        // Ленивый старт Tor: поднимаем демон только когда реально открыт чат через Tor.
        if (preferTor) context?.let { com.atrum.chat.TorManager.start(it) }
        return NostrTransport(chatId, chatPassword, myUserId, preferTor = preferTor, adminUserId = adminUserId)
    }

    private fun resolve(): ChatTransport = instant()

    companion object {
        /**
         * Быстрый транспорт для разовых операций экранов (профиль, верификация и т.п.).
         * [adminUserId] — передавать chat.adminUserId, если известно, что чат групповой
         * и нужно читать members.txt (по умолчанию null — как было раньше, 1:1 не тронуты).
         */
        fun forChat(
            context: Context,
            chatId: String,
            token: String,
            password: String,
            myUserId: String,
            adminUserId: String? = null
        ): ChatTransport = TransportFactory(
            chatId = chatId, transportToken = token, chatPassword = password,
            myUserId = myUserId, context = context, adminUserId = adminUserId
        ).instant()
    }
}
