package com.atrum.chat

import com.atrum.chat.transport.AllGistData
import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Единый ETag-polling engine. Single-flight: параллельные GET невозможны.
 *
 * Архитектура:
 *  • Один GET за тик. ETag/304 = 0 трафика если gist не изменился.
 *  • Single-flight guard (AtomicBoolean): новый тик пропускается если
 *    предыдущий ещё не завершён (slow GitHub CDN не создаёт request storm).
 *  • Adaptive interval: ACTIVE_MS пока чат открыт, BACKGROUND_MS иначе.
 *  • Rate limit: пауза на Retry-After при 429 / 403.
 *  • forceSync(): внеплановый тик после отправки сообщения.
 *
 * Использование:
 *   val engine = SyncEngine(transport)
 *   engine.start(lifecycleScope)
 *   launch { engine.events.collect { data -> handleNewData(data) } }
 *   engine.forceSync(delayMs = 1_500)   // после отправки
 *   engine.stop()                         // onPause / onDestroy
 */
class SyncEngine(private val transport: ChatTransport) {

    // ── Public events ─────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<AllGistData>(extraBufferCapacity = 4)
    /**
     * Горячий поток: данные от GitHub только когда gist реально изменился (200 ≠ 304).
     * Несколько подписчиков получают одни и те же данные. Нет backpressure loss:
     * extraBufferCapacity гарантирует что tryEmit() не дропает при медленном consumer.
     */
    val events: SharedFlow<AllGistData> = _events

    // ── Internal state ────────────────────────────────────────────────────────

    private var pollJob: Job? = null
    private val syncRunning      = AtomicBoolean(false)

    @Volatile private var rateLimitUntilMs = 0L
    @Volatile private var forceSyncAtMs    = Long.MAX_VALUE
    @Volatile private var currentIntervalMs = ACTIVE_INTERVAL_MS

    // ── Public API ────────────────────────────────────────────────────────────

    /** Переключить на быстрый интервал (чат на переднем плане). */
    fun setActive() { currentIntervalMs = ACTIVE_INTERVAL_MS }

    /** Переключить на медленный интервал (чат ушёл в фон). */
    fun setBackground() { currentIntervalMs = BACKGROUND_INTERVAL_MS }

    /**
     * Запустить polling loop. Безопасно вызывать повторно —
     * останавливает предыдущий job перед запуском нового.
     */
    fun start(scope: CoroutineScope) {
        pollJob?.cancel()
        syncRunning.set(false)
        pollJob = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()

                // ── Rate limit pause ──────────────────────────────────────────
                val ratePause = (rateLimitUntilMs - now).coerceAtLeast(0L)
                if (ratePause > 0L) {
                    delay(ratePause)
                    continue
                }

                // ── Вычисляем время до следующего тика ────────────────────────
                val forceIn = (forceSyncAtMs - now).coerceAtLeast(0L)
                val waitMs  = minOf(currentIntervalMs, forceIn)
                if (waitMs > 0L) delay(waitMs)

                // ── Single-flight guard: пропускаем если предыдущий GET не завершён.
                // ВАЖНО: forceSyncAtMs сбрасываем только ПОСЛЕ успешного compareAndSet.
                // Иначе: forceSync(0) во время активного GET → compareAndSet FAIL →
                // forceSyncAtMs уже MAX_VALUE → следующая итерация ждёт 10с → баг.
                // Теперь: compareAndSet FAIL → delay(500ms) → повтор с сохранённым forceSync.
                if (!syncRunning.compareAndSet(false, true)) {
                    delay(SINGLE_FLIGHT_RETRY_MS)
                    continue
                }
                forceSyncAtMs = Long.MAX_VALUE   // сбрасываем только когда взяли "замок"

                try {
                    doSync()
                } finally {
                    syncRunning.set(false)
                }
            }
        }
    }

    /**
     * Форсировать внеплановый sync через [delayMs] мс.
     * Используется после отправки сообщения — подтверждение быстрее среднего тика.
     * Безопасно вызывать из любого потока.
     */
    fun forceSync(delayMs: Long = 0L) {
        val target = System.currentTimeMillis() + delayMs
        // Записываем только если это раньше уже запланированного форсированного тика
        if (target < forceSyncAtMs) {
            forceSyncAtMs = target
        }
    }

    /** Остановить polling. Вызывать в onPause / onDestroy. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
        // Не сбрасываем syncRunning — если GET в flight, он завершится корректно
    }

    val isRunning: Boolean get() = pollJob?.isActive == true

    // ── Core sync ─────────────────────────────────────────────────────────────

    private suspend fun doSync() {
        try {
            val data: AllGistData? = withContext(Dispatchers.IO) {
                transport.loadAllIfChanged()
            }

            if (data == null) {
                // 304 Not Modified — gist не изменился
                // Продлеваем TTL кэша-подсказки appendLine (без GET не протухнет)
                transport.touchChatContentHint()
                return
            }

            // Обновляем кэш-подсказку для следующего appendLine (пропустит GET)
            transport.updateChatContentHint(data.chatContent)

            // Публикуем данные всем подписчикам
            _events.tryEmit(data)

        } catch (e: RateLimitException) {
            // GitHub rate limit — пауза на рекомендованное время (min 30s, max 2min)
            rateLimitUntilMs = System.currentTimeMillis() +
                e.retryAfterMs.coerceIn(30_000L, 120_000L)
        } catch (_: CancellationException) {
            throw CancellationException()   // propagate — coroutine отменена корректно
        } catch (_: Exception) {
            // Сетевые ошибки: ждём следующего регулярного тика, ничего не логируем
            // (один временный сбой не должен давать мерцание UI)
        }
    }

    companion object {
        /**
         * Интервал опроса когда чат на переднем плане.
         * 5с: среднее время получения сообщения = 2.5с.
         * Нагрузка: 12 GET/мин при ETag (большинство — 304, ~0 трафика).
         * GitHub rate limit: 5000/час = 83/мин → 14% утилизации. Безопасно.
         */
        const val ACTIVE_INTERVAL_MS     = 5_000L    // 5 с

        /** Интервал опроса когда чат уходит в фон (не используется — onPause stop). */
        const val BACKGROUND_INTERVAL_MS = 30_000L   // 30 с

        /** Задержка форсированного sync после отправки сообщения. */
        const val POST_SEND_SYNC_DELAY_MS = 1_500L

        /** Пауза перед повтором при single-flight: предыдущий GET ещё в полёте. */
        private const val SINGLE_FLIGHT_RETRY_MS = 500L
    }
}
