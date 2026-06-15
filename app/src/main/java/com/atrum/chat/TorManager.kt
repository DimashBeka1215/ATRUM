package com.atrum.chat

import android.content.Context
import io.matthewnelson.kmp.file.toFile
import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
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
        if (st == TorStatus.CONNECTING || st == TorStatus.READY) return
        _status.value = TorStatus.CONNECTING
        val appCtx = context.applicationContext
        scope.launch {
            startMutex.withLock {
                if (_status.value == TorStatus.READY) return@withLock
                try {
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
                            if (line.contains("Bootstrapped 100%")) {
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
                } catch (_: Throwable) {
                    // FAILED → следующий вызов start() выполнит повторную попытку.
                    _status.value = TorStatus.FAILED
                }
            }
        }
    }
}
