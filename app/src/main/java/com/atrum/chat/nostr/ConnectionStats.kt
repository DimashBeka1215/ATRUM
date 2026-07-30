package com.atrum.chat.nostr

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * Живая телеметрия соединения с реле — время ответа (RTT) и статус.
 *
 * ⚠️ ПРИВАТНОСТЬ: наружу (UI) отдаются только производные значения — индекс позиции
 * («Реле N»), статус, задержка, спарклайн. Сами URL реле НИКОГДА не покидают этот объект —
 * см. явное требование пользователя не светить рабочие реле в интерфейсе (экран «Соединение»).
 *
 * Данные собираются ПОПУТНО с уже существующим опросом (queryAllRelays в NostrTransport,
 * который и так крутится в едином SyncEngine-цикле) — НИКАКОГО нового polling-цикла здесь
 * не создаётся, см. CLAUDE.md §1.
 */
object ConnectionStats {

    private const val MAX_SAMPLES = 12

    /**
     * Итог одного запроса к реле — различаем сценарии для экрана «Соединение»:
     *  OK      — реле ответило и отдало данные (есть latency);
     *  NO_DATA — ответило чисто (EOSE), но событий нет («реле без данных»);
     *  DOWN    — не подключились / таймаут («реле недоступно / упало»);
     *  ERROR   — реле закрыло подписку с причиной (CLOSED reason: rate-limit/blocked/invalid…).
     */
    enum class Outcome { OK, NO_DATA, DOWN, ERROR }

    private data class Sample(val atMs: Long, val outcome: Outcome, val latencyMs: Long?)

    private val samplesByUrl = ConcurrentHashMap<String, MutableList<Sample>>()

    /** Растёт при каждой новой записи — UI использует как сигнал «есть что перечитать». */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Записывает итог одного запроса к [url]. Вызывать из NostrTransport (телеметрия). */
    fun record(url: String, outcome: Outcome, latencyMs: Long?) {
        val list = samplesByUrl.getOrPut(url) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(list) {
            list.add(Sample(System.currentTimeMillis(), outcome, latencyMs))
            while (list.size > MAX_SAMPLES) list.removeAt(0)
        }
        _version.update { it + 1 }
    }

    /** Статус для UI — прямое отражение сценариев (см. [Outcome]) + сглаживание OK-флапа. */
    enum class Status { OK, DEGRADED, NO_DATA, DOWN, ERROR, UNKNOWN }

    data class RelayState(
        /** 1-based позиция — UI показывает как «Реле N», НЕ имя хоста. */
        val index: Int,
        val status: Status,
        /** Последняя УСПЕШНАЯ задержка в мс, если есть. */
        val latencyMs: Long?,
        /** Недавние сэмплы для мини-спарклайна (null = провал попытки). */
        val sparkline: List<Long?>
    )

    /**
     * Снимок текущего состояния для UI, строго по позиции в [orderedUrls] (стабильный
     * порядок NostrTransport.activeRelays()) — сами URL из этого метода не возвращаются.
     */
    fun snapshot(orderedUrls: List<String>): List<RelayState> =
        orderedUrls.mapIndexed { i, url ->
            val list = samplesByUrl[url]
            val copy = if (list != null) synchronized(list) { list.toList() } else emptyList()
            val last = copy.lastOrNull()
            val recentBad = copy.takeLast(4).count { it.outcome != Outcome.OK }
            val status = when {
                last == null -> Status.UNKNOWN
                last.outcome == Outcome.OK -> if (recentBad == 0) Status.OK else Status.DEGRADED
                last.outcome == Outcome.NO_DATA -> Status.NO_DATA
                last.outcome == Outcome.DOWN -> Status.DOWN
                else -> Status.ERROR
            }
            RelayState(
                index = i + 1,
                status = status,
                // ПОСЛЕДНЯЯ успешная задержка (показываем даже при текущей деградации).
                latencyMs = copy.lastOrNull { it.outcome == Outcome.OK }?.latencyMs,
                // Спарклайн: высота по latency успешных проб, провал (не-OK) = null.
                sparkline = copy.takeLast(10).map { if (it.outcome == Outcome.OK) it.latencyMs else null }
            )
        }
}
