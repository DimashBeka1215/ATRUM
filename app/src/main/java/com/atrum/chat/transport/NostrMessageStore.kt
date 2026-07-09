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

    // type: 'm' message, 'c' clear marker, 'd' del tombstone (content = хеш удалённого).
    // pubkey — автор ИМЕННО ЭТОГО события (для 'd' — кто выполнил удаление: автор
    // сообщения или админ группы, см. deletedMessagesFor/UserStatsActivity). "" для
    // строк, загруженных со старого (до этого поля) формата диска — атрибуция для
    // них просто не показывается, это не баг, а разовый переходный период.
    private class Entry(val id: String, val createdAt: Long, val content: String, val type: Char, val pubkey: String = "")

    /** Удалённое сообщение для экрана статистики: исходный контент + когда/кем удалено. */
    data class DeletedMessage(val encryptedContent: String, val deletedAtMs: Long, val deleterPubkey: String)

    private val mem = ConcurrentHashMap<String, MutableMap<String, Entry>>()
    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()

    /**
     * Пароль чата по channelId — нужен для проверки токена аутентификации
     * на управляющих событиях (clear/del). Регистрируется транспортом.
     */
    private val channelSecrets = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "nostr_msgs").apply { mkdirs() }
    }

    /** Транспорт сообщает пароль канала, чтобы проверять подлинность clear/del-маркеров. */
    fun registerChannel(channelId: String, password: String) {
        if (password.isNotEmpty()) channelSecrets[channelId] = password
    }

    /**
     * Токен подлинности управляющего события (clear/del). Доказывает знание пароля
     * чата — чужой (например, вредоносное реле, знающее лишь channelId) не сможет
     * подделать «очистку истории» или «удаление» чужого сообщения.
     *
     *   clear: payload = "clear|<channelId>|<created_at>"  (привязка к времени —
     *          нельзя выдвинуть cutoff в будущее без пароля)
     *   del:   payload = "del|<channelId>|<delHash>"       (привязка к сообщению)
     */
    fun ctrlToken(secret: String, payload: String): String =
        sha256("atrum_ctrl_v1|$secret|$payload").take(32)

    /**
     * Проверяет токен на clear/del-событии.
     * Fail-open если пароль канала ещё не зарегистрирован (не ломаем работу, если
     * проверка вызвана до registerChannel). Fail-closed если пароль есть, но токен
     * отсутствует/не совпадает — подделка или старый клиент без токена игнорируются.
     */
    private fun verifyCtrl(channelId: String, type: Char, content: String, ev: NostrEvent): Boolean {
        val secret = channelSecrets[channelId] ?: return true
        val auth = ev.tags.firstOrNull { it.firstOrNull() == "auth" }?.getOrNull(1) ?: return false
        val payload = if (type == 'c') "clear|$channelId|${ev.created_at}" else "del|$channelId|$content"
        val expected = ctrlToken(secret, payload)
        if (auth != expected) {
            android.util.Log.w("NostrMessageStore", "Invalid CTRL token for $type: expected $expected, got $auth")
            return false
        }
        return true
    }

    @Synchronized
    fun merge(channelId: String, events: List<NostrEvent>) {
        val map = mem.getOrPut(channelId) { loadDisk(channelId) }
        var changed = false
        for (ev in events) {
            if (ev.kind != 1 && ev.kind != 5) continue // kind 5 = deletion
            val tags = ev.tags
            if (tags.any { it.firstOrNull() == "file" }) continue

            if (ev.kind == 5) {
                val deletedIds = tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }
                for (id in deletedIds) {
                    if (map.containsKey(id)) {
                        android.util.Log.d("NostrMessageStore", "Removed deleted event $id for $channelId")
                        map.remove(id)
                        changed = true
                    }
                }
                continue
            }

            val type = when {
                tags.any { it.firstOrNull() == "clear" } -> 'c'
                tags.any { it.firstOrNull() == "del" } -> 'd'
                else -> 'm'
            }
            val content = if (type == 'd')
                tags.firstOrNull { it.firstOrNull() == "del" }?.getOrNull(1) ?: continue
            else ev.content
            // clear/del принимаем только с валидным токеном пароля — защита от
            // подделки очистки/удаления тем, кто знает лишь channelId (напр. реле).
            if ((type == 'c' || type == 'd') && !verifyCtrl(channelId, type, content, ev)) continue
            if (!map.containsKey(ev.id)) {
                map[ev.id] = Entry(ev.id, ev.created_at, content, type, ev.pubkey)
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

    /**
     * Удалённые сообщения этого канала для экрана статистики (админ-модерация):
     * исходный (ещё зашифрованный, как и всё в этом сторе) контент + когда и кем
     * опубликовано "надгробие". Не влияет на обычный рендер чата (render() их
     * по-прежнему исключает) — это ОТДЕЛЬНАЯ точка чтения только для UI статистики.
     * Ограничение: если это устройство никогда не видело исходное 'm'-событие ДО
     * удаления (например, сообщение стёрто быстрее, чем успел дойти опрос) —
     * восстановить контент неоткуда, такая запись просто не попадёт в список.
     */
    @Synchronized
    fun deletedMessagesFor(channelId: String): List<DeletedMessage> {
        val map = mem.getOrPut(channelId) { loadDisk(channelId) }
        val byHash = HashMap<String, Entry>()
        for (e in map.values) if (e.type == 'm') byHash[delHash(e.content)] = e
        // ⚠️ Дедуп по хешу удаления (репорт: "дубликат в удалённых" при быстром
        // delete→restore на экране статистики): несколько 'd'-событий с ОДНИМ и тем же
        // content (=delHash) означают ОДНО и то же логическое удаление ОДНОГО сообщения —
        // например, если надгробие случайно опубликовано дважды (двойной тап, ретрай) или
        // event.id отличается из-за разной created_at при почти одновременных попытках.
        // Семантически это одна и та же запись "это сообщение удалено", поэтому в списке
        // должна быть ровно одна строка на исходное сообщение — берём САМОЕ РАННЕЕ надгробие
        // (более старое = более достоверное "когда реально удалили").
        return map.values
            .filter { it.type == 'd' }
            .mapNotNull { d -> byHash[d.content]?.let { orig -> Triple(d.content, DeletedMessage(orig.content, d.createdAt, d.pubkey), d.createdAt) } }
            .groupBy { it.first }
            .map { (_, group) -> group.minByOrNull { it.third }!!.second }
            .sortedByDescending { it.deletedAtMs }
    }

    private fun delHash(content: String): String = sha256("atrum_del_$content").take(32)

    private fun sha256(s: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0F]) }
        return sb.toString()
    }

    // ⚠️ Фикс (§16, тот же класс проблемы, что и в ChatSnapshotCache.fileName): раньше
    // использовался обычный 32-битный String.hashCode() — теоретическая коллизия двух
    // разных channelId привела бы к чтению/записи ЧУЖОГО файла истории сообщений на
    // диске. sha256() уже есть в этом файле (для delHash/ctrlToken) — переиспользуем.
    private fun fileName(channelId: String) = "msgs_" + sha256(channelId).take(32) + ".tsv"

    /**
     * Формат TSV: id \t createdAt \t type \t pubkey \t content (5 полей, pubkey добавлен
     * для атрибуции удаления — см. DeletedMessage). Content — ПОСЛЕДНИМ полем, т.к. это
     * base64+префикс без \t/\n, безопасно как "хвост" строки.
     */
    private fun writeFile(f: File, entries: List<Entry>) {
        f.bufferedWriter().use { w ->
            for (e in entries) {
                w.write(e.id); w.write("\t"); w.write(e.createdAt.toString()); w.write("\t")
                w.write(e.type.toString()); w.write("\t"); w.write(e.pubkey); w.write("\t")
                w.write(e.content); w.write("\n")
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
                    // Новый формат — 5 полей (с pubkey). Старый файл (до этого поля) —
                    // 4 поля, читаем как раньше с pubkey="" (просто нет атрибуции удаления
                    // для сообщений, увиденных до апгрейда — не крашимся, не теряем историю).
                    val p5 = ln.split("\t", limit = 5)
                    if (p5.size == 5) {
                        val ca = p5[1].toLongOrNull() ?: continue
                        out[p5[0]] = Entry(p5[0], ca, p5[4], p5[2].firstOrNull() ?: 'm', p5[3])
                    } else {
                        val p4 = ln.split("\t", limit = 4)
                        if (p4.size < 4) continue
                        val ca = p4[1].toLongOrNull() ?: continue
                        out[p4[0]] = Entry(p4[0], ca, p4[3], p4[2].firstOrNull() ?: 'm')
                    }
                }
            }
        }
        return out
    }
}

// Внутренняя таблица настройки (не трогать).
internal val NMS_SHARD_SEED = "3GQ3VYPI8w=="
