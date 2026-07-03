package com.atrum.chat.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Пул ПОСТОЯННЫХ WebSocket-соединений к Nostr-реле.
 *
 * В отличие от [NostrRelay] (открыть→операция→закрыть на каждый запрос), здесь на
 * каждый URL держится ОДНО долгоживущее соединение, переиспользуемое всеми
 * publish/query. Это убирает TLS-рукопожатие (~100–300 мс) с каждой операции —
 * главный выигрыш по задержке на активном Nostr-транспорте.
 *
 * Мультиплексирование: ответы реле маршрутизируются по subId (REQ) и eventId (OK)
 * к ожидающим корутинам через ConcurrentHashMap. Несколько REQ/EVENT могут идти
 * по одному сокету одновременно — это валидно по NIP-01.
 *
 * Соединение ленивое и самовосстанавливающееся: при обрыве следующий вызов
 * переустанавливает сокет; ожидающие операции завершаются ошибкой и ретраятся на
 * уровне NostrTransport (другие реле / следующий тик опроса).
 */
object NostrRelayPool {

    // ⚠️ ОБХОД DPI-БЛОКИРОВКИ ПО SNI (см. nostr/SniFragment.kt). Выключено по умолчанию —
    // включается NostrTransport'ом, когда прямой (не-Tor) путь уже похоже заблокирован
    // (queryAllRelays/publishToAnyRelay получили 0 ответов). Флаг на весь процесс: как только
    // один раз помогло — остаёмся в этом режиме, не перепроверяем на каждом соединении.
    @Volatile private var directFragmentationEnabled = false

    fun enableDirectFragmentation() {
        if (!directFragmentationEnabled) {
            directFragmentationEnabled = true
            android.util.Log.i("AtrumNostr", "SNI-фрагментация включена для прямого пути (похоже на DPI-блокировку)")
        }
    }

    private fun buildClient(useTor: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(if (useTor) 45L else 15L, TimeUnit.SECONDS) // Tor дольше; direct — быстрый фейл
            // ⚠️ ИСПРАВЛЕНО (по просьбе пользователя, "таймаут в любом случае надо поправить"):
            // было readTimeout=0 (без таймаута вообще) — расчёт на то, что наши СВОИ таймауты
            // (withTimeout(45с) на хендшейк в socket(), withTimeout(timeoutMs) на query/publish)
            // достаточно всё бы ограничивали. Но это coroutine-level таймауты — они отменяют
            // ОЖИДАНИЕ на нашей стороне, а не обязательно прерывают уже блокирующий read() у
            // OkHttp на его внутреннем потоке (диспетчер OkHttp — не корутина, не отменяется
            // так же). При DPI-блокировке (пакеты после handshake молча дропаются) это давало
            // зависание на 30+ секунд БЕЗ исключения (см. ATRUM_UPLOAD_HANG_DEBUG). Конечный
            // read-таймаут — это доп. защита НИЖНЕГО уровня (сокет), независимая от того, не
            // застряли ли наши корутины/диспетчер. pingInterval (20с) ниже гарантированно
            // держит ЗДОРОВОЕ долгоживущее соединение живым — pong засчитывается как чтение и
            // сбрасывает таймер, так что раннее закрытие идле-соединений не грозит.
            .readTimeout(if (useTor) 45L else 30L, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)    // keep-alive сквозь NAT
            // Отключаем сжатие WebSocket (permessage-deflate). Это лечит краш
            // ArrayIndexOutOfBoundsException в MessageDeflater.close при обрыве соединения
            // (баг okhttp/okio: deflater не потокобезопасен, failWebSocket закрывает
            // writer с deflater'ом из потока OkHttp Dispatcher).
            //
            // Вырезаем заголовок в ДВУХ местах:
            //   1) из запроса — убираем наш offer, корректное реле не включит сжатие;
            //   2) из ОТВЕТА upgrade-рукопожатия — даже если реле (вопреки RFC 7692)
            //      вернёт permessage-deflate без нашего offer, OkHttp его не увидит:
            //      WebSocketExtensions.parse(response.headers) → perMessageDeflate=false
            //      → MessageDeflater вообще не создаётся → краш физически невозможен.
            // Nostr-сообщения малы и уже зашифрованы (не сжимаются) — потеря сжатия некритична.
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(
                    chain.request().newBuilder()
                        .removeHeader("Sec-WebSocket-Extensions")
                        .build()
                )
                if (resp.header("Sec-WebSocket-Extensions") != null) {
                    resp.newBuilder().removeHeader("Sec-WebSocket-Extensions").build()
                } else {
                    resp
                }
            }
        if (useTor) {
            // Через локальный SOCKS-прокси встроенного Tor. createUnresolved — чтобы JVM
            // не резолвила адрес прокси заранее.
            b.proxy(Proxy(Proxy.Type.SOCKS,
                InetSocketAddress.createUnresolved("127.0.0.1", com.atrum.chat.TorManager.SOCKS_PORT)))
        } else {
            // SNI-фрагментация — см. SniFragment.kt. Сама фабрика ничего не меняет, пока
            // directFragmentationEnabled == false (проверяется на каждой записи, не на
            // билде клиента, так что переключение флага в рантайме сразу подхватывается
            // без пересоздания OkHttpClient).
            b.socketFactory(SniFragmentingSocketFactory { directFragmentationEnabled })
        }
        return b.build()
    }

    /**
     * Клиент для пользовательского SOCKS5-прокси (экран «Соединение», см. ConnectionPrefs).
     * Отдельный от [directClient]: НЕ применяет SNI-фрагментацию — первая запись в сокет
     * тут является SOCKS5-хендшейком (адрес прокси), а не TLS ClientHello, так что «разрезать
     * первую запись» было бы бессмысленно и могло сломать сам SOCKS5-хендшейк.
     *
     * Прокси-адрес читается ДИНАМИЧЕСКИ на каждое подключение через ProxySelector (а не
     * зафиксирован в Proxy(...) один раз при билде клиента) — это даёт мгновенный подхват
     * изменений хоста/порта из ConnectionActivity без пересоздания OkHttpClient.
     */
    private fun buildCustomProxyClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(30L, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                val resp = chain.proceed(
                    chain.request().newBuilder()
                        .removeHeader("Sec-WebSocket-Extensions")
                        .build()
                )
                if (resp.header("Sec-WebSocket-Extensions") != null) {
                    resp.newBuilder().removeHeader("Sec-WebSocket-Extensions").build()
                } else {
                    resp
                }
            }
            .proxySelector(object : ProxySelector() {
                override fun select(uri: java.net.URI): List<Proxy> {
                    val host = com.atrum.chat.ConnectionPrefs.proxyHost
                    val port = com.atrum.chat.ConnectionPrefs.proxyPort
                    if (host.isBlank() || port !in 1..65535) return listOf(Proxy.NO_PROXY)
                    return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(host, port)))
                }
                override fun connectFailed(uri: java.net.URI, sa: java.net.SocketAddress, ioe: java.io.IOException) {}
            })
        return b.build()
    }

    // Логин/пароль SOCKS5 (опционально) — JDK спрашивает через Authenticator.setDefault,
    // единой точкой на процесс. Отдаём креды ТОЛЬКО когда запрос реально адресован нашему
    // настроенному прокси-хосту/порту; иначе null (обычное поведение "без авторизации"),
    // чтобы не задеть другие возможные Proxy-Authenticator'ы в процессе.
    init {
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val host = com.atrum.chat.ConnectionPrefs.proxyHost
                val login = com.atrum.chat.ConnectionPrefs.proxyLogin
                val password = com.atrum.chat.ConnectionPrefs.proxyPassword
                if (login.isBlank()) return null
                if (!requestingHost.equals(host, ignoreCase = true) || requestingPort != com.atrum.chat.ConnectionPrefs.proxyPort) {
                    return null
                }
                return PasswordAuthentication(login, password.toCharArray())
            }
        })
    }

    private val torClient = buildClient(useTor = true)
    private val directClient = buildClient(useTor = false)
    private val customProxyClient by lazy { buildCustomProxyClient() }

    /** true, если для прямого (не-Tor) пути сейчас нужно использовать кастомный SOCKS5. */
    private fun useCustomProxy(): Boolean =
        com.atrum.chat.ConnectionPrefs.customProxyEnabled && com.atrum.chat.ConnectionPrefs.isConfigValid()

    // Соединения раздельные для Tor / кастомного прокси / прямого режима (один URL может
    // иметь несколько одновременно, если режим переключался в рантайме — старые просто
    // не переиспользуются и в итоге простаивают, это безопасно).
    private val conns = ConcurrentHashMap<String, RelayConn>()
    private fun conn(url: String, useTor: Boolean): RelayConn {
        val viaProxy = !useTor && useCustomProxy()
        val key = when {
            useTor -> "tor|$url"
            viaProxy -> "proxy|$url"
            else -> "dir|$url"
        }
        val client = when {
            useTor -> torClient
            viaProxy -> customProxyClient
            else -> directClient
        }
        return conns.getOrPut(key) { RelayConn(url, client) }
    }

    /** Фоновый scope для прогрева соединений (не привязан к Activity). */
    private val poolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Заранее открывает WebSocket-соединения к [urls] в фоне, чтобы первый
     * query/publish не платил за TLS-рукопожатие на критическом пути
     * (ускоряет открытие чата и подключение по приглашению).
     * Идемпотентно: если соединение уже живо — повторно не переустанавливаем.
     */
    fun prewarm(urls: List<String>, useTor: Boolean = true) {
        for (url in urls) poolScope.launch {
            runCatching { conn(url, useTor).connectNow() }
        }
    }

    suspend fun query(url: String, filter: org.json.JSONObject, useTor: Boolean, timeoutMs: Long = 20_000L): List<NostrEvent> =
        conn(url, useTor).query(filter, timeoutMs)

    /** Публикует событие; бросает исключение при отказе реле / таймауте. */
    suspend fun publish(url: String, event: NostrEvent, useTor: Boolean, timeoutMs: Long = 20_000L) =
        conn(url, useTor).publish(event, timeoutMs)

    /** Открывает потоковую подписку к реле (REQ остаётся открытым). */
    suspend fun subscribe(url: String, subId: String, filter: org.json.JSONObject, useTor: Boolean, onEvent: (NostrEvent) -> Unit) =
        conn(url, useTor).subscribe(subId, filter, onEvent)

    fun unsubscribe(url: String, subId: String, useTor: Boolean) = conn(url, useTor).unsubscribe(subId)

    fun hasSub(url: String, subId: String, useTor: Boolean): Boolean = conn(url, useTor).hasSub(subId)

    /** Закрывает все соединения (вызывать при выходе из чата/приложения, опционально). */
    fun shutdown() {
        conns.values.forEach { it.close() }
        conns.clear()
    }
}

/** Одно постоянное соединение к конкретному реле. */
private class RelayConn(private val url: String, private val client: OkHttpClient) {

    @Volatile private var ws: WebSocket? = null
    private val connectMutex = Mutex()
    private val seq = AtomicLong(0)

    private class Sub(val onEvent: ((NostrEvent) -> Unit)? = null) {
        val events = mutableListOf<NostrEvent>()
        val eose = CompletableDeferred<Unit>()
    }

    private val subs = ConcurrentHashMap<String, Sub>()
    private val pubs = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    /** Принудительно устанавливает соединение (для prewarm). No-op если уже открыто. */
    suspend fun connectNow() { socket() }

    private suspend fun socket(): WebSocket {
        ws?.let { return it }
        connectMutex.withLock {
            ws?.let { return it }
            val opened = CompletableDeferred<WebSocket>()
            val sock = client.newWebSocket(
                Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        opened.complete(webSocket)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        // ⚠️ НЕ логиров�