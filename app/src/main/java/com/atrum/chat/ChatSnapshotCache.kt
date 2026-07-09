package com.atrum.chat

import android.content.Context
import com.atrum.chat.transport.AllGistData
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Снимок последнего загруженного состояния чата (chat.txt + reactions + profiles).
 * Память + диск: при перезаходе (в т.ч. после перезапуска приложения) чат
 * показывается МГНОВЕННО из снимка, а сеть через медленный Tor догружает в фоне.
 *
 * На диске лежит уже ЗАШИФРОВАННЫЙ контент (тот же, что на реле) — ключей тут нет,
 * поэтому хранение в filesDir безопасно.
 */
object ChatSnapshotCache {

    private val map = ConcurrentHashMap<String, AllGistData>()
    @Volatile private var dir: File? = null
    private val io = Executors.newSingleThreadExecutor()

    /** Вызывать один раз из App.onCreate. */
    fun init(context: Context) {
        dir = File(context.applicationContext.filesDir, "chat_snapshots").apply { mkdirs() }
    }

    fun get(chatId: String): AllGistData? {
        map[chatId]?.let { return it }
        val d = dir ?: return null
        val f = File(d, fileName(chatId))
        if (!f.exists()) return null
        return try {
            readSnap(f).also { map[chatId] = it }
        } catch (_: Exception) { null }
    }

    fun put(chatId: String, data: AllGistData) {
        val prev = map.put(chatId, data)
        if (prev != null &&
            prev.chatContent == data.chatContent &&
            prev.reactionsContent == data.reactionsContent &&
            prev.profilesContent == data.profilesContent
        ) return
        val d = dir ?: return
        io.execute { runCatching { writeSnap(File(d, fileName(chatId)), data) } }
    }

    fun clear(chatId: String) {
        map.remove(chatId)
        val d = dir ?: return
        io.execute { runCatching { File(d, fileName(chatId)).delete() } }
    }

    // ⚠️ Фикс (аудит §16): раньше имя файла строилось из Integer.toHexString(chatId.hashCode())
    // — обычный 32-битный String.hashCode(). У двух РАЗНЫХ chatId (у нас это 128-битный
    // SecureRandom-секрет, см. CreateChatActivity.generateChannelId) теоретически может
    // совпасть 32-битный хеш — тогда чат Б читал/писал бы файл снимка чата А на диске
    // (после перезапуска процесса, когда в памяти ещё пусто — см. get()). Вероятность на
    // практике ничтожна, но у сборки, которую многократно переустанавливают/пересобирают
    // для тестов, лучше не полагаться на это. SHA-256 снижает риск до пренебрежимого.
    private fun fileName(chatId: String): String = "snap_" + sha256(chatId).take(32) + ".dat"

    private fun sha256(s: String): String {
        val b = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(hex[v ushr 4]); sb.append(hex[v and 0x0F]) }
        return sb.toString()
    }

    private fun writeSnap(f: File, d: AllGistData) {
        DataOutputStream(f.outputStream().buffered()).use { o ->
            writeStr(o, d.chatContent)
            writeStr(o, d.reactionsContent)
            writeStr(o, d.profilesContent)
        }
    }

    private fun readSnap(f: File): AllGistData =
        DataInputStream(f.inputStream().buffered()).use { i ->
            AllGistData(readStr(i), readStr(i), readStr(i))
        }

    private fun writeStr(o: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun readStr(i: DataInputStream): String {
        val n = i.readInt()
        val b = ByteArray(n)
        i.readFully(b)
        return String(b, Charsets.UTF_8)
    }
}
