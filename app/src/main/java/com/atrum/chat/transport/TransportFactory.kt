package com.atrum.chat.transport

import android.content.Context

/**
 * Создаёт транспорт для одного чата. Проект работает на Nostr (публичные реле) —
 * GitHub и DHT убраны.
 *
 * Поле токена чата (gistToken) зарезервировано для будущего выбора пути
 * (например, Nostr напрямую vs Nostr через Tor). Сейчас всё → NostrTransport,
 * а "Избранное" → локальный LocalTransport.
 */
class TransportFactory(
    private val gistId: String,
    @Suppress("unused") private val gistToken: String,
    private val chatPassword: String,
    private val myUserId: String,
    private val isFavorites: Boolean = false,
    private val chatIdLong: Long = -1L,
    private val chatDao: com.atrum.chat.data.ChatDao? = null,
    private val context: Context? = null
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
        if (isFavorites && chatDao != null && context != null)
            return LocalTransport(chatIdLong, chatDao, context)
        // Через Tor по умолчанию; напрямую — только если токен явно "nostrdirect".
        val useTor = gistToken != NostrTransport.NOSTR_DIRECT_TOKEN
        // Ленивый старт Tor: поднимаем демон только когда реально открыт чат через Tor.
        if (useTor) context?.let { com.atrum.chat.TorManager.start(it) }
        return NostrTransport(gistId, chatPassword, myUserId, useTor = useTor)
    }

    private fun resolve(): ChatTransport = instant()

    companion object {
        /** Быстрый транспорт для разовых операций экранов (профиль, верификация и т.п.). */
        fun forChat(
            context: Context,
            gistId: String,
            token: String,
            password: String,
            myUserId: String
        ): ChatTransport = TransportFactory(
            gistId = gistId, gistToken = token, chatPassword = password,
            myUserId = myUserId, context = context
        ).instant()
    }
}
