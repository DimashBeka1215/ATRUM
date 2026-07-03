package com.atrum.chat

/**
 * In-memory зеркало пользовательских настроек SOCKS5-прокси (экран «Соединение»).
 *
 * Зачем отдельный объект, а не читать [Prefs] напрямую: [com.atrum.chat.nostr.NostrRelayPool] —
 * синглтон-object без Context (см. его doc-comment), а пробрасывать Context через ВСЕ
 * сигнатуры query/publish/subscribe ради одной настройки — неоправданно рискованный рефактор
 * (широкий blast radius на транспортный слой, см. CLAUDE.md §1). Вместо этого используем тот
 * же паттерн, что уже есть в проекте (App.onCreate(): `BatteryUtils.animatePersistOverride =
 * prefs.lowBattAnimate`) — volatile-зеркало, загружаемое один раз при старте процесса и
 * обновляемое сразу при сохранении на экране настроек.
 *
 * Персистентность — через [Prefs] (EncryptedSharedPreferences), это лишь runtime-кэш.
 */
object ConnectionPrefs {
    @Volatile var customProxyEnabled: Boolean = false
    @Volatile var proxyHost: String = ""
    @Volatile var proxyPort: Int = 1080
    @Volatile var proxyLogin: String = ""
    @Volatile var proxyPassword: String = ""

    /** Вызывать один раз при старте процесса (App.onCreate()). */
    fun loadFrom(prefs: Prefs) {
        customProxyEnabled = prefs.customProxyEnabled
        proxyHost = prefs.customProxyHost
        proxyPort = prefs.customProxyPort
        proxyLogin = prefs.customProxyLogin
        proxyPassword = prefs.customProxyPassword
    }

    /** Сохраняет в Prefs И сразу обновляет runtime-зеркало (вызывается из ConnectionActivity). */
    fun save(
        prefs: Prefs,
        enabled: Boolean,
        host: String,
        port: Int,
        login: String,
        password: String
    ) {
        prefs.customProxyEnabled = enabled
        prefs.customProxyHost = host
        prefs.customProxyPort = port
        prefs.customProxyLogin = login
        prefs.customProxyPassword = password
        customProxyEnabled = enabled
        proxyHost = host
        proxyPort = port
        proxyLogin = login
        proxyPassword = password
    }

    /** Валиден ли текущий конфиг для реального подключения (хост задан, порт в допустимом диапазоне). */
    fun isConfigValid(): Boolean =
        proxyHost.isNotBlank() && proxyPort in 1..65535
}
