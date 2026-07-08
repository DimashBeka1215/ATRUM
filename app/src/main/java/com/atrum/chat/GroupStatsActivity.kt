package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
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
    }

    data class UserStat(
        val userId: String,
        val name: String,
        val avatarBase64: String?,
        val isAdmin: Boolean,
        val messageCount: Int,
        val lastMessageAtMs: Long
    )

    private lateinit var adapter: UsersAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private var chatRoomId: Long = -1L

    private var transportReady = false
    private lateinit var transport: ChatTransport
    private lateinit var chatEntity: Chat
    private lateinit var networkChatId: String
    private lateinit var chatPassword: String

    /** Живая подписка на новые сообщения канала — см. UserStatsActivity.transportWatch
     *  (тот же переиспользуемый REQ-стрим NostrTransport, без нового поллинг-цикла). */
    private var transportWatch: AutoCloseable? = null
    private var isFirstResume = true

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

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        swipeRefresh.setOnRefreshListener {
            if (transportReady) lifecycleScope.launch { refreshStats() } else swipeRefresh.isRefreshing = false
        }

        if (chatRoomId < 0) { finish(); return }
        setupAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (isFirstResume) { isFirstResume = false }
        else if (transportReady) lifecycleScope.launch { refreshStats() }
    }

    override fun onDestroy() {
        transportWatch?.close()
        super.onDestroy()
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

            transportWatch = transport.watchMessages {
                lifecycleScope.launch { refreshStats() }
            }

            refreshStats()
        }
    }

    /** Повторно читает канал и пересобирает список участников — вызывается из onResume,
     *  pull-to-refresh и живой подписки; переиспользует уже поднятый [transport]. */
    private suspend fun refreshStats() {
        try {
            val db = AppDatabase.get(this@GroupStatsActivity)
            val participants = withContext(Dispatchers.IO) { db.chatParticipantDao().getForChat(chatEntity.id) }
            val allData = withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }

            val profiles = if (allData != null) withContext(Dispatchers.Default) {
                if (ChatActivity.SLOT_UNION_PROFILES && allData.profileSlots.isNotEmpty())
                    ProfileSync.unionProfileSlots(allData.profileSlots, chatPassword, networkChatId)
                else ProfileSync.parseProfiles(allData.profilesContent, chatPassword, networkChatId)
            } else emptyMap()

            val messages = if (allData != null) withContext(Dispatchers.Default) {
                StatsUtil.decodeAll(allData.chatContent, chatPassword, networkChatId, prefs.myUserId, prefs.myName)
            } else emptyList()

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
}
