package com.atrum.chat.transport

import android.content.Context
import com.atrum.chat.BleManager
import kotlinx.coroutines.delay
import java.io.File

/**
 * Транспорт локального BT-чата (Фаза C).
 *
 * Модель: история хранится на диске (как у [LocalTransport]), а доставка между двумя
 * телефонами идёт по живому BLE-каналу из [BleManager]. При [appendLine] строка и
 * сохраняется локально, и отправляется собеседнику; входящие строки приходят через
 * [BleManager.Listener.onMessage] и дозаписываются в локальный стор.
 *
 * Только текст — голосовые/медиа в BT-чатах отключены (BLE медленный).
 * Шифрование как обычно: ChatActivity шифрует строку через CryptoHelper по паролю+channelId,
 * оба телефона получили одинаковые секреты при обмене invite по BLE → один ключ.
 *
 * ВНИМАНИЕ: путь проверяется только на двух реальных телефонах.
 */
class BluetoothTransport(
    private val channelId: String,
    private val context: Context
) : ChatTransport, BleManager.Listener {

    override val displayName: String = "Bluetooth"
    override val displayIcon: String = "BT"
    override val chatId: String = channelId

    @Volatile private var localContent: String = loadFromDisk()
    @Volatile private var dirty: Boolean = true
    @Volatile private var onNewCb: (() -> Unit)? = null

    init {
        // Перехватываем поток BLE-событий у CreateChatActivity.
        BleManager.setListener(this)
    }

    // ── Дисковый стор ────────────────────────────────────────────────────────────

    private fun storeFile(name: String) = File(context.filesDir, "bt_chat_${channelId}_${safeChatFileName(name)}")
    private fun chatFile() = File(context.filesDir, "bt_chat_${channelId}.dat")

    private fun loadFromDisk(): String = try {
        chatFile().takeIf { it.exists() }?.readText(Charsets.UTF_8) ?: ""
    } catch (_: Exception) { "" }

    @Synchronized
    private fun saveToDisk() {
        try { chatFile().writeText(localContent, Charsets.UTF_8) } catch (_: Exception) {}
    }

    @Synchronized
    private fun appendLocal(line: String) {
        localContent = if (localContent.isEmpty()) line else "$localContent\n$line"
        dirty = true
        saveToDisk()
    }

    // ── BleManager.Listener: приём строк собеседника ─────────────────────────────

    override fun onMessage(text: String) {
        if (text.isBlank()) return
        appendLocal(text)
        onNewCb?.invoke()
    }

    /** Собеседник отредактировал сообщение (BT_AUDIT.md 1.3) — заменяем строку у себя. */
    override fun onEdit(oldLine: String, newLine: String) {
        val lines = localContent.split("\n").toMutableList()
        val idx = lines.indexOf(oldLine)
        if (idx == -1) return // строки уже нет (гонка/уже удалена) — тихо игнорируем
        lines[idx] = newLine
        synchronized(this) { localContent = lines.joinToString("\n"); dirty = true; saveToDisk() }
        onNewCb?.invoke()
    }

    /** Собеседник удалил сообщение (BT_AUDIT.md 1.3) — убираем строку у себя. */
    override fun onDelete(line: String) {
        val lines = localContent.split("\n").toMutableList()
        if (!lines.remove(line)) return
        synchronized(this) { localContent = lines.joinToString("\n"); dirty = true; saveToDisk() }
        onNewCb?.invoke()
    }

    // onConnected/onDisconnected/onInvite — в BT-чате уже соединены, реагировать не нужно.

    // ── ChatTransport ────────────────────────────────────────────────────────────

    override suspend fun loadContent(): String = localContent

    override suspend fun loadContentIfChanged(): String? {
        return if (dirty) { dirty = false; localContent } else null
    }

    override suspend fun loadAll(): AllGistData = AllGistData(
        chatContent = localContent,
        reactionsContent = loadFileOrNull("reactions.txt") ?: "",
        profilesContent = loadFileOrNull("profiles.txt") ?: ""
    )

    override suspend fun loadAllIfChanged(): AllGistData? {
        if (!dirty) return null
        dirty = false
        return loadAll()
    }

    override fun watchMessages(onNew: () -> Unit): AutoCloseable {
        onNewCb = onNew
        return AutoCloseable { onNewCb = null }
    }

    override fun updateChatContentHint(content: String) {}
    override fun touchChatContentHint() {}

    override suspend fun appendLine(
        encryptedLine: String,
        extraFiles: Map<String, String>,
        onFileProgress: ((fileName: String, current: Int, total: Int) -> Unit)?
    ) {
        extraFiles.forEach { (name, content) -> saveFile(name, content) }
        // Собеседник может быть сейчас вне радиуса BLE — это НЕ ошибка отправки. Ждём
        // реальной доставки сколько нужно, ретраим при появлении связи, и уходим сразу же
        // при первой возможности. MessageSendManager.runWorker обрабатывает очередь строго
        // по одному элементу за раз — следующее сообщение не начнёт отправляться, пока это
        // не уйдёт, поэтому порядок доставки гарантирован сам по себе.
        //
        // ⚠️ Строку пишем в localContent/на диск ТОЛЬКО после подтверждённой доставки, а не
        // сразу, как было раньше. chatFile() — это и локальный буфер, и "удалённые" данные,
        // которые видит SyncEngine → ChatStore.reconcile() (сверка pending по контенту
        // строки). Запись ДО доставки заставляла reconcile() увидеть свою же неотправленную
        // строку на следующем тике и снять часики раньше времени, независимо от того, дошло
        // ли сообщение до собеседника (проверено симуляцией на конкурентных
        // connect/disconnect и отмене, см. CLAUDE.md §18). Пока ждём — часики держит
        // ChatStore.pendingByRaw (в памяти), диск тут не участвует, сообщение не потеряется
        // при обычном уходе в фон (lifecycleScope не отменяется), но не переживёт полное
        // закрытие чата/приложения — это уже отдельная, более крупная задача (персистентная
        // очередь), если понадобится.
        while (true) {
            if (BleManager.connected && BleManager.sendText(encryptedLine)) break
            delay(RECONNECT_RETRY_MS)
        }
        appendLocal(encryptedLine)
    }

    override suspend fun saveFile(name: String, content: String) {
        try { storeFile(name).writeText(content, Charsets.UTF_8) } catch (_: Exception) {}
    }

    override suspend fun loadFileOrNull(name: String): String? = try {
        storeFile(name).takeIf { it.exists() }?.readText(Charsets.UTF_8)
    } catch (_: Exception) { null }

    override suspend fun loadFile(name: String): String = loadFileOrNull(name) ?: ""

    override suspend fun replaceLine(oldLine: String, newLine: String): Boolean {
        val lines = localContent.split("\n").toMutableList()
        val idx = lines.indexOf(oldLine)
        if (idx == -1) return false
        // ⚠️ Раньше эта строка уходила собеседнику как ОБЫЧНОЕ сообщение (BleManager.sendText) —
        // у него правка превращалась в дубликат-новое-сообщение вместо замены (BT_AUDIT.md 1.3).
        // Теперь — отдельный тип кадра 'E' с ACK; применяем у себя ТОЛЬКО если собеседник
        // подтвердил получение, иначе PatchQueue (§ вызывающая сторона) увидит false и
        // ретраит — а oldLine у нас всё ещё на месте, повтор останется валидным.
        val delivered = BleManager.sendEdit(oldLine, newLine)
        if (!delivered) return false
        lines[idx] = newLine
        synchronized(this) { localContent = lines.joinToString("\n"); dirty = true; saveToDisk() }
        return true
    }

    override suspend fun deleteLine(line: String): Boolean {
        val lines = localContent.split("\n").toMutableList()
        if (line !in lines) return false
        // Раньше удаление вообще не отправлялось собеседнику (BT_AUDIT.md 1.3) — у него
        // сообщение оставалось. Теперь — кадр 'D' с ACK, применяем локально только по подтверждению.
        val delivered = BleManager.sendDelete(line)
        if (!delivered) return false
        val lines2 = localContent.split("\n").toMutableList()
        val ok = lines2.remove(line)
        if (ok) synchronized(this) { localContent = lines2.joinToString("\n"); dirty = true; saveToDisk() }
        return ok
    }

    override suspend fun clearHistory() {
        synchronized(this) { localContent = ""; dirty = true; saveToDisk() }
    }

    override suspend fun uploadImage(
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): String {
        // Медиа в BT-чате не поддерживаются; сохраняем локально как заглушку.
        val id = System.currentTimeMillis().toString()
        saveFile("img_$id.txt", encryptedContent)
        return "img_$id.txt"
    }

    override suspend fun loadImageByRef(ref: String): String = loadFile(ref)

    companion object {
        /** Токен пути в Prefs/приглашении, помечающий чат как Bluetooth-локальный. */
        const val BT_TOKEN = "bluetooth"

        /** Пауза между попытками отправки, пока нет связи/ACK (мс). */
        private const val RECONNECT_RETRY_MS = 1000L
    }
}
