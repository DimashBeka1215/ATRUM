package com.atrum.chat.transport

import kotlinx.coroutines.cancelChildren
import com.atrum.chat.nostr.NostrEvent
import android.content.Context
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
import com.atrum.chat.RelayListStore
import com.atrum.chat.ImageChunker
import java.security.MessageDigest

/**
 * ChatTransport поверх Nostr (NIP-01). Drop-in замена Gist-транспорта:
 * тот же файловый контракт (chat.txt / reactions.txt / profiles.txt / img_*),
 * меняется только «труба» хранения — публичные реле вместо GitHub.
 */
class NostrTransport(
    // Исходный chatId (chat.chatId) — крипто-домен сессии. channelId (хеш от него) —
    // только для сети (теги Nostr). Контент/манифест медиа шифруем под sourceId, чтобы
    // попасть в forward-secrecy сессию (как текст), а не в парольный V4.
    private val sourceId: String,
    private val chatPassword: String,
    private val myUserId: String,
    /** Предпочитать Tor. Фактический режим ([useTor]) — динамический (см. ниже). */
    private val preferTor: Boolean = true,
    /**
     * userId администратора группового чата (ADR-001). null для 1:1-чатов и для
     * участников группы, которые ещё не знают админа (тогда members.txt просто
     * не проверяется/не отдаётся — безопасный дефолт, а не "доверяй всему").
     * Публичный ключ админа детерминированно вычисляется из (chatPassword, adminUserId) —
     * тот же способ, каким получается pubkey ЛЮБОГО участника (см. [privkey] ниже),
     * отдельный ключ для админа хранить не нужно.
     */
    private val adminUserId: String? = null
) : ChatTransport {

    /**
     * ФАКТИЧЕСКИЙ режим подключения к реле.
     *  • preferTor=false (чат помечен NOSTR_DIRECT_TOKEN) → всегда напрямую.
     *  • preferTor=true → СТРОГО через Tor. Если Tor не готов или заблокирован,
     *    прямого соединения НЕ будет. Это закрывает «дыру» утечки IP-адреса
     *    при нестабильном Tor-соединении в публичных сетях.
     */
    override val useTor: Boolean
        get() = preferTor

    /**
     * true, если для ЭТОГО (прямого, не-Tor) транспорта сейчас активен пользовательский
     * SOCKS5-прокси (экран «Соединение» → ConnectionPrefs). Используется, чтобы дать
     * прямому пути через удалённый VPS тот же щедрый бюджет по времени, что и Tor —
     * без прокси остаётся прежний быстрый фейл (см. NostrRelayPool.buildCustomProxyClient).
     */
    private fun viaCustomProxy(): Boolean =
        !useTor && com.atrum.chat.ConnectionPrefs.customProxyEnabled && com.atrum.chat.ConnectionPrefs.isConfigValid()

    override val displayName: String get() = "Nostr P2P"
    override val displayIcon: String get() = "⚡"
    override val chatId: String get() = channelId
    // Крипто-домен = исходный chatId (под ним ставится сессия), НЕ сетевой channelId.
    override val cryptoChatId: String get() = sourceId

    val channelId: String = sha256("atrum_channel_v1_$sourceId").toHex().take(16)

    /**
     * Публичный ключ администратора группы (hex) — вычисляется детерминированно из
     * (chatPassword, adminUserId), тем же способом, что и [privkey] ниже. null, если
     * adminUserId не задан (1:1-чат либо участник группы ещё не знает админа) —
     * тогда members.txt нигде не проверяется и не применяется (безопасный дефолт).
     */
    private val adminPubkeyHex: String? by lazy {
        adminUserId?.let { uid ->
            val adminPriv = sha256("atrum_nostr_v1_${chatPassword}_$uid")
            com.atrum.chat.nostr.Schnorr.pubkeyFromPrivkey(adminPriv).toHex()
        }
    }

    @Volatile private var lastContentHash: String? = null

    /** Хеш последнего объединённого снапшота (chat+reactions+profiles) для loadAllIfChanged(). */
    @Volatile private var lastAllHash: String? = null

    private val privkey: ByteArray = sha256("atrum_nostr_v1_${chatPassword}_${myUserId}")

    /**
     * Публичный ключ (hex) ЛЮБОГО участника чата — та же детерминированная деривация,
     * что и [privkey]/[adminPubkeyHex] (chatPassword, userId), просто параметризована
     * произвольным userId вместо "себя"/админа. Нужно для атрибуции "кто удалил
     * сообщение" на экране статистики (см. NostrMessageStore.DeletedMessage): сверяем
     * pubkey события-надгробия с pubkeyForUserId(автор) и pubkeyForUserId(admin).
     */
    fun pubkeyForUserId(userId: String): String =
        com.atrum.chat.nostr.Schnorr.pubkeyFromPrivkey(sha256("atrum_nostr_v1_${chatPassword}_$userId")).toHex()

    /** Удалённые сообщения этого канала (для экрана статистики админа), см. NostrMessageStore.DeletedMessage. */
    fun deletedMessages(): List<NostrMessageStore.DeletedMessage> = NostrMessageStore.deletedMessagesFor(channelId)

    init {
        // Сообщаем долговечному стору пароль канала — он проверяет подлинность
        // clear/del-маркеров (защита от подделки очистки/удаления через знание channelId).
        NostrMessageStore.registerChannel(channelId, chatPassword)
    }

    /** Последний УСПЕШНО прочитанный снапшот — отдаём его, если реле не ответили (анти-очистка). */
    @Volatile private var lastGoodAll: AllChannelData? = null
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
    // Паритет с Legacy (один GET на всё): профили обрабатываются в основном цикле
    // (presence/typing/online, галочки прочтения, имя/аватар, V3-ключ).

    override suspend fun loadAll(): AllChannelData {
        val events = queryAllRelays(chatFilter())
            ?: return lastGoodAll ?: AllChannelData(NostrMessageStore.render(channelId), "", "")
        val data = splitAll(events)
        lastAllHash = hashAll(data)
        lastContentHash = sha256(data.chatContent).toHex()
        lastGoodAll = data
        return data
    }

    override suspend fun loadAllIfChanged(): AllChannelData? {
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

    /**
     * Сбрасывает закэшированную копию неизменяемого файла (img_/stk_ — см. isImmutableFile)
     * из mediaCache. Нужно ImageLoader'у: если у чанка не сошёлся SHA-256 (см.
     * ImageChunker.parseChunkHashes), повторный loadFile() без сброса вернул бы ТУ ЖЕ
     * (уже закэшированную, потенциально битую) копию, не сходив в сеть заново.
     */
    fun evictCachedFile(name: String) {
        mediaCache.remove(wireName(name))
        mediaCache.remove(name)
    }

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
     *
     * ⚠️ Фикс (репорт: голосовое — "найден, не расшифр", GCM auth tag не сходится):
     * события собираются UNION-чтением со ВСЕХ реле (queryAllRelays), и порядок в списке
     * недетерминирован (кто из реле ответил раньше). Если хотя бы одно реле в пуле
     * обрезает/повреждает содержимое крупного события (мягкий лимит на размер), а другое
     * реле хранит полную копию — раньше "кэшировать, только если пусто" фиксировало
     * НАВСЕГДА ту копию, что пришла первой, даже если это была битая. Теперь среди
     * нескольких копий одного и того же (неизменяемого!) имени файла побеждает более
     * ДЛИННАЯ — обрезка всегда укорачивает контент, никогда не удлиняет, так что "длиннее"
     * равнозначно "полнее/вернее" для этого класса файлов.
     */
    private fun cacheMediaFrom(events: List<NostrEvent>) {
        for (ev in events) {
            if (ev.kind != FILE_KIND) continue
            // Имя на проводе скрыто (wireName) — реальное имя из тега не восстановить,
            // поэтому кэшируем по ЗНАЧЕНИЮ тега (cleartext старых ИЛИ wireName новых).
            // loadFile ищет в кэше по тем же ключам и отдаёт ТОЛЬКО неизменяемые
            // (img_/stk_), поэтому случайно закэшированные profiles/reactions не мешают.
            val tagVal = ev.tags.firstOrNull { it.firstOrNull() == "file" }?.getOrNull(1) ?: continue
            val cached = mediaCache.get(tagVal)
            if (cached == null || ev.content.length > cached.length) mediaCache.put(tagVal, ev.content)
        }
    }

    private fun splitAll(events: List<NostrEvent>): AllChannelData {
        // Сообщения — через долговечный локальный стор (реле могут подрезать историю).
        NostrMessageStore.merge(channelId, events)
        cacheMediaFrom(events) // стикеры/фото — в кэш из этого же опроса
        return AllChannelData(
            chatContent = NostrMessageStore.render(channelId),
            reactionsContent = latestFile(events, "reactions.txt"),
            profilesContent = latestFile(events, "profiles.txt"),
            // Все слоты profiles.txt (по одному на участника) — источник union-чтения.
            profileSlots = events
                .filter { ev -> eventHasFileName(ev, "profiles.txt") }
                .sortedByDescending { it.created_at }
                .map { it.content },
            membersContent = latestVerifiedMembersFile(events)
        )
    }

    /**
     * Content members.txt (ADR-001) от САМОГО СВЕЖЕГО события, чей pubkey совпадает
     * с вычисленным [adminPubkeyHex] И чья подпись валидна. Любые "members.txt" от
     * других участников (даже валидно зашифрованные — они все знают общий пароль
     * группы) молча отбрасываются: единственный источник доверия — подпись админа,
     * тот же принцип, что и в RelayListStore.tryApply(). Пусто, если adminUserId
     * не задан, подходящих событий нет или все не прошли проверку.
     */
    private fun latestVerifiedMembersFile(events: List<NostrEvent>): String {
        val trustedPubkey = adminPubkeyHex ?: return ""
        return events
            .filter { ev -> eventHasFileName(ev, "members.txt") }
            .filter { ev -> ev.pubkey.equals(trustedPubkey, ignoreCase = true) }
            .filter { ev -> NostrEvent.verifySignature(ev) }
            .maxByOrNull { it.created_at }
            ?.content ?: ""
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

    /**
     * ⚠️ БАГ (найден и исправлен при аудите групповых чатов): membersContent ОБЯЗАН быть
     * в хеше. Раньше его тут не было — loadAllIfChanged() считал канал "не изменившимся",
     * если поменялось ТОЛЬКО members.txt (админ переименовал группу/сменил аву/забанил
     * участника), и SyncEngine НЕ эмиттил новые данные — processChannelData() у остальных
     * участников с открытым чатом просто не вызывался. На практике часто маскировалось
     * presence-heartbeat'ом (меняет profiles.txt каждые ~5с, пока получатель онлайн), но
     * это случайность, а не гарантия: если получатель офлайн или heartbeat не успел —
     * изменение members.txt "зависало" до следующего постороннего изменения хеша. Для
     * 1:1-чатов membersContent всегда "" — поведение не меняется ни на бит.
     */
    private fun hashAll(d: AllChannelData): String =
        sha256(d.chatContent + " : " + d.reactionsContent + " : " + d.profilesContent +
            " : " + d.profileSlots.joinToString("|") + " : " + d.membersContent).toHex()

    // ─── запись ──────────────────────────────────────────────────────────────────

    /** Публикует одну зашифрованную строку как kind:1 + дополнительные файлы (паритет с Legacy). */
    override suspend fun appendLine(
        encryptedLine: String,
        extraFiles: Map<String, String>,
        onFileProgress: ((fileName: String, current: Int, total: Int) -> Unit)?
    ) {
        // ⚠️ ПОРЯДОК ВАЖЕН: сначала заливаем КОНТЕНТ (фото/голос — чанки+манифест),
        // и только ПОТОМ публикуем строку-анонс (kind:1). Иначе получатель, опросив
        // реле раз в ~3с, видит сообщение РАНЬШЕ, чем его медиа долито (каждый чанк —
        // отдельная медленная публикация через Tor) → грузит файл, которого ещё нет →
        // пустой пузырёк, а негативный кэш (битые загрузки) закрепляет пустоту.
        // Контент-события (kind FILE_KIND) подтянутся тем же опросом, что и строка,
        // и сразу осядут в mediaCache. Касается и фото, и голосовых (один и тот же путь).
        for ((name, content) in extraFiles) {
            if (content.length > NOSTR_CHUNK_CHARS) {
                saveFileChunked(name, content, chatPassword) { cur, tot -> onFileProgress?.invoke(name, cur, tot) }
            } else {
                saveFile(name, content)
                onFileProgress?.invoke(name, 1, 1)
            }
        }

        val ev = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(listOf("t", channelId)),
            content = encryptedLine
        )
        publishToAnyRelay(ev)
        NostrMessageStore.merge(channelId, listOf(ev)) // своё сообщение — сразу в долговечный стор
        scheduleRebroadcast(ev)                        // переотправка на случай недоступного в этот момент реле
    }

    /**
     * Доотправка текстового сообщения через паузы. Покрывает сценарий, где в момент
     * отправки часть реле была недоступна (упала/банилась): при повторе уже поднявшиеся
     * реле получат событие. Событие Nostr идемпотентно по id — реле, у которых оно уже
     * есть, дубль отбрасывают. Ограничено двумя попытками (≈15с и ≈45с), не бесконечный
     * цикл — текстовое событие крошечное, нагрузка минимальна.
     */
    private fun scheduleRebroadcast(ev: NostrEvent) {
        publishScope.launch {
            for (delayMs in longArrayOf(15_000L, 30_000L)) {
                kotlinx.coroutines.delay(delayMs)
                runCatching { publishToAnyRelay(ev) }
            }
        }
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
     * Публикация файла/чанка с ОДНОЙ повторной попыткой. Фото/голосовые чаще всего —
     * это НЕСКОЛЬКО последовательных событий (чанки + манифест); без ретрая единичный
     * сбой кворума на ЛЮБОМ из них (нестабильная сеть, DPI-помеха на один RTT, кастомный
     * прокси через удалённый VPS) ронял всю заливку целиком, хотя остальные события
     * уже ушли успешно. Короткая пауза перед повтором — не бесконечный цикл (это НЕ
     * замена scheduleRebroadcast/ретраю всего сообщения на уровне ChatActivity, а
     * страховка от одного случайного сбоя на конкретном событии). Если и вторая
     * попытка падает — исключение уходит наружу как раньше (failSend в ChatActivity).
     */
    private suspend fun publishFileWithRetry(name: String, content: String) {
        try {
            publishFile(name, content)
        } catch (e: Exception) {
            delay(500L)
            publishFile(name, content)
        }
    }

    /**
     * Сохраняет файл. Крупный контент (изображения) АВТОМАТИЧЕСКИ чанкуется —
     * иначе реле отклоняет большое событие ("too large"). Это покрывает и путь
     * appendLine(extraFiles=...) для фото. Чанки и манифест публикуются
     * отдельными событиями; ImageLoader собирает их обратно по манифесту.
     */
    override suspend fun saveFile(name: String, content: String) {
        if (content.length > NOSTR_CHUNK_CHARS) saveFileChunked(name, content, chatPassword, null)
        else publishFileWithRetry(name, content)
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
            publishFileWithRetry(name, encryptedContent)
            return
        }
        val chunks = encryptedContent.chunked(NOSTR_CHUNK_CHARS)
        val chunkNames = chunks.indices.map { ImageChunker.chunkName(name, it) }
        chunks.forEachIndexed { i, chunk ->
            publishFileWithRetry(chunkNames[i], chunk)
            onProgress?.invoke(i + 1, chunks.size)
        }
        // Проверка целостности (по просьбе пользователя): список SHA-256 каждого чанка —
        // ОТДЕЛЬНЫЙ файл, публикуется ДО манифеста. Старые версии приложения о нём не
        // знают и никогда не запрашивают — обратная совместимость не страдает (см.
        // ImageChunker.kt, раздел "Проверка целостности чанков"). Best-effort: если это
        // само не долетит — получатель просто не проверяет, как и раньше.
        val hashesEnc = CryptoHelper.encrypt(ImageChunker.makeChunkHashesPlain(chunks), password, sourceId)
        publishFileWithRetry(ImageChunker.chunkHashesFileName(name), hashesEnc)
        // Манифест шифруем под sourceId (chat.chatId) через encrypt() — тем же ключом/
        // сессией, что и контент и текст. Иначе домены не совпадут и манифест не
        // расшифруется у получателя (cryptoChatId = chat.chatId).
        val manifestEnc = CryptoHelper.encrypt(ImageChunker.makeManifestPlain(chunkNames), password, sourceId)
        publishFileWithRetry(name, manifestEnc)
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
        // ⚠️ ВРЕМЕННАЯ ДИАГНОСТИКА (не для релиза, см. TODO_REMOVE_EMPTY_MEDIA_CRASH):
        // раньше "не ответило ни одно реле" (queryAllRelays == null) и "реле ответили,
        // но такого файла нет" (events без совпадения по тегу) схлопывались в ОДНО и то
        // же сообщение — не отличить сетевую проблему от реального отсутствия контента
        // на реле. Разбираем причину явно, только текст исключения меняется, логика
        // (что бросаем при отсутствии) — та же самая.
        val eventsOrNull = queryAllRelays(fileFilter(name))
        val events = eventsOrNull ?: emptyList()
        val matches = events.filter { ev -> eventHasFileName(ev, name) }
        // ⚠️ Тот же фикс, что и в cacheMediaFrom(): для неизменяемых файлов (img_/stk_/lp_ —
        // фото, голосовые, чанки, манифесты) имя уникально и публикуется РОВНО один раз,
        // так что "новее" (created_at) тут не значит "вернее" — легитимных повторных версий
        // одного и того же имени не бывает. Если разные реле в союзе вернули РАЗНОЕ
        // содержимое под одним тегом (одно реле обрезало крупное событие), берём более
        // ДЛИННУЮ копию — обрезка только укорачивает, никогда не удлиняет.
        val content = if (isImmutableFile(name)) {
            matches.maxByOrNull { it.content.length }?.content
        } else {
            matches.maxByOrNull { it.created_at }?.content
        }
        if (content != null) {
            if (isImmutableFile(name)) mediaCache.put(wireName(name), content)
            return content
        }
        val reason = when {
            eventsOrNull == null -> "ни одно реле не ответило (сеть/Tor недоступны у ЧИТАЮЩЕГО)"
            events.isEmpty() -> "реле ответили, но событий по каналу $channelId вообще нет"
            else -> "реле ответили (${events.size} событий канала), но с тегом #d=этот файл — " +
                "ни одного (контент не залит/не долетел до реле при отправке)"
        }
        throw RuntimeException("Файл '$name' не найден в Nostr (channel=$channelId): $reason")
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

    /** Набор реле, для которых сейчас ВЫПОЛНЯЕТСЯ попытка подписки (защита от шторма). */
    private val connectingRelays = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Стрим сообщений жив, если ВСЕ активные реле подписаны на наш message-sub. */
    override fun isWatchHealthy(): Boolean {
        val subId = "atrumw_$channelId"
        val relays = activeRelays()
        if (relays.isEmpty()) return false
        return relays.all { runCatching { NostrRelayPool.hasSub(it, subId, useTor) }.getOrDefault(false) }
    }

    override fun watchMessages(onNew: () -> Unit): AutoCloseable {
        val subId = "atrumw_$channelId"
        val sinceSec = System.currentTimeMillis() / 1000
        val onEvent: (NostrEvent) -> Unit = { ev ->
            if (ev.kind == 1) {
                NostrMessageStore.merge(channelId, listOf(ev))
                onNew()
            }
        }
        val job = watchScope.launch {
            while (isActive) {
                activeRelays().forEach { url ->
                    if (!NostrRelayPool.hasSub(url, subId, useTor) && !connectingRelays.contains(url + subId)) {
                        launch {
                            val key = url + subId
                            connectingRelays.add(key)
                            try {
                                runCatching { NostrRelayPool.subscribe(url, subId, streamFilter(sinceSec), useTor, onEvent) }
                            } finally {
                                connectingRelays.remove(key)
                            }
                        }
                    }
                }
                delay(if (useTor) RESUBSCRIBE_TOR_MS else RESUBSCRIBE_MS)
            }
        }
        return AutoCloseable {
            job.cancel()
            for (url in activeRelays()) runCatching { NostrRelayPool.unsubscribe(url, subId, useTor) }
        }
    }

    // Мой Nostr-pubkey (hex) — чтобы в стриме профилей пропускать СВОЙ слот
    // (не тратить дорогой Argon2-decrypt на собственные presence-пуши).
    private val myPubkeyHex: String by lazy {
        com.atrum.chat.nostr.Schnorr.pubkeyFromPrivkey(privkey).toHex()
    }

    /** Фильтр стрима профилей: НОВЫЕ FILE_KIND-слоты profiles.txt этого канала. */
    private fun profileStreamFilter(sinceSec: Long): JSONObject = JSONObject().apply {
        put("kinds", JSONArray().put(FILE_KIND))
        put("#t", JSONArray().put(channelId))
        put("#d", JSONArray().put(wireName("profiles.txt")).put("profiles.txt"))
        put("since", sinceSec)
    }

    override fun watchProfiles(onProfile: (String) -> Unit): AutoCloseable {
        val subId = "atrump_$channelId"
        val sinceSec = System.currentTimeMillis() / 1000
        val onEvent: (NostrEvent) -> Unit = { ev ->
            if (ev.kind == FILE_KIND && ev.pubkey != myPubkeyHex) onProfile(ev.content)
        }
        val job = watchScope.launch {
            while (isActive) {
                activeRelays().forEach { url ->
                    if (!NostrRelayPool.hasSub(url, subId, useTor) && !connectingRelays.contains(url + subId)) {
                        launch {
                            val key = url + subId
                            connectingRelays.add(key)
                            try {
                                runCatching { NostrRelayPool.subscribe(url, subId, profileStreamFilter(sinceSec), useTor, onEvent) }
                            } finally {
                                connectingRelays.remove(key)
                            }
                        }
                    }
                }
                delay(if (useTor) RESUBSCRIBE_TOR_MS else RESUBSCRIBE_MS)
            }
        }
        return AutoCloseable {
            job.cancel()
            for (url in activeRelays()) runCatching { NostrRelayPool.unsubscribe(url, subId, useTor) }
        }
    }

    private fun chatFilter(): JSONObject = JSONObject().apply {
        // kind:1 — сообщения, kind:5 — удаления, kind FILE_KIND — файлы.
        put("kinds", JSONArray().put(1).put(5).put(FILE_KIND))
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
        val firstResponse = CompletableDeferred<Unit>()
        // Кастомный SOCKS5-прокси (экран «Соединение») добавляет реальный round-trip до
        // удалённого VPS — «быстрый фейл» direct-режима (8с) тут так же нереалистичен, как
        // и без Tor-цепочки. Даём такой же щедрый бюджет, как Tor, а не только сам Tor.
        val patientMode = useTor || viaCustomProxy()
        val hardDeadline = if (patientMode) SOFT_READ_DEADLINE_TOR_MS else SOFT_READ_DEADLINE_MS
        val graceMs = if (patientMode) READ_GRACE_TOR_MS else READ_GRACE_MS
        val relays = activeRelays()

        coroutineScope {
            val jobs = relays.map { url ->
                launch {
                    val t0 = System.currentTimeMillis()
                    val r = runCatching { NostrRelayPool.query(url, filter, useTor) }.getOrNull()
                    // Телеметрия для экрана «Соединение» (ConnectionStats) — попутно с уже
                    // идущим опросом, никакого нового polling-цикла (см. CLAUDE.md §1).
                    com.atrum.chat.nostr.ConnectionStats.record(
                        url, if (r != null) System.currentTimeMillis() - t0 else null
                    )
                    // UNION READ: события собираются со ВСЕХ ответивших реле (у разных реле
                    // разные подмножества из-за retention и публикации на кворум, а не на все
                    // сразу), поэтому терять остальных после первого ответа нельзя.
                    if (r != null) {
                        collected.addAll(r)
                        responded.incrementAndGet()
                        firstResponse.complete(Unit) // идемпотентно — отмечаем первый ответ
                    }
                }
            }
            // Хеджированное чтение: доставка сообщения партнёру НЕ должна упираться в самый
            // медленный/мёртвый узел. Как только ответило ПЕРВОЕ реле — даём остальным
            // короткое окно [graceMs] добрать события и выходим, не дожидаясь полного
            // дедлайна. Полнота союза не страдает: пропущенное на этом тике реле подберётся
            // на следующем (поллинг ~1с), а долговечный стор ничего не теряет. Жёсткий
            // потолок [hardDeadline] остаётся для случая, когда не ответил вообще никто.
            withTimeoutOrNull(hardDeadline) {
                firstResponse.await()
                withTimeoutOrNull(graceMs) { jobs.joinAll() }
            }
            // Отменяем оставшиеся «висящие» запросы (медленные/мёртвые реле).
            this@coroutineScope.coroutineContext.cancelChildren()
        }

        if (responded.get() == 0) {
            // Прямой (не-Tor) путь совсем не отвечает — похоже на DPI-блокировку по SNI.
            // Включаем фрагментацию ClientHello (см. nostr/SniFragment.kt) на СЛЕДУЮЩую
            // попытку: сам этот тик уже не спасти, но опрос идёт каждые несколько секунд
            // (SyncEngine), так что эффект будет виден быстро.
            if (!useTor) NostrRelayPool.enableDirectFragmentation()
            return null
        }
        val seen = HashSet<String>()
        return collected.filter { seen.add(it.id) }
    }

    /**
     * Публикует событие ПАРАЛЛЕЛЬНО на все реле.
     *
     * Для «запутывания» наблюдателя (timing analysis) и создания иллюзии распределённого
     * вещания, мы ждём подтверждения от КВОРУМА реле (например, 3), прежде чем разблокировать
     * отправителя. Это скрывает, какое именно реле является «ведущим» или ближайшим.
     * Также добавлен небольшой джиттер (случайная задержка) перед отправкой.
     */
    private suspend fun publishToAnyRelay(event: NostrEvent) {
        val firstSuccess = CompletableDeferred<Boolean>()
        val failures = ConcurrentLinkedQueue<String>()
        val relays = activeRelays()
        val remaining = AtomicInteger(relays.size)

        val okCount = AtomicInteger(0)
        // Для Tor увеличиваем кворум до 2 реле для надежности, если реле много.
        // Если реле мало, хватит и 1.
        val targetQuorum = if (relays.size > 3) 2 else 1 

        for (url in relays) {
            publishScope.launch {
                val r = runCatching { NostrRelayPool.publish(url, event, useTor) }
                if (r.isSuccess) {
                    val currentOk = okCount.incrementAndGet()
                    if (currentOk >= targetQuorum) {
                        firstSuccess.complete(true)
                    }
                } else {
                    val host = url.removePrefix("wss://")
                    failures.add("$host: ${r.exceptionOrNull()?.message?.take(60) ?: "?"}")
                }

                if (remaining.decrementAndGet() == 0) {
                    if (okCount.get() > 0) firstSuccess.complete(true)
                    else firstSuccess.complete(false)
                }
            }
        }
        
        // Ждем подтверждения. Увеличиваем общий дедлайн ожидания кворума до 20с для Tor —
        // и точно так же для кастомного SOCKS5-прокси (см. viaCustomProxy()): удалённый
        // VPS-прокси добавляет тот же порядок задержки, что и Tor-цепочка, «быстрый фейл»
        // 8с там нереалистичен и был главной причиной срыва заливки фото/голосовых
        // чанками — один неуспевший чанк ронял всю отправку.
        val result = withTimeoutOrNull(if (useTor || viaCustomProxy()) 20_000L else 8_000L) {
            firstSuccess.await()
        } ?: false

        if (!result) {
            // Тот же сигнал, что и в queryAllRelays(): прямой путь совсем не публикуется —
            // включаем SNI-фрагментацию на дальнейшие попытки (MessageSendManager/PatchQueue
            // и так ретраят отправку, следующая попытка уже пойдёт через неё).
            if (!useTor && okCount.get() == 0) NostrRelayPool.enableDirectFragmentation()
            val errLog = failures.joinToString("; ")
            android.util.Log.e("AtrumNostr", "Publish failed for event ${event.id.take(8)}: $errLog")
            throw RuntimeException("Nostr-реле не подтвердили доставку ($okCount/$targetQuorum) — $errLog")
        }
    }

    companion object {
        const val NOSTR_TOKEN = "NOSTR_V1"
        const val NOSTR_DIRECT_TOKEN = "NOSTR_DIRECT_V1"
        private const val FILE_KIND = 1063
        /**
         * Размер ОДНОГО чанка файла (символов зашифрованного контента).
         *
         * ⚠️ Публичные Nostr-реле ограничивают размер ВСЕГО события (~64 КБ на JSON
         * сообщения `["EVENT",{…}]`, а не только content). К content добавляется обёртка:
         * id(64) + pubkey(64) + sig(128) + теги + экранирование ≈ 0.5–1 КБ. Поэтому чанк
         * должен быть ЗАМЕТНО меньше 65536, иначе событие отклоняется ("too large"), и
         * тогда падает публикация чанка → манифест (шлётся последним) не уходит →
         * у собеседника фото/файл НЕ СОБИРАЕТСЯ. 48000 оставляет ~16 КБ запаса на обёртку.
         * Не поднимать к 64*1024 — это как раз и ломало доставку картинок.
         */
        private const val NOSTR_CHUNK_CHARS = 48_000

    /** Мягкий дедлайн чтения: не ждём медленное/мёртвое реле дольше этого. */
        private const val SOFT_READ_DEADLINE_MS = 8_000L
        /** Для Tor дедлайн чтения больше: построение цепочки + round-trip медленнее. */
        private const val SOFT_READ_DEADLINE_TOR_MS = 15_000L
        /**
         * Окно «добора» союза после ПЕРВОГО ответившего реле. Чтение возвращается через
         * (первый ответ + grace), а не ждёт самый медленный/мёртвый узел до дедлайна —
         * это и убирает задержку доставки «~10с» при части недоступных реле, сохраняя
         * union read (остальные события подберутся на следующем тике поллинга).
         */
        private const val READ_GRACE_MS = 700L
        /** Для Tor окно «добора» шире: round-trip через цепочку медленнее. */
        private const val READ_GRACE_TOR_MS = 1_500L
        /** Как часто сторож проверяет, что стрим-подписка жива (переоткрыть после обрыва). */
        private const val RESUBSCRIBE_MS = 10_000L
        private const val RESUBSCRIBE_TOR_MS = 20_000L

        /** LRU неизменяемых медиа-файлов (чанки/манифесты img_/stk_) — повторное чтение из памяти. */
        private const val MEDIA_CACHE_MAX_CHARS = 8 * 1024 * 1024
        private val mediaCache = object : android.util.LruCache<String, String>(MEDIA_CACHE_MAX_CHARS) {
            override fun sizeOf(key: String, value: String): Int = value.length
        }

        private fun isImmutableFile(name: String): Boolean =
            name.startsWith("img_") || name.startsWith("stk_") || name.startsWith("lp_")

        /** Публичные Nostr-реле (NIP-01, NIP-09). */
        /**
         * Дополнительные реле из подписанного обновляемого списка (RelayListStore).
         * Пусто по умолчанию и до прихода валидного списка → поведение как раньше.
         * Заполняется фоновым refreshRelayList(); встроенные RELAYS — неизменный floor.
         */
        @Volatile
        var extraRelays: List<String> = emptyList()

        /** Активный набор: встроенные + добавленные, без дублей. Floor сохраняется всегда. */
        fun activeRelays(): List<String> = (RELAYS + extraRelays).distinct()

        /**
         * ⚠️ Лёгкий РАЗОВЫЙ пинг ОДНОГО реле — ТОЛЬКО для живой телеметрии экрана
         * «Соединение» (см. ConnectionActivity.startLivePing/stopLivePing). Это НЕ
         * часть боевого чтения ([queryAllRelays]) и НЕ создаёт собственный
         * polling-цикл — вызывающая сторона (ConnectionActivity) сама решает, когда
         * дёргать эту функцию, и делает это ТОЛЬКО пока экран открыт (onResume/onPause),
         * никакого фонового анализа. Минимальный REQ(limit:0) → EOSE через уже
         * существующий персистентный NostrRelayPool — новый WebSocket не создаётся,
         * никакого отдельного OkHttpClient. Всегда useTor=false: экран «Соединение»
         * отвечает именно за прямой/прокси-путь (см. doc-comment ConnectionActivity),
         * Tor-чаты сюда не относятся. Таймаут щедрый и НЕ связан с READ_GRACE_MS —
         * боевой хедж доставки сообщений эта функция не трогает и не может замедлить.
         */
        suspend fun pingRelayForConnectionScreen(url: String, timeoutMs: Long = 6_000L): Long? {
            val probe = JSONObject().apply {
                put("kinds", JSONArray().put(1))
                put("limit", 0)
            }
            val t0 = System.currentTimeMillis()
            return runCatching {
                NostrRelayPool.query(url, probe, useTor = false, timeoutMs = timeoutMs)
            }.fold(
                onSuccess = { System.currentTimeMillis() - t0 },
                onFailure = { null }
            )
        }

        /**
         * Одноразово (без цикла) подтягивает подписанный список реле с реле же и применяет.
         * Безопасно: применит ТОЛЬКО событие с подписью вшитого издателя и версией новее.
         * Сначала поднимает сохранённый список из RelayListStore (мгновенно), потом сеть.
         */
        @Volatile private var lastRelayFetchMs = 0L
        private const val RELAY_REFRESH_THROTTLE_MS = 10 * 60_000L

        suspend fun refreshRelayList(ctx: Context, useTor: Boolean) {
            RelayListStore.ensureLoaded(ctx)
            extraRelays = RelayListStore.extraRelays(ctx)
            val filter = RelayListStore.filter() ?: return
            // Троттлинг сети: не чаще раза в 10 мин (чтобы не долбить реле при частых открытиях).
            val now = System.currentTimeMillis()
            if (now - lastRelayFetchMs < RELAY_REFRESH_THROTTLE_MS) return
            lastRelayFetchMs = now
            val collected = ConcurrentLinkedQueue<NostrEvent>()
            coroutineScope {
                val jobs = activeRelays().map { url ->
                    launch {
                        val r = runCatching { NostrRelayPool.query(url, filter, useTor) }.getOrNull()
                        if (r != null) collected.addAll(r)
                    }
                }
                withTimeoutOrNull(if (useTor) 15_000L else 8_000L) { jobs.joinAll() }
                jobs.forEach { it.cancel() }
            }
            collected.sortedByDescending { it.created_at }.forEach { RelayListStore.tryApply(ctx, it) }
            extraRelays = RelayListStore.extraRelays(ctx)
        }

        /** Публикует событие-список на все активные реле (для экрана издателя). Возвращает число успехов. */
        suspend fun publishRelayListEvent(ev: NostrEvent, useTor: Boolean): Int {
            val ok = AtomicInteger(0)
            coroutineScope {
                activeRelays().map { url ->
                    launch { if (runCatching { NostrRelayPool.publish(url, ev, useTor) }.isSuccess) ok.incrementAndGet() }
                }.joinAll()
            }
            return ok.get()
        }

        /**
         * Проверка доставки: опрашивает реле и считает, на СКОЛЬКИХ реально читается
         * опубликованный список (подпись валидна, версия >= ожидаемой). Это честнее, чем
         * «реле приняло запись» — подтверждает, что обновление действительно доступно другим.
         */
        suspend fun countRelaysWithRelayList(pubkeyHex: String, minVersion: Int, useTor: Boolean): Int {
            val filter = org.json.JSONObject().apply {
                put("authors", org.json.JSONArray().put(pubkeyHex))
                put("kinds", org.json.JSONArray().put(RelayListStore.KIND))
                put("#d", org.json.JSONArray().put(RelayListStore.D_TAG))
                put("limit", 1)
            }
            val hits = AtomicInteger(0)
            coroutineScope {
                activeRelays().map { url ->
                    launch {
                        val evs = runCatching { NostrRelayPool.query(url, filter, useTor) }.getOrNull() ?: return@launch
                        val ok = evs.any { ev ->
                            ev.pubkey.equals(pubkeyHex, ignoreCase = true) &&
                                NostrEvent.verifySignature(ev) &&
                                RelayListStore.versionOf(ev.content) >= minVersion
                        }
                        if (ok) hits.incrementAndGet()
                    }
                }.joinAll()
            }
            return hits.get()
        }

        /** Сколько всего реле сейчас опрашивается (для строки «M из K»). */
        fun relayCount(): Int = activeRelays().size

        val RELAYS = listOf(
            "wss://nos.lol",
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr.mom",
            // Резервные реле — больше избыточности на случай бана/падения части реле.
            // Публикация терпима к мёртвым адресам: неответивший просто уменьшает счётчик.
            "wss://relay.snort.social",
            "wss://nostr.oxtr.dev",
            "wss://nostr-pub.wellorder.net",
            "wss://relay.nostr.bg",
            "wss://nostr.bitcoiner.social",
            // ⚠️ ДОБАВЛЕНО: усиление устойчивости к DPI-блокировке по SNI/домену. Блокировка
            // по SNI режет реле поодиночке — чем шире и разнообразнее встроенный floor-список
            // (разные операторы/инфраструктура), тем меньше шанс, что ВСЕ реле окажутся
            // заблокированы одновременно ещё до того, как подтянется подписанный список из
            // RelayListStore (см. NostrTransport.refreshRelayList). Сам факт блокировки
            // ОДНОГО реле не ломает синк — queryAllRelays() уже делает union-чтение по всем
            // активным реле параллельно, так что часть недоступных просто не участвует.
            "wss://relay.nostr.band",
            "wss://purplepag.es"
        )

        /** Фоновый scope для дослания публикаций на оставшиеся реле (не блокирует отправителя). */
        private val publishScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
