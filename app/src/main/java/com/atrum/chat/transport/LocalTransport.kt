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
    override val displayIcon: String = "S" // Changed from ★ to S (Sparkle/Secure/Storage)
    override val chatId: String = "local_$chatIdLong" // Используем фиксированный ID для локального чата

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

    // ⚠️ Чтение и дозапись — всегда через СВЕЖЕЕ состояние диска, а не кэш конструктора:
    // у локального чата теперь может быть НЕСКОЛЬКО писателей через РАЗНЫЕ экземпляры
    // LocalTransport — системные уведомления (SystemNotifications) пишут из открытого
    // чата, списка чатов и фонового сервиса, каждый своим экземпляром. Кэш конструктора
    // у открытого экрана никогда бы не увидел эти строки до перезахода, а дозапись
    // поверх устаревшего кэша ТЕРЯЛА бы чужие строки. Файл крошечный — перечитывание
    // на тике (2с) незаметно.
    override suspend fun loadContent(): String {
        localContent = loadFromDisk()
        return localContent
    }

    override suspend fun loadAll(): AllGistData {
        localContent = loadFromDisk()
        return AllGistData(
            chatContent = localContent,
            reactionsContent = loadFile("reactions.txt"),
            profilesContent = loadFile("profiles.txt")
        )
    }

    override suspend fun loadAllIfChanged(): AllGistData? = loadAll()

    override fun touchChatContentHint() {}
    override fun updateChatContentHint(content: String) {}

    override suspend fun appendLine(
        encryptedLine: String,
        extraFiles: Map<String, String>,
        onFileProgress: ((fileName: String, current: Int, total: Int) -> Unit)?
    ) {
        localContent = loadFromDisk().let { fresh ->
            if (fresh.isEmpty()) encryptedLine else "$fresh\n$encryptedLine"
        }
        saveToDisk()
        
        // В локальном чате стикеры и другие файлы тоже нужно сохранять на диск,
        // иначе MessageAdapter не сможет их загрузить (будут вечно «в обработке»).
        extraFiles.forEach { (name, content) ->
            saveFile(name, content)
        }
    }

    override suspend fun loadFileOrNull(name: String): String? = try {
        val f = File(context.filesDir, "local_chat_${chatIdLong}_${safeChatFileName(name)}")
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    } catch (_: Exception) { null }

    override suspend fun loadFile(name: String): String = loadFileOrNull(name) ?: ""

    override suspend fun saveFile(name: String, content: String) {
        try {
            val f = File(context.filesDir, "local_chat_${chatIdLong}_${safeChatFileName(name)}")
            f.writeText(content, Charsets.UTF_8)
        } catch (_: Exception) { }
    }

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

    override suspend fun uploadImage(
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): String {
        // В локальном чате просто сохраняем файл на диск и возвращаем псевдо-ссылку
        val id = System.currentTimeMillis().toString()
        saveFile("img_$id.txt", encryptedContent)
        return "img_$id.txt"
    }

    override suspend fun loadImageByRef(ref: String): String {
        // Для локального чата ссылки вида img_xxx.txt
        return loadFile(ref)
    }
}
