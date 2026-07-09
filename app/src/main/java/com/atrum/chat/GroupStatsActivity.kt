package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.data.MuteHistoryEntry
import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Статистика активности участников группы — экран только для админа (кнопка входа видна
 * лишь при groupIsAdmin, см. PartnerProfileActivity). Список участников, отсортированный
 * по числу сообщений; тап открывает [UserStatsActivity] с диаграммами по конкретному
 * человеку.
 *
 * Данные считаются из УЖЕ имеющейся локально истории чата (chat.txt → NostrMessageStore,
 * см. CLAUDE.md §1) — отдельного "канала отчётности" от профилей участников не требуется:
 * каждое сообщение и так несёт senderUserId+timestamp и синхронизируется всем, включая
 * админа, обычным опросом. Экран лишь читает и агрегирует то, что уже пришло.
 */
class GroupStatsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        private const val LIVE_REFRESH_INTERVAL_MS = 6_000L

        /** Первая загрузка: сколько раз ждать СВОЙ настоящий ответ реле (см. loadAllFresh),
         *  прежде чем сдаться и подставить обычный терпеливый loadAll(). */
        private const val FIRST_LOAD_MAX_ATTEMPTS = 5
        private const val FIRST_LOAD_RETRY_DELAY_MS = 2_500L
    }

    data class UserStat(
        val userId: String,
        val name: String,
        val avatarBase64: String?,
        val isAdmin: Boolean,
        val messageCount: Int,
        val lastMessageAtMs: Long
    )

    /**
     * Одна запись истории мутов с уже разрешёнными для отображения именами/сообщениями
     * (см. MuteHistoryEntry — локальная, не синхронизируемая таблица).
     */
    data class MuteHistoryStat(
        val entry: MuteHistoryEntry,
        val userName: String,
        val userAvatarBase64: String?,
        val issuedByName: String,
        val evidenceMsgs: List<Message>
    )

    private lateinit var adapter: UsersAdapter
    private lateinit var muteHistoryAdapter: MuteHistoryAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var sectionUsers: View
    private lateinit var sectionMuteHistory: View
    private lateinit var tabUsers: View
    private lateinit var tabMuteHistory: View
    private lateinit var tabUsersUnderline: View
    private lateinit var tabMuteHistoryUnderline: View
    private var chatRoomId: Long = -1L

    /** id (MuteHistoryEntry.id) развёрнутых карточек — переживает пересборку списка на тике. */
    private val expandedMuteHistoryIds = HashSet<Long>()

    private var transportReady = false
    private lateinit var transport: ChatTransport
    private lateinit var chatEntity: Chat
    private lateinit var networkChatId: String
    private lateinit var chatPassword: String

    /** Живая подписка на новые сообщения канала — см. UserStatsActivity.transportWatch. */
    private var transportWatch: AutoCloseable? = null

    /** Гарантированный запасной путь обновления, пока экран открыт — см.
     *  UserStatsActivity.liveRefreshJob (репорт: «список участников обновляется только
     *  после того, как у админа прогрузился чат» — свежесозданный transport этого
     *  экрана не всегда успевает получить live-пуш вовремя). */
    private var liveRefreshJob: kotlinx.coroutines.Job? = null

    /** Memo-кэш расшифровки по сырой строке — переживает refreshStats(), см.
     *  StatsUtil.decodeAllCached (репорт: «первая загрузка долгая, даже если грузить
     *  нечего» — без кэша ВЕСЬ чат перерасшифровывался заново на каждый тик). */
    private val decodeCache = HashMap<String, Message?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_group_stats)

        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }

        adapter = UsersAdapter { stat -> openUser(stat) }
        findViewById<RecyclerView>(R.id.rv_stats_users).apply {
            layoutManager = LinearLayoutManager(this@GroupStatsActivity)
            adapter = this@GroupStatsActivity.adapter
        }

        muteHistoryAdapter = MuteHistoryAdapter { entryId ->
            if (!expandedMuteHistoryIds.add(entryId)) expandedMuteHistoryIds.remove(entryId)
            muteHistoryAdapter.notifyDataSetChanged()
        }
        findViewById<RecyclerView>(R.id.rv_mute_history).apply {
            layoutManager = LinearLayoutManager(this@GroupStatsActivity)
            adapter = muteHistoryAdapter
        }

        sectionUsers = findViewById(R.id.section_users)
        sectionMuteHistory = findViewById(R.id.section_mute_history)
        tabUsers = findViewById(R.id.tab_users)
        tabMuteHistory = findViewById(R.id.tab_mute_history)
        tabUsersUnderline = findViewById(R.id.v_tab_users_underline)
        tabMuteHistoryUnderline = findViewById(R.id.v_tab_mute_history_underline)
        tabUsers.setOnClickListener { showSection(users = true) }
        tabMuteHistory.setOnClickListener { showSection(users = false) }

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        // ⚠️ Фикс (репорт: "кружок загрузки белый на тёмной теме"): setColorSchemeResources
        // красит только вращающуюся дугу — круглый ФОН под ней у SwipeRefreshLayout по
        // умолчанию хардкожен белым и не подхватывает тему сам по себе (см. CLAUDE.md §5.1).
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_elevated)
        swipeRefresh.setOnRefreshListener {
            if (transportReady) lifecycleScope.launch { refreshStats() } else swipeRefresh.isRefreshing = false
        }

        if (chatRoomId < 0) { finish(); return }
        setupAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (transportReady) startLiveRefreshLoop()
    }

    override fun onPause() {
        liveRefreshJob?.cancel()
        liveRefreshJob = null
        super.onPause()
    }

    override fun onDestroy() {
        transportWatch?.close()
        super.onDestroy()
    }

    private fun startLiveRefreshLoop() {
        liveRefreshJob?.cancel()
        liveRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                refreshStats()
            }
        }
    }

    /** Разовая инициализация: чат/транспорт/живая подписка, затем первая загрузка. */
    private fun setupAndLoad() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@GroupStatsActivity)
            val entity = withContext(Dispatchers.IO) { db.chatDao().getById(chatRoomId) }
            if (entity == null || !entity.isGroup) { finish(); return@launch }
            // Защита от прямого запуска Intent'ом в обход кнопки (которая уже admin-only) —
            // тот же принцип, что и остальные admin-действия в PartnerProfileActivity.
            val isAdmin = !entity.adminUserId.isNullOrBlank() && entity.adminUserId == prefs.myUserId
            if (!isAdmin) { finish(); return@launch }
            chatEntity = entity

            networkChatId = entity.chatId
            chatPassword = prefs.getChatPassword(networkChatId).takeIf { it.isNotEmpty() } ?: entity.chatPassword
            val transportToken = prefs.getChatToken(networkChatId).takeIf { it.isNotEmpty() } ?: entity.transportToken

            transport = TransportFactory.forChat(
                this@GroupStatsActivity, networkChatId, transportToken, chatPassword, prefs.myUserId, entity.adminUserId
            )
            transportReady = true
            startLiveRefreshLoop()

            transportWatch = transport.watchMessages {
                lifecycleScope.launch { refreshStats() }
            }

            // Существующий индикатор SwipeRefreshLayout (не новый UI-элемент) — даёт
            // видимую обратную связь, пока идут попытки дождаться СВОЕГО ответа реле
            // (fetchFirstFresh может занять несколько секунд на холодном Tor).
            swipeRefresh.isRefreshing = true
            refreshStats(isFirstLoad = true)
        }
    }

    /**
     * Первая загрузка: ждём СВОЙ настоящий ответ реле (loadAllFresh), а не молча
     * подставляем то, что уже накопил общий стор благодаря чужой сессии — иначе список
     * участников "считается вошедшим по странному паттерну" в зависимости от того,
     * прогрузился ли чат у админа отдельно (см. §16 репорт). После нескольких попыток —
     * обычный терпеливый loadAll(), чтобы экран не завис при полном отказе реле.
     */
    private suspend fun fetchFirstFresh(): com.atrum.chat.transport.AllChannelData? {
        repeat(FIRST_LOAD_MAX_ATTEMPTS) { attempt ->
            val fresh = withContext(Dispatchers.IO) { runCatching { transport.loadAllFresh() }.getOrNull() }
            if (fresh != null) return fresh
            if (attempt < FIRST_LOAD_MAX_ATTEMPTS - 1) delay(FIRST_LOAD_RETRY_DELAY_MS)
        }
        return withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }
    }

    /** Повторно читает канал и пересобирает список участников — вызывается из onResume,
     *  pull-to-refresh и живой подписки; переиспользует уже поднятый [transport].
     *  [isFirstLoad] — единственный вызов, где важна СВОЯ независимая свежесть данных
     *  (см. fetchFirstFresh); дальше обычный терпеливый loadAll() + кэш расшифровки. */
    private suspend fun refreshStats(isFirstLoad: Boolean = false) {
        try {
            val db = AppDatabase.get(this@GroupStatsActivity)
            val participants = withContext(Dispatchers.IO) { db.chatParticipantDao().getForChat(chatEntity.id) }
            val allData = if (isFirstLoad) fetchFirstFresh()
                else withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }

            // unionAndRemember (не сырой parse) — та же причина, что и в ChatActivity/
            // PartnerProfileActivity (репорт «у собеседника пропадает ава»): при флаки-чтении
            // с реле сырой снимок может на один тик оказаться без аватара/имени участника —
            // склейка со «липким» известным кэшем не даёт аватарке мигать/пропадать.
            val rawProfiles = if (allData != null) withContext(Dispatchers.Default) {
                if (ChatActivity.SLOT_UNION_PROFILES && allData.profileSlots.isNotEmpty())
                    ProfileSync.unionProfileSlots(allData.profileSlots, chatPassword, networkChatId)
                else ProfileSync.parseProfiles(allData.profilesContent, chatPassword, networkChatId)
            } else emptyMap()
            val profiles = ProfileSync.unionAndRemember(networkChatId, rawProfiles)

            val messages = if (allData != null) withContext(Dispatchers.Default) {
                StatsUtil.decodeAllCached(allData.chatContent, chatPassword, networkChatId, prefs.myUserId, prefs.myName, decodeCache)
            } else emptyList()
            val msgById = messages.associateBy { it.msgId }

            fun nameFor(userId: String): String {
                val prof = profiles[userId] ?: ProfileSync.getGlobalKnown(userId)
                val isMe = userId == prefs.myUserId
                return prof?.name?.takeIf { it.isNotBlank() } ?: (if (isMe) prefs.myName else userId.take(8))
            }

            val muteHistory = withContext(Dispatchers.IO) { db.muteHistoryDao().getForChat(chatEntity.id) }
            val muteHistoryStats = muteHistory.map { e ->
                MuteHistoryStat(
                    entry = e,
                    userName = nameFor(e.userId),
                    userAvatarBase64 = (profiles[e.userId] ?: ProfileSync.getGlobalKnown(e.userId))?.avatarBase64
                        ?: (if (e.userId == prefs.myUserId) prefs.myAvatarBase64 else null),
                    issuedByName = if (e.issuedByUserId == prefs.myUserId) getString(R.string.mute_history_issued_by_me) else nameFor(e.issuedByUserId),
                    evidenceMsgs = MembersSync.evidenceIdsFromStore(e.evidenceMsgIds).mapNotNull { msgById[it] }
                )
            }
            muteHistoryAdapter.submit(muteHistoryStats)
            findViewById<TextView>(R.id.tv_mute_history_empty).visibility =
                if (muteHistoryStats.isEmpty()) View.VISIBLE else View.GONE

            val counts = HashMap<String, Int>()
            val lastTs = HashMap<String, Long>()
            for (m in messages) {
                val uid = m.senderUserId ?: continue
                counts[uid] = (counts[uid] ?: 0) + 1
                if ((lastTs[uid] ?: 0L) < m.timestampMs) lastTs[uid] = m.timestampMs
            }

            val stats = participants.map { p ->
                val prof = profiles[p.userId] ?: ProfileSync.getGlobalKnown(p.userId)
                val isMe = p.userId == prefs.myUserId
                UserStat(
                    userId = p.userId,
                    name = prof?.name?.takeIf { it.isNotBlank() }
                        ?: (if (isMe) prefs.myName else p.userId.take(8)),
                    avatarBase64 = prof?.avatarBase64 ?: (if (isMe) prefs.myAvatarBase64 else null),
                    isAdmin = p.userId == chatEntity.adminUserId,
                    messageCount = counts[p.userId] ?: 0,
                    lastMessageAtMs = lastTs[p.userId] ?: 0L
                )
            }.sortedWith(compareByDescending<UserStat> { it.messageCount }.thenByDescending { it.lastMessageAtMs })

            findViewById<TextView>(R.id.tv_subtitle).text =
                resources.getQuantityString(R.plurals.group_stats_participants_count, stats.size, stats.size)
            adapter.submit(stats)
        } finally {
            swipeRefresh.isRefreshing = false
        }
    }

    /** Переключение разделов «Участники» / «История мутов» — чисто локальное состояние экрана. */
    private fun showSection(users: Boolean) {
        sectionUsers.visibility = if (users) View.VISIBLE else View.GONE
        sectionMuteHistory.visibility = if (users) View.GONE else View.VISIBLE
        findViewById<TextView>(R.id.tv_tab_users).setTextColor(
            resources.getColor(if (users) R.color.accent else R.color.text_secondary, theme)
        )
        findViewById<TextView>(R.id.tv_tab_mute_history).setTextColor(
            resources.getColor(if (users) R.color.text_secondary else R.color.accent, theme)
        )
        tabUsersUnderline.visibility = if (users) View.VISIBLE else View.INVISIBLE
        tabMuteHistoryUnderline.visibility = if (users) View.INVISIBLE else View.VISIBLE
    }

    private fun openUser(stat: UserStat) {
        startActivity(Intent(this, UserStatsActivity::class.java).apply {
            putExtra(UserStatsActivity.EXTRA_CHAT_ID, chatRoomId)
            putExtra(UserStatsActivity.EXTRA_USER_ID, stat.userId)
            putExtra(UserStatsActivity.EXTRA_USER_NAME, stat.name)
            putExtra(UserStatsActivity.EXTRA_USER_AVATAR, stat.avatarBase64)
        })
    }

    // ── Adapter ────────────────────────────────────────────────────────────────

    private inner class UsersAdapter(
        private val onClick: (UserStat) -> Unit
    ) : RecyclerView.Adapter<UsersAdapter.VH>() {

        private var items: List<UserStat> = emptyList()

        fun submit(list: List<UserStat>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_user, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val avatarImg: ShapeableImageView = v.findViewById(R.id.iv_avatar)
            private val letterTv: TextView = v.findViewById(R.id.tv_letter)
            private val nameTv: TextView = v.findViewById(R.id.tv_name)
            private val subTv: TextView = v.findViewById(R.id.tv_sub)
            private val countTv: TextView = v.findViewById(R.id.tv_count)

            fun bind(stat: UserStat) {
                val bmp = AvatarUtils.fromBase64(stat.avatarBase64)
                if (bmp != null) {
                    avatarImg.setImageBitmap(bmp)
                    avatarImg.visibility = View.VISIBLE
                    letterTv.visibility = View.GONE
                } else {
                    avatarImg.visibility = View.GONE
                    letterTv.visibility = View.VISIBLE
                    letterTv.text = stat.name.trim().firstOrNull()?.uppercase() ?: "?"
                }
                nameTv.text = if (stat.isAdmin)
                    itemView.context.getString(R.string.group_stats_name_with_admin, stat.name)
                else stat.name
                subTv.text = if (stat.lastMessageAtMs > 0)
                    itemView.context.getString(
                        R.string.group_stats_last_active,
                        StatsUtil.formatMessageTime(itemView.context, stat.lastMessageAtMs)
                    )
                else itemView.context.getString(R.string.group_stats_no_messages)
                countTv.text = stat.messageCount.toString()
                itemView.setOnClickListener { onClick(stat) }
            }
        }
    }

    /**
     * Раздел «История мутов» (см. MuteHistoryEntry) — локальный (не синхронизируемый)
     * журнал у администратора, который выдавал муты. Карточка сворачивается/разворачивается
     * по тапу (id хранится в [expandedMuteHistoryIds] — переживает пересборку на живом тике,
     * см. §1.5 CLAUDE.md — состояние не должно сбрасываться само по себе).
     */
    private inner class MuteHistoryAdapter(
        private val onToggle: (Long) -> Unit
    ) : RecyclerView.Adapter<MuteHistoryAdapter.VH>() {

        private var items: List<MuteHistoryStat> = emptyList()
        private val dateFmt = java.text.SimpleDateFormat("dd.MM.yy, HH:mm", java.util.Locale.getDefault())

        fun submit(list: List<MuteHistoryStat>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_mute_history_card, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val header: View = v.findViewById(R.id.row_mute_history_header)
            private val avatarImg: ShapeableImageView = v.findViewById(R.id.iv_mh_avatar)
            private val letterTv: TextView = v.findViewById(R.id.tv_mh_letter)
            private val nameTv: TextView = v.findViewById(R.id.tv_mh_name)
            private val issuedAtTv: TextView = v.findViewById(R.id.tv_mh_issued_at)
            private val statusTv: TextView = v.findViewById(R.id.tv_mh_status)
            private val chevronIv: ImageView = v.findViewById(R.id.iv_mh_chevron)
            private val expandedContainer: View = v.findViewById(R.id.ll_mh_expanded)
            private val issuedByTv: TextView = v.findViewById(R.id.tv_mh_issued_by)
            private val untilTv: TextView = v.findViewById(R.id.tv_mh_until)
            private val reasonLabelTv: View = v.findViewById(R.id.tv_mh_reason_label)
            private val reasonTv: TextView = v.findViewById(R.id.tv_mh_reason)
            private val evidenceLabelTv: View = v.findViewById(R.id.tv_mh_evidence_label)
            private val evidenceList: LinearLayout = v.findViewById(R.id.ll_mh_evidence_list)

            fun bind(stat: MuteHistoryStat) {
                val ctx = itemView.context
                val e = stat.entry
                val now = System.currentTimeMillis()

                val bmp = AvatarUtils.fromBase64(stat.userAvatarBase64)
                if (bmp != null) {
                    avatarImg.setImageBitmap(bmp)
                    avatarImg.visibility = View.VISIBLE
                    letterTv.visibility = View.GONE
                } else {
                    avatarImg.visibility = View.GONE
                    letterTv.visibility = View.VISIBLE
                    letterTv.text = stat.userName.trim().firstOrNull()?.uppercase() ?: "?"
                }
                nameTv.text = stat.userName
                issuedAtTv.text = ctx.getString(R.string.mute_history_issued_at_fmt, dateFmt.format(java.util.Date(e.issuedAtMs)))

                val isActiveNow = e.unmutedEarlyAtMs == null && e.mutedUntilMs > now
                if (isActiveNow) {
                    statusTv.setBackgroundResource(R.drawable.bg_mute_status_active)
                    statusTv.setTextColor(ContextCompat.getColor(ctx, R.color.warning_on))
                    statusTv.text = ctx.getString(R.string.mute_history_status_active_fmt, dateFmt.format(java.util.Date(e.mutedUntilMs)))
                } else {
                    statusTv.setBackgroundResource(R.drawable.bg_mute_status_inactive)
                    statusTv.setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    statusTv.text = if (e.unmutedEarlyAtMs != null)
                        ctx.getString(R.string.mute_history_status_unmuted_early)
                    else ctx.getString(R.string.mute_history_status_expired)
                }

                val expanded = expandedMuteHistoryIds.contains(e.id)
                expandedContainer.visibility = if (expanded) View.VISIBLE else View.GONE
                chevronIv.setImageResource(if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                header.setOnClickListener { onToggle(e.id) }

                issuedByTv.text = ctx.getString(R.string.mute_history_issued_by_fmt, stat.issuedByName)
                untilTv.text = if (e.unmutedEarlyAtMs != null)
                    ctx.getString(R.string.mute_history_until_early_fmt, dateFmt.format(java.util.Date(e.mutedUntilMs)), dateFmt.format(java.util.Date(e.unmutedEarlyAtMs)))
                else ctx.getString(R.string.mute_history_until_fmt, dateFmt.format(java.util.Date(e.mutedUntilMs)))

                val reason = e.reason?.takeIf { it.isNotBlank() }
                if (reason != null) {
                    reasonLabelTv.visibility = View.VISIBLE
                    reasonTv.visibility = View.VISIBLE
                    reasonTv.text = reason
                } else {
                    reasonLabelTv.visibility = View.GONE
                    reasonTv.visibility = View.GONE
                }

                evidenceList.removeAllViews()
                val hasEvidenceRefs = !e.evidenceMsgIds.isNullOrBlank()
                if (hasEvidenceRefs) {
                    evidenceLabelTv.visibility = View.VISIBLE
                    if (stat.evidenceMsgs.isEmpty()) {
                        addEvidenceLine(ctx, null, ctx.getString(R.string.mute_history_evidence_unavailable), isOwn = true, sender = null)
                    } else {
                        // Ветка-переписка (см. PartnerProfileActivity.addEvidenceThreadRow) может
                        // включать реплику ДРУГОГО человека — такие строки рисуются нейтральным
                        // цветом слева с подписью имени, свои — жёлтым справа как раньше
                        // (см. ChatActivity.addMuteEvidenceBubble, тот же принцип).
                        stat.evidenceMsgs.forEach { m ->
                            val isOwn = m.senderUserId == null || m.senderUserId == e.userId
                            val sender = if (isOwn) null else m.sender
                            when {
                                m.isVoice -> addEvidenceLine(
                                    ctx, R.drawable.ic_mic,
                                    ctx.getString(R.string.mute_history_evidence_voice_fmt, m.voiceDurationSec.coerceAtLeast(0)),
                                    isOwn, sender
                                )
                                m.isImage -> addEvidenceLine(ctx, R.drawable.ic_image_outline, ctx.getString(R.string.mute_history_evidence_photo), isOwn, sender)
                                else -> addEvidenceLine(ctx, null, m.text.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.mute_history_evidence_unavailable), isOwn, sender)
                            }
                        }
                    }
                } else {
                    evidenceLabelTv.visibility = View.GONE
                }
            }

            private fun addEvidenceLine(ctx: android.content.Context, iconRes: Int?, text: String, isOwn: Boolean, sender: String?) {
                val row = LayoutInflater.from(ctx).inflate(R.layout.item_mute_history_evidence_line, evidenceList, false)
                val tvSender = row.findViewById<TextView>(R.id.tv_mhe_sender)
                val bubble = row.findViewById<LinearLayout>(R.id.ll_mhe_bubble)
                val iv = row.findViewById<ImageView>(R.id.iv_mhe_icon)
                val tv = row.findViewById<TextView>(R.id.tv_mhe_text)
                if (iconRes != null) {
                    iv.visibility = View.VISIBLE
                    iv.setImageResource(iconRes)
                } else {
                    iv.visibility = View.GONE
                }
                tv.text = text
                (row.layoutParams as? LinearLayout.LayoutParams)?.let {
                    it.gravity = if (isOwn) android.view.Gravity.END else android.view.Gravity.START
                    row.layoutParams = it
                }
                if (isOwn) {
                    tvSender.visibility = View.GONE
                    bubble.setBackgroundResource(R.drawable.bg_message_muted_evidence)
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.warning_on))
                    iv.setColorFilter(ContextCompat.getColor(ctx, R.color.warning_on), android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    tvSender.visibility = View.VISIBLE
                    tvSender.text = sender
                    bubble.setBackgroundResource(R.drawable.bg_message_muted_evidence_other)
                    tv.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                    iv.setColorFilter(ContextCompat.getColor(ctx, R.color.text_primary), android.graphics.PorterDuff.Mode.SRC_IN)
                }
                evidenceList.addView(row)
            }
        }
    }
}
