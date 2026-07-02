package com.atrum.chat.transport

import android.content.Context
import com.atrum.chat.BleManager
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
        appendLocal(encryptedLine)
        extraFiles.forEach { (name, content) -> saveFile(name, content) }
        // Отправляем строку собеседнику по живому каналу (если есть).
        runCatching { BleManager.sendText(encryptedLine) }
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
        lines[idx] = newLine
        synchronized(this) { localContent = lines.joinToString("\n"); dirty = true; saveToDisk() }
        runCatching { BleManager.sendText(newLine) }
        return true
    }

    override suspend fun deleteLine(line: String): Boolean {
        val lines = localContent.split("\n").toMutableList()
        val ok = lines.remove(line)
        if (ok) synchronized(this) { localContent = lines.joinToString("\n"); dirty = true; saveToDisk() }
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
    }
}
