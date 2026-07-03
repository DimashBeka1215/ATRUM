package com.atrum.chat

/**
 * Генератор содержимого `torrc` для [TorAndroidEngine] (tor-android / Guardian Project,
 * см. TOR_BRIDGES_CONTINUE.md — путь B). Чистая функция, без сети/файлов — только строит
 * текст, легко проверяется отдельно.
 *
 * `TorService` (библиотека) сама подставляет через командную строку DataDirectory,
 * CacheDirectory, ControlSocket и т.п. — сюда попадают ТОЛЬКО дополнительные опции,
 * которые нужны именно нам: порт SOCKS и (в Фазе 2) мосты.
 */
object TorrcConfig {

    /**
     * @param socksPort порт локального SOCKS5 (см. TorManager.SOCKS_PORT — 9151, не
     *   пересекается с Orbot/9050).
     * @param bridgeLines строки `Bridge ...` (формат torrc, см. bridges.torproject.org).
     *   Пусто в Фазе 1 (обычный Tor без мостов) — заполняется в Фазе 2.
     * @param transportPlugins строки `ClientTransportPlugin ...` — для внешне управляемых
     *   pluggable transports (IPtProxy/Snowflake/obfs4, см. §4 TOR_BRIDGES_CONTINUE.md).
     *   Пусто в Фазе 1.
     */
    fun build(
        socksPort: Int,
        bridgeLines: List<String> = emptyList(),
        transportPlugins: List<String> = emptyList()
    ): String = buildString {
        appendLine("SOCKSPort $socksPort")
        if (transportPlugins.isNotEmpty() || bridgeLines.isNotEmpty()) {
            transportPlugins.forEach { appendLine(it) }
            appendLine("UseBridges 1")
            bridgeLines.forEach { appendLine("Bridge $it") }
        }
    }
}
