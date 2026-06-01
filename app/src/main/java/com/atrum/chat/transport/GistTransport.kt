package com.atrum.chat.transport

import com.atrum.chat.GistApi
import com.atrum.chat.transport.AllGistData

/**
 * ChatTransport поверх GitHub Gist API.
 *
 * Тонкая обёртка — делегирует все вызовы в GistApi без изменения логики.
 * Используется как основной транспорт, пока GitHub доступен.
 */
class GistTransport(private val api: GistApi) : ChatTransport {

    override val displayName: String get() = "GitHub Gist"
    override val displayIcon: String get() = "☁"
    override val chatId: String get() = api.gistId

    override suspend fun loadContent(): String = api.loadContent()

    /**
     * ETag-оптимизированная загрузка: возвращает null если гист не изменился (304).
     * Экономит API-квоту GitHub при частом polling-е без новых сообщений.
     */
    override suspend fun loadContentIfChanged(): String? = api.loadContentIfChanged()

    /**
     * Загружает chat.txt и reactions.txt за один fetchGistJson (ETag-оптимизация).
     * Вдвое сокращает GET-запросы на каждый тик polling-а по сравнению с двумя отдельными вызовами.
     */
    override suspend fun loadChatAndReactionsIfChanged(): ChatAndReactions? =
        api.loadChatAndReactionsIfChanged()

    /** Полная (без ETag) версия: один GET, оба файла. */
    override suspend fun loadChatAndReactions(): ChatAndReactions =
        api.loadChatAndReactions()

    /** ETag-оптимизированный единый GET: chat + reactions + profiles. null при 304. */
    override suspend fun loadAllIfChanged(): AllGistData? = api.loadAllIfChanged()

    /** Полный (без ETag) единый GET: chat + reactions + profiles. */
    override suspend fun loadAll(): AllGistData = api.loadAll()

    override fun updateChatContentHint(content: String) = api.updateChatContentHint(content)
    override fun touchChatContentHint() = api.touchChatContentHint()
    override suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String>) =
        api.appendLine(encryptedLine, extraFiles)
    override suspend fun saveFile(name: String, content: String) = api.saveFile(name, content)
    override suspend fun saveFiles(files: Map<String, String>) = api.saveFiles(files)
    override suspend fun loadFileOrNull(name: String): String? = api.loadFileOrNull(name)
    override suspend fun loadFile(name: String): String = api.loadFile(name)
    override suspend fun replaceLine(oldLine: String, newLine: String): Boolean = api.replaceLine(oldLine, newLine)
    override suspend fun deleteLine(line: String): Boolean = api.deleteLine(line)

    // Reactions — атомарная версия через writeMutex в GistApi
    override suspend fun loadReactions(): String = api.loadReactionsContent()
    override suspend fun toggleReaction(msgId: String, emoji: String, userId: String): Boolean =
        api.toggleReaction("$msgId|$emoji|$userId")

    override suspend fun saveFileChunked(
        name: String,
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ) = api.saveFileChunked(name, encryptedContent, password, onProgress)

    /**
     * Создаёт отдельный приватный gist для изображения (один POST-запрос).
     * Возвращает "gist:GIST_ID".
     *
     * Ключевое преимущество: не PATCH-ает основной чат-gist → ноль конкуренции
     * с фоновыми операциями (heartbeat, typing, read-receipt) → не вызывает
     * secondary rate limit GitHub.
     */
    override suspend fun uploadImage(
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)?
    ): String = api.createImageGist(encryptedContent)

    /**
     * Загружает зашифрованный контент изображения по ссылке.
     *
     * "gist:GIST_ID" → загрузить все файлы из image gist, склеить чанки,
     *                   вернуть полный зашифрованный контент.
     * "img_xxx.txt"  → loadFile из основного чат-gist (старый формат).
     */
    override suspend fun loadImageByRef(ref: String): String {
        if (!ref.startsWith("gist:")) return api.loadFile(ref)

        val imageGistId = ref.removePrefix("gist:")
        val files = api.loadGistAllFiles(imageGistId)

        // Один файл (img.txt) — просто возвращаем контент
        if (files.size == 1) return files.values.first()

        // Несколько файлов (img_c00.txt, img_c01.txt, …) — склеиваем по порядку
        val chunks = files.entries
            .sortedBy { it.key }
            .map { it.value }
        return chunks.joinToString("")
    }

    /** Сбрасывает кэшированный ETag — вызывать после clearHistory. */
    fun resetEtag() = api.resetEtag()
}
