package com.atrum.chat.transport

/**
 * Результат совмещённой загрузки chat.txt и reactions.txt за один сетевой запрос.
 */
data class ChatAndReactions(
    val chatContent: String,
    val reactionsContent: String
)

/**
 * Результат единого poll-запроса: chat.txt + reactions.txt + profiles.txt за один GET.
 *
 * Единый polling loop читает весь gist JSON один раз и извлекает все три файла —
 * вместо двух отдельных запросов (сообщения + профили). Экономит один полный GET/тик.
 */
data class AllGistData(
    val chatContent: String,
    val reactionsContent: String,
    val profilesContent: String
)

/**
 * Абстракция над транспортным слоем чата.
 *
 * Реализации:
 *   - GistTransport  — основной, GitHub Gist (HTTPS)
 *   - NostrTransport — P2P-фолбэк через Nostr-реле (WebSocket)
 *
 * Интерфейс зеркалит методы GistApi, поэтому существующий код
 * (ProfileSync, ImageLoader, ChatActivity) переключается без логических правок.
 */
interface ChatTransport {

    /** Человекочитаемое имя для UI: "GitHub Gist" / "Nostr P2P" */
    val displayName: String

    /** Иконка-символ для статусной строки (☁ / ⚡) */
    val displayIcon: String

    /**
     * Стабильный идентификатор чата, уникальный для каждого канала.
     *
     * Используется как входной параметр для деривации соли Argon2id в CryptoHelper:
     *   salt = SHA-256("atrum_argon2_v1:" + chatId)[0:16]
     *
     * GistTransport  → gistId (GUID gist'а на GitHub)
     * NostrTransport → channelId (hex(SHA256("atrum_channel_v1_" + gistId)).take(16))
     *
     * Обе стороны чата получают одинаковый chatId → одинаковую соль → одинаковый ключ
     * без явного обмена солью через канал связи.
     */
    val chatId: String

    /** Загружает полное содержимое chat.txt (все зашифрованные строки). */
    suspend fun loadContent(): String

    /**
     * Потоковая подписка на новые сообщения: транспорт сам зовёт [onNew] при появлении
     * нового сообщения (минимальная задержка, без частого опроса реле). Возвращает
     * «стоп» — закрыть при завершении. По умолчанию заглушка (стрима нет).
     */
    fun watchMessages(onNew: () -> Unit): AutoCloseable = AutoCloseable {}

    /**
     * Загружает содержимое chat.txt только если оно изменилось с последнего запроса.
     * Возвращает null если контент не изменился (HTTP 304 Not Modified) — UI не нужно обновлять.
     *
     * Дефолтная реализация для транспортов без поддержки ETag — всегда возвращает свежие данные.
     * GistTransport переопределяет через api.loadContentIfChanged().
     */
    suspend fun loadContentIfChanged(): String? = loadContent()

    /**
     * Загружает chat.txt и reactions.txt за ОДИН сетевой запрос.
     * Позволяет сократить вдвое число GET-запросов при каждом тике polling-а.
     *
     * Дефолтная реализация: два отдельных вызова (Nostr и другие транспорты без поддержки объединённой загрузки).
     * GistTransport переопределяет и делает один fetchGistJson, извлекая оба файла из общего JSON.
     */
    suspend fun loadChatAndReactions(): ChatAndReactions =
        ChatAndReactions(loadContent(), loadFileOrNull("reactions.txt") ?: "")

    /**
     * ETag-оптимизированная версия [loadChatAndReactions].
     * Возвращает null если gist не изменился (304) — ни chat.txt, ни reactions.txt обновлять не нужно.
     *
     * Дефолтная реализация: вызывает loadContentIfChanged + loadFileOrNull.
     * GistTransport переопределяет на один fetchGistJson с ETag.
     */
    suspend fun loadChatAndReactionsIfChanged(): ChatAndReactions? {
        val chatContent = loadContentIfChanged() ?: return null
        return ChatAndReactions(chatContent, loadFileOrNull("reactions.txt") ?: "")
    }

    /**
     * Единый ETag-оптимизированный запрос: chat.txt + reactions.txt + profiles.txt.
     * Возвращает null при 304 Not Modified — ничего не изменилось, UI не трогаем.
     *
     * GistTransport переопределяет — один fetchGistJson извлекает все три файла.
     * Дефолтная реализация для прочих транспортов (Nostr, Local) — два запроса.
     */
    suspend fun loadAllIfChanged(): AllGistData? {
        val cr = loadChatAndReactionsIfChanged() ?: return null
        return AllGistData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Полный (без ETag) единый запрос: chat.txt + reactions.txt + profiles.txt.
     * GistTransport переопределяет — один fetchGistJson.
     */
    suspend fun loadAll(): AllGistData {
        val cr = loadChatAndReactions()
        return AllGistData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Обновляет кэш-подсказку для appendLine с последним известным содержимым chat.txt.
     * Вызывать из ChatActivity после каждого успешного чтения chatContent.
     * Дефолтная реализация — no-op (NostrTransport, LocalTransport не используют кэш).
     */
    fun updateChatContentHint(content: String) {}

    /**
     * Продлевает TTL кэш-подсказки без изменения содержимого.
     * Вызывать когда сервер вернул 304 Not Modified — сервер подтвердил что кэш актуален.
     * Дефолтная реализация — no-op.
     */
    fun touchChatContentHint() {}

    /**
     * Дозаписывает строку в конец чата.
     *
     * @param encryptedLine Новая зашифрованная строка
     * @param extraFiles Дополнительные файлы для сохранения в том же PATCH-запросе
     *                   (только для GistTransport, атомарно с appendLine)
     */
    suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String> = emptyMap())

    /** Перезаписывает именованный файл (profiles.txt, img_*.txt и т.д.). */
    suspend fun saveFile(name: String, content: String)

    /**
     * Атомарно перезаписывает несколько файлов за один PATCH-запрос.
     * Дефолтная реализация — последовательные saveFile (для LocalTransport/NostrTransport).
     * GistTransport переопределяет через api.saveFiles() — один запрос вместо N.
     */
    suspend fun saveFiles(files: Map<String, String>) {
        files.forEach { (name, content) -> saveFile(name, content) }
    }

    /** Загружает файл; возвращает null если не существует или ошибка. */
    suspend fun loadFileOrNull(name: String): String?

    /** Загружает файл; бросает исключение если не существует. */
    suspend fun loadFile(name: String): String

    /** Заменяет строку в чате по точному совпадению. Возвращает false если не найдена. */
    suspend fun replaceLine(oldLine: String, newLine: String): Boolean

    /** Удаляет строку из чата по точному совпадению. Возвращает false если не найдена. */
    suspend fun deleteLine(line: String): Boolean

    /**
     * Полная очистка истории чата.
     * Дефолт (Local/прочие): перезаписывает chat.txt пустым манифестом.
     * NostrTransport переопределяет: публикует "маркер очистки" + NIP-09 удаление.
     */
    suspend fun clearHistory() {
        saveFile("chat.txt", "# Atrum Chat")
    }

    /**
     * Сохраняет файл с автоматическим разбиением на чанки для обхода
     * GitHub API rate limit при отправке больших изображений.
     *
     * Дефолтная реализация — просто вызывает [saveFile] (подходит для
     * Nostr и других транспортов без ограничений размера).
     * GistTransport переопределяет этот метод с реальной чанковой логикой.
     *
     * @param name             имя файла (manifest), например img_123.txt
     * @param encryptedContent уже зашифрованный контент для сохранения
     * @param password         пароль чата (нужен для шифрования манифеста)
     * @param onProgress       callback прогресса: (current, total) чанков
     */
    suspend fun saveFileChunked(
        name: String,
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) = saveFile(name, encryptedContent)

    // ─────────────────────────────────────────────────────────────────────────
    // Reactions — хранятся в "reactions.txt" как plaintext строки msgId|emoji|userId
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Загружает содержимое reactions.txt. Пустая строка если файла нет.
     * Дефолтная реализация через loadFileOrNull — используется NostrTransport и др.
     */
    suspend fun loadReactions(): String = loadFileOrNull("reactions.txt") ?: ""

    /**
     * Атомарно переключает реакцию (add / remove toggle).
     * Возвращает true = реакция добавлена, false = удалена.
     *
     * GistTransport переопределяет для атомарной операции через writeMutex.
     * Дефолтная реализация — read-modify-write через saveFile (non-atomic).
     */
    suspend fun toggleReaction(msgId: String, emoji: String, userId: String): Boolean {
        val line = "$msgId|$emoji|$userId"
        val content = loadFileOrNull("reactions.txt") ?: ""
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val idx = lines.indexOfFirst { it == line }
        return if (idx != -1) {
            lines.removeAt(idx)
            saveFile("reactions.txt", lines.joinToString("\n").ifBlank { "\n" })
            false
        } else {
            lines.add(line)
            saveFile("reactions.txt", lines.joinToString("\n"))
            true
        }
    }

    /**
     * Загружает зашифрованный контент изображения по ссылке.
     *
     * Поддерживаемые форматы [ref]:
     *   "gist:GIST_ID"  → загрузить из отдельного image gist (новый формат)
     *   "img_xxx.txt"   → загрузить файл из основного чат-gist (старый формат)
     *
     * Возвращает сырую зашифрованную строку. Расшифровка — в ImageLoader.
     * GistTransport переопределяет для обработки "gist:" ссылок.
     */
    suspend fun loadImageByRef(ref: String): String = loadFile(ref)

    /**
     * Загружает изображение в оптимальное хранилище и возвращает ссылку.
     *
     * GistTransport: создаёт НОВЫЙ приватный gist одним POST-запросом
     * → не трогает основной чат-gist, не конкурирует с heartbeat/typing
     * → полный обход rate limit без задержек.
     *
     * Остальные транспорты: fallback — saveFileChunked в основном транспорте.
     *
     * @param encryptedContent уже зашифрованный base64 изображения
     * @param password         пароль чата (для fallback saveFileChunked)
     * @param onProgress       прогресс загрузки (только для fallback)
     * @return "gist:GIST_ID" (новый формат) или "img_xxx.txt" (fallback)
     */
    suspend fun uploadImage(
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        val fileName = com.atrum.chat.Message.newImageFileName()
        saveFileChunked(fileName, encryptedContent, password, onProgress)
        return fileName
    }
}
