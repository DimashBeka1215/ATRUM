package com.atrum.chat.transport

import android.content.Context
import com.atrum.chat.nostr.NostrEvent
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Долговечное локальное хранилище событий-сообщений чата (kind:1): память + диск.
 *
 * Публичные реле НЕ вечны (retention/лимиты подчищают старые события), поэтому история
 * не должна зависеть только от них. Этот стор НАКАПЛИВАЕТ все когда-либо увиденные
 * сообщения и НЕ теряет их при усечённом ответе реле — реле лишь ДОБАВЛЯЮТ новое.
 * Очистка ("clear"-маркер) и удаление ("del"-надгробие) учитываются при рендере.
 *
 * Хранится зашифрованный контент (как на реле) — ключей нет, filesDir безопасен.
 */
object NostrMessageStore {

    // type: 'm' message, 'c' clear marker, 'd' del tombstone (content = хеш удалённого)
    private class Entry(val id: String, val createdAt: Long, val content: String, val type: Char)

    private val mem = ConcurrentHashMap<String, MutableMap<String, Entry>>()
    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "nostr_msgs").apply { mkdirs() }
    }

    @Synchronized
    fun merge(channelId: String, events: List<NostrEvent>) {
        val map = mem.getOrPut(channelId) { loadDisk(channelId) }
        var changed = false
        for (ev in events) {
            if (ev.kind != 1) continue
            val tags = ev.tags
            if (tags.any { it.firstOrNull() == "file" }) continue
            val type = when {
                tags.any { it.firstOrNull() == "clear" } -> 'c'
                tags.any { it.firstOrNull() == "del" } -> 'd'
                else -> 'm'
            }
            val content = if (type == 'd')
                tags.firstOrNull { it.firstOrNull() == "del" }?.getOrNull(1) ?: continue
            else ev.content
            if (!map.containsKey(ev.id)) {
                map[ev.id] = Entry(ev.id, ev.created_at, content, type)
                changed = true
            }
        }
        if (changed) {
            val snapshot = map.values.toList()
            val d = dir
            if (d != null) io.execute { runCatching { writeFile(File(d, fileName(channelId)), snapshot) } }
        }
    }

    @Synchronized
    fun render(channelId: String): String {
        val map = mem.getOrPut(channelId) { loadDisk(channelId) }
        if (map.isEmpty()) return ""
        val clearCutoff = map.values.filter { it.type == 'c' }.maxOfOrNull { it.createdAt } ?: 0L
        val delHashes = map.values.filter { it.type == 'd' }.map { it.content }.toSet()
        return map.values
            .filter { it.type == 'm' && it.createdAt >= clearCutoff && delHash(it.content) !in delHashes }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
            .map { it.content }
            .distinct()
            .joinToString("\n")
    }

    private fun delHash(content: String): String = sha256("atrum_del_$content").take(32)

    private fun sha256(s: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0F]) }
        return sb.toString()
    }

    private fun fileName(channelId: String) = "msgs_" + Integer.toHexString(channelId.hashCode()) + ".tsv"

    private fun writeFile(f: File, entries: List<Entry>) {
        f.bufferedWriter().use { w ->
            for (e in entries) {
                // content — base64+префикс (без \t и \n), безопасно для TSV.
                w.write(e.id); w.write("\t"); w.write(e.createdAt.toString()); w.write("\t")
                w.write(e.type.toString()); w.write("\t"); w.write(e.content); w.write("\n")
            }
        }
    }

    private fun loadDisk(channelId: String): MutableMap<String, Entry> {
        val d = dir ?: return mutableMapOf()
        val f = File(d, fileName(channelId))
        if (!f.exists()) return mutableMapOf()
        val out = LinkedHashMap<String, Entry>()
        runCatching {
            f.bufferedReader().useLines { lines ->
                for (ln in lines) {
                    val p = ln.split("\t", limit = 4)
                    if (p.size < 4) continue
                    val ca = p[1].toLongOrNull() ?: continue
                    out[p[0]] = Entry(p[0], ca, p[3], p[2].firstOrNull() ?: 'm')
                }
            }
        }
        return out
    }
}
