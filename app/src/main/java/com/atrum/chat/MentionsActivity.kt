package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Экран «Упоминания» ОДНОЙ беседы (мокап одобрен пользователем, см. docs/mockup_mentions.html).
 *
 * Показывает сообщения беседы, где упомянули меня; тап по строке открывает чат и
 * подпрыгивает к сообщению (ChatActivity.EXTRA_SCROLL_TO_MSGID — механизм уже существует,
 * им же пользуются переходы к закрепам и к следующему упоминанию).
 *
 * Почему на ОДНУ беседу, а не общий по всем: набор упоминаний уже хранится по чату
 * (Chat.mentionMsgIds — его считают и опрос списка, и стрим, и фоновый сервис), поэтому экран
 * ложится на готовые данные и не требует обхода всех чатов. Вход — тап по бейджу «@N» в списке.
 *
 * ⚠️ Сети НЕ трогает (§1): читает ленту из долговечного локального стора через
 * loadLocalSnapshotOrNull. Всё, что здесь показано, уже доставлено обычным синком.
 */
class MentionsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var rv: RecyclerView
    private lateinit var emptyState: View
    private lateinit var progress: View
    private lateinit var tvCount: TextView

    private val items = ArrayList<Row>()
    private var chatRoomId: Long = -1L

    /** Одна строка списка: всё уже разобрано, вью-холдеру остаётся только показать. */
    data class Row(
        val msgId: String,
        val sender: String,
        val text: CharSequence,
        val time: String,
        val avatarBase64: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        // Экран показывает текст сообщений (упоминания) — тоже часть переписки, скриншоты
        // запрещены всегда, если тестер явно не снял блокировку в настройках.
        if (!prefs.isScreenshotsAllowed) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        setContentView(R.layout.activity_mentions)

        rv = findViewById(R.id.rv_mentions)
        emptyState = findViewById(R.id.empty_state)
        progress = findViewById(R.id.progress)
        tvCount = findViewById(R.id.tv_count)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = Adapter()

        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ROOM_ID, -1L)
        if (chatRoomId <= 0) { finish(); return }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Пока экран был свёрнут, могли прийти новые упоминания — перечитываем локально.
        if (chatRoomId > 0 && items.isNotEmpty()) load()
    }

    private fun load() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { runCatching { buildRows() }.getOrDefault(emptyList()) }
            items.clear()
            items.addAll(rows)
            rv.adapter?.notifyDataSetChanged()
            progress.visibility = View.GONE
            emptyState.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            rv.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
            tvCount.text = rows.size.toString()
            tvCount.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    /**
     * Собирает строки из ЛОКАЛЬНОЙ ленты беседы. Упоминанием считается ровно то же, что и
     * везде (MentionUtil.mentionsMeAny): точный путь по метке с userId плюс запасной
     * текстовый — чтобы сообщения, отправленные до перехода на метки, тоже попадали сюда.
     */
    private suspend fun buildRows(): List<Row> {
        val db = AppDatabase.get(this)
        val chat = db.chatDao().getById(chatRoomId) ?: return emptyList()
        if (!chat.isGroup) return emptyList()

        val password = prefs.getChatPassword(chat.chatId).ifEmpty {
            @Suppress("DEPRECATION") chat.chatPassword
        }
        val token = prefs.getChatToken(chat.chatId).ifEmpty {
            @Suppress("DEPRECATION") chat.transportToken
        }
        val api = TransportFactory.forChat(
            applicationContext, chat.chatId, token, password, prefs.myUserId, chat.adminUserId
        )
        // Локальный снимок необязателен по контракту (ChatTransport.loadLocalSnapshotOrNull
        // возвращает null у транспортов без долговечного стора) — нет снимка, нет и списка.
        val content = api.loadLocalSnapshotOrNull()?.chatContent ?: return emptyList()
        if (content.isBlank()) return emptyList()

        val myUserId = prefs.myUserId
        val myName = prefs.myName
        val myTag = prefs.myTag
        val aliases = prefs.nameHistory

        // Актуальные ники берём из межчатового кэша профилей (ProfileSync.getGlobalKnown):
        // он наполняется обычным синком по всем чатам, поэтому имя упомянутого показывается
        // НЫНЕШНЕЕ, а не то, что было вписано при отправке (см. Message.renderMentions).
        val accent = ContextCompat.getColor(this, R.color.accent_light)

        val rows = ArrayList<Row>()
        for (line in content.split("\n")) {
            if (line.isBlank()) continue
            val decrypted = CryptoHelper.decrypt(line, password, chat.chatId) ?: continue
            val msg = Message.fromDecrypted(decrypted, myUserId, myName, aliases)
            if (msg.isSelf || msg.msgId.isBlank()) continue
            if (!MentionUtil.mentionsMeAny(msg.text, myUserId, myTag, myName)) continue
            // Хэндл, а НЕ имя (см. Profile.mentionHandle) — иначе, если у человека тег
            // отличается от имени, упоминание визуально обрубается до имени (репорт:
            // «подчёркивание в теге не вставляется, тегом считается только первая половина»).
            val shown = Message.renderMentions(msg.text) { uid -> ProfileSync.getGlobalKnown(uid)?.mentionHandle() }
            val senderNow = msg.senderUserId
                ?.let { ProfileSync.getGlobalKnown(it)?.name }
                ?.takeIf { it.isNotBlank() } ?: msg.sender
            rows.add(
                Row(
                    msgId = msg.msgId,
                    sender = senderNow,
                    text = MessageAdapter.highlightMentions(shown, accent),
                    time = formatWhen(msg.timestampMs),
                    avatarBase64 = msg.senderUserId?.let { ProfileSync.getGlobalKnown(it)?.avatarBase64 }
                )
            )
        }
        // Новые сверху — как в мокапе.
        return rows.asReversed()
    }

    /**
     * Время строки: сегодняшнее — часами, вчерашнее — словом, остальное — датой.
     * Своё, а не MessageAdapter.formatTime: тот приватный и завязан на кэши адаптера ленты.
     */
    private fun formatWhen(ms: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        val sameYear = now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR)
        val dDoy = now.get(java.util.Calendar.DAY_OF_YEAR) - then.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            sameYear && dDoy == 0 ->
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))
            sameYear && dDoy == 1 -> getString(R.string.mentions_yesterday)
            else ->
                java.text.SimpleDateFormat("dd.MM", java.util.Locale.getDefault()).format(java.util.Date(ms))
        }
    }

    private fun openMessage(msgId: String) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra(ChatActivity.EXTRA_CHAT_ID, chatRoomId)
                .putExtra(ChatActivity.EXTRA_SCROLL_TO_MSGID, msgId)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mention, parent, false))

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    }

    private inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val initial: TextView = v.findViewById(R.id.tv_avatar_initial)
        private val avatar: com.google.android.material.imageview.ShapeableImageView =
            v.findViewById(R.id.iv_avatar)
        private val sender: TextView = v.findViewById(R.id.tv_sender)
        private val time: TextView = v.findViewById(R.id.tv_time)
        private val text: TextView = v.findViewById(R.id.tv_text)

        fun bind(row: Row) {
            sender.text = row.sender
            time.text = row.time
            text.text = row.text
            initial.text = row.sender.trim().firstOrNull()?.uppercase() ?: "?"
            val bmp = AvatarUtils.fromBase64(row.avatarBase64)
            if (bmp != null) {
                avatar.setImageBitmap(bmp)
                avatar.visibility = View.VISIBLE
            } else {
                avatar.visibility = View.GONE
            }
            itemView.setOnClickListener { openMessage(row.msgId) }
        }
    }

    companion object {
        const val EXTRA_CHAT_ROOM_ID = "chat_room_id"
    }
}
