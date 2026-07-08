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
 *  2. Ссылка на файл / источник (новый формат одиночного изображения):
 *       "<US>ms<US>Имя: <DC1>img_xxx.txt<DC1>caption"   (файл в основном контенте)
 *       "<US>ms<US>Имя: <DC1>gist:GIST_ID<DC1>caption"  (отдельный медиа-источник)
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
    /** Ссылка на контент голосового (img_.../gist:...) — null если не голосовое. */
    val voiceFileName: String? = null,
    /** Длительность голосового в секундах. */
    val voiceDurationSec: Int = 0,
    /** Кодированная огибающая громкости голосового (для дорожки/«спектрограммы»). */
    val voiceWaveform: String? = null,
    /**
     * true = сообщение ещё не подтверждено сервером (отображается с иконкой часов).
     * Никогда не сохраняется на реле — только в памяти адаптера.
     */
    val isPending: Boolean = false,
    /** true если транспорт подтвердил доставку, но мы еще не увидели сообщение в опросе. */
    val isConfirmed: Boolean = false,
    /**
     * true = отправка окончательно провалилась (все ретраи MessageSendManager исчерпаны,
     * либо заливка фото/голоса упала) — часики сменяются на значок ошибки, сообщение
     * ОСТАЁТСЯ видимым (см. ChatStore.failSend). Раньше такие сообщения тихо удалялись
     * (dropPending) — не по правилам проекта («часики → ошибка», не бесследное исчезновение).
     * Как и isPending/isConfirmed — только в памяти, никогда не синкается/не сохраняется.
     */
    val isFailed: Boolean = false,
    /**
     * Прогресс отправки голосового (только UI, не синкается, не сохраняется на реле):
     * VP_NONE — обычное состояние; VP_PROCESSING — идёт шумодав/кодек (неопредел. кольцо);
     * 0..100 — процент загрузки. Сбрасывается после подтверждения отправки.
     */
    val voiceProgress: Int = VP_NONE,
    /** Прогресс заливки фото у отправителя: индекс текущей загружаемой картинки
     *  (коллаж), -1 = нет заливки; imageUploadPct — процент 0..99 этой картинки. */
    val imageUploadIndex: Int = -1,
    val imageUploadPct: Int = 0,
    /**
     * ID сообщения, которое это (pending) сообщение должно заменить в UI.
     * Используется для редактирования «на месте» без мерцания.
     */
    val replacingId: String? = null,
    /**
     * true = локальное системное сообщение (например «X присоединился к чату»).
     * Как и isPending/isFailed — ТОЛЬКО в памяти, никогда не шифруется, не синкается,
     * не сохраняется в NostrMessageStore/на реле. Рендерится отдельной облегчённой
     * веткой в MessageAdapter (без пузырька/реакций/медиа) — см. TYPE_SYSTEM.
     */
    val isSystem: Boolean = false
) {
    val isReply: Boolean get() = quotedSender != null
    /** Стабильный уникальный ключ сообщения для режима выбора. */
    val msgId: String get() = if (rawEncrypted.isNotBlank()) rawEncrypted.take(40) else "${senderUserId}_$timestampMs"
    /** true если сообщение подтверждено транспортом, но еще не получено от сервера. */
    val isSent: Boolean get() = isConfirmed || !isPending
    /** true если это голосовое сообщение. */
    val isVoice: Boolean get() = voiceFileName != null
    /** true если это анимированный стикер (.tgs файл) */
    val isSticker: Boolean get() = imageFileName?.startsWith(STK_FILENAME_PREFIX) == true
    val isImage: Boolean get() = imageBase64 != null || (imageFileName != null && !isSticker) || imageFileNames != null
    val isMultiImage: Boolean get() = imageFileNames != null && imageFileNames.isNotEmpty()

    companion object {
        /** Состояния voiceProgress. 0..100 — процент загрузки. */
        const val VP_NONE = -1
        const val VP_PROCESSING = -2

        private val US: Char = Char(0x1F)
        private val RS: Char = Char(0x1E)
        private val SOH: Char = Char(0x01)
        private val STX: Char = Char(0x02)
        private val ETX: Char = Char(0x03)
        private val DC1: Char = Char(0x11)

        /** Префикс имени файла-картинки в основном контенте. */
        private const val IMG_FILENAME_PREFIX = "img_"
        /** Префикс анимированного стикера (.tgs) в основном контенте. */
        const val STK_FILENAME_PREFIX = "stk_"
        /** Префикс ссылки на отдельный медиа-источник. */
        private const val GIST_REF_PREFIX = "gist:"
        /** Префикс коллажа из нескольких изображений. */
        private const val MULTI_PREFIX = "MULTI:"
        /** Префикс голосового: VOICE:<секунды>[:<огибающая>]|<ссылка> в DC1-канале. */
        private const val VOICE_PREFIX = "VOICE:"

        /** Алфавит для кодирования уровней огибающей (32 уровня, без |/:/управляющих). */
        private const val WF_ALPHABET = "0123456789abcdefghijklmnopqrstuv"

        /** Кодирует уровни 0..100 в компактную строку (каждый столбик — 1 символ). */
        fun encodeWaveform(levels0to100: IntArray): String {
            val sb = StringBuilder(levels0to100.size)
            for (v in levels0to100) sb.append(WF_ALPHABET[(v.coerceIn(0, 100) * 31 / 100)])
            return sb.toString()
        }

        /** Декодирует строку огибающей обратно в уровни 0..100. */
        fun decodeWaveform(wf: String): IntArray = IntArray(wf.length) { i ->
            WF_ALPHABET.indexOf(wf[i]).coerceAtLeast(0) * 100 / 31
        }

        /**
         * Разделитель в новом формате имени стикера: "stk_<ts>_<rand>.<ext>|<contentRef>".
         * Левая часть уникальна на сообщение (нужна для reconcile/дедупа в сторе),
         * правая часть (после '|') — общая ссылка на контент в ОТДЕЛЬНОМ источнике
         * ("gist:ID" / "img_..."), что даёт дедуп и убирает раздувание основного контента.
         * Старый формат (без '|') хранит контент инлайн в основном контенте — поддержан для совместимости.
         */
        private const val STK_REF_SEP = '|'

        /** Расширение стикера (webm/tgs/webp) из imageFileName — для старого и нового формата. */
        fun stickerExt(fn: String): String =
            fn.substringBefore(STK_REF_SEP).substringAfterLast('.', "")

        /**
         * Ссылка на контент стикера. Новый формат — часть после '|' (отдельный источник),
         * старый — само имя файла (контент инлайн в основном контенте).
         */
        fun stickerContentRef(fn: String): String =
            if (fn.indexOf(STK_REF_SEP) >= 0) fn.substringAfter(STK_REF_SEP) else fn

        /** Собрать имя стикер-сообщения нового формата: уникальное имя + ссылка на контент. */
        fun stickerRefName(ext: String, contentRef: String): String =
            newStickerFileName().removeSuffix(".txt") + "." + ext + STK_REF_SEP + contentRef

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
            //   "img_xxx.txt"      — файл в основном контенте
            //   "gist:GIST_ID"     — отдельный медиа-источник
            //   "MULTI:r1@ar|..."  — коллаж нескольких изображений
            //   raw base64         — старый inline-формат
            if (body.startsWith(DC1)) {
                val parts = body.substring(1).split(DC1, limit = 2)
                val ref = parts[0]
                val caption = if (parts.size == 2) parts[1] else ""

                return when {
                    // ── Голосовое ───────────────────────────────────────────
                    ref.startsWith(VOICE_PREFIX) -> {
                        val rest = ref.removePrefix(VOICE_PREFIX)
                        val bar = rest.indexOf('|')
                        val meta = if (bar >= 0) rest.substring(0, bar) else ""
                        val contentRef = if (bar >= 0) rest.substring(bar + 1) else rest
                        val metaParts = meta.split(':')
                        val dur = metaParts.getOrNull(0)?.toIntOrNull() ?: 0
                        val wf = metaParts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        Message(
                            sender = sender,
                            text = caption,
                            isSelf = isSelf,
                            rawEncrypted = raw,
                            timestampMs = timestamp,
                            voiceFileName = contentRef,
                            voiceDurationSec = dur,
                            voiceWaveform = wf,
                            senderUserId = parsedUserId
                        )
                    }
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
                    // ── Файл в контенте или отдельный медиа-источник ────────────────
                    ref.startsWith(IMG_FILENAME_PREFIX) || ref.startsWith(STK_FILENAME_PREFIX) || ref.startsWith(GIST_REF_PREFIX) ||
                    ref.startsWith("http://", true) || ref.startsWith("https://", true) -> {
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
            voiceFileName: String? = null,
            voiceDurationSec: Int = 0,
            voiceWaveform: String? = null,
            timestampMs: Long = System.currentTimeMillis()
        ): String {
            val tsPrefix = "$US$timestampMs$US"
            val uidPrefix = if (senderUserId.isNotBlank()) "$RS$senderUserId$RS" else ""
            val cleanSender = senderName
                .replace(US, ' ').replace(RS, ' ')
                .replace(SOH, ' ').replace(STX, ' ').replace(DC1, ' ')

            return when {
                // ── Голосовое ───────────────────────────────────────────────
                voiceFileName != null -> {
                    val wfPart = if (!voiceWaveform.isNullOrEmpty()) ":$voiceWaveform" else ""
                    "$tsPrefix$uidPrefix$cleanSender: $DC1$VOICE_PREFIX$voiceDurationSec$wfPart|$voiceFileName"
                }
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

        /** Генерация уникального имени для файла стикера (.tgs). */
        fun newStickerFileName(): String {
            val now = System.currentTimeMillis()
            val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            return "${STK_FILENAME_PREFIX}${now}_${rand}.txt"
        }

        fun isSameContent(m1: Message, m2: Message): Boolean {
            if (m1.rawEncrypted == m2.rawEncrypted) return true
            if (m1.isSelf == m2.isSelf && m1.timestampMs == m2.timestampMs && m1.text == m2.text) {
                // Если это медиа, проверяем совпадение ссылок
                if (m1.isImage || m2.isImage) {
                    return m1.imageFileName == m2.imageFileName && m1.imageFileNames == m2.imageFileNames
                }
                if (m1.isVoice || m2.isVoice) {
                    return m1.voiceFileName == m2.voiceFileName
                }
                return true
            }
            return false
        }

        /** Генерация уникального имени для нового файла-картинки. */
        fun newImageFileName(): String {
            val now = System.currentTimeMillis()
            val rand = java.util.UUID.randomUUID().toString().replace("-", "").take(8)
            return "${IMG_FILENAME_PREFIX}${now}_${rand}.txt"
        }

        /**
         * Локальное системное сообщение (например «X присоединился к чату»).
         * Не шифруется, не публикуется — см. isSystem. msgId стабилен по timestamp+тексту,
         * чтобы повторный вызов (например, при пересборке списка) не плодил дублей визуально.
         */
        fun system(text: String, timestampMs: Long = System.currentTimeMillis()): Message = Message(
            sender = "",
            text = text,
            isSelf = false,
            timestampMs = timestampMs,
            isSystem = true
        )
    }
}
