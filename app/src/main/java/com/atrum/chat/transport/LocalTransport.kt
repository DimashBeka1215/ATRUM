package com.atrum.chat.transport

import android.content.Context
import com.atrum.chat.data.ChatDao
import java.io.File

/**
 * Локальный транспорт для чата «Избранное».
 * Сообщения сохраняются в файл на диске — история не теряется при перезаходе.
 */
class LocalTransport(
    private val chatIdLong: Long,
    private val chatDao: ChatDao,
    private val context: Context
) : ChatTransport {
    override val displayName: String = "Local"
    override val displayIcon: String = "★"
    override val chatId: String = "local_$chatIdLong"

    private val storageFile: File
        get() = File(context.filesDir, "local_chat_${chatIdLong}.dat")

    private var localContent: String = loadFromDisk()

    private fun loadFromDisk(): String = try {
        val f = File(context.filesDir, "local_chat_${chatIdLong}.dat")
        if (f.exists()) f.readText(Charsets.UTF_8) else ""
    } catch (_: Exception) { "" }

    private fun saveToDisk() {
        try {
            storageFile.writeText(localContent, Charsets.UTF_8)
        } catch (_: Exception) { }
    }

    override suspend fun loadContent(): String = localContent

    override suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String>) {
        localContent = if (localContent.isEmpty()) encryptedLine
                       else "$localContent\n$encryptedLine"
        saveToDisk()
    }

    override suspend fun saveFile(name: String, content: String) {
        // Профили и реакции не нужны в локальном чате
    }

    override suspend fun loadFileOrNull(name: String): String? = null

    override suspend fun loadFile(name: String): String = ""

    override suspend fun replaceLine(oldLine: String, newLine: String): Boolean {
        val lines = localContent.split("\n").toMutableList()
        val idx = lines.indexOf(oldLine)
        if (idx == -1) return false
        lines[idx] = newLine
        localContent = lines.joinToString("\n")
        saveToDisk()
        return true
    }

    override suspend fun deleteLine(line: String): Boolean {
        val lines = localContent.split("\n").toMutableList()
        val ok = lines.remove(line)
        localContent = lines.joinToString("\n")
        if (ok) saveToDisk()
        return ok
    }
}
