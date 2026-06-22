package com.atrum.chat.transport

import com.atrum.chat.nostr.NostrEvent
import com.atrum.chat.nostr.NostrRelayPool
import com.atrum.chat.nostr.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import com.atrum.chat.CryptoHelper
import com.atrum.chat.ImageChunker
import java.security.MessageDigest

/**
 * ChatTransport поверх Nostr (NIP-01). Drop-in замена GistTransport:
 * тот же файловый контракт (chat.txt / reactions.txt / profiles.txt / img_*),
 * меняется только «труба» хранения — публичные реле вместо GitHub Gist.
 */
class NostrTransport(
    gistId: String,
    private val chatPassword: String,
    private val myUserId: String,
    /** Предпочитать Tor. Фактический режим ([useTor]) — динамический (см. ниже). */
    private val preferTor: Boolean = true
) : ChatTransport {

    /**
     * ФАКТИЧЕСКИЙ режим подключения к реле.
     *  • preferTor=false (чат помечен NOSTR_DIRECT_TOKEN) → всегда напрямую.
     *  • Tor READY → через Tor (приватность).
     *  • Tor FAILED, либо не дошёл до READY за [TOR_FALLBACK_MS] от старта → ПРЯМОЕ
     *    подключение к публичным реле. Это даёт работу Nostr там, где Tor заблокирован,
     *    БЕЗ VPN. На синхронизацию не влияет: channelId и шифрование от способа
     *    подключения не зависят — меняется только «труба».
     */
    private val useTor: Boolean
        get() {
            if (!preferTor) return false
            return when (com.atrum.chat.TorManager.status.value) {
                com.atrum.chat.TorManager.TorStatus.READY  -> true
                com.atrum.chat.TorManager.TorStatus.FAILED -> false
                else -> {
                    val started = com.atrum.chat.TorManager.startedAtMs
                    // Пока ждём Tor в пределах дедлайна — через Tor; вышли за дедлайн
                    // (Tor, видимо, заблокирован) — переходим на прямое подключение.
                    started == 0L || System.currentTimeMillis() - started < TOR_FALLBACK_MS
                }
            }
        }

    override val displayName: String get() = "Nostr P2P"
    override val displayIcon: String get() = "⚡"
    override val chatId: String get() = channelId

    val channelId: String = sha256("atrum_channel_v1_$gistId").toHex().take(16)

    @Volatile private var lastContentHash: String? = null

    /** Хеш последнего объединённого снапшота (chat+reactions+profiles) для loadAllIfChanged(). */
    @Volatile private var lastAllHash: String? = null

    private val privkey: ByteArray = sha256("atrum_nostr_v1_${chatPassword}_${myUserId}")

    init {
        // Сообщаем долговечному стору пароль канала — он проверяет подлинность
        // clear/del-маркеров (защита от подделки очистки/удаления через знание channelId).
        NostrMessageStore.registerChannel(channelId, chatPassword)
    }

    /** Последний УСПЕШНО прочитанный снапшот — отдаём его, если реле не ответили (анти-очистка). */
    @Volatile private var lastGoodAll: AllGistData? = null
    @Volatile private var lastGoodContent: String? = null

    // ─── чтение чата ────────────────────────────────────────────────────────────

    override suspend fun loadContent(): String {
        val events = queryAllRelays(chatFilter())
            ?: return NostrMessageStore.render(channelId).ifEmpty { lastGoodContent ?: "" }
        NostrMessageStore.merge(channelId, events)
        cacheMediaFrom(events)
        val content = NostrMessageStore.render(channelId)
        lastContentHash = sha256(content).toHex()
        lastGoodContent = content
        return content
    }

    override suspend fun loadContentIfChanged(): String? {
        val events = queryAllRelays(chatFilter()) ?: return null // реле не ответили — без изменений
        NostrMessageStore.merge(channelId, events)
        cacheMediaFrom(events)
        val content = NostrMessageStore.render(channelId)
        val hash = sha256(content).toHex()
        if (hash == lastContentHash) return null
        lastContentHash = hash
        lastGoodContent = content
        return content
    }

    // ─── Объединённый снапшот: chat + reactions + profiles ОДНИМ запросом ──────
    // Паритет с Gist (один GET на всё): профили обрабатываются в основном цикле
    // (presence/typing/online, галочки прочтения, имя/аватар, V3-ключ).

    override suspend fun loadAll(): AllGistData {
        val events = queryAllRelays(chatFilter())
            ?: return lastGoodAll ?: AllGistData(NostrMessageStore.render(channelId), "", "")
        val data = splitAll(events)
        lastAllHash = hashAll(data)
        lastContentHash = sha256(data.chatContent).toHex()
        lastGoodAll = data
        return data
    }

    override suspend fun loadAllIfChanged(): AllGistData? {
        val events = queryAllRelays(chatFilter()) ?: return null // реле не ответили — без изменений
        val data = splitAll(events)
        val h = hashAll(data)
        if (h == lastAllHash) return null
        lastAllHash = h
        lastContentHash = sha256(data.chatContent).toHex()
        lastGoodAll = data
        return data
    }

    private fun chatFrom(events: List<NostrEvent>): String {
        fun has(ev: NostrEvent, key: String) = ev.tags.any { it.firstOrNull() == key }
        // Маркер очистки: оба клиента отбрасывают сообщения старше последнего "clear".
        val clearCutoff = events.filter { has(it, "clear") }.maxOfOrNull { it.created_at } ?: 0L
        // Надгробия (del): хеши удалённых сообщений — скрываем их детерминированно у обоих.
        val delHashes = events.filter { has(it, "del") }
            .mapNotNull { ev -> ev.tags.firstOrNull { it.firstOrNull() == "del" }?.getOrNull(1) }
            .toSet()
        return events
            .filter { ev ->
                ev.kind == 1 &&
                    !has(ev, "file") && !has(ev, "clear") && !has(ev, "del") &&
                    ev.created_at >= clearCutoff &&
                    delHash(ev.content) !in delHashes
            }
            // Стабильный порядок на обоих устройствах: по времени, при равенстве — по id.
            .sortedWith(compareBy({ it.created_at }, { it.id }))
            .map { it.content }
            // Дедуп по шифртексту: одинаковый зашифрованный текст бывает ТОЛЬКО при
            // ретрае одного события (соль/nonce случайны → разные сообщения = разный
            // шифртекст). Так уходят дубликаты после повторной отправки через флаки-Tor.
            .distinct()
            .joinToString("\n")
    }

    /** Хеш шифртекста для «надгробий» удаления (детерминирован, стабилен между ретраями). */
    private fun delHash(content: String): String = sha256("atrum_del_$content").toHex().take(32)

    // ─── Скрытие имён файлов от реле ─────────────────────────────────────────────
    // Имя файла раньше уходило в тегах ["file", name]/["d", name] открыто — реле
    // читало "profiles.txt"/"img_…". wireName деривирует непрозрачный токен из имени
    // и пароля чата: реле (знает channelId, НЕ знает пароль) не восстановит имя, а оба
    // телефона считают одинаковый токен → фильтрация/получение по-прежнему работают.
    private fun wireName(name: String): String =
        sha256("atrum_file_v1_${chatPassword}_$name").toHex().take(24)

    /** Совпадает ли file/d-тег события с именем [name] — принимает И старый
     *  cleartext-тег, И новый wireName (обратная совместимость чтения истории). */
    private fun eventHasFileName(ev: NostrEvent, name: String): Boolean {
        val w = wireName(name)
        return ev.tags.any { t ->
            val k = t.firstOrNull(); val v = t.getOrNull(1)
            (k == "file" || k == "d") && (v == name || v == w)
        }
    }

    /**
     * Кэширует контент неизменяемых файловых событий (img_/stk_), пришедших в ОСНОВНОМ
     * опросе. Эти события и так скачиваются (chatFilter запрашивает kind FILE_KIND),
     * поэтому стикеры/фото партнёра подхватываются из памяти мгновенно — без отдельного
     * медленного per-file запроса к реле (8–15с + ретраи + по чанкам).
     */
    private fun cacheMediaFrom(events: List<NostrEvent>) {
        for (ev in events) {
            if (ev.kind != FILE_KIND) continue
            // Имя на проводе скрыто (wireName) — реальное имя из тега не восстановить,
            // поэтому кэшируем по ЗНАЧЕНИЮ тега (cleartext старых ИЛИ wireName новых).
            // loadFile ищет в кэше по тем же ключам и отдаёт ТОЛЬКО неизменяемые
            // (img_/stk_), поэтому случайно закэшированные profiles/reactions не мешают.
            val tagVal = ev.tags.firstOrNull { it.firstOrNull() == "file" }?.getOrNull(1) ?: continue
            if (mediaCache.get(tagVal) == null) mediaCache.put(tagVal, ev.content)
        }
    }

    private fun splitAll(events: List<NostrEvent>): AllGistData {
        // Сообщения — через долговечный локальный стор (реле могут подрезать историю).
        NostrMessageStore.merge(channelId, events)
        cacheMediaFrom(events) // стикеры/фото — в кэш из этого же опроса
        return AllGistData(
            chatContent = NostrMessageStore.render(channelId),
            reactionsContent = latestFile(events, "reactions.txt"),
            profilesContent = latestFile(events, "profiles.txt")
        )
    }

    /**
     * Возвращает контент САМОГО СВЕЖЕГО события-файла [name].
     *
     * ⚠️ ИЗВЕСТНОЕ ОГРАНИЧЕНИЕ (profiles.txt / reactions.txt): это NIP-78 replaceable-
     * события, по ОДНОМУ на pubkey, т.е. у каждого участника свой слот. Здесь берётся
     * только новейший слот — слияния двух слотов нет. Поэтому при ОДНОВРЕМЕННОЙ записи
     * с двух устройств правка более «старого» писателя может потеряться (lost update):
     * presence-мерцание, откат галочек, потеря одновременной реакции. Смягчено
     * read-modify-write + sticky-кэшем (ProfileSync.known) и тем, что reactions-toggle
     * теперь мёржит полный набор. Полное решение — слот-на-пользователя с union при
     * чтении; требует протокольной миграции и тестов на двух устройствах (см. аудит).
     */
    private fun latestFile(events: List<NostrEvent>, name: String): String =
        events
            .filter { ev -> eventHasFileName(ev, name) }
            .maxByOrNull { it.created_at }
            ?.content ?: ""

    private fun hashAll(d: AllGistData): String =
        sha256(d.chatContent + " : " + d.reactionsContent + " : " + d.profilesContent).toHex()

    // ─── запись ──────────────────────────────────────────────────────────────────

    /** Публикует одну зашифрованную строку как kind:1 + дополнительные файлы (паритет с Gist). */
    override suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String>) {
        val ev = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(listOf("t", channelId)),
            content = encryptedLine
        )
        publishToAnyRelay(ev)
        NostrMessageStore.merge(channelId, listOf(ev)) // своё сообщение — сразу в долговечный стор
        for ((name, content) in extraFiles) saveFile(name, content)
    }

    /** Сырая публикация одного файла-события (kind:1, тег ["file", name]) без чанкинга. */
    private suspend fun publishFile(name: String, content: String) {
        publishToAnyRelay(
            NostrEvent.create(
                privkeyBytes = privkey,
                // Параметризованное replaceable-событие (NIP-78): реле хранит только
                // ПОСЛЕДНЮЮ версию на (pubkey, kind, d=name). Так profiles.txt/reactions.txt,
                // переписываемые каждые ~2с, НЕ копятся и не забивают ленту сообщений.
                kind = FILE_KIND,
                tags = listOf(listOf("t", channelId), listOf("file", wireName(name)), listOf("d", wireName(name))),
                content = content
            )
        )
    }

    /**
     * Сохраняет файл. Крупный контент (изображения) АВТОМАТИЧЕСКИ чанкуется —
     * иначе реле отклоняет большое событие ("too large"). Это покрывает и путь
     * appendLine(extraFiles=...) для фото. Чанки и манифест публикуются
     * отдельными событиями; ImageLoader собирает их обратно по манифесту.
     */
    override suspend fun saveFile(name: String, content: String) {
        if (content.length > NOSTR_CHUNK_CHARS) saveFileChunked(name, content, chatPassword, null)
        else publishFile(name, content)
    }

    /**
     * Чанковая заливка большого изображения/стикера: режем зашифрованный контент на
     * части по [NOSTR_CHUNK_CHARS], каждую — отдельным событием-файлом, плюс
     * зашифрованный манифест "CHUNKED:N" под основным именем. ImageLoader соберёт.
     */
    override suspend fun saveFileChunked(
        name: String,
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ) {
        if (encryptedContent.length <= NOSTR_CHUNK_CHARS) {
            publishFile(name, encryptedContent)
            return
        }
        val chunks = encryptedContent.chunked(NOSTR_CHUNK_CHARS)
        val chunkNames = chunks.indices.map { ImageChunker.chunkName(name, it) }
        chunks.forEachIndexed { i, chunk ->
            publishFile(chunkNames[i], chunk)
            onProgress?.invoke(i + 1, chunks.size)
        }
        val manifestEnc = CryptoHelper.encrypt(ImageChunker.makeManifestPlain(chunkNames), password, chatId)
        publishFile(name, manifestEnc)
    }

    override suspend fun loadFileOrNull(name: String): String? = try {
        loadFile(name)
    } catch (_: Exception) {
        null
    }

    override suspend fun loadFile(name: String): String {
        if (isImmutableFile(name)) {
            mediaCache.get(wireName(name))?.let { return it }
            mediaCache.get(name)?.let { return it } // старые события с cleartext-именем
        }
        val events = queryAllRelays(fileFilter(name)) ?: emptyList()
        val content = events
            .filter { ev -> eventHasFileName(ev, name) }
            .maxByOrNull { it.created_at }
            ?.content
            ?: throw RuntimeException("Файл '$name' не найден в Nostr (channel=$channelId)")
        if (isImmutableFile(name)) mediaCache.put(wireName(name), content)
        return content
    }

    /** Замена строки: надгробие старой + публикация новой. */
    override suspend fun replaceLine(oldLine: String, newLine: String): Boolean {
        deleteLine(oldLine)
        appendLine(newLine)
        return true
    }

    /**
     * Удаление строки: публикуем «надгробие» (тег "del" с хешем шифртекста) — оба
     * клиента скрывают сообщение детерминированно. Плюс best-effort NIP-09 удаление.
     */
    override suspend fun deleteLine(line: String): Boolean {
        val h = delHash(line)
        val marker = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(
                listOf("t", channelId),
                listOf("del", h),
                // Токен подлинности: доказывает знание пароля чата — чужой не подделает удаление.
                listOf("auth", NostrMessageStore.ctrlToken(chatPassword, "del|$channelId|$h"))
            ),
            content = ""
        )
        publishToAnyRelay(marker)
        NostrMessageStore.merge(channelId, listOf(marker)) // надгробие сразу локально
        findMessageEvent(line)?.let { ev ->
            runCatching { publishToAnyRelay(NostrEvent.createDeletion(privkey, ev.id)) }
        }
        return true
    }

    /**
     * Полная очистка истории. Публикуем "маркер очистки" (событие с тегом "clear"):
     * оба клиента после его created_at отбрасывают старые сообщения детерминированно,
     * даже если реле не исполняют NIP-09. Плюс best-effort NIP-09 удаление событий.
     */
    override suspend fun clearHistory() {
        // created_at и токен считаются от ОДНОГО значения времени: токен привязан
        // к метке, поэтому подделать «очистку с будущим cutoff» без пароля нельзя.
        val now = System.currentTimeMillis() / 1000L
        val marker = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(
                listOf("t", channelId),
                listOf("clear", ""),
                listOf("auth", NostrMessageStore.ctrlToken(chatPassword, "clear|$channelId|$now"))
            ),
            content = "",
            createdAt = now
        )
        publishToAnyRelay(marker)
        NostrMessageStore.merge(channelId, listOf(marker)) // cutoff сразу локально
        val ids = (queryAllRelays(chatFilter()) ?: emptyList())
            .filter { ev -> ev.tags.none { t -> t.firstOrNull() == "file" || t.firstOrNull() == "clear" || t.firstOrNull() == "del" } }
            .map { it.id }
        if (ids.isNotEmpty()) {
            runCatching { publishToAnyRelay(NostrEvent.createDeletion(privkey, ids)) }
        }
        lastContentHash = null
        lastAllHash = null
        lastGoodAll = null
        lastGoodContent = null
    }

    // ─── internal ─────────────────────────────────────────────────────────────

    private suspend fun findMessageEvent(content: String): NostrEvent? =
        (queryAllRelays(chatFilter()) ?: emptyList())
            .filter { ev -> ev.tags.none { t -> t.firstOrNull() == "file" } }
            .firstOrNull { it.content.trim() == content.trim() }

    /** Scope потоковых подписок этого транспорта. */
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Фильтр стрима: только НОВЫЕ kind:1 сообщения этого канала (since=сейчас). */
    private fun streamFilter(sinceSec: Long): JSONObject = JSONObject().apply {
        put("kinds", JSONArray().put(1))
        put("#t", JSONArray().put(channelId))
        put("since", sinceSec)
    }

    override fun watchMessages(onNew: () -> Unit): AutoCloseable {
        val subId = "atrumw_$channelId"
        val sinceSec = System.currentTimeMillis() / 1000
        val onEvent: (NostrEvent) -> Unit = { ev ->
            if (ev.kind == 1) {
                NostrMessageStore.merge(channelId, listOf(ev)) // сразу в долговечный стор
                onNew()
            }
        }
        // Сторож: держим подписку открытой на всех реле; после реконнекта (drop()
        // снимает subs) переоткрываем. Реле НЕ опрашиваем — оно само шлёт события.
        val job = watchScope.launch {
            while (isActive) {
                for (url in RELAYS) {
                    if (!NostrRelayPool.hasSub(url, subId, useTor)) {
                        runCatching { NostrRelayPool.subscribe(url, subId, streamFilter(sinceSec), useTor, onEvent) }
                    }
                }
                delay(RESUBSCRIBE_MS)
            }
        }
        return AutoCloseable {
            job.cancel()
            for (url in RELAYS) runCatching { NostrRelayPool.unsubscribe(url, subId, useTor) }
        }
    }

    private fun chatFilter(): JSONObject = JSONObject().apply {
        // kind:1 — сообщения (хранятся), kind FILE_KIND — файлы (replaceable). Один запрос на всё.
        put("kinds", JSONArray().put(1).put(FILE_KIND))
        put("#t", JSONArray().put(channelId))
        put("limit", 1000)
    }

    private fun fileFilter(name: String): JSONObject = JSONObject().apply {
        put("kinds", JSONArray().put(1).put(FILE_KIND))
        put("#t", JSONArray().put(channelId))
        // Точечно по имени файла/чанка через индексируемый тег #d.
        put("#d", JSONArray().put(name).put(wireName(name))) // старые cleartext + новые blinded
        put("limit", 100)
    }

    /**
     * Запрос ко всем реле БЕЗ ожидания самого медленного: возвращаемся, как только
     * ответили все ИЛИ истёк мягкий дедлайн [SOFT_READ_DEADLINE_MS]. Дедуп по id.
     */
    /**
     * Запрос ко всем реле. Возвращает null, если НИ ОДНО реле не ответило за дедлайн
     * (частый случай нестабильного Tor) — чтобы вызыватели НЕ трактовали это как
     * "чат пуст" и не стирали уже показанную историю. Если ответило хотя бы одно
     * реле (пусть и пустым множеством) — возвращаем дедуплицированный список.
     */
    private suspend fun queryAllRelays(filter: JSONObject): List<NostrEvent>? {
        val collected = ConcurrentLinkedQueue<NostrEvent>()
        val responded = AtomicInteger(0)
        coroutineScope {
            val jobs = RELAYS.map { url ->
                launch {
                    val r = runCatching { NostrRelayPool.query(url, filter, useTor) }.getOrNull()
                    if (r != null) { responded.incrementAndGet(); collected.addAll(r) }
                }
            }
            withTimeoutOrNull(if (useTor) SOFT_READ_DEADLINE_TOR_MS else SOFT_READ_DEADLINE_MS) { jobs.joinAll() }
            jobs.forEach { it.cancel() }
        }
        if (responded.get() == 0) return null
        val seen = HashSet<String>()
        return collected.filter { seen.add(it.id) }
    }

    /**
     * Публикует событие ПАРАЛЛЕЛЬНО на все реле и возвращается, как только ПЕРВОЕ
     * реле приняло событие (не ждём самое медленное — иначе через Tor часы у
     * сообщения висят до 20с-таймаута). Остальные публикации продолжаются в фоне
     * на [publishScope] для надёжности доставки на несколько реле.
     * Если ВСЕ реле отклонили — бросаем исключение с причинами.
     */
    private suspend fun publishToAnyRelay(event: NostrEvent) {
        val firstSuccess = CompletableDeferred<Boolean>()
        val failures = ConcurrentLinkedQueue<String>()
        val remaining = AtomicInteger(RELAYS.size)
        for (url in RELAYS) {
            publishScope.launch {
                val r = runCatching { NostrRelayPool.publish(url, event, useTor) }
                if (r.isSuccess) {
                    firstSuccess.complete(true) // первый успех разблокирует отправителя
                } else {
                    val host = url.removePrefix("wss://")
                    failures.add("$host: ${r.exceptionOrNull()?.message?.take(80) ?: "?"}")
                    if (remaining.decrementAndGet() == 0) firstSuccess.complete(false)
                }
            }
        }
        if (!firstSuccess.await()) {
            throw RuntimeException("Все Nostr-реле отклонили событие — ${failures.joinToString("; ")}")
        }
    }

    companion object {
        /** Маркер транспорта в поле токена чата: token == "nostr" → чат живёт в Nostr-реле. */
        const val NOSTR_TOKEN = "nostr"

        /** Маркер чата, который ходит к реле НАПРЯМУЮ (без Tor). Всё остальное → через Tor. */
        const val NOSTR_DIRECT_TOKEN = "nostrdirect"

        /**
         * Сколько ждать READY от Tor, прежде чем перейти на ПРЯМОЕ подключение к реле.
         * Если Tor заблокирован в сети, он не доходит до READY — после этого окна
         * транспорт автоматически идёт напрямую, чтобы Nostr работал без VPN.
         */
        private const val TOR_FALLBACK_MS = 20_000L

        /** Kind параметризованного replaceable-события (NIP-78) для файлов — реле хранит latest. */
        const val FILE_KIND = 30078

        /** Максимум символов зашифрованного контента в одном Nostr-событии (крупнее — чанки). */
        const val NOSTR_CHUNK_CHARS = 48_000

        /** Мягкий дедлайн чтения: не ждём медленное/мёртвое реле дольше этого. */
        private const val SOFT_READ_DEADLINE_MS = 8_000L
        /** Для Tor дедлайн чтения больше: построение цепочки + round-trip медленнее. */
        private const val SOFT_READ_DEADLINE_TOR_MS = 15_000L
        /** Как часто сторож проверяет, что стрим-подписка жива (переоткрыть после обрыва). */
        private const val RESUBSCRIBE_MS = 30_000L

        /** LRU неизменяемых медиа-файлов (чанки/манифесты img_/stk_) — повторное чтение из памяти. */
        private const val MEDIA_CACHE_MAX_CHARS = 8 * 1024 * 1024
        private val mediaCache = object : android.util.LruCache<String, String>(MEDIA_CACHE_MAX_CHARS) {
            override fun sizeOf(key: String, value: String): Int = value.length
        }

        private fun isImmutableFile(name: String): Boolean =
            name.startsWith("img_") || name.startsWith("stk_")

        /** Публичные Nostr-реле (NIP-01, NIP-09). */
        val RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr.mom"
        )

        /** Фоновый scope для дослания публикаций на оставшиеся реле (не блокирует отправителя). */
        private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
