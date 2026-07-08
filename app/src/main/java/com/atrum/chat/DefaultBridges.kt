package com.atrum.chat

/**
 * Значения мостов Tor по умолчанию — Фаза 2 (см. TOR_BRIDGES_CONTINUE.md, путь B, §4/§5).
 *
 * ⚠️ obfs4-мосты СО ВРЕМЕНЕМ БЛОКИРУЮТСЯ цензорами. Перед реальным использованием —
 * свериться/обновить на https://bridges.torproject.org (запросить obfs4-мосты).
 * Snowflake-параметры (broker/ICE/фронты) меняются реже — при сомнениях брать из
 * актуального Tor Browser (about:config → ключи snowflake.*).
 *
 * Ничто отсюда не подключено к сети, пока оба флага в TorManager выключены
 * (USE_TOR_ANDROID_ENGINE=false, USE_BRIDGES=false — оба по умолчанию false).
 */
object DefaultBridges {

    /** Broker Snowflake — принимает запросы клиентов, сводит их с добровольческими прокси. */
    const val SNOWFLAKE_BROKER_URL =
        "https://snowflake-broker.torproject.net.global.prod.fastly.net/"

    /** STUN-сервера для установления P2P-соединения (ICE) со снежинкой-прокси. */
    const val SNOWFLAKE_ICE_SERVERS =
        "stun:stun.l.google.com:19302,stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.nextcloud.com:3478"

    /**
     * Домены прикрытия (domain fronting) — запрос к брокеру маскируется под трафик CDN.
     * ⚠️ `cdn.zk.mk` убран — подтверждено на устройстве, что домен вообще не резолвится
     * DNS-ом ("no such host"), не просто временный сбой. Список взят из стороннего
     * агрегатора, не с официального Tor Project — если и оставшиеся два начнут падать
     * похожим образом, надёжнее взять актуальный набор из своего Tor Browser
     * (about:config → snowflake.*), а не из сторонних гайдов.
     */
    const val SNOWFLAKE_FRONT_DOMAINS = "cdn.sstatic.net,www.phpmyadmin.net"

    /**
     * `Bridge snowflake ...` — строка для torrc. Сама по себе почти декоративна (реальная
     * маршрутизация идёт через broker/ICE выше, а не через этот IP), но torrc требует
     * синтаксически валидный адрес. 192.0.2.3 — TEST-NET-1 (RFC 5737), стандартная практика
     * для snowflake-строк в документации Tor Project.
     */
    const val SNOWFLAKE_BRIDGE_LINE =
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72"

    /**
     * Запасные obfs4-мосты — получены пользователем через bridges.torproject.org (см.
     * TOR_BRIDGES_CONTINUE.md §4). ⚠️ Требуют периодической проверки на
     * https://bridges.torproject.org — со временем блокируются цензорами, обновлять раз
     * в несколько месяцев или при жалобах, что obfs4-путь не поднимается.
     */
    val OBFS4_BRIDGE_LINES: List<String> = listOf(
        "obfs4 108.50.202.242:46037 C26661629B7B8E05CB11D109360D02447EB9B5B5 " +
            "cert=+A3dhOmzBR23iD4LoSgTO3fzTPsov91wbeA2c2D2FcQSlEV4H6ruI6ksxxsejqFsRbyDeQ iat-mode=0",
        "obfs4 82.50.113.187:8080 CB83451C5B2ECBF2ED4B15E5845EF8469210FB1D " +
            "cert=cry1YmniQhZOu5xTZOUvbwxm35Foy0yWAjhjRA/teIcnrcSumHbijbGrhNmWsmVlR3eREQ iat-mode=0"
    )
}
