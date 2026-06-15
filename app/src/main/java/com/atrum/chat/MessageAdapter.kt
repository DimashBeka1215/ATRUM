package com.atrum.chat

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.imageview.ShapeableImageView
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.RenderMode
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private var messages: List<Message> = emptyList(),
    private val onLongClick: (Message, View) -> Unit = { _, _ -> },
    private val onImageClick: (Message) -> Unit = { },
    /** Клик по цитате: (исходное_сообщение). */
    private val onQuoteClick: ((Message) -> Unit)? = null,
    /** Клик по ячейке коллажа — передаётся полный список refs и индекс нажатой ячейки. */
    private val onCollageImageClick: (refs: List<String>, startIndex: Int) -> Unit = { _, _ -> },
    private var imageLoader: ImageLoader? = null,
    private val loadScope: CoroutineScope? = null,
    /** Клик по чипу реакции: (msgId, emoji). */
    private val onReactionClick: ((msgId: String, emoji: String) -> Unit)? = null
) : RecyclerView.Adapter<MessageAdapter.VH>() {

    /** true = Atmospheric Glass UI mode: полупрозрачные пузырьки поверх обоев. */
    var glassMode: Boolean = false

    /** Непрозрачность пузырьков своих сообщений (0.1–1.0). Управляется настройками. */
    var bubbleAlphaSelf: Float = 1.0f

    /** Непрозрачность пузырьков сообщений собеседника (0.1–1.0). Управляется настройками. */
    var bubbleAlphaOther: Float = 1.0f

    private val locale = Locale("ru")
    private val timeFmt = SimpleDateFormat("HH:mm", locale)
    private val dayMonthFmt = SimpleDateFormat("EEE, d MMM", locale)
    private val fullDateFmt = SimpleDateFormat("dd.MM.yy", locale)

    private var partnerLastReadIndex: Int = 0

    /** msgId → emoji → Set<userId> */
    private var reactions: Map<String, Map<String, Set<String>>> = emptyMap()
    /** userId текущего пользователя — для подсветки собственных реакций. */
    private var myUserId: String = ""

    /** ID сообщения, которое нужно подсветить (акцент при переходе по цитате) */
    var highlightedMsgId: String? = null
        private set

    fun highlightMessage(msgId: String?) {
        highlightedMsgId = msgId
        notifyDataSetChanged()
    }

    /**
     * Обновляет карту реакций. Вызывается из ChatActivity после каждого poll
     * или после оптимистичного обновления при постановке реакции.
     */
    fun setReactions(map: Map<String, Map<String, Set<String>>>, userId: String) {
        reactions = map
        myUserId  = userId
        notifyDataSetChanged()
    }

    // ── Selection mode ────────────────────────────────────────────────────────
    /** true когда активен режим множественного выбора */
    var isSelectionMode: Boolean = false
        private set

    /** rawId выбранных сообщений */
    val selectedRawIds: MutableSet<String> = mutableSetOf()

    /** Callback вызывается при каждом изменении выборки; передаёт текущее множество rawId */
    var onSelectionChanged: ((Set<String>) -> Unit)? = null

    /** Войти в режим выбора и выбрать первое сообщение */
    fun enterSelectionMode(msg: Message) {
        isSelectionMode = true
        selectedRawIds.clear()
        selectedRawIds.add(msg.msgId)
        onSelectionChanged?.invoke(selectedRawIds.toSet())
        notifyDataSetChanged()
    }

    /** Выйти из режима выбора и снять все отметки */
    fun exitSelectionMode() {
        isSelectionMode = false
        selectedRawIds.clear()
        onSelectionChanged?.invoke(emptySet())
        notifyDataSetChanged()
    }

    /** Переключить выборку для одного сообщения */
    fun toggleSelection(msg: Message) {
        if (selectedRawIds.contains(msg.msgId)) {
            selectedRawIds.remove(msg.msgId)
        } else {
            selectedRawIds.add(msg.msgId)
        }
        onSelectionChanged?.invoke(selectedRawIds.toSet())
        notifyDataSetChanged()
    }

    /** Выбрать все сообщения */
    fun selectAll() {
        effectiveList().forEach { selectedRawIds.add(it.msgId) }
        onSelectionChanged?.invoke(selectedRawIds.toSet())
        notifyDataSetChanged()
    }

    /**
     * Оптимистичные pending-сообщения — добавляются сразу при постановке
     * в очередь, до подтверждения от сервера. Отображаются с иконкой часов.
     * Очищаются при следующем успешном submit() или явном clearPending().
     */
    private val pendingMessages = mutableListOf<Message>()

    companion object {
        private const val TYPE_SELF = 1
        private const val TYPE_OTHER = 2
    }

    /**
     * Обновляет список подтверждённых сообщений.
     * Pending-очередь передаётся явно — обычно это снимок из MessageSendManager.
     * Если передан пустой список (по умолчанию) — pending очищаются,
     * что означает «сервер подтвердил все сообщения».
     */
    fun submit(list: List<Message>, pendingQueue: List<Message> = emptyList()) {
        messages = list
        pendingMessages.clear()
        pendingMessages.addAll(pendingQueue)
        notifyDataSetChanged()
    }

    /**
     * Заменяет список pending-сообщений (вызывается из onQueueChanged).
     * Не трогает подтверждённые сообщения.
     */
    fun setPendingMessages(msgs: List<Message>) {
        pendingMessages.clear()
        pendingMessages.addAll(msgs)
        notifyDataSetChanged()
    }

    /** Убирает все pending-сообщения (при фатальной ошибке). */
    fun clearPending() {
        if (pendingMessages.isEmpty()) return
        pendingMessages.clear()
        notifyDataSetChanged()
    }

    fun setPartnerLastReadIndex(index: Int) {
        if (partnerLastReadIndex == index) return
        partnerLastReadIndex = index
        notifyDataSetChanged()
    }

    /** Все видимые элементы: подтверждённые + pending-очередь. */
    private fun effectiveList(): List<Message> {
        if (pendingMessages.isEmpty()) return messages
        return messages + pendingMessages
    }

    override fun getItemViewType(position: Int): Int =
        if (effectiveList()[position].isSelf) TYPE_SELF else TYPE_OTHER

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val layoutId = if (viewType == TYPE_SELF) R.layout.item_message_self else R.layout.item_message_other
        val view = LayoutInflater.from(parent.context).inflate(layoutId, parent, false)
        return VH(view, { imageLoader }, loadScope, onCollageImageClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val msg = effectiveList()[position]
        val isRead = msg.isSelf && position < partnerLastReadIndex
        val isSelected = isSelectionMode && selectedRawIds.contains(msg.msgId)
        val isHighlighted = msg.msgId == highlightedMsgId
        holder.bind(
            msg             = msg,
            time            = formatTime(msg.timestampMs),
            isRead          = isRead,
            isSelected      = isSelected,
            isHighlighted   = isHighlighted,
            inSelectionMode = isSelectionMode,
            onLongClick     = onLongClick,
            onImageClick    = onImageClick,
            onQuoteClick    = { onQuoteClick?.invoke(it) },
            onToggleSelect  = { toggleSelection(it) },
            msgReactions      = reactions[msg.msgId] ?: emptyMap(),
            myUserId          = myUserId,
            onReactionClick   = { emoji -> onReactionClick?.invoke(msg.msgId, emoji) },
            glassMode         = glassMode,
            bubbleAlphaSelf   = bubbleAlphaSelf,
            bubbleAlphaOther  = bubbleAlphaOther
        )
    }

    override fun getItemCount(): Int = effectiveList().size

    fun getItem(position: Int): Message? = effectiveList().getOrNull(position)

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        holder.resumeSticker()
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        holder.pauseSticker()
    }

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.recycleSticker()
    }

    private fun formatTime(ms: Long): String {
        val now = Calendar.getInstance()
        val msg = Calendar.getInstance().apply { timeInMillis = ms }

        val sameDay = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
        if (sameDay) return timeFmt.format(Date(ms))

        val yesterday = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        val isYesterday = yesterday.get(Calendar.YEAR) == msg.get(Calendar.YEAR) &&
                yesterday.get(Calendar.DAY_OF_YEAR) == msg.get(Calendar.DAY_OF_YEAR)
        if (isYesterday) return "Вчера " + timeFmt.format(Date(ms))

        val sameYear = now.get(Calendar.YEAR) == msg.get(Calendar.YEAR)
        return if (sameYear) {
            dayMonthFmt.format(Date(ms)) + " " + timeFmt.format(Date(ms))
        } else {
            fullDateFmt.format(Date(ms)) + " " + timeFmt.format(Date(ms))
        }
    }

    /** Обновляет транспорт картинок (при переключении Gist → Nostr). */
    fun updateImageLoader(loader: ImageLoader) {
        imageLoader = loader
    }

    class VH(
        itemView: View,
        private val getImageLoader: () -> ImageLoader?,
        private val loadScope: CoroutineScope?,
        private val onCollageImageClick: (refs: List<String>, startIndex: Int) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val senderView: TextView? = itemView.findViewById(R.id.tv_sender)
        private val textView: TextView = itemView.findViewById(R.id.tv_text)
        private val timeView: TextView = itemView.findViewById(R.id.tv_time)
        private val quoteBlock: View? = itemView.findViewById(R.id.quote_block)
        private val reactionRow: LinearLayout? = itemView.findViewById(R.id.reaction_row)
        private val quoteSender: TextView? = itemView.findViewById(R.id.tv_quote_sender)
        private val quoteText: TextView? = itemView.findViewById(R.id.tv_quote_text)
        private val imageView: ShapeableImageView? = itemView.findViewById(R.id.iv_image)
        private val collageView: CollageLayout? = itemView.findViewById(R.id.collage_layout)
        private val lottieView: LottieAnimationView? = itemView.findViewById(R.id.lottie_sticker)
        private val webmView: WebmStickerView? = itemView.findViewById(R.id.webm_sticker)
        private val voiceContainer: View? = itemView.findViewById(R.id.voice_container)
        private val voicePlayBtn: ImageView? = itemView.findViewById(R.id.btn_voice_play)
        private val voiceWaveform: WaveformView? = itemView.findViewById(R.id.voice_waveform)
        private val voiceSpinner: View? = itemView.findViewById(R.id.voice_spinner)
        private val voiceDur: TextView? = itemView.findViewById(R.id.tv_voice_dur)
        private val tickView: ImageView? = itemView.findViewById(R.id.iv_tick)
        /** The rounded bubble container — background changes between classic and glass modes. */
        private val bubbleContainer: View? = itemView.findViewById(R.id.bubble_container)

        fun resumeSticker() {
            lottieView?.takeIf { it.visibility == View.VISIBLE }?.resumeAnimation()
            webmView?.takeIf { it.visibility == View.VISIBLE }?.resume()
        }
        fun pauseSticker()  {
            lottieView?.pauseAnimation()
            webmView?.pause()
        }
        fun recycleSticker() {
            lottieView?.cancelAnimation()
            webmView?.release()
        }

        /** Радиус скруглений для ячеек коллажа (7dp). */
        private val cellCornerRadius = 7f * itemView.context.resources.displayMetrics.density

        fun bind(
            msg: Message,
            time: String,
            isRead: Boolean,
            isSelected: Boolean,
            isHighlighted: Boolean = false,
            inSelectionMode: Boolean,
            onLongClick: (Message, View) -> Unit,
            onImageClick: (Message) -> Unit,
            onQuoteClick: (Message) -> Unit,
            onToggleSelect: (Message) -> Unit,
            msgReactions: Map<String, Set<String>> = emptyMap(),
            myUserId: String = "",
            onReactionClick: ((String) -> Unit)? = null,
            glassMode: Boolean = false,
            bubbleAlphaSelf: Float = 1.0f,
            bubbleAlphaOther: Float = 1.0f
        ) {
            senderView?.let {
                if (msg.sender.isNotBlank()) {
                    it.text = msg.sender
                    it.visibility = View.VISIBLE
                } else {
                    it.visibility = View.GONE
                }
            }

            // ── Картинка / коллаж / стикер / голосовое ───────────────────────
            voiceContainer?.visibility = View.GONE
            when {
                msg.isVoice && voiceContainer != null -> {
                    imageView?.visibility = View.GONE; imageView?.tag = null
                    collageView?.let { it.visibility = View.GONE; it.removeAllViews() }
                    lottieView?.let { it.visibility = View.GONE; it.cancelAnimation() }
                    webmView?.let { it.visibility = View.GONE; it.release() }
                    bindVoice(msg)
                }
                msg.isSticker && (lottieView != null || webmView != null) -> {
                    imageView?.visibility = View.GONE
                    imageView?.tag = null
                    collageView?.let { it.visibility = View.GONE; it.removeAllViews() }
                    // webm -> по-кадровый движок (WebmStickerView): декод один раз в лёгкие
                    // кадры с прозрачностью, дальше смена картинок. Без video-декодеров при
                    // скролле -> без крашей/лагов. .tgs/.webp -> bindSticker.
                    val isWebm = msg.imageFileName?.let { Message.stickerExt(it).equals("webm", true) } == true
                    if (isWebm && webmView != null) {
                        lottieView?.let { it.visibility = View.GONE; it.cancelAnimation() }
                        bindWebmSticker(msg, webmView)
                    } else if (lottieView != null) {
                        webmView?.let { it.visibility = View.GONE; it.release() }
                        bindSticker(msg, lottieView)
                    }
                    // Стикеры не реагируют на тапы
                    itemView.isClickable = false
                    itemView.isLongClickable = false
                }
                msg.isMultiImage && collageView != null -> {
                    // Коллаж: скрываем одиночный ImageView, показываем CollageLayout
                    imageView?.visibility = View.GONE
                    imageView?.tag = null
                    webmView?.let { it.visibility = View.GONE; it.release() }
                    bindCollage(msg, collageView)
                    collageView.setOnClickListener(null)   // клик — на отдельных ячейках
                }
                msg.isImage && imageView != null -> {
                    // Одиночное изображение
                    collageView?.let { it.visibility = View.GONE; it.removeAllViews() }
                    lottieView?.let { it.visibility = View.GONE; it.cancelAnimation() }
                    webmView?.let { it.visibility = View.GONE; it.release() }
                    bindImage(msg, imageView)
                    imageView.setOnClickListener { onImageClick(msg) }
                }
                else -> {
                    imageView?.visibility = View.GONE
                    imageView?.tag = null
                    collageView?.let { it.visibility = View.GONE; it.removeAllViews() }
                    lottieView?.let { it.visibility = View.GONE; it.cancelAnimation() }
                    webmView?.let { it.visibility = View.GONE; it.release() }
                }
            }

            if (msg.text.isBlank()) {
                textView.visibility = View.GONE
            } else {
                textView.visibility = View.VISIBLE
                textView.text = msg.text
            }

            timeView.text = time

            if (msg.isReply && quoteBlock != null) {
                quoteBlock.visibility = View.VISIBLE
                quoteSender?.text = msg.quotedSender
                quoteText?.text = msg.quotedText
                quoteBlock.setOnClickListener { onQuoteClick(msg) }
            } else {
                quoteBlock?.visibility = View.GONE
                quoteBlock?.setOnClickListener(null)
            }

            tickView?.let { tick ->
                if (msg.isSelf) {
                    tick.visibility = View.VISIBLE
                    val context = itemView.context
                    when {
                        msg.isPending -> {
                            tick.setImageResource(R.drawable.ic_clock_thin)
                            tick.imageTintList = ColorStateList.valueOf(0xA6FFFFFF.toInt())
                        }
                        isRead -> {
                            tick.setImageResource(R.drawable.ic_check_double)
                            tick.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(context, R.color.read_tick)
                            )
                        }
                        else -> {
                            tick.setImageResource(R.drawable.ic_check)
                            tick.imageTintList = ColorStateList.valueOf(
                                ContextCompat.getColor(context, R.color.sent_tick)
                            )
                        }
                    }
                } else {
                    tick.visibility = View.GONE
                }
            }

            val anchor: View = when {
                msg.isSticker && msg.imageFileName?.let { Message.stickerExt(it).equals("webm", true) } == true && webmView != null -> webmView
                msg.isSticker && lottieView != null -> lottieView
                msg.isMultiImage && collageView != null -> collageView
                msg.isImage && imageView != null -> imageView
                else -> textView
            }

            // ── Glass mode & Sticker Style ────────────────────────────────────
            if (msg.isSticker) {
                // Нативный вид Telegram: убираем всё лишнее, оставляем только сам стикер
                bubbleContainer?.background = null
                bubbleContainer?.setPadding(0, 0, 0, 0)
                bubbleContainer?.elevation = 0f
                bubbleContainer?.alpha = 1f
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    bubbleContainer?.outlineSpotShadowColor = android.graphics.Color.TRANSPARENT
                }
            } else {
                bubbleContainer?.let { bubble ->
                    val ctx = itemView.context
                    val density = ctx.resources.displayMetrics.density
                    // Возвращаем стандартные паддинги (14dp horiz, 8dp top, 6dp bot)
                    bubble.setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                    
                    if (glassMode) {
                        val drawableRes = if (msg.isSelf) R.drawable.bg_glass_msg_self
                                          else            R.drawable.bg_glass_msg_other
                        bubble.background = ContextCompat.getDrawable(ctx, drawableRes)
                        if (!msg.isSelf) textView.setTextColor(Color.WHITE)
                    } else {
                        val drawableRes = if (msg.isSelf) R.drawable.bg_message_self
                                          else            R.drawable.bg_message_other
                        bubble.background = ContextCompat.getDrawable(ctx, drawableRes)
                        if (!msg.isSelf) textView.setTextColor(
                            ContextCompat.getColor(ctx, R.color.text_primary)
                        )
                    }
                    bubble.alpha = if (msg.isSelf) bubbleAlphaSelf else bubbleAlphaOther
                }
            }


            // ── Selection highlight ───────────────────────────────────────────
            when {
                isSelected -> itemView.setBackgroundColor(0x28A855F7.toInt()) // полупрозрачный фиолетовый
                isHighlighted -> itemView.setBackgroundColor(0x28FFFFFF.toInt()) // полупрозрачный белый (акцент)
                else -> itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }

            // ── Click / LongClick handlers ────────────────────────────────────
            itemView.setOnClickListener {
                if (inSelectionMode) onToggleSelect(msg)
                // else: normal tap behaviour (images etc. handled separately above)
            }
            itemView.setOnLongClickListener {
                if (inSelectionMode) {
                    // In selection mode long-press just toggles
                    onToggleSelect(msg)
                } else {
                    onLongClick(msg, anchor)
                }
                true
            }

            // ── Reaction chips ───────────────────────────────────────────────
            reactionRow?.let { bindReactions(it, msgReactions, myUserId, onReactionClick, glassMode) }
        }

        /**
         * Привязывает одиночную картинку к ImageView:
         *  - inline base64 → парсим и ставим сразу
         *  - filename/gist ref → берём из кеша, иначе ставим placeholder и грузим в фоне
         */
        private fun bindImage(msg: Message, image: ShapeableImageView) {
            image.visibility = View.VISIBLE

            // 1. Inline base64 (старый формат)
            if (msg.imageBase64 != null) {
                image.tag = null
                val bitmap = ImageUtils.fromBase64(msg.imageBase64)
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                } else {
                    image.visibility = View.GONE
                }
                return
            }

            // 2. Файл/gist ссылка (новый формат)
            val fileName = msg.imageFileName ?: run {
                image.visibility = View.GONE
                return
            }

            image.tag = fileName
            val cached = ImageCache.getBitmap(fileName)
            if (cached != null) {
                image.setImageBitmap(cached)
                return
            }

            image.setImageDrawable(null)
            image.setBackgroundColor(ContextCompat.getColor(itemView.context, R.color.surface_elevated))

            val loader = getImageLoader() ?: return
            val scope = loadScope ?: return
            scope.launch {
                val bitmap = loader.loadBitmap(fileName)
                if (image.tag == fileName) {
                    image.setBackgroundColor(Color.TRANSPARENT)
                    if (bitmap != null) image.setImageBitmap(bitmap)
                    else image.visibility = View.GONE
                }
            }
        }

        /**
         * Создаёт ячейки коллажа ([CollageCell]) и загружает изображения.
         *
         * Логика отображения:
         *   1. Bitmap в LruCache → [CollageCell.showBitmapImmediate] (мгновенно, без анимации).
         *   2. Base64 в кеше или анимация уже показывалась → [CollageCell.showBitmapImmediate]
         *      после декода (bitmap был вытеснен из LruCache — повторная анимация не нужна).
         *   3. Первая загрузка (нет ни bitmap, ни base64) → [CollageCell.showBitmap]
         *      с анимацией подтверждения (зелёный кружок). После показа — регистрируем
         *      в [ImageCache.markShownConfirmation] чтобы следующий rebind не показал её снова.
         *
         * Click-защита: тап по ячейке срабатывает только если [ImageCache.isKnown] = true
         * (данные готовы). Пока изображение загружается (спиннер виден), клик игнорируется.
         */
        /**
         * Загружает стикер (STATIC .webp, ANIMATED .tgs или VIDEO .webm) и отображает его.
         * STATIC отображается как Bitmap, ANIMATED через Lottie, VIDEO как первый кадр (Bitmap).
         */
        private fun voiceTime(totalMs: Int): String {
            val s = totalMs / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }

        private suspend fun loadVoiceFile(loader: ImageLoader, ref: String): File? {
            val dir = File(itemView.context.cacheDir, "voice_play").apply { mkdirs() }
            val f = File(dir, "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
            if (f.exists() && f.length() > 0) return f
            val bytes = loader.loadRawBytes(ref) ?: return null
            return try { f.writeBytes(bytes); f } catch (_: Exception) { null }
        }

        private fun bindVoice(msg: Message) {
            val container = voiceContainer ?: return
            val playBtn = voicePlayBtn ?: return
            container.visibility = View.VISIBLE
            val key = msg.msgId
            val ref = msg.voiceFileName ?: return
            val totalSec = msg.voiceDurationSec
            val ctx = itemView.context
            playBtn.tag = key

            // Цвета дорожки под свой/чужой пузырёк.
            if (msg.isSelf) {
                voiceWaveform?.setColors(ContextCompat.getColor(ctx, R.color.white), 0x66FFFFFF)
            } else {
                voiceWaveform?.setColors(
                    ContextCompat.getColor(ctx, R.color.accent),
                    ContextCompat.getColor(ctx, R.color.text_tertiary)
                )
            }
            val levels = msg.voiceWaveform?.takeIf { it.isNotEmpty() }?.let { Message.decodeWaveform(it) }
                ?: IntArray(24) { 28 }
            voiceWaveform?.setSamples(levels)

            fun showLoading(loading: Boolean) {
                voiceSpinner?.visibility = if (loading) View.VISIBLE else View.GONE
                playBtn.visibility = if (loading) View.INVISIBLE else View.VISIBLE
                voiceWaveform?.alpha = if (loading) 0.4f else 1f
            }

            val progressCb: (String, Int, Int) -> Unit = { k, pos, dur ->
                if (playBtn.tag == k) {
                    voiceWaveform?.setProgress(if (dur > 0) pos.toFloat() / dur else 0f)
                    voiceDur?.text = voiceTime(pos)
                }
            }
            val completeCb: (String) -> Unit = { k ->
                if (playBtn.tag == k) {
                    playBtn.setImageResource(R.drawable.ic_play)
                    voiceWaveform?.setProgress(0f)
                    voiceDur?.text = voiceTime(totalSec * 1000)
                }
            }

            voiceDur?.text = voiceTime(totalSec * 1000)
            if (VoicePlayer.currentKey == key) {
                playBtn.setImageResource(R.drawable.ic_pause)
                VoicePlayer.rebind(key, progressCb, completeCb)
            } else {
                playBtn.setImageResource(R.drawable.ic_play)
                voiceWaveform?.setProgress(0f)
            }

            // Индикатор готовности: файл уже скачан → play; иначе спиннер + фоновая загрузка.
            val cached = File(File(ctx.cacheDir, "voice_play"), "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
            val readyNow = cached.exists() && cached.length() > 0
            showLoading(!readyNow)
            if (!readyNow) {
                val loader = getImageLoader()
                val scope = loadScope
                if (loader != null && scope != null) {
                    scope.launch {
                        val file = withContext(Dispatchers.IO) { loadVoiceFile(loader, ref) }
                        if (playBtn.tag == key) showLoading(file == null)
                    }
                }
            }

            playBtn.setOnClickListener {
                val loader = getImageLoader() ?: return@setOnClickListener
                val scope = loadScope ?: return@setOnClickListener
                if (VoicePlayer.isPlaying(key)) {
                    VoicePlayer.pause()
                    playBtn.setImageResource(R.drawable.ic_play)
                    return@setOnClickListener
                }
                scope.launch {
                    val file = withContext(Dispatchers.IO) { loadVoiceFile(loader, ref) }
                    if (playBtn.tag != key || file == null) return@launch
                    showLoading(false)
                    playBtn.setImageResource(R.drawable.ic_pause)
                    VoicePlayer.toggle(key, file, progressCb, completeCb)
                }
            }
        }

        private fun bindSticker(msg: Message, lottie: LottieAnimationView) {
            val fileName = msg.imageFileName ?: return
            // Контент стикера и его кеш — по общей ссылке (новый формат: часть после '|';
            // старый формат: само имя файла). Тип определяем по расширению из имени.
            val ref = Message.stickerContentRef(fileName)
            val ext = Message.stickerExt(fileName)
            // Тот же tgs уже загружен в этом холдере — не сбрасываем композицию/анимацию.
            // Иначе notifyDataSetChanged (например при отправке стикера) перебиндивает всё
            // и анимированные стикеры мигают/перезагружаются.
            if (lottie.tag == fileName && lottie.composition != null) {
                if (!lottie.isAnimating) lottie.resumeAnimation()
                return
            }
            lottie.visibility = View.VISIBLE
            lottie.isClickable = false
            lottie.isFocusable = false
            lottie.tag = fileName
            lottie.cancelAnimation()

            // Сразу ставим превью из кеша если есть, чтобы не было пустоты
            val cachedBmp = ImageCache.getBitmap(ref)
            if (cachedBmp != null) {
                lottie.setImageBitmap(cachedBmp)
            } else {
                lottie.setImageDrawable(null)
            }

            val loader = getImageLoader() ?: return
            val scope = loadScope ?: return
            scope.launch {
                val isTgs = ext.equals("tgs", true)

                if (isTgs) {
                    val cachedComp = ImageCache.getComposition(ref)
                    if (cachedComp != null) {
                        if (lottie.tag == fileName) {
                            lottie.setComposition(cachedComp)
                            lottie.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
                            lottie.setRenderMode(RenderMode.HARDWARE)
                            lottie.setSafeMode(false)
                            lottie.playAnimation()
                        }
                        return@launch
                    }
                }

                val base64 = ImageCache.getBase64(ref)
                    ?: loader.loadBase64(ref)
                    ?: run { if (lottie.tag == fileName) lottie.visibility = View.GONE; return@launch }

                if (lottie.tag != fileName) return@launch

                if (isTgs) {
                    val comp = withContext(Dispatchers.IO) {
                        try {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                            val gzis = GZIPInputStream(ByteArrayInputStream(bytes))
                            val jsonString = gzis.bufferedReader().use { it.readText() }
                            LottieCompositionFactory.fromJsonStringSync(jsonString, ref).value
                        } catch (e: Exception) {
                            android.util.Log.e("MessageAdapter", "Lottie error: ${e.message}")
                            null
                        }
                    }
                    if (lottie.tag == fileName && comp != null) {
                        ImageCache.putComposition(ref, comp)
                        lottie.setComposition(comp)
                        lottie.repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
                        lottie.setRenderMode(RenderMode.HARDWARE)
                        lottie.setSafeMode(false)
                        lottie.playAnimation()
                    } else if (lottie.tag == fileName && cachedBmp == null) {
                        lottie.visibility = View.GONE
                    }
                } else if (ext.equals("webm", true)) {
                    // VIDEO sticker: декодируем первый кадр из raw webm через MediaMetadataRetriever.
                    // BitmapFactory не умеет webm — раньше у получателя стикер просто исчезал (null).
                    val bitmap = cachedBmp ?: withContext(Dispatchers.IO) {
                        decodeWebmFirstFrame(base64)
                    }
                    if (lottie.tag == fileName && bitmap != null) {
                        if (ImageCache.getBitmap(ref) == null) ImageCache.put(ref, base64, bitmap)
                        lottie.setImageBitmap(bitmap)
                    } else if (lottie.tag == fileName) {
                        lottie.visibility = View.GONE
                    }
                } else {
                    // STATIC (.webp)
                    val bitmap = cachedBmp ?: withContext(Dispatchers.IO) {
                        try {
                            val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        } catch (_: Exception) { null }
                    }
                    if (lottie.tag == fileName && bitmap != null) {
                        lottie.setImageBitmap(bitmap)
                    } else if (lottie.tag == fileName) {
                        lottie.visibility = View.GONE
                    }
                }
            }
        }

        /**
         * Декодирует первый кадр .webm-стикера прямо из raw-байтов (base64) без temp-файла.
         * Используется MediaDataSource (API 23+, minSdk 24) + MediaMetadataRetriever.
         * BitmapFactory не понимает webm, поэтому раньше у получателя стикер пропадал.
         */
        private fun decodeWebmFirstFrame(base64: String): android.graphics.Bitmap? {
            return try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(object : android.media.MediaDataSource() {
                        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                            if (position >= bytes.size) return -1
                            val end = minOf(bytes.size.toLong(), position + size).toInt()
                            val len = end - position.toInt()
                            if (len <= 0) return -1
                            System.arraycopy(bytes, position.toInt(), buffer, offset, len)
                            return len
                        }
                        override fun getSize(): Long = bytes.size.toLong()
                        override fun close() {}
                    })
                    retriever.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } finally {
                    retriever.release()
                }
            } catch (_: Exception) { null }
        }

        /**
         * Привязывает .webm видео-стикер к WebmStickerView: материализует файл из base64
         * в кеш и запускает циклическое воспроизведение. Первый кадр — заглушка/фоллбэк.
         */
        private fun bindWebmSticker(msg: Message, webm: WebmStickerView) {
            val fileName = msg.imageFileName ?: return
            // Контент/кадры — по общей ссылке (новый формат: после '|'; старый: имя файла),
            // поэтому кеш и декод переиспользуются между всеми сообщениями с тем же стикером.
            val ref = Message.stickerContentRef(fileName)
            // Тот же стикер уже играет в этом холдере — не трогаем (иначе при обновлении списка
            // notifyDataSetChanged перебиндивает всё и стикеры мигают/перезагружаются).
            if (webm.isPlaying(ref)) return
            webm.visibility = View.VISIBLE
            webm.tag = ref
            webm.setFallbackBitmap(ImageCache.getBitmap(ref))

            val scope = loadScope ?: return
            scope.launch {
                // Быстрый путь: кадры уже в памяти/на диске — играем без base64/декода/temp-файла.
                if (webm.hasFrames(ref)) {
                    if (webm.tag == ref) webm.playCached(ref)
                    return@launch
                }

                // Медленный путь (первый раз для этого стикера): нужен сам webm-файл.
                val loader = getImageLoader() ?: return@launch
                val base64 = ImageCache.getBase64(ref)
                    ?: loader.loadBase64(ref)
                    ?: run { if (webm.tag == ref) webm.visibility = View.GONE; return@launch }
                if (webm.tag != ref) return@launch

                val file = withContext(Dispatchers.IO) { materializeWebm(ref, base64) }
                if (webm.tag == ref && file != null) {
                    webm.play(file, ref)
                }
            }
        }

        /** Распаковывает base64 .webm во временный файл кеша (один раз) и возвращает его. */
        private fun materializeWebm(fileName: String, base64: String): File? {
            return try {
                val dir = File(itemView.context.cacheDir, "webm_stickers").apply { mkdirs() }
                val f = File(dir, md5Hex(fileName) + ".webm")
                if (!f.exists() || f.length() == 0L) {
                    val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    f.writeBytes(bytes)
                    // Ограничиваем размер temp-папки (как кеш в Telegram).
                    StickerDiskCache.trimDir(dir, 32L * 1024 * 1024, ".webm")
                } else {
                    // Помечаем как недавно использованный, чтобы LRU-trim (по lastModified) не
                    // удалил файл, который прямо сейчас декодируется другим стикером.
                    try { f.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
                }
                f
            } catch (_: Exception) { null }
        }

        private fun md5Hex(s: String): String {
            val d = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
            return d.joinToString("") { "%02x".format(it) }
        }

                private fun bindCollage(msg: Message, collage: CollageLayout) {
            val refs   = msg.imageFileNames ?: return
            val ratios = msg.aspectRatios ?: refs.map { 1f }

            collage.visibility = View.VISIBLE
            collage.removeAllViews()

            val context = itemView.context

            // Создаём CollageCell для каждого изображения
            val cells = refs.mapIndexed { index, ref ->
                CollageCell(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    setCornerRadius(cellCornerRadius)
                    imageView.tag = ref
                    // Клик разрешён только когда данные готовы (защита от тапа во время загрузки)
                    setOnClickListener {
                        if (ImageCache.isKnown(ref)) onCollageImageClick(refs, index)
                    }
                    showLoading()
                }
            }

            cells.forEach { collage.addView(it) }
            collage.aspectRatios = ratios

            val loader = getImageLoader()
            val scope  = loadScope
            for (i in refs.indices) {
                val ref  = refs[i]
                val cell = cells[i]

                // Bitmap в LruCache — показываем мгновенно, без анимации
                val cached = ImageCache.getBitmap(ref)
                if (cached != null) {
                    cell.showBitmapImmediate(cached)
                    continue
                }

                // Определяем ЗАРАНЕЕ (до запуска корутины) — видел ли пользователь
                // эту картинку раньше. Если да — анимацию не показываем.
                // Случай: bitmap вытеснен из LruCache, но base64 или флаг подтверждения есть.
                val alreadyKnown = ImageCache.getBase64(ref) != null ||
                                   ImageCache.wasShownConfirmation(ref)

                if (loader == null || scope == null) continue

                scope.launch {
                    val bitmap = loader.loadBitmap(ref)
                    // Проверяем что cell ещё для того же ref (защита от RecyclerView recycling)
                    if (cell.imageView.tag == ref) {
                        when {
                            bitmap == null -> cell.showError()
                            alreadyKnown  -> cell.showBitmapImmediate(bitmap)
                            else          -> {
                                // Первый раз — показываем анимацию и запоминаем
                                ImageCache.markShownConfirmation(ref)
                                cell.showBitmap(bitmap)
                            }
                        }
                    }
                }
            }
        }

        /**
         * Рендерит чипы реакций в [row].
         * [reactions] = emoji → Set<userId>
         * Чипы собственных реакций (myUserId присутствует в Set) подсвечиваются фиолетовым.
         * Клик по чипу — toggle: если уже поставил эту реакцию — убирает, иначе ставит.
         */
        private fun bindReactions(
            row: LinearLayout,
            reactions: Map<String, Set<String>>,
            myUserId: String,
            onReactionClick: ((String) -> Unit)?,
            glassMode: Boolean = false
        ) {
            if (reactions.isEmpty()) {
                row.visibility = View.GONE
                return
            }
            row.visibility = View.VISIBLE
            row.removeAllViews()

            val ctx = row.context
            val density = ctx.resources.displayMetrics.density

            reactions.entries
                .sortedBy { it.key } // стабильный порядок
                .forEach { (emoji, userIds) ->
                    val isMine = myUserId.isNotBlank() && userIds.contains(myUserId)
                    val count  = userIds.size

                    val chip = TextView(ctx).apply {
                        text     = if (count > 1) "$emoji $count" else emoji
                        textSize = 13f

                        val hPad = (8 * density).toInt()
                        val vPad = (4 * density).toInt()
                        setPadding(hPad, vPad, hPad, vPad)

                        background = GradientDrawable().apply {
                            shape        = GradientDrawable.RECTANGLE
                            cornerRadius = 14 * density
                            if (isMine) {
                                // Мой чип: фиолетовый — одинаков в обеих темах и любом режиме
                                setColor(0x33A855F7.toInt())
                                setStroke((1.5 * density).toInt(), 0xFFA855F7.toInt())
                            } else if (glassMode) {
                                // Режим обоев: полупрозрачный белый — читается поверх фото
                                setColor(0x33000000.toInt())
                                setStroke((1 * density).toInt(), 0x55000000.toInt())
                            } else {
                                // Обычный режим: тема-адаптивный — surface_elevated + border
                                setColor(ContextCompat.getColor(ctx, R.color.surface_elevated))
                                setStroke(
                                    (1 * density).toInt(),
                                    ContextCompat.getColor(ctx, R.color.border)
                                )
                            }
                        }

                        // Цвет текста: белый только для своего чипа или glass-режима
                        if (isMine) {
                            setTextColor(Color.WHITE)
                        } else if (glassMode) {
                            setTextColor(Color.WHITE)
                        } else {
                            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                        }

                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.marginEnd = (4 * density).toInt()
                        layoutParams = lp

                        isClickable = true
                        isFocusable = true
                        setOnClickListener { onReactionClick?.invoke(emoji) }
                    }
                    row.addView(chip)
                }
        }
    }
}
