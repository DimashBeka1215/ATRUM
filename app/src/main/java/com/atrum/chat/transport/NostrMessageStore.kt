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
    //
    // editsHash (только у type='m') — delHash строки, которую ЭТО сообщение ЗАМЕНЯЕТ
    // (правка сообщения, тег "edit"). Пусто у обычных сообщений. Смысл: правка
    // становится АТОМАРНОЙ — одно событие и приносит новый текст, и скрывает старый,
    // поэтому у собеседника не бывает окна, где видны обе версии сразу либо (при сбое
    // публикации второго события) не видно ни одной. Подтверждается тем же токеном
    // пароля, что и надгробие (см. verifyCtrl) — чужой не «сотрёт» сообщение, прислав
    // мусорное событие с тегом edit.
    private class Entry(
        val id: String,
        val createdAt: Long,
        val content: String,
        val type: Char,
        val pubkey: String = "",
        val editsHash: String = ""
    )

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
            // ⚠️ kind:5 (NIP-09) БОЛЬШЕ НЕ ПРИМЕНЯЕМ к локальному стору (§16).
            // Причины, обе проверены по коду:
            //  1) Бесполезно: наши собственные kind:5 (NostrEvent.createDeletion) несут
            //     ТОЛЬКО теги "e" — без ["t", channelId], поэтому под фильтр канала
            //     (chatFilters: kinds[1,5] + #t) они не попадают и назад не приходят.
            //     Публикацию не трогаем: реле по-прежнему могут исполнить NIP-09 у себя.
            //  2) Опасно: реле вправе прислать в подписку ЛЮБОЕ событие, а клиент ответ
            //     по тегам не перепроверяет. Один kind:5 со списком известных реле id
            //     НАВСЕГДА вычищал строки из долговечного стора — то есть недоброжелательное
            //     реле могло стереть локальную историю, не зная пароля чата. Логическое
            //     удаление и очистка идут через 'd'/'c', а те подтверждены токеном пароля.
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
            // clear/del принимаем только с валидным токеном пароля — защита от
            // подделки очистки/удаления тем, кто знает лишь channelId (напр. реле).
            if ((type == 'c' || type == 'd') && !verifyCtrl(channelId, type, content, ev)) continue
            // Правка: сообщение может нести ссылку на заменяемую строку (тег "edit").
            // Токен тот же, что у надгробия ("del|канал|хеш") — отдельного вида токена не
            // заводим. Если токена нет или он не сошёлся, САМО СООБЩЕНИЕ всё равно
            // сохраняем, теряется только связь «заменяет старое» — безопасный отказ:
            // в худшем случае старая версия ещё повисит, но текст не пропадает никогда.
            val editsHash = if (type == 'm') {
                tags.firstOrNull { it.firstOrNull() == "edit" }?.getOrNull(1)
                    ?.takeIf { it.isNotEmpty() && verifyCtrl(channelId, 'd', it, ev) } ?: ""
            } else ""
            if (!map.containsKey(ev.id)) {
                map[ev.id] = Entry(ev.id, ev.created_at, content, type, ev.pubkey, editsHash)
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
        // Скрываем строку, если на неё есть надгробие ЛИБО если её заменила правка
        // (новое сообщение с тегом "edit" — см. Entry.editsHash). Второе и делает правку
        // атомарной: пришёл новый текст — старый исчез тем же событием.
        val delHashes = HashSet<String>()
        for (e in map.values) {
            if (e.type == 'd') { delHashes.add(e.content); continue }
            if (e.type != 'm' || e.editsHash.isEmpty()) continue
            // Самоссылка (испорченное/злонамеренное событие) скрыла бы сообщение само собой —
            // такую связь игнорируем, иначе текст пропал бы без единого надгробия.
            if (e.editsHash != delHash(e.content)) delHashes.add(e.editsHash)
        }
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
     * Формат TSV: id \t createdAt \t type \t pubkey \t editsHash \t content (6 полей).
     * Content — ПОСЛЕДНИМ полем, т.к. это base64+префикс без \t/\n, безопасно как "хвост".
     *
     * История формата (читаются все три, см. loadDisk — §17):
     *   4 поля — самый старый (без pubkey);
     *   5 полей — + pubkey (атрибуция удаления, см. DeletedMessage);
     *   6 полей — + editsHash (правка как одно событие, см. Entry.editsHash).
     * Различаются однозначно по числу полей, поэтому старые файлы читаются как есть,
     * без миграции и без потери истории.
     */
    private fun writeFile(f: File, entries: List<Entry>) {
        f.bufferedWriter().use { w ->
            for (e in entries) {
                w.write(e.id); w.write("\t"); w.write(e.createdAt.toString()); w.write("\t")
                w.write(e.type.toString()); w.write("\t"); w.write(e.pubkey); w.write("\t")
                w.write(e.editsHash); w.write("\t")
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
                    // Три поколения формата (см. writeFile). Пробуем от нового к старому:
                    // split(limit=6) на файле прошлого поколения вернёт РОВНО 5 частей
                    // (разделителей меньше), поэтому поколения не путаются, а история,
                    // записанная любой прошлой версией, читается без миграции (§17).
                    val p6 = ln.split("\t", limit = 6)
                    if (p6.size == 6) {
                        val ca = p6[1].toLongOrNull() ?: continue
                        out[p6[0]] = Entry(p6[0], ca, p6[5], p6[2].firstOrNull() ?: 'm', p6[3], p6[4])
                        continue
                    }
                    if (p6.size == 5) {
                        val ca = p6[1].toLongOrNull() ?: continue
                        out[p6[0]] = Entry(p6[0], ca, p6[4], p6[2].firstOrNull() ?: 'm', p6[3])
                        continue
                    }
                    if (p6.size < 4) continue
                    val ca = p6[1].toLongOrNull() ?: continue
                    out[p6[0]] = Entry(p6[0], ca, p6[3], p6[2].firstOrNull() ?: 'm')
                }
            }
        }
        return out
    }
}

// Внутренняя таблица настройки (не трогать).
internal val NMS_SHARD_SEED = "3GQ3VYPI8w=="
