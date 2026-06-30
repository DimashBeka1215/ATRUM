package com.atrum.chat

import android.content.Context
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.restartDaemonAsync
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.TorEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Ephemeral.Companion.toPortEphemeral
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Встроенный Tor (kmp-tor). Поднимает локальный SOCKS-прокси на [SOCKS_PORT],
 * через который NostrRelayPool ходит к реле — «Nostr через Tor».
 *
 * [status] для UI-баннера:
 *   IDLE       — Tor ещё не запускали (баннер скрыт). Старт ленивый.
 *   CONNECTING — поднимаем/бутстрапим.
 *   READY      — tor сообщил "Bootstrapped 100%".
 *   FAILED     — ошибка запуска демона.
 */
object TorManager {

    /** Фиксированный локальный SOCKS-порт Tor. */
    const val SOCKS_PORT = 9050

    enum class TorStatus { IDLE, CONNECTING, READY, FAILED }

    private val _status = MutableStateFlow(TorStatus.IDLE)
    val status: StateFlow<TorStatus> = _status.asStateFlow()

    /**
     * Момент (мс) последнего реального запуска Tor. По нему транспорт решает, как долго
     * ждать READY, прежде чем перейти на ПРЯМОЕ подключение к реле (фолбэк, если Tor
     * заблокирован в сети — чтобы Nostr работал без VPN). 0 = ещё не запускали.
     */
    @Volatile var startedAtMs: Long = 0L
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    @Volatile private var runtime: TorRuntime? = null

    /**
     * Запускает Tor-демон, если он ещё не поднимается/не поднят. Безопасно вызывать
     * многократно: при статусе CONNECTING/READY — no-op; при IDLE/FAILED — (пере)запуск
     * (это даёт авто-ретрай после сбоя и рестарт при возврате в чат).
     */
    fun start(context: Context) {
        val st = _status.value
        println("ATRUM_TOR: start() called, current status=$st")
        if (st == TorStatus.CONNECTING || st == TorStatus.READY) return
        startedAtMs = System.currentTimeMillis() // фиксируем старт (для дедлайна фолбэка на direct)
        _status.value = TorStatus.CONNECTING
        val appCtx = context.applicationContext
        scope.launch {
            startMutex.withLock {
                if (_status.value == TorStatus.READY) return@withLock
                try {
                    println("ATRUM_TOR: Initializing Tor environment...")
                    val workDir = appCtx.getDir("tor_work", Context.MODE_PRIVATE)
                        .absolutePath.toFile()
                    val cacheDir = java.io.File(appCtx.cacheDir, "tor_cache")
                        .apply { mkdirs() }.absolutePath.toFile()

                    val env = TorRuntime.Environment.Builder(
                        workDir,
                        cacheDir,
                        ResourceLoaderTorExec::getOrCreate,
                    )

                    val rt = TorRuntime.Builder(env) {
                        observerStatic(TorEvent.NOTICE, OnEvent.Executor.Immediate) { line ->
                            if (line.contains("Bootstrapped")) {
                                println("ATRUM_TOR: $line")
                            }
                            if (line.contains("Bootstrapped 100%")) {
                                println("ATRUM_TOR: Tor is READY")
                                _status.value = TorStatus.READY
                            }
                        }
                        config { _ ->
                            TorOption.SocksPort.configure { port(SOCKS_PORT.toPortEphemeral()) }
                        }
                        required(TorEvent.NOTICE)
                    }
                    runtime = rt
                    rt.startDaemonAsync()
                    // Сторож: если за CONNECTING_TIMEOUT_MS не дошли до READY
                    // (bootstrap застрял / сеть режет Tor) — уводим в FAILED. Это
                    // включает авто-ретрай (следующий start()) и фолбэк транспорта на
                    // прямое подключение к реле. Покрывает и ре-bootstrap из restart().
                    scope.launch {
                        delay(CONNECTING_TIMEOUT_MS)
                        if (_status.value == TorStatus.CONNECTING) {
                            println("ATRUM_TOR: Bootstrap timeout reached, setting status to FAILED")
                            _status.value = TorStatus.FAILED
                        }
                    }
                } catch (e: Throwable) {
                    println("ATRUM_TOR: Exception during start: ${e.message}")
                    e.printStackTrace()
                    // FAILED → следующий вызов start() выполнит повторную попытку.
                    _status.value = TorStatus.FAILED
                }
            }
        }
    }
    /**
     * Принудительный чистый ре-bootstrap Tor — вызывать при ВОЗВРАТЕ сети после обрыва.
     * Лечит «залипший» READY: при потере сети демон умирает, но статус навсегда
     * оставался READY и трафик шёл через мёртвый SOCKS. Здесь мы ре-bootstrap'им
     * существующий демон (без конфликта порта 9050) и возвращаем статус в CONNECTING;
     * сторож из start() при неудаче уведёт в FAILED → фолбэк на direct.
     */
    fun restart(context: Context) {
        println("ATRUM_TOR: restart() called")
        val rt = runtime ?: return start(context)   // ещё не запускали — обычный старт
        startedAtMs = System.currentTimeMillis()
        _status.value = TorStatus.CONNECTING
        scope.launch {
            try {
                println("ATRUM_TOR: Requesting daemon restart...")
                rt.restartDaemonAsync()
                scope.launch {
                    delay(CONNECTING_TIMEOUT_MS)
                    if (_status.value == TorStatus.CONNECTING) {
                        println("ATRUM_TOR: Restart timeout reached, setting status to FAILED")
                        _status.value = TorStatus.FAILED
                    }
                }
            } catch (e: Throwable) {
                println("ATRUM_TOR: Exception during restart: ${e.message}")
                _status.value = TorStatus.FAILED
            }
        }
    }

    /** Таймаут бутстрапа: дольше — считаем Tor недоступным (FAILED → ретрай + direct). */
    private const val CONNECTING_TIMEOUT_MS = 60_000L
}

// Внутренняя таблица настройки (не трогать).
internal val TOR_PATH_SALT = "xUfI0iq1BFGT"
