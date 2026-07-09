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
 * Единый polling loop читает весь канал один раз и извлекает все три файла —
 * вместо двух отдельных запросов (сообщения + профили). Экономит один полный GET/тик.
 */
data class AllChannelData(
    val chatContent: String,
    val reactionsContent: String,
    val profilesContent: String,
    /** Все слоты profiles.txt (по одному событию на участника) — для union-чтения
     *  (Фаза 1: убирает lost-update). Пусто для не-Nostr транспортов. */
    val profileSlots: List<String> = emptyList(),
    /**
     * Содержимое members.txt (ADR-001, групповые чаты) — УЖЕ проверенное по подписи
     * администратора группы (см. NostrTransport.adminUserId/splitAll). Пустая строка —
     * либо это не групповой чат, либо валидного admin-подписанного members.txt ещё нет.
     * Любые события members.txt от НЕ-администратора сюда не попадают — отфильтрованы
     * до того, как контент покинул транспортный слой.
     */
    val membersContent: String = ""
)

typealias AllGistData = AllChannelData

/**
 * Абстракция над транспортным слоем чата.
 *
 * Реализации:
 *   - NostrTransport — основной, P2P через Nostr-реле (WebSocket)
 *   - LocalTransport — оффлайн-путь (чат «Избранное»)
 *
 * Интерфейс зеркалит методы Legacy API, поэтому существующий код
 * (ProfileSync, ImageLoader, ChatActivity) переключается без логических правок.
 */
/**
 * Обезвреживает имя файла, пришедшее в т.ч. из сообщения собеседника (untrusted),
 * перед построением пути File(filesDir, prefix + name). Срезает компоненты каталога и
 * нейтрализует обход ("..", абсолютные пути) — защита от path traversal (особенно BLE,
 * где пир недоверенный). Легальные плоские имена ("chat.txt", "img_…") не меняются.
 */
internal fun safeChatFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    return if (base.isEmpty() || base == "." || base == "..") "_" else base
}

interface ChatTransport {

    /** Человекочитаемое имя для UI: "Relay Source" / "Nostr P2P" */
    val displayName: String

    /** Иконка-символ для статусной строки (☁ / ⚡) */
    val displayIcon: String

    /**
     * Использует ли транспорт Tor для сетевых запросов.
     * Если true, внешние ресурсы (например, HTTP-картинки) тоже должны грузиться через Tor.
     */
    val useTor: Boolean get() = false

    /**
     * Стабильный идентификатор чата, уникальный для каждого канала.
     *
     * Используется как входной параметр для деривации соли Argon2id в CryptoHelper:
     *   salt = SHA-256("atrum_argon2_v1:" + chatId)[0:16]
     *
     * LegacyTransport → sourceId (GUID канала в метаданных)
     * NostrTransport  → channelId (hex(SHA256("atrum_channel_v1_" + sourceId)).take(16))
     *
     * Обе стороны чата получают одинаковый chatId → одинаковую соль → одинаковый ключ
     * без явного обмена солью через канал связи.
     */
    val chatId: String

    /**
     * Крипто-домен для шифрования КОНТЕНТА медиа (фото/голос/стикеры/манифест).
     * Должен совпадать с доменом, под которым ставится forward-secrecy сессия
     * (chat.chatId), чтобы медиа шифровалось тем же сессионным ключом, что и текст,
     * и не зависело от пароля. По умолчанию = chatId (для транспортов без отдельного
     * сетевого хеша). NostrTransport переопределяет его на исходный sourceId.
     */
    val cryptoChatId: String get() = chatId

    /** Загружает полное содержимое chat.txt (все зашифрованные строки). */
    suspend fun loadContent(): String

    /**
     * Потоковая подписка на новые сообщения: транспорт сам зовёт [onNew] при появлении
     * нового сообщения (минимальная задержка, без частого опроса реле). Возвращает
     * «стоп» — закрыть при завершении. По умолчанию заглушка (стрима нет).
     */
    fun watchMessages(onNew: () -> Unit): AutoCloseable = AutoCloseable {}

    /** Потоковая подписка на изменения профиля собеседника (аватар/ник) для
     *  мгновенного обновления. По умолчанию no-op (не-Nostr транспорты). */
    fun watchProfiles(onProfile: (String) -> Unit): AutoCloseable = AutoCloseable { }

    /**
     * true — потоковая подписка на новые сообщения сейчас жива (все активные реле
     * подписаны). Фоновый сервис пушей использует это, чтобы НЕ делать дорогую сетевую
     * сверку, пока стрим гарантированно доставляет — экономия батареи. По умолчанию true
     * (транспорты без стрима не нуждаются в этом механизме).
     */
    fun isWatchHealthy(): Boolean = true

    /**
     * Загружает содержимое chat.txt только если оно изменилось с последнего запроса.
     * Возвращает null если контент не изменился (HTTP 304 Not Modified) — UI не нужно обновлять.
     *
     * Дефолтная реализация для транспортов без поддержки ETag — всегда возвращает свежие данные.
     * LegacyTransport переопределяет через api.loadContentIfChanged().
     */
    suspend fun loadContentIfChanged(): String? = loadContent()

    /**
     * Загружает chat.txt и reactions.txt за ОДИН сетевой запрос.
     * Позволяет сократить вдвое число GET-запросов при каждом тике polling-а.
     *
     * Дефолтная реализация: два отдельных вызова (Nostr и другие транспорты без поддержки объединённой загрузки).
     * LegacyTransport переопределяет и делает один fetchJson, извлекая оба файла из общего JSON.
     */
    suspend fun loadChatAndReactions(): ChatAndReactions =
        ChatAndReactions(loadContent(), loadFileOrNull("reactions.txt") ?: "")

    /**
     * ETag-оптимизированная версия [loadChatAndReactions].
     * Возвращает null если контент не изменился (304) — ни chat.txt, ни reactions.txt обновлять не нужно.
     *
     * Дефолтная реализация: вызывает loadContentIfChanged + loadFileOrNull.
     * LegacyTransport переопределяет на один fetchJson с ETag.
     */
    suspend fun loadChatAndReactionsIfChanged(): ChatAndReactions? {
        val chatContent = loadContentIfChanged() ?: return null
        return ChatAndReactions(chatContent, loadFileOrNull("reactions.txt") ?: "")
    }

    /**
     * Единый ETag-оптимизированный запрос: chat.txt + reactions.txt + profiles.txt.
     * Возвращает null при 304 Not Modified — ничего не изменилось, UI не трогаем.
     */
    suspend fun loadAllIfChanged(): AllChannelData? {
        val cr = loadChatAndReactionsIfChanged() ?: return null
        return AllChannelData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Полный (без ETag) единый запрос: chat.txt + reactions.txt + profiles.txt.
     */
    suspend fun loadAll(): AllChannelData {
        val cr = loadChatAndReactions()
        return AllChannelData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Как [loadAll], но БЕЗ анти-пустого fallback на уже накопленные где-то ещё данные —
     * возвращает null, если этот КОНКРЕТНЫЙ транспорт не получил собственного свежего
     * ответа от сети. Нужно одноразовым admin-экранам статистики (GroupStatsActivity/
     * UserStatsActivity, см. §16 репорт «участник считается вошедшим по странному
     * паттерну — то тогда, когда обновился чат у админа»): их transport создаётся заново
     * при каждом открытии экрана и должен сам дождаться СВОЕГО ответа, а не молча
     * унаследовать состояние, накопленное чужой параллельной сессией (например открытым
     * в другом окне чатом админа). ChatActivity по-прежнему использует [loadAll] — там
     * анти-пустой fallback необходим, чтобы не стирать уже показанную историю при
     * временном сбое реле. Дефолт — просто [loadAll] (транспорты без «холодного старта»
     * вроде Local/Bluetooth не нуждаются в различии).
     */
    suspend fun loadAllFresh(): AllChannelData? = loadAll()

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
     *                   (только для LegacyTransport, атомарно с appendLine)
     */
    suspend fun appendLine(
        encryptedLine: String,
        extraFiles: Map<String, String> = emptyMap(),
        onFileProgress: ((fileName: String, current: Int, total: Int) -> Unit)? = null
    )

    /** Перезаписывает именованный файл (profiles.txt, img_*.txt и т.д.). */
    suspend fun saveFile(name: String, content: String)

    /**
     * Атомарно перезаписывает несколько файлов за один PATCH-запрос.
     * Дефолтная реализация — последовательные saveFile (для LocalTransport/NostrTransport).
     * LegacyTransport переопределяет через api.saveFiles() — один запрос вместо N.
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
     * лимитов провайдера при отправке больших изображений.
     *
     * Дефолтная реализация — просто вызывает [saveFile] (подходит для
     * Nostr и других транспортов без ограничений размера).
     * LegacyTransport переопределяет этот метод с реальной чанковой логикой.
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
     * LegacyTransport переопределяет для атомарной операции через writeMutex.
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
     *   "source:ID"     → загрузить из отдельного хранилища (Content Room)
     *   "img_xxx.txt"   → загрузить файл из основного канала (Legacy)
     *
     * Возвращает сырую зашифрованную строку. Расшифровка — в ImageLoader.
     * LegacyTransport переопределяет для обработки "source:" ссылок.
     */
    suspend fun loadImageByRef(ref: String): String = loadFile(ref)

    /**
     * Загружает изображение в оптимальное хранилище и возвращает ссылку.
     *
     * LegacyTransport: создаёт НОВЫЙ приватный контейнер одним POST-запросом
     * → не трогает основной канал, не конкурирует с heartbeat/typing
     * → полный обход лимитов без задержек.
     *
     * Остальные транспорты: fallback — saveFileChunked в основном транспорте.
     *
     * @param encryptedContent уже зашифрованный base64 изображения
     * @param password         пароль чата (для fallback saveFileChunked)
     * @param onProgress       прогресс загрузки (только для fallback)
     * @return "source:ID" (Content Room) или "img_xxx.txt" (Legacy)
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
