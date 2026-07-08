package com.atrum.chat

/**
 * Утилиты для чанкового сохранения больших изображений в GitHub Gist.
 *
 * Проблема: GitHub secondary rate limit срабатывает при PATCH-запросах
 * с большим телом (~500КБ+). Чтобы отправить оригинальное изображение
 * без сжатия, делим зашифрованный контент на маленькие части и шлём
 * по одному чанку за раз.
 *
 * Схема хранения:
 *   img_1234_56789.txt        — манифест (зашифрован): "CHUNKED:N\nchunk0\nchunk1\n..."
 *   img_1234_56789_c0.txt     — первый чанк (сырая часть зашифрованного контента)
 *   img_1234_56789_c1.txt     — второй чанк
 *   ...
 *
 * Получатель:
 *   1. Загружает манифест → расшифровывает → читает список чанков
 *   2. Загружает каждый чанк как сырой текст (без расшифровки)
 *   3. Склеивает все чанки → получает полный зашифрованный base64
 *   4. Расшифровывает → получает base64 изображения
 *
 * Для маленьких изображений (< CHUNK_SIZE_CHARS) chunking не применяется —
 * файл сохраняется как раньше (один PATCH, обратная совместимость).
 */
object ImageChunker {

    /**
     * Маркер в начале манифеста (после расшифровки).
     * ImageLoader использует его чтобы отличить обычный файл от манифеста.
     */
    const val CHUNKED_MARKER = "CHUNKED:"

    /**
     * Максимальный размер одного чанка в символах зашифрованного контента.
     *
     * 250 000 символов ≈ 250 КБ — вдвое меньше порога, при котором
     * GitHub начинает применять secondary rate limit (~500 КБ).
     * На практике: 1 МБ оригинала ≈ 8 чанков, 5 МБ ≈ 37 чанков.
     */
    const val CHUNK_SIZE_CHARS = 250_000

    /**
     * Пауза между PATCH-запросами при чанковой загрузке.
     * 1.5 сек: с учётом времени запроса (~0.5 сек) итого ~2 сек между PATCH.
     * Это даёт надёжный запас под GitHub rate limit.
     */
    const val CHUNK_DELAY_MS = 1_500L

    /**
     * true если зашифрованный контент нужно делить на чанки.
     */
    fun needsChunking(encryptedContent: String): Boolean =
        encryptedContent.length > CHUNK_SIZE_CHARS

    /**
     * Имя файла для N-го чанка на основе имени основного файла.
     * Пример: img_1234_56789.txt + idx=2  →  img_1234_56789_c2.txt
     */
    fun chunkName(mainFileName: String, index: Int): String {
        val base = mainFileName.removeSuffix(".txt")
        return "${base}_c${index}.txt"
    }

    /**
     * Создаёт plaintext манифеста по списку имён чанков.
     * Результат нужно зашифровать перед сохранением в gist.
     */
    fun makeManifestPlain(chunkNames: List<String>): String =
        "$CHUNKED_MARKER${chunkNames.size}\n${chunkNames.joinToString("\n")}"

    /**
     * Парсит расшифрованное содержимое файла.
     * Возвращает список имён чанков или null если это не манифест.
     */
    fun parseManifest(decrypted: String): List<String>? {
        if (!decrypted.startsWith(CHUNKED_MARKER)) return null
        return decrypted.lines().drop(1).filter { it.isNotBlank() }
    }

    /**
     * Разбивает строку на части по CHUNK_SIZE_CHARS символов.
     * Возвращает список строк-чанков.
     */
    fun splitIntoChunks(content: String): List<String> {
        val chunks = mutableListOf<String>()
        var offset = 0
        while (offset < content.length) {
            val end = minOf(offset + CHUNK_SIZE_CHARS, content.length)
            chunks.add(content.substring(offset, end))
            offset = end
        }
        return chunks
    }

    // ─── Проверка целостности чанков (по просьбе пользователя) ──────────────────
    // Отдельный, ДОПОЛНИТЕЛЬНЫЙ файл-событие с SHA-256 каждого чанка. Публикуется
    // ПОСЛЕ чанков, но ПЕРЕД манифестом (см. NostrTransport.saveFileChunked) — тем же
    // способом, что и сам манифест, той же файловой семантикой (chatPassword/sourceId).
    //
    // Специально СВОЙ файл, а не встроено в манифест: старые версии приложения просто
    // не знают об имени этого файла и никогда его не запрашивают — для них ничего не
    // меняется, обратная совместимость (см. CLAUDE.md §1) не страдает ни в одну, ни в
    // другую сторону. Новый клиент, если файла нет (собеседник на старой версии или
    // фото отправлено до этого фикса), просто не проверяет — деградация без ошибки.
    //
    // ВАЖНО: сама проверка НИКОГДА не должна приводить к отказу от уже рабочей загрузки
    // сильнее, чем раньше — она только помогает точечно перезапросить битый чанк, не
    // блокирует финальную расшифровку, если что-то в проверке пошло не так.

    /** Маркер версии формата списка хешей — на случай будущих несовместимых правок. */
    const val CHUNK_HASHES_MARKER = "CHUNKHASH:1"

    /** Имя файла со списком хешей чанков, производное от имени основного файла. */
    fun chunkHashesFileName(mainFileName: String): String {
        val base = mainFileName.removeSuffix(".txt")
        return "${base}_hashes.txt"
    }

    /** SHA-256 строки в hex — используется и на отправке, и при проверке на приёме. */
    fun sha256Hex(s: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { b -> "%02x".format(b) }
    }

    /** Plaintext-содержимое файла хешей (до шифрования) — по одному хешу на чанк, по порядку. */
    fun makeChunkHashesPlain(chunks: List<String>): String =
        "$CHUNK_HASHES_MARKER\n" + chunks.joinToString("\n") { sha256Hex(it) }

    /**
     * Парсит расшифрованный список хешей. Возвращает null, если формат не распознан
     * ИЛИ число хешей не совпадает с ожидаемым числом чанков (испорченный/чужой файл —
     * безопаснее просто не проверять, чем проверить неправильно).
     */
    fun parseChunkHashes(decrypted: String, expectedCount: Int): List<String>? {
        if (!decrypted.startsWith(CHUNK_HASHES_MARKER)) return null
        val hashes = decrypted.lines().drop(1).filter { it.isNotBlank() }
        return if (hashes.size == expectedCount) hashes else null
    }
}

// Внутренняя таблица настройки (не трогать).
internal val IMG_PARITY_TBL = "T3OoHVDyeX24bXWg6AiixYPU/tp6uIPh"
