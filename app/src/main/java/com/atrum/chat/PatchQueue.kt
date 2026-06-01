package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext

/**
 * Сериализованная очередь всех PATCH-запросов к GitHub.
 *
 * Все записи (сообщения, реакции, presence, read receipt, edit, delete)
 * проходят через один канал. В каждый момент выполняется ≤ 1 PATCH.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  Action → Channel → [Debounce 350ms] → Batch → PATCH → GitHub  │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Debounce: несколько SaveFile для одного файла за 350 мс объединяются
 *           в один PATCH (последняя версия побеждает).
 *
 * Timeout: 4000 мс на один сетевой вызов → TimeoutCancellationException
 *          → failed state (не вечный зависон).
 *
 * Backoff: 1 с → 2 с → 4 с при ошибках, максимум MAX_RETRIES попыток.
 *
 * Rate limit: очередь засыпает на Retry-After при 429 / 403.
 *
 * Приоритет: AppendLine (сообщения) → ReplaceLine/DeleteLine → SaveFile
 * (presence/reactions не блокируют отправку сообщений в очереди).
 */
class PatchQueue(
    private val transport: ChatTransport,
    private val scope: CoroutineScope
) {

    // ── Action types ──────────────────────────────────────────────────────────

    sealed class Action {

        /**
         * Дозаписать строку в конец chat.txt.
         * [localId] — уникальный идентификатор для ChatStore.confirmSent / failSend.
         */
        data class AppendLine(
            val localId   : String,
            val encrypted : String,
            val onSuccess : () -> Unit = {},
            val onFailure : (reason: String) -> Unit = {}
        ) : Action()

        /**
         * Перезаписать файл целиком (reactions.txt, profiles.txt).
         * Для одного файла за DEBOUNCE_MS побеждает последняя запись.
         */
        data class SaveFile(
            val name    : String,
            val content : String,
            val onDone  : (() -> Unit)? = null
        ) : Action()

        /**
         * Заменить строку в chat.txt (edit сообщения).
         * [onResult] = true если замена прошла, false если строка не найдена.
         */
        data class ReplaceLine(
            val oldLine  : String,
            val newLine  : String,
            val onResult : (Boolean) -> Unit = {}
        ) : Action()

        /**
         * Удалить строку из chat.txt (delete сообщения).
         * [onResult] = true если успешно.
         */
        data class DeleteLine(
            val line     : String,
            val onResult : (Boolean) -> Unit = {}
        ) : Action()
    }

    // ── Channel + worker ──────────────────────────────────────────────────────

    private val channel = Channel<Action>(capacity = Channel.UNLIMITED)
    private val workerJob: Job

    /** Время до которого очередь приостановлена (rate limit). */
    @Volatile private var rateLimitUntilMs = 0L

    init {
        workerJob = scope.launch(Dispatchers.IO) { processLoop() }
    }

    /** Поставить действие в очередь. Thread-safe. */
    fun enqueue(action: Action) {
        channel.trySend(action)
    }

    /** Остановить worker. Вызывать в onDestroy. */
    fun cancel() {
        workerJob.cancel()
        channel.close()
    }

    // ── Processing loop ───────────────────────────────────────────────────────

    private suspend fun processLoop() {
        while (coroutineContext.isActive) {
            // ── Rate limit pause ──────────────────────────────────────────────
            val ratePause = (rateLimitUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (ratePause > 0L) {
                delay(ratePause)
                continue
            }

            // ── Ждём первое действие ──────────────────────────────────────────
            val first = try { channel.receive() } catch (_: Exception) { break }

            // ── Debounce: собираем ещё действия в течение DEBOUNCE_MS ─────────
            val batch = mutableListOf(first)
            val deadline = System.currentTimeMillis() + DEBOUNCE_MS
            while (System.currentTimeMillis() < deadline) {
                val extra = channel.tryReceive().getOrNull() ?: break
                batch.add(extra)
            }

            // ── Обрабатываем batch ────────────────────────────────────────────
            processBatch(batch)
        }
    }

    private suspend fun processBatch(batch: List<Action>) {
        val appends  = batch.filterIsInstance<Action.AppendLine>()
        val saves    = batch.filterIsInstance<Action.SaveFile>()
        val replaces = batch.filterIsInstance<Action.ReplaceLine>()
        val deletes  = batch.filterIsInstance<Action.DeleteLine>()

        // 1. Сообщения — наивысший приоритет, строгий порядок FIFO
        for (action in appends) {
            executeWithRetry(
                onSuccess = action.onSuccess,
                onFailure = action.onFailure
            ) {
                transport.appendLine(action.encrypted)
            }
        }

        // 2. SaveFile: для каждого имени файла — только последнее значение из batch
        //    (debounce: несколько presence / reaction за 350мс → 1 PATCH)
        val mergedSaves = saves.groupBy { it.name }.mapValues { (_, v) -> v.last() }
        for ((_, save) in mergedSaves) {
            executeWithRetry {
                transport.saveFile(save.name, save.content)
            }
            save.onDone?.invoke()
        }

        // 3. Edit (replaceLine)
        for (action in replaces) {
            executeWithRetry {
                val ok = transport.replaceLine(action.oldLine, action.newLine)
                withContext(Dispatchers.Main) { action.onResult(ok) }
            }
        }

        // 4. Delete (deleteLine)
        for (action in deletes) {
            executeWithRetry {
                val ok = transport.deleteLine(action.line)
                withContext(Dispatchers.Main) { action.onResult(ok) }
            }
        }
    }

    // ── Retry / backoff / timeout logic ───────────────────────────────────────

    private suspend fun executeWithRetry(
        onSuccess : (() -> Unit)? = null,
        onFailure : ((String) -> Unit)? = null,
        block     : suspend () -> Unit
    ) {
        var lastError = "unknown"
        for (attempt in 0..MAX_RETRIES) {
            try {
                withTimeout(ACTION_TIMEOUT_MS) { block() }
                withContext(Dispatchers.Main) { onSuccess?.invoke() }
                return

            } catch (e: TimeoutCancellationException) {
                lastError = "timeout after ${ACTION_TIMEOUT_MS}ms"
                if (attempt < MAX_RETRIES) delay(BACKOFF_MS.getOrElse(attempt) { 4_000L })

            } catch (e: RateLimitException) {
                // GitHub rate limit — пауза, не считаем как retry-попытку
                lastError = "rate limit"
                val pause = e.retryAfterMs.coerceIn(30_000L, 120_000L)
                rateLimitUntilMs = System.currentTimeMillis() + pause
                delay(pause)

            } catch (e: TokenExpiredException) {
                // Токен истёк — повторять бесполезно
                lastError = "token expired"
                break

            } catch (e: CancellationException) {
                throw e

            } catch (e: Exception) {
                lastError = e.message?.take(120) ?: "network error"
                if (attempt < MAX_RETRIES) delay(BACKOFF_MS.getOrElse(attempt) { 4_000L })
            }
        }
        // Все попытки исчерпаны
        withContext(Dispatchers.Main) { onFailure?.invoke(lastError) }
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        /** Окно сбора batch: действия за это время объединяются. */
        const val DEBOUNCE_MS = 350L

        /** Максимальное время одного PATCH-запроса до timeout. */
        const val ACTION_TIMEOUT_MS = 4_000L

        /** Максимальное число retry (не считая rate limit пауз). */
        const val MAX_RETRIES = 3

        /** Задержки между retry: 1с → 2с → 4с. */
        val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L)
    }
}
