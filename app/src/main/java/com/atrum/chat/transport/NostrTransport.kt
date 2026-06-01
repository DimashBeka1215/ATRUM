package com.atrum.chat.transport

import com.atrum.chat.nostr.NostrEvent
import com.atrum.chat.nostr.NostrRelay
import com.atrum.chat.nostr.toHex
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * ChatTransport поверх Nostr-протокола (NIP-01).
 *
 * ──────────────────────────────────────────────────────────────────────────────
 * Архитектура хранения данных в Nostr:
 *
 *   • Сообщения чата (chat.txt строки) —
 *       kind:1, tags=[["t", channelId]]
 *       content = зашифрованная строка (тот же формат что и в Gist)
 *
 *   • Файлы (profiles.txt, img_*.txt и т.д.) —
 *       kind:1, tags=[["t", channelId], ["file", "<name>"]]
 *       content = содержимое файла
 *       При чтении берём самое новое событие для данного имени файла.
 *
 *   • Удаление (deleteLine / replaceLine) —
 *       NIP-09: kind:5, tags=[["e", "<event_id>"]]
 *       Большинство публичных реле чтят эти запросы.
 *
 * Идентификация канала:
 *   channelId = hex(SHA256("atrum_channel_v1_" + gistId)).take(16)
 *   — детерминировано из gistId, не требует дополнительных данных в БД.
 *
 * Ключ подписи:
 *   privkey = SHA256("atrum_nostr_v1_" + chatPassword + "_" + myUserId)
 *   — уникален для каждого пользователя в каждом чате.
 *
 * Реле:
 *   Запросы рассылаются на все 4 реле параллельно, результаты объединяются
 *   и дедуплицируются по event id. Публикация — последовательно до первого успеха.
 * ──────────────────────────────────────────────────────────────────────────────
 */
class NostrTransport(
    gistId: String,
    private val chatPassword: String,
    private val myUserId: String
) : ChatTransport {

    override val displayName: String get() = "Nostr P2P"
    override val displayIcon: String get() = "⚡"
    override val chatId: String get() = channelId

    // SHA256(prefix + gistId), берём первые 16 hex-символов = 8 байт
    val channelId: String = sha256("atrum_channel_v1_$gistId").toHex().take(16)

    /**
     * SHA-256 последнего успешно загруженного контента.
     * Используется в loadContentIfChanged() для имитации ETag без HTTP-заголовков:
     * если хеш совпал → контент не изменился → возвращаем null.
     */
    @Volatile private var lastContentHash: String? = null

    // Приватный ключ Nostr: уникален per-user per-chat
    private val privkey: ByteArray = sha256("atrum_nostr_v1_${chatPassword}_${myUserId}")

    // ─── ChatTransport impl ───────────────────────────────────────────────────

    /**
     * Загружает все сообщения чата: kind:1 события без тега "file",
     * отсортированные по created_at. Возвращает зашифрованные строки через \n.
     */
    override suspend fun loadContent(): String {
        val events = queryAllRelays(chatFilter())
        val content = events
            .filter { ev -> ev.tags.none { t -> t.firstOrNull() == "file" } }
            .sortedBy { it.created_at }
            .joinToString("\n") { it.content }
        lastContentHash = sha256(content).toHex()
        return content
    }

    /**
     * Nostr-аналог ETag: загружает контент и возвращает null если он не изменился.
     *
     * Вместо HTTP 304 используем SHA-256 контента: если хеш совпал с прошлым
     * запросом — возвращаем null. ChatActivity интерпретирует null как «нет новых
     * данных» и сообщает AdaptiveInterval.reportIdle() → интервал увеличивается.
     *
     * Не идеально как ETag (сетевой запрос всё равно уходит), но даёт UI-оптимизацию:
     * при тихом чате адаптивный интервал замедляется до 30 сек, снижая нагрузку на реле.
     */
    override suspend fun loadContentIfChanged(): String? {
        val events = queryAllRelays(chatFilter())
        val content = events
            .filter { ev -> ev.tags.none { t -> t.firstOrNull() == "file" } }
            .sortedBy { it.created_at }
            .joinToString("\n") { it.content }
        val hash = sha256(content).toHex()
        if (hash == lastContentHash) return null
        lastContentHash = hash
        return content
    }

    /** Публикует одну зашифрованную строку как Nostr kind:1 событие. */
    override suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String>) {
        val event = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(listOf("t", channelId)),
            content = encryptedLine
        )
        publishToAnyRelay(event)
    }

    /**
     * Сохраняет файл (profiles.txt, img_*.txt) как kind:1 с тегом ["file", name].
     * При чтении всегда берём самое свежее событие — это эмулирует перезапись.
     */
    override suspend fun saveFile(name: String, content: String) {
        val event = NostrEvent.create(
            privkeyBytes = privkey,
            kind = 1,
            tags = listOf(
                listOf("t", channelId),
                listOf("file", name)
            ),
            content = content
        )
        publishToAnyRelay(event)
    }

    override suspend fun loadFileOrNull(name: String): String? = try {
        loadFile(name)
    } catch (_: Exception) {
        null
    }

    override suspend fun loadFile(name: String): String {
        val events = queryAllRelays(fileFilter(name))
        return events
            .filter { ev -> ev.tags.any { t -> t.firstOrNull() == "file" && t.getOrNull(1) == name } }
            .maxByOrNull { it.created_at }
            ?.content
            ?: throw RuntimeException("Файл '$name' не найден в Nostr (channel=$channelId)")
    }

    /**
     * Находит событие с контентом == oldLine, публикует NIP-09 deletion,
     * затем публикует newLine как новое событие.
     */
    override suspend fun replaceLine(oldLine: String, newLine: String): Boolean {
        val target = findMessageEvent(oldLine) ?: return false
        publishToAnyRelay(NostrEvent.createDeletion(privkey, target.id))
        appendLine(newLine)
        return true
    }

    /**
     * Находит событие с контентом == line и публикует NIP-09 deletion.
     */
    override suspend fun deleteLine(line: String): Boolean {
        val target = findMessageEvent(line) ?: return false
        publishToAnyRelay(NostrEvent.createDeletion(privkey, target.id))
        return true
    }

    // ─── internal helpers ─────────────────────────────────────────────────────

    private suspend fun findMessageEvent(content: String): NostrEvent? {
        return queryAllRelays(chatFilter())
            .filter { ev -> ev.tags.none { t -> t.firstOrNull() == "file" } }
            .firstOrNull { it.content.trim() == content.trim() }
    }

    /** Фильтр для сообщений чата (без файлов). */
    private fun chatFilter(): JSONObject = JSONObject().apply {
        put("kinds", JSONArray().put(1))
        put("#t", JSONArray().put(channelId))
        put("limit", 1000)
    }

    /** Фильтр для конкретного файла. */
    private fun fileFilter(name: String): JSONObject = JSONObject().apply {
        put("kinds", JSONArray().put(1))
        put("#t", JSONArray().put(channelId))
        // Тег "file" не является стандартным NIP-01 индексируемым тегом,
        // поэтому реле может не фильтровать по нему — фильтруем на клиенте.
        put("limit", 200)
    }

    /** Параллельный запрос на все реле + дедупликация по event id. */
    private suspend fun queryAllRelays(filter: JSONObject): List<NostrEvent> = coroutineScope {
        val jobs = RELAYS.map { url ->
            async {
                try { NostrRelay(url).query(filter) } catch (_: Exception) { emptyList() }
            }
        }
        val all = jobs.awaitAll().flatten()
        val seen = mutableSetOf<String>()
        all.filter { seen.add(it.id) }
    }

    /** Публикует событие последовательно до первого успешного реле. */
    private suspend fun publishToAnyRelay(event: NostrEvent) {
        var lastErr: Exception? = null
        for (url in RELAYS) {
            try {
                NostrRelay(url).publish(event)
                return
            } catch (e: Exception) {
                lastErr = e
            }
        }
        throw lastErr ?: RuntimeException("Все Nostr-реле недоступны")
    }

    // ─── static config ────────────────────────────────────────────────────────

    companion object {
        /** Публичные Nostr-реле с поддержкой NIP-01, NIP-09. */
        val RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.nostr.band",
            "wss://nostr-pub.wellorder.net",
            "wss://nos.lol"
        )

        fun sha256(s: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
    }
}
