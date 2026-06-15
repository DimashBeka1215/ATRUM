package com.atrum.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.min

/**
 * Многоуровневая система управления отправкой сообщений.
 *
 * Механика:
 *  1. Token bucket — 4 токена (burst), +1 токен каждые 3 сек.
 *     Быстрые сообщения тратят токены; когда токены кончаются,
 *     сообщения встают в очередь и отправляются по мере пополнения.
 *
 *  2. Очередь FIFO — сообщения в ней показываются со значком ⏱ (часы).
 *     Порядок гарантирован. Retry до 3 раз при сетевых ошибках.
 *
 *  3. Прогрессивные блокировки (только для агрессивного спама):
 *       Уровень 1 →  10 сек
 *       Уровень 2 →  30 сек
 *       Уровень 3 →   1 мин
 *       Уровень 4 →   5 мин
 *       Уровень 5 →  10 мин
 *       Уровень 6+ → +5 мин за каждый следующий
 *
 *  4. Автоматическое смягчение: 3 минуты нормального использования
 *     снижают уровень на 1 ступень.
 *
 *  5. Анти-дубликаты: одинаковый текст в течение 2 сек игнорируется.
 */
class MessageSendManager(
    private val scope: CoroutineScope,
    /** Выполнить реальную отправку зашифрованного сообщения (throws on error). */
    private val doSend: suspend (encrypted: String) -> Unit,
    /** Вызывается после каждой успешной отправки (обновить список из gist). */
    private val onMessageSent: suspend () -> Unit,
    /** Вызывается при изменении очереди — для обновления pending в адаптере. */
    private val onQueueChanged: (List<Message>) -> Unit,
    /** Вызывается при начале блокировки с полной длительностью в мс. */
    private val onPunishmentStart: (durationMs: Long) -> Unit,
    /** Вызывается когда блокировка снята. */
    private val onPunishmentEnd: () -> Unit,
    /** Вызывается если сообщение не удалось отправить после всех retry. */
    private val onSendFailed: (text: String, reason: String) -> Unit,
    /**
     * Вызывается при ответе GitHub «лимит запросов» (RateLimitException) с
     * рекомендованной паузой в мс — UI показывает жёлтую плашку с обратным отсчётом.
     */
    private val onRateLimit: (retryAfterMs: Long) -> Unit = {}
) {

    // ── Элемент очереди ───────────────────────────────────────────────────────

    data class QueueItem(
        val localId: String = UUID.randomUUID().toString(),
        val text: String,
        val encrypted: String,
        val pendingMsg: Message
    )

    // ── Token bucket ──────────────────────────────────────────────────────────

    @Volatile private var tokens = MAX_TOKENS
    @Volatile private var lastRefillMs = System.currentTimeMillis()

    // ── Queue ─────────────────────────────────────────────────────────────────

    private val queue = ArrayDeque<QueueItem>()
    private var workerJob: Job? = null

    // ── Блокировки (punishment) ───────────────────────────────────────────────

    /** Текущий уровень нарушения (0 = чисто). */
    private var punishmentLevel = 0

    /** Время окончания текущей блокировки (0 = нет блокировки). */
    @Volatile private var punishmentEndMs = 0L

    // ── Отслеживание спама ────────────────────────────────────────────────────

    /** Счётчик «спам-нажатий» в текущем окне. */
    private var spamPressCount = 0
    private var spamWindowStartMs = 0L

    /** Время последней успешной нормальной отправки. */
    private var lastGoodSendMs = System.currentTimeMillis()

    // ── Анти-дубликаты ────────────────────────────────────────────────────────

    /** text → lastSentMs (для отсева дублей в короткое окно). */
    private val recentTexts = LinkedHashMap<String, Long>()

    // ── Публичный API ─────────────────────────────────────────────────────────

    fun isPunished(): Boolean = System.currentTimeMillis() < punishmentEndMs

    fun remainingPunishmentMs(): Long = maxOf(0L, punishmentEndMs - System.currentTimeMillis())

    /** Снимок очереди для адаптера (pending сообщения). */
    fun queueSnapshot(): List<Message> = queue.map { it.pendingMsg }.toList()

    /**
     * Пытается поставить сообщение в очередь.
     * @return true — принято (отображается в UI с часами),
     *         false — отклонено (блокировка или спам).
     */
    fun tryEnqueue(item: QueueItem): Boolean {
        val now = System.currentTimeMillis()

        // 1. Активная блокировка — не принимаем, но замечаем агрессию
        if (now < punishmentEndMs) {
            recordSpamPress(now)
            return false
        }

        // 2. Анти-дубликаты: тот же текст ≤ 2 сек назад → тихо отбрасываем
        cleanupRecentTexts(now)
        if (recentTexts[item.text]?.let { now - it < DUPLICATE_WINDOW_MS } == true) {
            return false
        }

        // 3. Жёсткое ограничение очереди
        if (queue.size >= MAX_QUEUE_SIZE) {
            recordSpamPress(now)
            if (spamPressCount >= SPAM_PRESS_THRESHOLD) {
                triggerPunishment(now)
            }
            return false
        }

        // 4. Нажатие при исчерпанных токенах и уже большой очереди — подозрительно
        refreshTokens(now)
        if (tokens == 0 && queue.size >= MAX_TOKENS) {
            recordSpamPress(now)
            if (spamPressCount >= SPAM_PRESS_THRESHOLD) {
                triggerPunishment(now)
                return false
            }
        }

        // 5. Принимаем
        queue.addLast(item)
        recentTexts[item.text] = now
        onQueueChanged(queueSnapshot())
        ensureWorkerRunning()
        return true
    }

    // ── Внутренние методы ─────────────────────────────────────────────────────

    private fun recordSpamPress(now: Long) {
        if (now - spamWindowStartMs > SPAM_WINDOW_MS) {
            spamPressCount = 1
            spamWindowStartMs = now
        } else {
            spamPressCount++
        }
    }

    private fun triggerPunishment(now: Long) {
        punishmentLevel++
        val durationMs = computePunishmentMs(punishmentLevel)
        punishmentEndMs = now + durationMs
        spamPressCount = 0
        onPunishmentStart(durationMs)
        ensureWorkerRunning()  // воркер будет ждать окончания блокировки
    }

    private fun computePunishmentMs(level: Int): Long = when {
        level <= PUNISHMENT_LEVELS_MS.size -> PUNISHMENT_LEVELS_MS[level - 1]
        else -> {
            // Адаптивный уровень: 10 мин + 5 мин за каждый уровень свыше 5
            val extra = (level - PUNISHMENT_LEVELS_MS.size).toLong() * ADAPTIVE_STEP_MS
            PUNISHMENT_LEVELS_MS.last() + extra
        }
    }

    private fun refreshTokens(now: Long = System.currentTimeMillis()) {
        val elapsed = now - lastRefillMs
        val recovered = (elapsed / TOKEN_RECOVERY_MS).toInt()
        if (recovered > 0) {
            tokens = min(MAX_TOKENS, tokens + recovered)
            lastRefillMs += recovered * TOKEN_RECOVERY_MS
        }
    }

    private fun cleanupRecentTexts(now: Long) {
        recentTexts.entries.removeIf { now - it.value > 60_000L }
    }

    private fun ensureWorkerRunning() {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch { runWorker() }
    }

    /**
     * Основной цикл воркера: ждёт снятия блокировки → ждёт токен →
     * берёт первый элемент очереди → отправляет с retry.
     */
    private suspend fun runWorker() {
        try {
            while (true) {
                // ── Ожидание блокировки ───────────────────────────────────────
                val punishRemaining = punishmentEndMs - System.currentTimeMillis()
                if (punishRemaining > 0) {
                    delay(punishRemaining + 50L)  // небольшой зазор
                    if (System.currentTimeMillis() >= punishmentEndMs) {
                        punishmentEndMs = 0L
                        spamPressCount = 0
                        onPunishmentEnd()
                        maybeReduceLevel()
                    }
                    continue
                }

                // ── Нечего отправлять ─────────────────────────────────────────
                if (queue.isEmpty()) break

                // ── Ожидание токена ───────────────────────────────────────────
                refreshTokens()
                if (tokens == 0) {
                    val waitMs = TOKEN_RECOVERY_MS - (System.currentTimeMillis() - lastRefillMs)
                    delay(waitMs.coerceIn(100L, TOKEN_RECOVERY_MS))
                    continue
                }

                // ── Отправляем первый элемент очереди ─────────────────────────
                tokens--
                val item = queue.first()

                var success = false
                var lastError = "unknown"
                for (attempt in 0..MAX_RETRY) {
                    try {
                        doSend(item.encrypted)
                        success = true
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RateLimitException) {
                        // Rate limit — ждём рекомендованное время перед retry.
                        // Моментальный повтор бесполезен и только добавит ещё один 429/403.
                        lastError = e.message?.take(200) ?: "rate limit"
                        // Сообщаем UI, чтобы показать жёлтую плашку с обратным отсчётом.
                        val waitMs = e.retryAfterMs.coerceAtMost(MAX_RATE_LIMIT_WAIT_MS)
                        try { onRateLimit(waitMs) } catch (_: Exception) {}
                        if (attempt < MAX_RETRY) {
                            delay(waitMs)
                        }
                    } catch (e: Exception) {
                        lastError = e.message?.take(200) ?: "network error"
                        if (attempt < MAX_RETRY) {
                            delay(RETRY_DELAYS_MS[attempt])
                        }
                    }
                }

                queue.removeFirst()

                if (success) {
                    lastGoodSendMs = System.currentTimeMillis()
                    // onMessageSent() contains its own 1.5 s delay before the confirmatory GET,
                    // which is enough for GitHub CDN to propagate the PATCH.
                    try { onMessageSent() } catch (_: Exception) { /* UI error — non-fatal */ }
                } else {
                    // Возвращаем токен: сообщение не ушло из-за сети/rate limit, не из-за спама.
                    // Без этого после серии ошибок bucket пустел и следующая отправка ждала пополнения.
                    tokens = minOf(MAX_TOKENS, tokens + 1)
                    onSendFailed(item.text, lastError)
                    // При ошибке pending нужно убрать сразу — сообщение не ушло
                    onQueueChanged(queueSnapshot())
                }
            }
        } catch (_: CancellationException) {
            // Нормальная отмена при уничтожении Activity
        }
    }

    /** 3 минуты нормальной работы → понижаем уровень блокировки на 1. */
    private fun maybeReduceLevel() {
        if (punishmentLevel > 0) {
            val now = System.currentTimeMillis()
            if (now - lastGoodSendMs > GOOD_BEHAVIOR_MS) {
                punishmentLevel = maxOf(0, punishmentLevel - 1)
            }
        }
    }

    /** Отменить все задачи. Вызывать в onDestroy(). */
    fun cancel() {
        workerJob?.cancel()
    }

    // ── Константы ─────────────────────────────────────────────────────────────

    companion object {
        /** Максимум токенов (burst). */
        const val MAX_TOKENS = 12

        /** Пополнение: 1 токен каждые 2 сек. */
        const val TOKEN_RECOVERY_MS = 400L

        /** Жёсткое ограничение очереди до срабатывания наказания. */
        const val MAX_QUEUE_SIZE = 30

        /**
         * Сколько «спам-нажатий» (в SPAM_WINDOW_MS) нужно для блокировки.
         * Обычный пользователь никогда не достигнет этого порога.
         */
        const val SPAM_PRESS_THRESHOLD = 14

        /** Окно подсчёта спам-нажатий. */
        const val SPAM_WINDOW_MS = 8_000L

        /** Длительности блокировок по уровням (мс): 10с, 30с, 1м, 5м, 10м. */
        val PUNISHMENT_LEVELS_MS = longArrayOf(
            10_000L,
            30_000L,
            60_000L,
            300_000L,
            600_000L
        )

        /** Шаг адаптивного роста для уровней выше 5-го (+5 мин). */
        const val ADAPTIVE_STEP_MS = 5 * 60_000L

        /** Окно «хорошего поведения» для снижения уровня (3 мин). */
        const val GOOD_BEHAVIOR_MS = 3 * 60_000L

        /** Количество retry при сетевых ошибках. */
        const val MAX_RETRY = 5

        /** Задержки между попытками retry (1.5с, 3с, 6с). */
        val RETRY_DELAYS_MS = longArrayOf(800L, 1_500L, 3_000L, 5_000L, 8_000L)

        /** Окно анти-дублей: одинаковый текст в течение 2 сек игнорируется. */
        const val DUPLICATE_WINDOW_MS = 2_000L

        /**
         * Максимальное время ожидания при rate limit перед retry (30 сек).
         * GitHub рекомендует 60 сек, но мы ограничиваем 30 чтобы не подвешивать UI надолго.
         * Если после всех retry ещё rate limit — сообщение помечается как failed.
         */
        private const val MAX_RATE_LIMIT_WAIT_MS = 30_000L
    }
}
