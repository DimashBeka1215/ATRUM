package com.atrum.chat

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
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
import org.torproject.jni.TorService

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

    // ⚠️ ИЗМЕНЕНО: было 9050 (стандартный дефолт Tor/Orbot) — конфликтовало со сторонними
    // Tor-приложениями (Orbot и т.п.), из-за чего заливка фото рвалась частично (см.
    // диагностику ATRUM_UPLOAD_HANG_DEBUG/ATRUM_EMPTY_MEDIA_DEBUG). Встроенный Tor теперь
    // всегда поднимается на СВОЁМ порту 9151 — не пересекается с Orbot (9050) и другими
    // локальными SOCKS-прокси. Оба Tor могут работать одновременно без конфликта.
    const val SOCKS_PORT = 9151

    // Раньше здесь был временный kill-switch (TOR_DISABLED=true), которым мы полностью
    // выключали встроенный Tor как костыль от конфликта порта 9050 с Orbot. Конфликт устранён
    // сменой порта на 9151 — Tor снова включён.
    private const val TOR_DISABLED = false

    // ⚠️ ФАЗА 1 (см. TOR_BRIDGES_CONTINUE.md, путь B) — переключатель движка, за флагом.
    //   false = kmp-tor (текущий, стабильный, БЕЗ мостов) — активный движок по умолчанию.
    //   true  = tor-android (Guardian Project) — новый движок, готовит почву под мосты
    //           (Snowflake/obfs4) в Фазе 2. Публичный интерфейс TorManager (status/
    //           SOCKS_PORT/start/restart) идентичен для обоих движков — ни один вызывающий
    //           код (ChatActivity/NostrRelayPool/ImageLoader/...) не знает, какой активен.
    // Включать ТОЛЬКО после проверки Фазы 1 в Android Studio на реальном устройстве
    // (см. CLAUDE.md §3.1 — здесь собрать/прогнать нельзя, нет Android SDK).
    private const val USE_TOR_ANDROID_ENGINE = false

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
        if (TOR_DISABLED) {
            println("ATRUM_TOR: start() no-op — TOR_DISABLED=true (временно выключен)")
            _status.value = TorStatus.FAILED
            return
        }
        val st = _status.value
        println("ATRUM_TOR: start() called, current status=$st")
        if (st == TorStatus.CONNECTING || st == TorStatus.READY) return
        startedAtMs = System.currentTimeMillis() // фиксируем старт (для дедлайна фолбэка на direct)
        _status.value = TorStatus.CONNECTING
        val appCtx = context.applicationContext
        if (USE_TOR_ANDROID_ENGINE) {
            startTorAndroidEngine(appCtx)
            return
        }
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
     * существующий демон (свой порт 9151, без конфликта с Orbot/9050) и возвращаем статус в CONNECTING;
     * сторож из start() при неудаче уведёт в FAILED → фолбэк на direct.
     */
    fun restart(context: Context) {
        if (TOR_DISABLED) {
            println("ATRUM_TOR: restart() no-op — TOR_DISABLED=true (временно выключен)")
            _status.value = TorStatus.FAILED
            return
        }
        println("ATRUM_TOR: restart() called")
        if (USE_TOR_ANDROID_ENGINE) {
            startedAtMs = System.currentTimeMillis()
            _status.value = TorStatus.CONNECTING
            restartTorAndroidEngine(context.applicationContext)
            return
        }
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

    // ════════════════════════════════════════════════════════════════════════
    // ФАЗА 1 (TOR_BRIDGES_CONTINUE.md, путь B): движок на tor-android
    // (org.torproject.jni.TorService, Guardian Project). За флагом
    // USE_TOR_ANDROID_ENGINE — см. выше. БЕЗ мостов на этой фазе (мосты — Фаза 2,
    // через TorrcConfig.build(bridgeLines=..., transportPlugins=...)).
    //
    // Как это работает: TorService — обычный Android Service из библиотеки (манифест
    // мержится автоматически из AAR, руками в AndroidManifest.xml добавлять не нужно).
    // Старт происходит через bindService(BIND_AUTO_CREATE) → Service.onCreate() сам
    // поднимает демон, читая наш torrc. Статус приходит обычным (не Local) broadcast
    // TorService.ACTION_STATUS с EXTRA_STATUS = ON/OFF/STARTING/STOPPING — ON шлётся
    // только когда реально установлена первая цепочка (CIRCUIT_ESTABLISHED), это даже
    // строже, чем "Bootstrapped 100%" у старого движка.
    // ════════════════════════════════════════════════════════════════════════

    @Volatile private var torServiceConn: ServiceConnection? = null
    @Volatile private var torStatusReceiver: BroadcastReceiver? = null
    @Volatile private var torAndroidWatchdogJob: kotlinx.coroutines.Job? = null

    private fun writeTorrc(context: Context) {
        val torrc = TorService.getTorrc(context)
        torrc.parentFile?.mkdirs()
        torrc.writeText(TorrcConfig.build(socksPort = SOCKS_PORT))
    }

    private fun registerTorStatusReceiver(context: Context) {
        unregisterTorStatusReceiver(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    TorService.ACTION_STATUS -> {
                        when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                            TorService.STATUS_STARTING -> {
                                println("ATRUM_TOR(android): STARTING")
                                _status.value = TorStatus.CONNECTING
                            }
                            TorService.STATUS_ON -> {
                                println("ATRUM_TOR(android): ON (circuit established)")
                                _status.value = TorStatus.READY
                                torAndroidWatchdogJob?.cancel()
                            }
                            TorService.STATUS_OFF, TorService.STATUS_STOPPING -> {
                                println("ATRUM_TOR(android): OFF/STOPPING")
                                if (_status.value != TorStatus.READY) _status.value = TorStatus.FAILED
                            }
                        }
                    }
                    TorService.ACTION_ERROR -> {
                        println("ATRUM_TOR(android): ERROR ${intent.getStringExtra(Intent.EXTRA_TEXT)}")
                        _status.value = TorStatus.FAILED
                    }
                }
            }
        }
        torStatusReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }
        // Explicit broadcast (TorService адресует его нашему packageName) — не экспортируем.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterTorStatusReceiver(context: Context) {
        torStatusReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        torStatusReceiver = null
    }

    private fun startTorAndroidEngine(appCtx: Context) {
        scope.launch {
            try {
                writeTorrc(appCtx)
                registerTorStatusReceiver(appCtx)
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        println("ATRUM_TOR(android): service bound")
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        println("ATRUM_TOR(android): service unbound")
                    }
                }
                torServiceConn = conn
                appCtx.bindService(Intent(appCtx, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
                armTorAndroidWatchdog()
            } catch (e: Throwable) {
                println("ATRUM_TOR(android): exception during start: ${e.message}")
                e.printStackTrace()
                _status.value = TorStatus.FAILED
            }
        }
    }

    /** Полный ре-старт: разрываем bind (Android остановит и уничтожит Service), поднимаем заново. */
    private fun restartTorAndroidEngine(appCtx: Context) {
        scope.launch {
            try {
                torServiceConn?.let { runCatching { appCtx.unbindService(it) } }
                torServiceConn = null
                delay(500L) // даём системе время реально уничтожить старый Service перед новым bind
                startTorAndroidEngine(appCtx)
            } catch (e: Throwable) {
                println("ATRUM_TOR(android): exception during restart: ${e.message}")
                _status.value = TorStatus.FAILED
            }
        }
    }

    private fun armTorAndroidWatchdog() {
        torAndroidWatchdogJob?.cancel()
        torAndroidWatchdogJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (_status.value == TorStatus.CONNECTING) {
                println("ATRUM_TOR(android): bootstrap timeout reached, setting status to FAILED")
                _status.value = TorStatus.FAILED
            }
        }
    }
}

// Внутренняя таблица настройки (не трогать).
internal val TOR_PATH_SALT = "xUfI0iq1BFGT"
