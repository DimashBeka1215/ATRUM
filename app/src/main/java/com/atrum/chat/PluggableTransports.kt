package com.atrum.chat

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Обёртка над IPtProxy (Lyrebird/obfs4 + Snowflake) — pluggable transports для мостов Tor.
 * Фаза 2 (см. TOR_BRIDGES_CONTINUE.md, путь B, §4/§5).
 *
 * Используется ТОЛЬКО из [TorManager], когда включены оба флага:
 * `USE_TOR_ANDROID_ENGINE=true` и `USE_BRIDGES=true` (по умолчанию — оба false, код неактивен).
 *
 * IPtProxy запускает Go-биндинги obfs4proxy/snowflake локально и открывает СВОИ SOCKS5-порты.
 * torrc ссылается на них через `ClientTransportPlugin ... socks5 127.0.0.1:<port>` — сам процесс
 * подключения к мостам (`Bridge ...`) дальше выполняет уже tor поверх этих локальных портов.
 */
object PluggableTransports {

    // ⚠️ Строковые литералы, а НЕ `IPtProxy.Snowflake`/`IPtProxy.Obfs4` — это реальные
    // package-level Go-константы (controller.go: `Snowflake = "snowflake"`,
    // `Obfs4 = "obfs4"`), но в gomobile-байндинге они всплывают не так, как ожидалось
    // изначально (компилятор дал "Unresolved reference 'Snowflake'"). Литералы —
    // гарантированно верны, т.к. это ТЕ ЖЕ значения, что в самом Go-исходнике.
    private const val TRANSPORT_SNOWFLAKE = "snowflake"
    private const val TRANSPORT_OBFS4 = "obfs4"

    @Volatile private var controller: IPtProxy.Controller? = null

    @Volatile var snowflakePort: Int = 0
        private set

    @Volatile var obfs4Port: Int = 0
        private set

    /**
     * Стартует Snowflake + obfs4 (если ещё не запущены). Возвращает true, если оба порта
     * получены в пределах [timeoutMs] — иначе false (тогда вызывающий код должен передать в
     * torrc только те `ClientTransportPlugin`, чей порт реально поднялся, см. TorManager).
     */
    suspend fun ensureStarted(context: Context, timeoutMs: Long = 15_000L): Boolean {
        if (controller != null && (snowflakePort != 0 || obfs4Port != 0)) return true
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { startInternal(context) }
        } != null && (snowflakePort != 0 || obfs4Port != 0)
    }

    /**
     * Детальный лог сбоя PT — БЕЗ обобщений: класс исключения, сообщение, полный стектрейс.
     * Дублируется через [CrashHandler.report] (полный лог доступен через CrashActivity, даже
     * если logcat уже прокрутился мимо при ручном тесте на устройстве).
     */
    private fun logPtError(context: Context, step: String, t: Throwable) {
        val full = buildString {
            appendLine("ATRUM_PT СБОЙ на шаге: $step")
            appendLine("  класс исключения: ${t::class.qualifiedName}")
            appendLine("  сообщение: ${t.message}")
            appendLine("  полный стектрейс:")
            append(t.stackTraceToString())
        }
        println(full)
        runCatching { CrashHandler.report(context, "PluggableTransports: $step", t) }
    }

    private fun startInternal(context: Context) {
        if (controller != null) return
        val stateDir = java.io.File(context.cacheDir, "pt_state").apply { mkdirs() }.absolutePath
        println("ATRUM_PT: [1/4] Controller(stateDir=$stateDir)...")
        val c = try {
            IPtProxy.Controller(
                stateDir, true, false, "INFO",
                object : IPtProxy.OnTransportEvents {
                    override fun connected(name: String?) {
                        println("ATRUM_PT: событие — $name connected")
                    }
                    override fun error(name: String?, error: Exception?) {
                        // ⚠️ ЭТО НЕ ФАТАЛЬНАЯ ОШИБКА — штатное событие цикла ретраев Snowflake
                        // (см. IPtProxy: "will continue until Connected... or Controller.Stop").
                        // Библиотека сама перебирает прокси/broker-рандеву дальше. Раньше здесь
                        // стоял logPtError() → CrashHandler.report() → полноэкранный
                        // CrashActivity на КАЖДУЮ такую попытку — блокировало работу с
                        // приложением. Теперь только подробный лог в logcat, без краш-экрана.
                        println(
                            "ATRUM_PT: событие — $name error (штатный ретрай, не фатально)" +
                                (error?.let {
                                    " класс=${it::class.qualifiedName} сообщение=${it.message}\n${it.stackTraceToString()}"
                                } ?: " (error=null)")
                        )
                    }
                    override fun stopped(name: String?, error: Exception?) {
                        println(
                            "ATRUM_PT: событие — $name stopped" +
                                (error?.let { " с ошибкой класс=${it::class.qualifiedName} сообщение=${it.message}" } ?: "")
                        )
                    }
                }
            )
        } catch (e: Throwable) {
            logPtError(context, "Controller() construction", e)
            throw e
        }
        println("ATRUM_PT: [1/4] Controller() OK")

        c.snowflakeBrokerUrl = DefaultBridges.SNOWFLAKE_BROKER_URL
        c.snowflakeIceServers = DefaultBridges.SNOWFLAKE_ICE_SERVERS
        c.snowflakeFrontDomains = DefaultBridges.SNOWFLAKE_FRONT_DOMAINS

        println("ATRUM_PT: [2/4] start(Snowflake)...")
        runCatching { c.start(TRANSPORT_SNOWFLAKE, "") }
            .onFailure { e -> logPtError(context, "start(Snowflake)", e) }
            .onSuccess { println("ATRUM_PT: [2/4] start(Snowflake) OK") }

        println("ATRUM_PT: [3/4] start(Obfs4)...")
        runCatching { c.start(TRANSPORT_OBFS4, "") }
            .onFailure { e -> logPtError(context, "start(Obfs4)", e) }
            .onSuccess { println("ATRUM_PT: [3/4] start(Obfs4) OK") }

        // ⚠️ Go-исходник объявляет Port() как возвращающий `int`, но gomobile мапит
        // платформенный Go `int` на Kotlin/Java `Long` (не `Int`, компилятор подтвердил:
        // "actual type is 'Long', but 'Int' was expected") — нужна явная конверсия.
        println("ATRUM_PT: [4/4] port() для обоих транспортов...")
        snowflakePort = runCatching { c.port(TRANSPORT_SNOWFLAKE).toInt() }
            .onFailure { e -> logPtError(context, "port(Snowflake)", e) }
            .getOrDefault(0)
        obfs4Port = runCatching { c.port(TRANSPORT_OBFS4).toInt() }
            .onFailure { e -> logPtError(context, "port(Obfs4)", e) }
            .getOrDefault(0)
        controller = c
        println("ATRUM_PT: [4/4] готово, snowflakePort=$snowflakePort obfs4Port=$obfs4Port")
        if (snowflakePort == 0 && obfs4Port == 0) {
            logPtError(
                context, "startInternal итог",
                IllegalStateException(
                    "ОБА транспорта вернули порт 0 — ни Snowflake, ни obfs4 не поднялись. " +
                        "См. записи выше по шагам [2/4]/[3/4]/[4/4] для точной причины каждого."
                )
            )
        }
    }

    /** Останавливает оба транспорта. Вызывать вместе с полной остановкой Tor (не сейчас). */
    fun stop() {
        controller?.let { c ->
            runCatching { c.stop(TRANSPORT_SNOWFLAKE) }
            runCatching { c.stop(TRANSPORT_OBFS4) }
        }
        controller = null
        snowflakePort = 0
        obfs4Port = 0
    }
}
