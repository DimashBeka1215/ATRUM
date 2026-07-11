package com.atrum.chat.nostr

import com.atrum.chat.BuildConfig
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

    // ── Раннее включение фрагментации по СТАБИЛЬНО НИЗКОМУ покрытию (не только по нулю) ──
    // Идея (из обсуждения репорта «от друга доходило долго»): при ЧАСТИЧНОЙ DPI-блокировке
    // прямой путь отвечает не нулём, а 1-2 реле — enableDirectFragmentation по «ноль ответов»
    // тогда не срабатывает, и покрытие реле у пользователя остаётся узким (события из чужой
    // сети редко пересекаются). Сэмплируем покрытие КАЖДОГО union-чтения (частый, точный
    // сигнал — см. NostrTransport.queryAllRelays) и, если несколько замеров подряд стабильно
    // низкие, включаем фрагментацию превентивно. Порог мягкий, включение одноразовое и
    // необратимое (как и по нулю) — ложное срабатывание в редкой сети максимум добавит
    // фрагментацию, которая корректной доставке не мешает.
    private const val LOW_COVERAGE_RELAYS = 2      // ≤2 ответивших реле — «узкое» покрытие
    private const val LOW_COVERAGE_SAMPLES = 6     // столько подряд низких замеров подряд
    private val coverageWindow = ArrayDeque<Int>()
    private val coverageLock = Any()

    /**
     * Сообщает о покрытии одного прямого (не-Tor) union-чтения: [responders] реле ответило
     * из [totalRelays] опрошенных. Копит скользящее окно; при [LOW_COVERAGE_SAMPLES] подряд
     * замеров с покрытием ≤ [LOW_COVERAGE_RELAYS] включает SNI-фрагментацию. Пустой опрос
     * (никто не ответил) сюда не попадает — его обрабатывает прямой путь «ноль ответов» в
     * queryAllRelays; здесь именно ЧАСТИЧНАЯ блокировка.
     */
    fun recordDirectReadCoverage(responders: Int, totalRelays: Int) {
        if (directFragmentationEnabled) return
        if (totalRelays <= LOW_COVERAGE_RELAYS) return // мало реле в списке — сигнал недостоверен
        if (responders <= 0) return                    // ноль — отдельный путь (см. выше)
        val trigger = synchronized(coverageLock) {
            coverageWindow.addLast(responders)
            while (coverageWindow.size > LOW_COVERAGE_SAMPLES) coverageWindow.removeFirst()
            coverageWindow.size >= LOW_COVERAGE_SAMPLES &&
                coverageWindow.all { it <= LOW_COVERAGE_RELAYS }
        }
        if (trigger) {
            android.util.Log.i("AtrumNostr", "SNI-фрагментация включена превентивно: покрытие реле стабильно низкое")
            enableDirectFragmentation()
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
    suspend fun publish(url: String, event: NostrEvent, useTor: Boolean, timeoutMs: Long = 20_000L) {
        // ⚠️ Только для DEBUG-сборки (BuildConfig.DEBUG) — в release R8 вырезает эту ветку
        // целиком (см. §14 CLAUDE.md, нет утечки в проде). Нужно для отладочного
        // watch_relay_messages.bat — посмотреть, что реально уходит на реле.
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "AtrumRelayDebug",
                "-> PUBLISH url=$url kind=${event.kind} id=${event.id.take(8)} " +
                    "len=${event.content.length} content=${event.content.take(300)}"
            )
        }
        conn(url, useTor).publish(event, timeoutMs)
    }

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
                        // ⚠️ НЕ логировать здесь сырьё: onMessage срабатывает на КАЖДОЕ
                        // событие реле (до 1000 за запрос × ~10 реле), и синхронный лог в
                        // этом потоке-читателе okhttp растёт вместе с историей чата →
                        // доставка «тормозит со временем». Плюс это утечка шифртекста в logcat.
                        dispatch(text)
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!opened.isCompleted) opened.completeExceptionally(t)
                        drop(t)
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        drop(RuntimeException("relay closed: $reason"))
                    }
                }
            )
            return try {
                withTimeout(45_000L) { opened.await() }.also { ws = it }
            } catch (e: Exception) {
                sock.cancel()
                throw e
            }
        }
    }

    private fun dispatch(text: String) {
        try {
            val arr = JSONArray(text)
            when (arr.optString(0)) {
                "EVENT" -> {
                    val sub = subs[arr.optString(1)] ?: return
                    NostrEvent.fromJson(arr.getJSONObject(2))?.let { ev ->
                        // DEBUG-only, см. комментарий у top-level publish() — для
                        // watch_relay_messages.bat. Не влияет на release (ветка вырезается R8).
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d(
                                "AtrumRelayDebug",
                                "<- EVENT url=$url kind=${ev.kind} id=${ev.id.take(8)} " +
                                    "len=${ev.content.length} content=${ev.content.take(300)}"
                            )
                        }
                        val cb = sub.onEvent
                        if (cb != null) cb(ev) else synchronized(sub.events) { sub.events.add(ev) }
                    }
                }
                "EOSE" -> subs[arr.optString(1)]?.eose?.complete(Unit)
                "CLOSED" -> subs[arr.optString(1)]?.eose?.complete(Unit)
                "OK" -> {
                    val waiter = pubs.remove(arr.optString(1)) ?: return
                    val accepted = arr.optBoolean(2, true)
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d(
                            "AtrumRelayDebug",
                            "<- OK url=$url id=${arr.optString(1).take(8)} accepted=$accepted reason=${arr.optString(3)}"
                        )
                    }
                    if (accepted) waiter.complete(Unit)
                    else waiter.completeExceptionally(
                        RuntimeException("Relay rejected event: ${arr.optString(3)}")
                    )
                }
                // "NOTICE" — игнорируем
            }
        } catch (_: Exception) { }
    }

    /**
     * Принудительный сброс сокета — ТОЛЬКО если он всё ещё текущий (===). Защита от
     * гонки: если параллельная корутина уже переподключила соединение, мы не рвём
     * новое живое. Используется когда query/publish не получили ответа за таймаут —
     * мёртвое соединение нужно отбраковать, не дожидаясь медленного okhttp-пинга.
     */
    private fun resetIfCurrent(sock: WebSocket) {
        if (ws === sock) {
            runCatching { sock.cancel() }
            drop(RuntimeException("relay reset: stale socket $url"))
        }
    }

    /** Обрыв соединения: сбрасываем сокет и завершаем все ожидания, чтобы они ретраились. */
    private fun drop(cause: Throwable) {
        ws = null
        subs.values.forEach { if (!it.eose.isCompleted) it.eose.complete(Unit) }
        subs.clear()
        pubs.values.forEach { if (!it.isCompleted) it.completeExceptionally(cause) }
        pubs.clear()
    }

    suspend fun query(filter: org.json.JSONObject, timeoutMs: Long): List<NostrEvent> =
        withContext(Dispatchers.IO) {
            val sock = socket()
            val subId = "atrum_${seq.incrementAndGet()}"
            val sub = Sub()
            subs[subId] = sub
            try {
                val req = JSONArray().apply { put("REQ"); put(subId); put(filter) }.toString()
                if (!sock.send(req)) throw RuntimeException("ws send failed")
                try {
                    withTimeout(timeoutMs) { sub.eose.await() }
                } catch (_: TimeoutCancellationException) {
                    // EOSE так и не пришёл за timeoutMs → сокет, скорее всего, мёртв
                    // (мёртвая Tor-цепочка / half-open соединение, которое okhttp-пинг
                    // ещё не отбраковал). Сбрасываем его, чтобы СЛЕДУЮЩИЙ запрос
                    // переподключился, и сигналим вызывателю ошибкой — иначе пустой
                    // ответ зомби-сокета засчитается как «реле ответило» и опрос
                    // молча застрянет навсегда (sync «не происходит»).
                    resetIfCurrent(sock)
                    throw RuntimeException("relay query timeout (no EOSE): $url")
                }
                runCatching { sock.send(JSONArray().apply { put("CLOSE"); put(subId) }.toString()) }
                synchronized(sub.events) { sub.events.toList() }
            } catch (ce: CancellationException) {
                // Внешняя отмена (мягкий дедлайн queryAllRelays). Не сбрасываем сокет
                // принудительно, т.к. он может быть здоров, просто медленнее конкурентов.
                // Настоящий «зомби-сокет» будет отбракован по внутреннему timeoutMs (20с).
                throw ce
            } finally {
                subs.remove(subId)
            }
        }

    /** Потоковая подписка: REQ остаётся ОТКРЫТЫМ, реле само шлёт новые EVENT в onEvent. */
    suspend fun subscribe(subId: String, filter: org.json.JSONObject, onEvent: (NostrEvent) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val sock = socket()
            subs[subId] = Sub(onEvent)
            val req = JSONArray().apply { put("REQ"); put(subId); put(filter) }.toString()
            if (!sock.send(req)) { subs.remove(subId); throw RuntimeException("ws send failed") }
        }

    fun unsubscribe(subId: String) {
        subs.remove(subId)
        try { ws?.send(JSONArray().apply { put("CLOSE"); put(subId) }.toString()) } catch (_: Exception) {}
    }

    /** true если подписка ещё жива (реконнект через drop() её снимает → нужно переоткрыть). */
    fun hasSub(subId: String): Boolean = subs.containsKey(subId)

    suspend fun publish(event: NostrEvent, timeoutMs: Long): Unit = withContext(Dispatchers.IO) {
        val sock = socket()
        val waiter = CompletableDeferred<Unit>()
        pubs[event.id] = waiter
        try {
            val msg = JSONArray().apply { put("EVENT"); put(event.toJson()) }.toString()
            if (!sock.send(msg)) throw RuntimeException("ws send failed")
            try {
                withTimeout(timeoutMs) { waiter.await() }
            } catch (_: TimeoutCancellationException) {
                // Нет "OK" за таймаут → сокет, вероятно, мёртв. Сбрасываем, чтобы
                // следующая отправка переподключилась, а не зависала на зомби-сокете.
                resetIfCurrent(sock)
                throw RuntimeException("Relay $url publish timeout")
            }
        } finally {
            pubs.remove(event.id)
        }
    }

    fun close() {
        try { ws?.close(1000, null) } catch (_: Exception) {}
        drop(RuntimeException("pool shutdown"))
    }
}
