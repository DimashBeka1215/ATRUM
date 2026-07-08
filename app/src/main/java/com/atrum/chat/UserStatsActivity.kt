package com.atrum.chat

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.NostrTransport
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Детальная статистика активности ОДНОГО участника группы (админ-модерация):
 *  — диаграмма активности с переключателем период (день/неделя/месяц/год);
 *  — распределение по часам суток (когда чаще пишет);
 *  — сводные карточки (всего/среднее в день/пиковый час/активных дней);
 *  — список ВСЕХ его сообщений (свайп влево — удалить у всех, зажатие — перейти
 *    к сообщению в чате);
 *  — раздел удалённых сообщений (с атрибуцией кто и когда удалил).
 *
 * Всё считается из уже имеющейся локально истории (см. StatsUtil) — без отдельного
 * "канала отчётности" от профиля, см. GroupStatsActivity.
 */
class UserStatsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_USER_NAME = "user_name"
        const val EXTRA_USER_AVATAR = "user_avatar"
    }

    // ── Строки списка ────────────────────────────────────────────────────────
    private sealed class Row {
        object Header : Row()
        data class Section(val title: String, val count: Int) : Row()
        data class MsgRow(val msg: Message) : Row()
        data class DeletedRow(val msg: Message, val deletedAtMs: Long, val deleterLabel: String) : Row()
    }

    private var chatRoomId: Long = -1L
    private var targetUserId: String = ""
    private var targetUserName: String = "?"
    private var targetUserAvatar: String? = null

    private var networkChatId = ""
    private var chatPassword = ""
    private lateinit var transport: NostrTransport
    private var transportReady = false
    private var adminUserId: String? = null

    /** Живая подписка на новые события (см. ChatActivity.transportWatch) — толкает
     *  refreshData() при появлении нового сообщения в канале, БЕЗ отдельного
     *  поллинг-цикла (переиспользуем существующий REQ-стрим NostrTransport, см. §1). */
    private var transportWatch: AutoCloseable? = null
    private var isFirstResume = true

    /** Все сообщения ЭТОГО участника (для диаграмм и списка) — newest first для списка. */
    private var userMessages: List<Message> = emptyList()
    private var currentPeriod: StatsUtil.Period = StatsUtil.Period.WEEK

    private lateinit var adapter: RowsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_stats)

        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        targetUserId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        targetUserName = intent.getStringExtra(EXTRA_USER_NAME)?.takeIf { it.isNotBlank() } ?: targetUserId.take(8)
        targetUserAvatar = intent.getStringExtra(EXTRA_USER_AVATAR)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = targetUserName

        val avatarImg = findViewById<ShapeableImageView>(R.id.iv_header_avatar)
        val letterTv = findViewById<TextView>(R.id.tv_header_letter)
        val bmp = AvatarUtils.fromBase64(targetUserAvatar)
        if (bmp != null) {
            avatarImg.setImageBitmap(bmp); avatarImg.visibility = View.VISIBLE; letterTv.visibility = View.GONE
        } else {
            avatarImg.visibility = View.GONE; letterTv.visibility = View.VISIBLE
            letterTv.text = targetUserName.trim().firstOrNull()?.uppercase() ?: "?"
        }

        adapter = RowsAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_user_stats)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback(this) { position -> onSwipeDelete(position) }).attachToRecyclerView(rv)

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            if (transportReady) lifecycleScope.launch { refreshData() } else swipeRefresh.isRefreshing = false
        }

        if (chatRoomId < 0 || targetUserId.isBlank()) { finish(); return }
        setupAndLoad()
    }

    override fun onResume() {
        super.onResume()
        // Пропускаем самый первый onResume — сразу после onCreate его уже покрывает
        // initial-load из setupAndLoad(); последующие (вернулись на экран) — освежаем.
        if (isFirstResume) { isFirstResume = false }
        else if (transportReady) lifecycleScope.launch { refreshData() }
    }

    override fun onDestroy() {
        transportWatch?.close()
        super.onDestroy()
    }

    /** Разовая инициализация: находим чат/транспорт, поднимаем живую подписку, затем первая загрузка. */
    private fun setupAndLoad() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@UserStatsActivity)
            val chatEntity = withContext(Dispatchers.IO) { db.chatDao().getById(chatRoomId) }
            if (chatEntity == null || !chatEntity.isGroup) { finish(); return@launch }
            val isAdmin = !chatEntity.adminUserId.isNullOrBlank() && chatEntity.adminUserId == prefs.myUserId
            if (!isAdmin) { finish(); return@launch }

            networkChatId = chatEntity.chatId
            chatPassword = prefs.getChatPassword(networkChatId).takeIf { it.isNotEmpty() } ?: chatEntity.chatPassword
            val transportToken = prefs.getChatToken(networkChatId).takeIf { it.isNotEmpty() } ?: chatEntity.transportToken
            adminUserId = chatEntity.adminUserId

            transport = TransportFactory.forChat(
                this@UserStatsActivity, networkChatId, transportToken, chatPassword, prefs.myUserId, adminUserId
            ) as? NostrTransport ?: run { finish(); return@launch }
            transportReady = true

            // Живая подписка (тот же REQ-стрим, что ChatActivity.transportWatch) — новое
            // сообщение в канале сразу дёргает пересчёт статистики, без ожидания
            // повторного открытия экрана.
            transportWatch = transport.watchMessages {
                lifecycleScope.launch { refreshData() }
            }

            refreshData()
        }
    }

    /** Повторно читает историю канала и пересобирает список — вызывается из onResume,
     *  pull-to-refresh и живой подписки; переиспользует уже поднятый [transport]. */
    private suspend fun refreshData() {
        try {
            val allData = withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }
            val allMessages = if (allData != null) withContext(Dispatchers.Default) {
                StatsUtil.decodeAll(allData.chatContent, chatPassword, networkChatId, prefs.myUserId, prefs.myName)
            } else emptyList()

            userMessages = allMessages.filter { it.senderUserId == targetUserId }

            val deleted = withContext(Dispatchers.IO) { runCatching { transport.deletedMessages() }.getOrDefault(emptyList()) }
            val deletedDecoded = withContext(Dispatchers.Default) {
                StatsUtil.decodeDeleted(deleted, chatPassword, networkChatId, prefs.myUserId, prefs.myName)
            }.filter { it.message.senderUserId == targetUserId }

            val authorPub = transport.pubkeyForUserId(targetUserId)
            val adminPub = adminUserId?.let { transport.pubkeyForUserId(it) }
            val deletedRows = deletedDecoded
                .sortedByDescending { it.deletedAtMs }
                .map { dr ->
                    val label = when {
                        dr.deleterPubkey.isBlank() -> getString(R.string.stats_deleted_by_unknown)
                        dr.deleterPubkey.equals(adminPub, ignoreCase = true) && adminPub != null && adminPub != authorPub ->
                            getString(R.string.stats_deleted_by_admin)
                        dr.deleterPubkey.equals(authorPub, ignoreCase = true) -> getString(R.string.stats_deleted_by_author)
                        else -> getString(R.string.stats_deleted_by_unknown)
                    }
                    Row.DeletedRow(dr.message, dr.deletedAtMs, label)
                }

            buildRows(deletedRows)
        } finally {
            swipeRefresh.isRefreshing = false
        }
    }

    /** Пересобирает список строк (шапка+диаграммы, «все сообщения», «удалённые»). */
    private fun buildRows(deletedRows: List<Row.DeletedRow>) {
        val rows = ArrayList<Row>(userMessages.size + deletedRows.size + 3)
        rows.add(Row.Header)
        rows.add(Row.Section(getString(R.string.stats_section_all), userMessages.size))
        userMessages.sortedByDescending { it.timestampMs }.forEach { rows.add(Row.MsgRow(it)) }
        if (deletedRows.isNotEmpty()) {
            rows.add(Row.Section(getString(R.string.stats_section_deleted), deletedRows.size))
            rows.addAll(deletedRows)
        }
        adapter.submit(rows)
    }

    private fun onPeriodChanged(period: StatsUtil.Period) {
        if (currentPeriod == period) return
        currentPeriod = period
        adapter.notifyItemChanged(0)
    }

    // ── Свайп-удаление (только для MsgRow) ──────────────────────────────────────

    private fun onSwipeDelete(position: Int) {
        val row = adapter.rowAt(position) as? Row.MsgRow ?: return
        val msg = row.msg
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.dialog_delete_title),
            message = getString(R.string.dialog_delete_message),
            positiveText = getString(R.string.action_delete),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) { performDelete(msg) }
    }

    private fun performDelete(msg: Message) {
        // Оптимистично убираем из "всех сообщений" и сразу показываем в "удалённых" —
        // это МОДЕРАЦИЯ админом (только админ видит этот экран/свайп), поэтому атрибуция
        // однозначна без пересчёта pubkey.
        userMessages = userMessages.filter { it.rawEncrypted != msg.rawEncrypted }
        val currentRows = adapter.currentRows().toMutableList()
        val idx = currentRows.indexOfFirst { it is Row.MsgRow && it.msg.rawEncrypted == msg.rawEncrypted }
        if (idx >= 0) currentRows.removeAt(idx)
        val deletedRow = Row.DeletedRow(msg, System.currentTimeMillis(), getString(R.string.stats_deleted_by_admin))
        val sectionDeletedIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_deleted) }
        if (sectionDeletedIdx >= 0) {
            currentRows.add(sectionDeletedIdx + 1, deletedRow)
            val old = currentRows[sectionDeletedIdx] as Row.Section
            currentRows[sectionDeletedIdx] = old.copy(count = old.count + 1)
        } else {
            currentRows.add(Row.Section(getString(R.string.stats_section_deleted), 1))
            currentRows.add(deletedRow)
        }
        val sectionAllIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_all) }
        if (sectionAllIdx >= 0) {
            val old = currentRows[sectionAllIdx] as Row.Section
            currentRows[sectionAllIdx] = old.copy(count = max(0, old.count - 1))
        }
        adapter.submit(currentRows)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { transport.deleteLine(msg.rawEncrypted) }
            } catch (e: Exception) {
                Toast.makeText(this@UserStatsActivity, R.string.error_delete, Toast.LENGTH_SHORT).show()
                // Не откатываем локально — следующий refreshData() (onResume/pull/live-стрим)
                // подтянет реальное состояние с реле; надгробие best-effort ретраится транспортом.
            }
        }
    }

    // ── Переход к сообщению в чате (долгое нажатие) ─────────────────────────────

    private fun jumpToMessage(msg: Message) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chatRoomId)
            putExtra(ChatActivity.EXTRA_SCROLL_TO_MSGID, msg.msgId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()
        private val density = resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }
        fun currentRows(): List<Row> = rows
        fun rowAt(position: Int): Row? = rows.getOrNull(position)

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Section -> 1
            is Row.MsgRow -> 2
            is Row.DeletedRow -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inf.inflate(R.layout.item_stats_header, parent, false))
                1 -> SectionVH(inf.inflate(R.layout.item_stats_section, parent, false))
                2 -> MsgVH(inf.inflate(R.layout.item_stats_message, parent, false))
                else -> DeletedVH(inf.inflate(R.layout.item_stats_deleted, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind()
                is Row.Section -> (holder as SectionVH).bind(row)
                is Row.MsgRow -> (holder as MsgVH).bind(row.msg)
                is Row.DeletedRow -> (holder as DeletedVH).bind(row)
            }
        }

        /** Можно свайпать только строки активных сообщений (не шапку/раздел/удалённые). */
        fun isSwipeable(position: Int): Boolean = rows.getOrNull(position) is Row.MsgRow

        // ── Header: сегменты + диаграммы + сводка ───────────────────────────────
        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val segDay: TextView = v.findViewById(R.id.seg_day)
            private val segWeek: TextView = v.findViewById(R.id.seg_week)
            private val segMonth: TextView = v.findViewById(R.id.seg_month)
            private val segYear: TextView = v.findViewById(R.id.seg_year)
            private val barChart: LinearLayout = v.findViewById(R.id.bar_chart)
            private val barLabels: LinearLayout = v.findViewById(R.id.bar_labels)
            private val hourChart: LinearLayout = v.findViewById(R.id.hour_chart)
            private val chartHint: TextView = v.findViewById(R.id.tv_chart_hint)
            private val peakHint: TextView = v.findViewById(R.id.tv_peak_hint)
            private val statTotal: TextView = v.findViewById(R.id.stat_total)
            private val statPerDay: TextView = v.findViewById(R.id.stat_per_day)
            private val statPeakHour: TextView = v.findViewById(R.id.stat_peak_hour)
            private val statActiveDays: TextView = v.findViewById(R.id.stat_active_days)

            fun bind() {
                val segs = listOf(
                    segDay to StatsUtil.Period.DAY, segWeek to StatsUtil.Period.WEEK,
                    segMonth to StatsUtil.Period.MONTH, segYear to StatsUtil.Period.YEAR
                )
                for ((tv, period) in segs) {
                    val selected = period == currentPeriod
                    tv.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
                    tv.setTextColor(ContextCompat.getColor(itemView.context, if (selected) R.color.accent_light else R.color.text_secondary))
                    tv.setOnClickListener { onPeriodChanged(period) }
                }

                val bucketCount = when (currentPeriod) {
                    StatsUtil.Period.DAY -> 14
                    StatsUtil.Period.WEEK -> 8
                    StatsUtil.Period.MONTH -> 12
                    StatsUtil.Period.YEAR -> 5
                }
                chartHint.text = itemView.context.getString(when (currentPeriod) {
                    StatsUtil.Period.DAY -> R.string.stats_activity_hint_day
                    StatsUtil.Period.WEEK -> R.string.stats_activity_hint_week
                    StatsUtil.Period.MONTH -> R.string.stats_activity_hint_month
                    StatsUtil.Period.YEAR -> R.string.stats_activity_hint_year
                })

                val buckets = StatsUtil.buckets(userMessages, currentPeriod, bucketCount)
                val maxVal = (buckets.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                barChart.removeAllViews()
                barLabels.removeAllViews()
                val chartHeightPx = dp(90)
                for ((label, value) in buckets) {
                    val bar = View(itemView.context).apply {
                        val h = if (value == 0) dp(2) else max(dp(2), (chartHeightPx * value / maxVal))
                        layoutParams = LinearLayout.LayoutParams(0, h, 1f).also { it.marginEnd = dp(2) }
                        setBackgroundColor(ContextCompat.getColor(
                            context, if (value == maxVal && value > 0) R.color.accent else R.color.accent_dark
                        ))
                    }
                    barChart.addView(bar)
                    val lbl = TextView(itemView.context).apply {
                        text = label
                        textSize = 9f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    barLabels.addView(lbl)
                }

                val hourCounts = StatsUtil.hourHistogram(userMessages)
                val maxHour = (hourCounts.maxOrNull() ?: 0).coerceAtLeast(1)
                val peakHourIdx = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: 0
                hourChart.removeAllViews()
                val hourChartHeightPx = dp(60)
                for (h in 0 until 24) {
                    val v = hourCounts[h]
                    val bar = View(itemView.context).apply {
                        val hh = if (v == 0) dp(2) else max(dp(2), (hourChartHeightPx * v / maxHour))
                        layoutParams = LinearLayout.LayoutParams(0, hh, 1f).also { it.marginEnd = dp(1) }
                        setBackgroundColor(ContextCompat.getColor(
                            context, if (h == peakHourIdx && v > 0) R.color.accent else R.color.accent_dark
                        ))
                    }
                    hourChart.addView(bar)
                }
                peakHint.text = if (userMessages.isEmpty()) itemView.context.getString(R.string.stats_no_data)
                    else itemView.context.getString(R.string.stats_peak_hint, "%02d:00".format(peakHourIdx))

                val activeDays = StatsUtil.activeDaysCount(userMessages)
                statTotal.text = userMessages.size.toString()
                statPerDay.text = if (activeDays > 0) "%.1f".format(userMessages.size.toDouble() / activeDays) else "0"
                statPeakHour.text = if (userMessages.isEmpty()) "—" else "%02d:00".format(peakHourIdx)
                statActiveDays.text = activeDays.toString()
            }
        }

        inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.tv_section_title)
            private val count: TextView = v.findViewById(R.id.tv_section_count)
            fun bind(row: Row.Section) {
                title.text = row.title
                count.text = row.count.toString()
            }
        }

        inner class MsgVH(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(R.id.tv_msg_time)
            private val text: TextView = v.findViewById(R.id.tv_msg_text)
            fun bind(msg: Message) {
                time.text = StatsUtil.formatMessageTime(itemView.context, msg.timestampMs)
                text.text = previewText(itemView.context, msg)
                // Стандартный OnLongClickListener — уже корректно уживается с ItemTouchHelper
                // (свайп) на уровне фреймворка, в отличие от ручного отслеживания ACTION_MOVE
                // (тот же паттерн долгого нажатия, что и в MediaListActivity.select()).
                itemView.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    jumpToMessage(msg)
                    true
                }
            }
        }

        inner class DeletedVH(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(R.id.tv_del_time)
            private val tag: TextView = v.findViewById(R.id.tv_del_tag)
            private val text: TextView = v.findViewById(R.id.tv_del_text)
            private val meta: TextView = v.findViewById(R.id.tv_del_meta)
            fun bind(row: Row.DeletedRow) {
                time.text = StatsUtil.formatMessageTime(itemView.context, row.msg.timestampMs)
                text.text = previewText(itemView.context, row.msg)
                tag.background = tag.background?.mutate()
                (tag.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    ColorUtils.setAlphaComponent(ContextCompat.getColor(itemView.context, R.color.error), 0x24)
                )
                meta.text = itemView.context.getString(
                    R.string.stats_deleted_meta, row.deleterLabel, StatsUtil.formatMessageTime(itemView.context, row.deletedAtMs)
                )
            }
        }

        private fun previewText(ctx: android.content.Context, msg: Message): String = when {
            msg.isVoice -> ctx.getString(R.string.msg_preview_voice)
            msg.isSticker -> ctx.getString(R.string.msg_preview_sticker)
            msg.isMultiImage || msg.isImage -> ctx.getString(R.string.msg_preview_photo)
            msg.text.isNotBlank() -> msg.text
            else -> ""
        }
    }

    /**
     * Свайп влево — удалить у всех (тот же паттерн, что SwipeToReplyCallback: порог,
     * вибро-триггер и снэп-бэк, НЕ постоянное открытие — после срабатывания строка сама
     * возвращается на место, а действие подтверждается диалогом). Свайп работает ТОЛЬКО
     * для активных сообщений (см. RowsAdapter.isSwipeable) — шапку/разделы/уже удалённые
     * свайпать нельзя.
     */
    private class SwipeToDeleteCallback(
        context: android.content.Context,
        private val onDelete: (position: Int) -> Unit
    ) : ItemTouchHelper.Callback() {

        private val density = context.resources.displayMetrics.density
        private val trashIcon = ContextCompat.getDrawable(context, R.drawable.ic_trash_menu)!!
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.error), 0x33)
        }

        private var hasTriggered = false
        private var activeViewHolder: RecyclerView.ViewHolder? = null

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val adapter = recyclerView.adapter as? RowsAdapter
            if (adapter != null && !adapter.isSwipeable(viewHolder.bindingAdapterPosition)) return makeMovementFlags(0, 0)
            if (activeViewHolder != null && activeViewHolder != viewHolder) return makeMovementFlags(0, 0)
            return makeMovementFlags(0, ItemTouchHelper.LEFT)
        }

        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 10f
        override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 20f
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val itemView = viewHolder.itemView
                val maxTranslation = 80f * density
                val clamped = if (abs(dX) > maxTranslation) -(maxTranslation + (abs(dX) - maxTranslation) * 0.2f) else dX
                val finalDx = clamped.coerceAtMost(0f)
                itemView.translationX = finalDx

                if (isCurrentlyActive && abs(finalDx) >= maxTranslation * 0.8f && !hasTriggered) {
                    hasTriggered = true
                    itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDelete(pos)
                }

                if (abs(finalDx) > 0) {
                    val progress = (abs(finalDx) / maxTranslation).coerceIn(0f, 1f)
                    c.drawRect(itemView.right + finalDx, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat(), bgPaint)
                    val iconSize = (22f * density).toInt()
                    val margin = (18f * density).toInt()
                    val cx = itemView.right - margin - iconSize / 2f
                    val cy = itemView.top + itemView.height / 2f
                    val half = (iconSize / 2f * (0.5f + 0.5f * progress)).toInt()
                    trashIcon.setTint(ContextCompat.getColor(recyclerView.context, R.color.error))
                    trashIcon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                    trashIcon.alpha = (255 * progress).toInt().coerceIn(0, 255)
                    trashIcon.draw(c)
                }
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                activeViewHolder = viewHolder
                hasTriggered = false
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            viewHolder.itemView.translationX = 0f
            super.clearView(recyclerView, viewHolder)
            if (activeViewHolder == viewHolder) { activeViewHolder = null; hasTriggered = false }
        }
    }
}
