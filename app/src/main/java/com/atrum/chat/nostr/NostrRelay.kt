package com.atrum.chat.nostr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket-клиент одного Nostr-реле (NIP-01).
 *
 * Каждый вызов publish/query открывает соединение, использует его и закрывает.
 * Для мобильного приложения (эпизодический трафик) это проще, чем постоянный
 * keep-alive: не нужно следить за переподключением и ping/pong.
 *
 * Таймауты:
 *   connect  10 сек  — не смогли установить TCP/TLS
 *   publish  10 сек  — ждём OK или NOTICE от реле
 *   query    15 сек  — ждём EOSE (end-of-stored-events)
 */
class NostrRelay(private val url: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // ─── publish ─────────────────────────────────────────────────────────────

    /**
     * Публикует событие на реле.
     * Ждёт подтверждения ["OK", id, true, ...] или таймаута 10 сек.
     * Бросает исключение если реле отклонило событие или соединение упало.
     */
    suspend fun publish(event: NostrEvent) = withContext(Dispatchers.IO) {
        val result = CompletableDeferred<Unit>()

        val msg = JSONArray().apply {
            put("EVENT")
            put(event.toJson())
        }.toString()

        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(msg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = JSONArray(text)
                        when (arr.optString(0)) {
                            "OK" -> {
                                val ok = arr.optBoolean(2, true)
                                val reason = arr.optString(3, "")
                                if (ok) {
                                    result.complete(Unit)
                                } else {
                                    result.completeExceptionally(
                                        RuntimeException("Relay rejected event: $reason")
                                    )
                                }
                                webSocket.close(1000, null)
                            }
                            "NOTICE" -> {
                                // Информационное сообщение от реле — логируем, не падаем
                            }
                        }
                    } catch (_: Exception) { }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    result.completeExceptionally(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    // Если OK так и не пришёл, но соединение закрылось честно — считаем успехом
                    if (!result.isCompleted) result.complete(Unit)
                }
            }
        )

        try {
            withTimeout(10_000L) { result.await() }
        } catch (e: TimeoutCancellationException) {
            throw RuntimeException("Relay $url publish timeout")
        } finally {
            ws.close(1000, null)
        }
    }

    // ─── query ───────────────────────────────────────────────────────────────

    /**
     * Выполняет REQ-подписку и возвращает все события до EOSE или таймаута 15 сек.
     *
     * @param filter  JSON-объект NIP-01 фильтра (kinds, #t, limit, ...)
     * @return список событий, дедупликация по id производится в NostrTransport
     */
    suspend fun query(filter: JSONObject): List<NostrEvent> = withContext(Dispatchers.IO) {
        val subId = "atrum_${System.currentTimeMillis()}"
        val events = mutableListOf<NostrEvent>()
        val done = CompletableDeferred<Unit>()

        val reqMsg = JSONArray().apply {
            put("REQ")
            put(subId)
            put(filter)
        }.toString()

        val closeMsg = JSONArray().apply {
            put("CLOSE")
            put(subId)
        }.toString()

        val ws = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(reqMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val arr = JSONArray(text)
                        when (arr.optString(0)) {
                            "EVENT" -> {
                                if (arr.optString(1) == subId) {
                                    NostrEvent.fromJson(arr.getJSONObject(2))
                                        ?.let { synchronized(events) { events.add(it) } }
                                }
                            }
                            "EOSE" -> {
                                webSocket.send(closeMsg)
                                done.complete(Unit)
                            }
                            "CLOSED" -> {
                                if (!done.isCompleted) done.complete(Unit)
                            }
                        }
                    } catch (_: Exception) { }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!done.isCompleted) done.completeExceptionally(t)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!done.isCompleted) done.complete(Unit)
                }
            }
        )

        try {
            withTimeout(15_000L) { done.await() }
        } catch (_: TimeoutCancellationException) {
            // Вернём что успели получить — частичный результат лучше пустого
        } finally {
            ws.close(1000, null)
        }

        synchronized(events) { events.toList() }
    }
}
