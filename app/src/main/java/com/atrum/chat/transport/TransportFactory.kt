package com.atrum.chat.transport

import android.content.Context
import com.atrum.chat.GistApi

/**
 * Создаёт и кэширует GistTransport для одного чата.
 *
 * NostrTransport намеренно отключён: публичные Nostr-реле раскрывают метаданные
 * (время и факт общения), что противоречит модели угрозы приложения.
 * Содержимое зашифровано, но метаданные всё равно утекают.
 * Если GitHub недоступен — операция завершается с ошибкой, что честнее
 * чем тихий фолбэк на публичную инфраструктуру.
 *
 * Создавай один экземпляр на Activity и передавай его в ProfileSync / ImageLoader.
 */
class TransportFactory(
    private val gistId: String,
    private val gistToken: String,
    @Suppress("UNUSED_PARAMETER") chatPassword: String,
    @Suppress("UNUSED_PARAMETER") myUserId: String,
    private val isFavorites: Boolean = false,
    private val chatIdLong: Long = -1L,
    private val chatDao: com.atrum.chat.data.ChatDao? = null,
    private val context: Context? = null
) {
    @Volatile
    private var cached: ChatTransport? = null

    /**
     * Возвращает актуальный транспорт. При первом вызове проверяет доступность Gist.
     * Последующие вызовы возвращают закэшированный результат.
     */
    suspend fun get(): ChatTransport = cached ?: resolve().also { cached = it }

    /**
     * Принудительно перепроверяет транспорт (сбрасывает кэш).
     * Вызывай при смене сети или по кнопке в UI.
     */
    suspend fun refresh(): ChatTransport {
        cached = null
        return get()
    }

    /**
     * Возвращает GistTransport напрямую, без проверки доступности.
     * Удобно для CreateChatActivity / JoinChatActivity — там Gist точно есть.
     */
    fun gistDirect(): ChatTransport {
        if (isFavorites && chatDao != null && context != null)
            return LocalTransport(chatIdLong, chatDao, context)
        return GistTransport(makeGistApi())
    }

    // ─── private ──────────────────────────────────────────────────────────────

    private suspend fun resolve(): ChatTransport {
        if (isFavorites && chatDao != null && context != null)
            return LocalTransport(chatIdLong, chatDao, context)
        // Nostr-фолбэк убран: публичные реле раскрывают метаданные.
        // При недоступности GitHub бросаем исключение — ChatActivity покажет ошибку.
        return GistTransport(makeGistApi())
    }

    private fun makeGistApi() = GistApi(token = gistToken, gistId = gistId)
}
