package com.atrum.chat

/**
 * Расшифрованное сообщение, готовое к показу в UI.
 *
 * Картинка может храниться тремя способами:
 *
 *  1. Inline base64 (самый старый формат):
 *       "<US>ms<US>Имя: <DC1>base64<DC1>caption"
 *     → imageBase64 != null
 *
 *  2. Ссылка на файл / gist (новый формат одиночного изображения):
 *       "<US>ms<US>Имя: <DC1>img_xxx.txt<DC1>caption"   (файл в основном gist)
 *       "<US>ms<US>Имя: <DC1>gist:GIST_ID<DC1>caption"  (отдельный image gist)
 *     → imageFileName != null
 *
 *  3. Коллаж из нескольких изображений:
 *       "<US>ms<US>Имя: <DC1>MULTI:ref1@ratio1|ref2@ratio2|...<DC1>caption"
 *     → imageFileNames != null, aspectRatios != null
 *
 * Форматы 1 и 2 совместимы — ImageLoader понимает оба.
 */
data class Message(
    val sender: String,
    val text: String,
    val isSelf: Boolean,
    val rawEncrypted: String = "",
    val timestampMs: Long = System.currentTimeMillis(),
    val quotedSender: String? = null,
    val quotedText: String? = null,
    val imageBase64: String? = null,
    val imageFileName: String? = null,
    /** Список ссылок на изображения в коллаже (формат MULTI:). */
    val imageFileNames: List<String>? = null,
    /** Соотношения сторон (ширина/высота) для каждого изображения коллажа. */
    val aspectRatios: List<Float>? = null,
    val senderUserId: String? = null,
    val forwardedSender: String? = null,
    /**
     * true = сообщение ещё не подтверждено сервером (отображается с иконкой часов).
     * Никогда не сохраняется в gist — только в памяти адаптера.
     */
    val isPending: Boolean = false
) {
    val isReply: Boolean get() = quotedSender != null
    /** Стабильный уникальный ключ сообщения для режима выбора. */
    val msgId: String get() = if (rawEncrypted.isNotBlank()) rawEncrypted.take(40) else "${senderUserId}_$timestampMs"
    /** true если это анимированный стикер (.tgs файл) */
    val isSticker: Boolean get() = imageFileName?.startsWith(STK_FILENAME_PREFIX) == true
    val isImage: Boolean get() = imageBase64 != null || (imageFileName != null && !isSticker) || imageFileNames != null
    val isMultiImage: Boolean get() = imageFileNames != null && imageFileNames.isNotEmpty()

    companion object {
        private val US: Char = Char(0x1F)
        private val RS: Char = Char(0x1E)
        private val SOH: Char = Char(0x01)
        private val STX: Char = Char(0x02)
        private val ETX: Char = Char(0x03)
        private val DC1: Char = Char(0x11)

        /** Префикс имени файла-картинки в основном gist. */
        private const val IMG_FILENAME_PREFIX = "img_"
        /** Префикс анимированного стикера (.tgs) в основном gist. */
        const val STK_FILENAME_PREFIX = "stk_"
        /** Префикс ссылки на отдельный image gist. */
        private const val GIST_REF_PREFIX = "gist:"
        /** Префикс коллажа из нескольких изображений. */
        private const val MULTI_PREFIX = "MULTI:"

        fun fromDecrypted(
            decrypted: String,
            currentUserId: String,
            currentUserName: String,
            aliases: Set<String> = emptySet(),
            raw: String = ""
        ): Message {
            var workingString = decrypted
            var timestamp = System.currentTimeMillis()
            var parsedUserId: String? = null

            if (workingString.startsWith(US)) {
                val tsEnd = workingString.indexOf(US, 1)
                if (tsEnd > 1) {
                    val tsStr = workingString.substring(1, tsEnd)
                    val parsed = tsStr.toLongOrNull()
                    if (parsed != null) {
                        timestamp = parsed
                        workingString = workingString.substring(tsEnd + 1)
                    }
                }
            }

            if (workingString.startsWith(RS)) {
                val uidEnd = workingString.indexOf(RS, 1)
                if (uidEnd > 1) {
                    parsedUserId = workingString.substring(1, uidEnd)
                    workingString = workingString.substring(uidEnd + 1)
                }
            }

            val idx = workingString.indexOf(": ")
            if (idx <= 0) {
                return Message(
                    sender = "",
                    text = workingString,
                    isSelf = false,
                    rawEncrypted = raw,
                    timestampMs = timestamp,
                    senderUserId = parsedUserId
                )
            }

            val sender = workingString.substring(0, idx)
            val body = workingString.substring(idx + 2)

            // Определяем "своё ли" сообщение строго по userId.
            // Никогда не используем имя/псевдонимы — это даёт ложные срабатывания
            // если у собеседника совпадает имя, или если nameHistory засорена.
            // Если userId не записан (старые сообщения до v2.3) — всегда false,
            // чтобы не показывать чужие сообщения фиолетовыми.
            val isSelf = parsedUserId != null
                    && currentUserId.isNotBlank()
                    && parsedUserId == currentUserId

            // Reply: <SOH>ЦитНик<STX>ЦитТекст<SOH>текст
            if (body.startsWith(SOH)) {
                val parts = body.substring(1).split(SOH, limit = 2)
                if (parts.size == 2) {
                    val quote = parts[0].split(STX, limit = 2)
                    if (quote.size == 2) {
                        return Message(
                            sender = sender,
                            text = parts[1],
                            isSelf = isSelf,
                            rawEncrypted = raw,
                            timestampMs = timestamp,
                            quotedSender = quote[0],
                            quotedText = quote[1],
                            senderUserId = parsedUserId
                        )
                    }
                }
            }

            // Image: <DC1>ref[<DC1>подпись]
            // ref может быть:
            //   "img_xxx.txt"      — файл в основном gist
            //   "gist:GIST_ID"     — отдельный image gist
            //   "MULTI:r1@ar|..."  — коллаж нескольких изображений
            //   raw base64         — старый inline-формат
            if (body.startsWith(DC1)) {
                val parts = body.substring(1).split(DC1, limit = 2)
                val ref = parts[0]
                val caption = if (parts.size == 2) parts[1] else ""

                return when {
                    // ── Коллаж ──────────────────────────────────────────────
                    ref.startsWith(MULTI_PREFIX) -> {
                        val entries = ref.removePrefix(MULTI_PREFIX).split("|")
                        val names = mutableListOf<String>()
                        val ratios = mutableListOf<Float>()
                        for (entry in entries) {
                            val at = entry.lastIndexOf('@')
                            if (at > 0) {
                                names.add(entry.substring(0, at))
                                ratios.add(entry.substring(at + 1).toFloatOrNull() ?: 1f)
                            } else if (entry.isNotBlank()) {
                                names.add(entry)
                                ratios.add(1f)
                            }
                        }
                        Message(
                            sender = sender,
                            text = caption,
                            isSelf = isSelf,
                            rawEncrypted = raw,
                            timestampMs = timestamp,
                            imageFileNames = names,
                            aspectRatios = ratios,
                            senderUserId = parsedUserId
                        )
                    }
                    // ── Файл в gist или отдельный image gist ────────────────
                    ref.startsWith(IMG_FILENAME_PREFIX) || ref.startsWith(STK_FILENAME_PREFIX) || ref.startsWith(GIST_REF_PREFIX) -> {
                        Message(
                            sender = sender,
                            text = caption,
                            isSelf = isSelf,
                            rawEncrypted = raw,
                            timestampMs = timestamp,
                            imageFileName = ref,
                            senderUserId = parsedUserId
                        )
                    }
                    // ── Старый inline base64 ─────────────────────────────────
                    else -> {
                        Message(
                            sender = sender,
                            text = caption,
                            isSelf = isSelf,
                            rawEncrypted = raw,
                            timestampMs = timestamp,
                            imageBase64 = ref,
                            senderUserId = parsedUserId
                        )
                    }
                }
            }

            return Message(
                sender = sender,
                text = body,
                isSelf = isSelf,
                rawEncrypted = raw,
                timestampMs = timestamp,
                senderUserId = parsedUserId
            )
        }

        /**
         * Формирует тело сообщения для шифрования.
         *
         * Для одиночной картинки — imageFileName (новый формат).
         * Для коллажа — imageFileNames + aspectRatios → формат MULTI:.
         * imageBase64 — устаревший параметр, оставлен для обратной совместимости.
         */
        fun composePlaintext(
            senderName: String,
            senderUserId: String,
            text: String,
            quotedSender: String? = null,
            quotedText: String? = null,
            imageBase64: String? = null,
            imageFileName: String? = null,
            imageFileNames: List<String>? = null,
            aspectRatios: List<Float>? = null,
            timestampMs: Long = System.currentTimeMillis()
        ): String {
            val tsPrefix = "$US$timestampMs$US"
            val uidPrefix = if (senderUserId.isNotBlank()) "$RS$senderUserId$RS" else ""
            val cleanSender = senderName
                .replace(US, ' ').replace(RS, ' ')
                .replace(SOH, ' ').replace(STX, ' ').replace(DC1, ' ')

            return when {
                // ── Коллаж ──────────────────────────────────────────────────
                imageFileNames != null && imageFileNames.isNotEmpty() -> {
                    val multiContent = imageFileNames.mapIndexed { i, name ->
                        val ar = aspectRatios?.getOrNull(i) ?: 1f
                        // Ограничиваем точность до 3 знаков чтобы не раздувать строку
                        val arStr = "%.3f".format(ar)
                        "$name@$arStr"
                    }.joinToString("|")
                    if (text.isBlank()) "$tsPrefix$uidPrefix$cleanSender: ${DC1}${MULTI_PREFIX}$multiContent"
                    else "$tsPrefix$uidPrefix$cleanSender: ${DC1}${MULTI_PREFIX}$multiContent${DC1}$text"
                }
                // ── Одиночное изображение (файл/gist ссылка) ────────────────
                imageFileName != null -> {
                    if (text.isBlank()) "$tsPrefix$uidPrefix$cleanSender: $DC1$imageFileName"
                    else "$tsPrefix$uidPrefix$cleanSender: $DC1$imageFileName$DC1$text"
                }
                // ── Старый inline base64 ─────────────────────────────────────
                imageBase64 != null -> {
                    if (text.isBlank()) "$tsPrefix$uidPrefix$cleanSender: $DC1$imageBase64"
                    else "$tsPrefix$uidPrefix$cleanSender: $DC1$imageBase64$DC1$text"
                }
                // ── Reply ────────────────────────────────────────────────────
                quotedSender != null && quotedText != null -> {
                    val q = quotedText.take(120)
                        .replace(SOH, ' ').replace(STX, ' ').replace(DC1, ' ')
                    val qs = quotedSender
                        .replace(SOH, ' ').replace(STX, ' ').replace(DC1, ' ')
                    "$tsPrefix$uidPrefix$cleanSender: $SOH$qs$STX$q$SOH$text"
                }
                // ── Обычный текст ────────────────────────────────────────────
                else -> "$tsPrefix$uidPrefix$cleanSender: $text"
            }
        }

        /** Генерация уникального имени для файла стикера (.tgs) в gist. */
        fun newStickerFileName(): String {
            val now = System.currentTimeMillis()
            val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            return "${STK_FILENAME_PREFIX}${now}_${rand}.txt"
        }

        /** Генерация уникального имени для нового файла-картинки в gist. */
        fun newImageFileName(): String {
            val now = System.currentTimeMillis()
            val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            return "${IMG_FILENAME_PREFIX}${now}_${rand}.txt"
        }
    }
}
