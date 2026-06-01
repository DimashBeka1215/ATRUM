package com.atrum.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Адаптивная очередь загрузки изображений с защитой от GitHub rate limit.
 *
 * Архитектура:
 *  - [MAX_CONCURRENT] параллельных загрузок (Semaphore): по умолчанию 3.
 *    Изображения в одной sendImages-партии загружаются параллельно, но
 *    не более 3 одновременно → быстро и не бьём GitHub.
 *
 *  - Адаптивный throttle: при HTTP 429 или "rate limit" добавляется
 *    задержка перед следующим запросом. При успехе — плавно снижается.
 *
 *  - Retry с exponential backoff до [MAX_RETRY] попыток.
 *
 *  - Полностью изолирован от текстового pipeline: не конкурирует за
 *    writeMutex чат-gist'а, использует отдельный POST-запрос (createImageGist).
 *
 * Нормальный режим (нет ошибок):
 *   throttleDelayMs = 0 → почти нулевые задержки между загрузками.
 *
 * Режим защиты (429 / rate limit):
 *   throttleDelayMs растёт на [THROTTLE_STEP_MS] за каждую ошибку (макс. [MAX_THROTTLE_MS]).
 *   После восстановления — снижается на [THROTTLE_DECAY_MS] за каждый успех.
 */
class ImageUploadQueue {

    private val semaphore = Semaphore(MAX_CONCURRENT)

    @Volatile private var throttleDelayMs = 0L
    @Volatile private var consecutiveErrors = 0

    /**
     * Выполняет загрузку [upload] с контролем concurrency, throttling и retry.
     *
     * Suspend-функция: блокирует корутину (не поток).
     * Вызывать из lifecycleScope / coroutineScope — не из Main-потока напрямую.
     */
    suspend fun <T> execute(upload: suspend () -> T): T {
        return semaphore.withPermit {
            val delay = throttleDelayMs
            if (delay > 0) delay(delay)

            var lastError: Exception? = null
            for (attempt in 0..MAX_RETRY) {
                try {
                    val result = upload()
                    onSuccess()
                    return@withPermit result
                } catch (e: CancellationException) {
                    throw e   // корутина отменена — не ловим
                } catch (e: Exception) {
                    lastError = e
                    val is429 = isRateLimitError(e)
                    onError(is429)
                    if (attempt < MAX_RETRY) {
                        val backoff = if (is429) {
                            RATE_LIMIT_BACKOFF_MS * (attempt + 1L)
                        } else {
                            BASE_RETRY_BACKOFF_MS * (1L shl attempt)
                        }
                        delay(backoff)
                    }
                }
            }
            throw lastError ?: RuntimeException("Upload failed after $MAX_RETRY retries")
        }
    }

    private fun isRateLimitError(e: Exception): Boolean {
        val msg = e.message ?: return false
        return "429" in msg ||
               "rate limit" in msg.lowercase() ||
               "secondary rate" in msg.lowercase()
    }

    private fun onSuccess() {
        consecutiveErrors = 0
        if (throttleDelayMs > 0) {
            throttleDelayMs = maxOf(0L, throttleDelayMs - THROTTLE_DECAY_MS)
        }
    }

    private fun onError(is429: Boolean) {
        consecutiveErrors++
        if (is429) {
            throttleDelayMs = minOf(MAX_THROTTLE_MS, throttleDelayMs + THROTTLE_STEP_MS)
        }
    }

    companion object {
        /** Максимум параллельных загрузок. */
        const val MAX_CONCURRENT = 3

        /** Количество retry при ошибке (не считая первой попытки). */
        const val MAX_RETRY = 3

        /** Базовая задержка retry при сетевой ошибке (мс). Удваивается каждый attempt. */
        const val BASE_RETRY_BACKOFF_MS = 1_500L

        /** Базовая задержка при 429/rate limit (мс). Умножается на номер попытки. */
        const val RATE_LIMIT_BACKOFF_MS = 8_000L

        /** Шаг увеличения throttle delay при rate limit (мс). */
        const val THROTTLE_STEP_MS = 3_000L

        /** Шаг уменьшения throttle delay при успехе (мс). */
        const val THROTTLE_DECAY_MS = 500L

        /** Максимальный throttle delay (мс). */
        const val MAX_THROTTLE_MS = 30_000L
    }
}
