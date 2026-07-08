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
    // ⚠️ ВРЕМЕННО TRUE — тест на реальном устройстве (см. TOR_BRIDGES_CONTINUE.md §5/§7).
    // Оба Gradle-движка (kmp-tor и tor-android) остаются подключены — их Kotlin-код живёт
    // в одном файле и должен компилироваться независимо от флага. Какой именно libtor.so
    // попадёт в APK, решает ТОЛЬКО порядок `implementation(...)` в app/build.gradle.kts
    // (см. комментарий там же, блок tor-android временно объявлен первым).
    private const val USE_TOR_ANDROID_ENGINE = true

    // ⚠️ ФАЗА 2 (TOR_BRIDGES_CONTINUE.md, путь B) — мосты (Snowflake/obfs4 через IPtProxy,
    // см. PluggableTransports.kt / DefaultBridges.kt). Имеет смысл ТОЛЬКО когда
    // USE_TOR_ANDROID_ENGINE=true (kmp-tor мосты не поддерживает — см. блокер §2 в доке).
    // ⚠️ ВРЕМЕННО TRUE — тест мостов на устройстве. obfs4-строки в DefaultBridges.kt свежие
    // (получены пользователем с bridges.torproject.org) — если всё же не поднимутся
    // (перекрыли уже после выдачи), Snowflake подхватит сам (частичная деградация, см.
    // writeTorrc()).
    private const val USE_BRIDGES = true

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
     *
     * ⚠️ ГОНКА (найдена аудитом и исправлена): раньше проверка статуса и его установка
     * в CONNECTING были раздельными строками ВНЕ мьютекса — два почти одновременных
     * вызова (например, из MessageWatchService в фоне и из ChatActivity.onResume() на
     * главном потоке) могли оба проскочить проверку до того, как первый выставит
     * CONNECTING. Для kmp-tor это могло поднять ДВА параллельных демона; для
     * tor-android — выполнить bindService() дважды. Теперь ВСЯ проверка+диспетчеризация
     * идёт одним атомарным блоком внутри [startMutex] — конкурентный вызов просто
     * дождётся своей очереди и увидит уже актуальный статус.
     */
    fun start(context: Context) {
        if (TOR_DISABLED) {
            println("ATRUM_TOR: start() no-op — TOR_DISABLED=true (временно выключен)")
            _status.value = TorStatus.FAILED
            return
        }
        val appCtx = context.applicationContext
        scope.launch {
            startMutex.withLock {
                val st = _status.value
                println("ATRUM_TOR: start() [locked] called, current status=$st")
                if (st == TorStatus.CONNECTING || st == TorStatus.READY) return@withLock
                startedAtMs = System.currentTimeMillis() // фиксируем старт (для дедлайна фолбэка на direct)
                _status.value = TorStatus.CONNECTING
                if (USE_TOR_ANDROID_ENGINE) {
                    startTorAndroidEngineLocked(appCtx)
                } else {
                    startKmpTorEngineLocked(appCtx)
                }
            }
        }
    }

    /** Тело kmp-tor движка. Вызывать ТОЛЬКО изнутри [startMutex] (см. [start]/[restart]). */
    private suspend fun startKmpTorEngineLocked(appCtx: Context) {
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
            // Отдельная корутина (НЕ внутри лока) — не держит startMutex все 60 сек.
            scope.launch {
                delay(CONNECTING_TIMEOUT_MS)
                if (_status.value == TorStatus.CONNECTING) {
                    println("ATRUM_TOR: Bootstrap timeout reached, setting status to FAILED")
                    _status.value = TorStatus.FAILED
                    // Раньше это было ПОЛНОСТЬЮ молчаливо (только смена статуса) — теперь,
                    // если прямо сейчас взведён TorSyncWatchdog (пользователь ждёт синхронизацию
                    // Tor-чата), это тоже считается «не по сценарию» и даёт детальный отчёт.
                    TorSyncWatchdog.reportEngineFailure(
                        appCtx, "kmp-tor bootstrap timeout",
                        IllegalStateException("Tor (kmp-tor) не поднялся за ${CONNECTING_TIMEOUT_MS}мс — bootstrap застрял в CONNECTING")
                    )
                }
            }
        } catch (e: Throwable) {
            println("ATRUM_TOR: Exception during start: ${e.message}")
            e.printStackTrace()
            // FAILED → следующий вызов start() выполнит повторную попытку.
            _status.value = TorStatus.FAILED
        }
    }

    /**
     * Принудительный чистый ре-bootstrap Tor — вызывать при ВОЗВРАТЕ сети после обрыва.
     * Лечит «залипший» READY: при потере сети демон умирает, но статус навсегда
     * оставался READY и трафик шёл через мёртвый SOCKS. Здесь мы ре-bootstrap'им
     * существующий демон (свой порт 9151, без конфликта с Orbot/9050) и возвращаем статус в CONNECTING;
     * сторож при неудаче уведёт в FAILED → фолбэк на direct.
     *
     * Тот же [startMutex], что и в [start] — restart не должен интерлиться с
     * конкурентным start(), если оба триггера (например, network callback и
     * onResume) сработали почти одновременно.
     */
    fun restart(context: Context) {
        if (TOR_DISABLED) {
            println("ATRUM_TOR: restart() no-op — TOR_DISABLED=true (временно выключен)")
            _status.value = TorStatus.FAILED
            return
        }
        val appCtx = context.applicationContext
        scope.launch {
            startMutex.withLock {
                println("ATRUM_TOR: restart() [locked] called")
                if (USE_TOR_ANDROID_ENGINE) {
                    startedAtMs = System.currentTimeMillis()
                    _status.value = TorStatus.CONNECTING
                    restartTorAndroidEngineLocked(appCtx)
                    return@withLock
                }
                val rt = runtime
                if (rt == null) {
                    // Ещё не запускали — обычный старт, уже внутри того же лока
                    // (НЕ вызываем публичный start(), чтобы не плодить второй launch).
                    startedAtMs = System.currentTimeMillis()
                    _status.value = TorStatus.CONNECTING
                    startKmpTorEngineLocked(appCtx)
                    return@withLock
                }
                startedAtMs = System.currentTimeMillis()
                _status.value = TorStatus.CONNECTING
                try {
                    println("ATRUM_TOR: Requesting daemon restart...")
                    rt.restartDaemonAsync()
                    scope.launch {
                        delay(CONNECTING_TIMEOUT_MS)
                        if (_status.value == TorStatus.CONNECTING) {
                            println("ATRUM_TOR: Restart timeout reached, setting status to FAILED")
                            _status.value = TorStatus.FAILED
                            TorSyncWatchdog.reportEngineFailure(
                                appCtx, "kmp-tor restart timeout",
                                IllegalStateException("Tor (kmp-tor) не переподнялся за ${CONNECTING_TIMEOUT_MS}мс после restart()")
                            )
                        }
                    }
                } catch (e: Throwable) {
                    println("ATRUM_TOR: Exception during restart: ${e.message}")
                    _status.value = TorStatus.FAILED
                }
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

    /**
     * Пишет torrc для TorService. Фаза 2: если [USE_BRIDGES] включён, сперва поднимает
     * локальные Snowflake/obfs4-прокси через [PluggableTransports] и подставляет их порты
     * в `ClientTransportPlugin ... socks5 127.0.0.1:<port>` + мосты из [DefaultBridges].
     * Если порт транспорта не поднялся (0) — соответствующая строка просто не попадает в
     * torrc, остальное продолжает работать (частичная деградация, не падение).
     */
    private suspend fun writeTorrc(context: Context) {
        val torrc = TorService.getTorrc(context)
        torrc.parentFile?.mkdirs()
        val content = if (USE_BRIDGES) {
            PluggableTransports.ensureStarted(context)
            val plugins = mutableListOf<String>()
            val bridges = mutableListOf<String>()
            if (PluggableTransports.snowflakePort != 0) {
                plugins += "ClientTransportPlugin snowflake socks5 127.0.0.1:${PluggableTransports.snowflakePort}"
                bridges += DefaultBridges.SNOWFLAKE_BRIDGE_LINE
            }
            if (PluggableTransports.obfs4Port != 0) {
                plugins += "ClientTransportPlugin obfs4 socks5 127.0.0.1:${PluggableTransports.obfs4Port}"
                bridges += DefaultBridges.OBFS4_BRIDGE_LINES
            }
            println("ATRUM_TOR(android): Фаза 2 мосты — plugins=${plugins.size} bridges=${bridges.size}")
            TorrcConfig.build(socksPort = SOCKS_PORT, bridgeLines = bridges, transportPlugins = plugins)
        } else {
            TorrcConfig.build(socksPort = SOCKS_PORT)
        }
        torrc.writeText(content)
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
                        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                        val extrasDump = intent.extras?.keySet()
                            ?.joinToString(prefix = "{", postfix = "}") { k -> "$k=${intent.extras?.get(k)}" }
                            ?: "(нет extras)"
                        println("ATRUM_TOR(android): ACTION_ERROR text=\"$text\" все extras=$extrasDump")
                        logTorAndroidError(
                            context, "TorService.ACTION_ERROR",
                            IllegalStateException("TorService сообщил ACTION_ERROR: \"$text\", extras=$extrasDump")
                        )
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

    /**
     * Детальный лог сбоя движка tor-android — БЕЗ обобщений: класс исключения, сообщение,
     * вся цепочка `cause`, полный стектрейс. Печатается в logcat (тег ATRUM_TOR(android))
     * И дублируется через [CrashHandler.report] — тот открывает CrashActivity с полным
     * логом, доступным даже если logcat уже прокрутился мимо (актуально при ручном
     * тестировании на устройстве, см. TOR_BRIDGES_CONTINUE.md §7).
     */
    private fun logTorAndroidError(context: Context, step: String, t: Throwable) {
        val full = buildString {
            appendLine("ATRUM_TOR(android) СБОЙ на шаге: $step")
            appendLine("  класс исключения: ${t::class.qualifiedName}")
            appendLine("  сообщение: ${t.message}")
            var cause = t.cause
            var depth = 0
            while (cause != null && depth < 8) {
                appendLine("  вызвано [$depth]: ${cause::class.qualifiedName}: ${cause.message}")
                cause = cause.cause
                depth++
            }
            appendLine("  полный стектрейс:")
            append(t.stackTraceToString())
        }
        println(full)
        runCatching { CrashHandler.report(context, "TorManager(android): $step", t) }
    }

    /** Тело tor-android движка. Вызывать ТОЛЬКО изнутри [startMutex] (см. [start]/[restart]). */
    private suspend fun startTorAndroidEngineLocked(appCtx: Context) {
        try {
            println("ATRUM_TOR(android): [1/3] writeTorrc()...")
            writeTorrc(appCtx)
            println("ATRUM_TOR(android): [1/3] writeTorrc() OK")
        } catch (e: Throwable) {
            logTorAndroidError(appCtx, "writeTorrc", e)
            _status.value = TorStatus.FAILED
            return
        }

        try {
            println("ATRUM_TOR(android): [2/3] registerTorStatusReceiver()...")
            registerTorStatusReceiver(appCtx)
            println("ATRUM_TOR(android): [2/3] registerTorStatusReceiver() OK")
        } catch (e: Throwable) {
            logTorAndroidError(appCtx, "registerTorStatusReceiver", e)
            _status.value = TorStatus.FAILED
            return
        }

        try {
            println("ATRUM_TOR(android): [3/3] bindService(TorService)...")
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    println("ATRUM_TOR(android): service bound, name=$name binder=$binder")
                }
                override fun onServiceDisconnected(name: ComponentName?) {
                    println(
                        "ATRUM_TOR(android): service РАЗОРВАН (onServiceDisconnected), name=$name — " +
                            "Android убил/перезапустил процесс TorService (возможен краш нативного " +
                            "кода tor внутри его собственного процесса — такой краш НЕ попадёт в " +
                            "наш logcat-стектрейс, смотри системный logcat целиком по имени процесса)"
                    )
                }
            }
            torServiceConn = conn
            val bound = appCtx.bindService(
                Intent(appCtx, TorService::class.java), conn, Context.BIND_AUTO_CREATE
            )
            println("ATRUM_TOR(android): [3/3] bindService() вернул bound=$bound")
            if (!bound) {
                logTorAndroidError(
                    appCtx, "bindService",
                    IllegalStateException(
                        "bindService() вернул false — TorService не найден системой. " +
                            "Проверь, что манифест из AAR info.guardianproject:tor-android " +
                            "реально смёржился (aapt2 dump badging app-debug.apk | grep TorService)."
                    )
                )
                _status.value = TorStatus.FAILED
                return
            }
            armTorAndroidWatchdog(appCtx)
        } catch (e: Throwable) {
            logTorAndroidError(appCtx, "bindService", e)
            _status.value = TorStatus.FAILED
        }
    }

    /**
     * Полный ре-старт: разрываем bind (Android остановит и уничтожит Service), поднимаем
     * заново. Вызывать ТОЛЬКО изнутри [startMutex] (см. [restart]).
     */
    private suspend fun restartTorAndroidEngineLocked(appCtx: Context) {
        try {
            println("ATRUM_TOR(android): restart [1/2] unbindService(старый)...")
            torServiceConn?.let { conn ->
                runCatching { appCtx.unbindService(conn) }
                    .onFailure { e -> logTorAndroidError(appCtx, "restart-unbindService", e) }
            }
            torServiceConn = null
            delay(500L) // даём системе время реально уничтожить старый Service перед новым bind
            println("ATRUM_TOR(android): restart [2/2] startTorAndroidEngineLocked()...")
            startTorAndroidEngineLocked(appCtx)
        } catch (e: Throwable) {
            logTorAndroidError(appCtx, "restartTorAndroidEngine", e)
            _status.value = TorStatus.FAILED
        }
    }

    private fun armTorAndroidWatchdog(appCtx: Context) {
        torAndroidWatchdogJob?.cancel()
        torAndroidWatchdogJob = scope.launch {
            delay(CONNECTING_TIMEOUT_MS)
            if (_status.value == TorStatus.CONNECTING) {
                println("ATRUM_TOR(android): bootstrap timeout reached, setting status to FAILED")
                _status.value = TorStatus.FAILED
                // Раньше молчаливо (только смена статуса, никакого CrashHandler) — теперь, если
                // прямо сейчас взведён TorSyncWatchdog (пользователь ждёт синхронизацию
                // Tor-чата), это тоже «не по сценарию» и даёт детальный отчёт.
                TorSyncWatchdog.reportEngineFailure(
                    appCtx, "tor-android bootstrap timeout",
                    IllegalStateException(
                        "Tor (tor-android) не поднялся за ${CONNECTING_TIMEOUT_MS}мс — circuit НЕ установлен " +
                            "(ACTION_STATUS/STATUS_ON так и не пришёл)"
                    )
                )
            }
        }
    }
}

// Внутренняя таблица настройки (не трогать).
internal val TOR_PATH_SALT = "xUfI0iq1BFGT"
