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

    /** null latencyMs = реле не ответило за дедлайн / ошибка. */
    private data class Sample(val atMs: Long, val latencyMs: Long?)

    private val samplesByUrl = ConcurrentHashMap<String, MutableList<Sample>>()

    /** Растёт при каждой новой записи — UI использует как сигнал «есть что перечитать». */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    /** Записывает результат одного запроса к [url]. Вызывать из NostrTransport.queryAllRelays(). */
    fun record(url: String, latencyMs: Long?) {
        val list = samplesByUrl.getOrPut(url) { java.util.Collections.synchronizedList(ArrayList()) }
        synchronized(list) {
            list.add(Sample(System.currentTimeMillis(), latencyMs))
            while (list.size > MAX_SAMPLES) list.removeAt(0)
        }
        _version.update { it + 1 }
    }

    enum class Status { OK, DEGRADED, DOWN, UNKNOWN }

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
            val recentFailures = copy.takeLast(4).count { it.latencyMs == null }
            val status = when {
                copy.isEmpty() -> Status.UNKNOWN
                last?.latencyMs != null -> if (recentFailures == 0) Status.OK else Status.DEGRADED
                recentFailures >= 4 -> Status.DOWN
                else -> Status.DEGRADED
            }
            RelayState(
                index = i + 1,
                status = status,
                latencyMs = last?.latencyMs,
                sparkline = copy.takeLast(10).map { it.latencyMs }
            )
        }
}
