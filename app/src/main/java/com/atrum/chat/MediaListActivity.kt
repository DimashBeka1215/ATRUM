package com.atrum.chat

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Полный список медиа из профиля: фото (сетка), ссылки или голосовые (список).
 *
 * Долгое нажатие на элементе включает выбор; справа вверху появляются действия:
 *  — глаз: перейти к исходному сообщению в чате (с подсветкой акцентом);
 *  — копировать: только для текста (ссылки);
 *  — удалить: только для своих сообщений.
 * Переход/удаление делегируются ChatActivity (единый, проверенный путь — см. CLAUDE.md §1).
 */
class MediaListActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_MODE          = "mode"          // "photos" | "links" | "voice"
        const val EXTRA_TITLE         = "title"
        const val EXTRA_CHAT_ID       = "chat_id"
        const val EXTRA_CHANNEL_ID    = "channel_id"
        const val EXTRA_TRANSPORT_TOKEN = "transport_token"
        const val EXTRA_CHAT_PASSWORD = "chat_password"
        const val EXTRA_ITEMS         = "items"
        const val EXTRA_MSGIDS        = "msgids"
        const val EXTRA_SELF          = "self"
    }

    private var mode = "links"
    private var chatId = -1L
    private var channelId = ""
    private var transportToken = ""
    private var chatPassword = ""
    private var items = ArrayList<String>()
    private var msgIds = ArrayList<String>()
    private var selfFlags = ArrayList<String>()

    private lateinit var loader: ImageLoader

    private lateinit var actionBar: View
    private lateinit var btnCopy: ImageButton
    private lateinit var btnEye: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var tvSubtitle: TextView
    private lateinit var adapter: MediaAdapter

    private var selectedPos = -1
    private var activeVoiceIcon: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_list)

        mode         = intent.getStringExtra(EXTRA_MODE) ?: "links"
        chatId       = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        channelId    = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: ""
        transportToken = intent.getStringExtra(EXTRA_TRANSPORT_TOKEN) ?: ""
        chatPassword = intent.getStringExtra(EXTRA_CHAT_PASSWORD) ?: ""
        items        = intent.getStringArrayListExtra(EXTRA_ITEMS) ?: arrayListOf()
        msgIds       = intent.getStringArrayListExtra(EXTRA_MSGIDS) ?: arrayListOf()
        selfFlags    = intent.getStringArrayListExtra(EXTRA_SELF) ?: arrayListOf()

        val transport = TransportFactory.forChat(this, channelId, transportToken, chatPassword, prefs.myUserId)
        loader = ImageLoader(transport, chatPassword)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener {
            if (selectedPos >= 0) clearSelection() else finish()
        }
        findViewById<TextView>(R.id.tv_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.profile_links_label)
        tvSubtitle = findViewById(R.id.tv_subtitle)

        actionBar = findViewById(R.id.action_bar)
        btnCopy   = findViewById(R.id.btn_copy)
        btnEye    = findViewById(R.id.btn_eye)
        btnDelete = findViewById(R.id.btn_delete)
        btnEye.setOnClickListener { onEye() }
        btnCopy.setOnClickListener { onCopy() }
        btnDelete.setOnClickListener { onDelete() }

        updateSubtitle()

        val rv = findViewById<RecyclerView>(R.id.rv_media)
        adapter = MediaAdapter()
        if (mode == "photos") {
            rv.layoutManager = GridLayoutManager(this, 3)
        } else {
            rv.layoutManager = LinearLayoutManager(this)
        }
        rv.adapter = adapter
    }

    override fun onPause() {
        super.onPause()
        VoicePlayer.pause()
        activeVoiceIcon?.setImageResource(R.drawable.ic_play)
        activeVoiceIcon = null
    }

    private fun updateSubtitle() {
        tvSubtitle.text = if (selectedPos >= 0) getString(R.string.media_selected_one)
                          else resources.getQuantityString(R.plurals.media_count, items.size, items.size)
    }

    private fun isSelf(pos: Int): Boolean = selfFlags.getOrNull(pos) == "1"

    private fun select(pos: Int) {
        val prev = selectedPos
        selectedPos = pos
        if (prev >= 0) adapter.notifyItemChanged(prev)
        adapter.notifyItemChanged(pos)
        actionBar.visibility = View.VISIBLE
        btnCopy.visibility = if (mode == "links") View.VISIBLE else View.GONE
        btnDelete.visibility = if (isSelf(pos)) View.VISIBLE else View.GONE
        updateSubtitle()
    }

    private fun clearSelection() {
        val prev = selectedPos
        selectedPos = -1
        if (prev >= 0) adapter.notifyItemChanged(prev)
        actionBar.visibility = View.GONE
        updateSubtitle()
    }

    private fun selectedMsgId(): String? = msgIds.getOrNull(selectedPos)?.takeIf { it.isNotBlank() }

    private fun onEye() {
        val id = selectedMsgId() ?: return
        startActivity(android.content.Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
            putExtra(ChatActivity.EXTRA_SCROLL_TO_MSGID, id)
            addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        finish()
    }

    private fun onCopy() {
        val url = items.getOrNull(selectedPos) ?: return
        val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
        android.widget.Toast.makeText(this, R.string.msg_copied, android.widget.Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun onDelete() {
        val id = selectedMsgId() ?: return
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.dialog_delete_title),
            message = getString(R.string.dialog_delete_message),
            positiveText = getString(R.string.action_delete),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) {
            startActivity(android.content.Intent(this, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
                putExtra(ChatActivity.EXTRA_DELETE_MSGID, id)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
    }

    // ── Tap-действия (короткое нажатие) ───────────────────────────────────────
    private fun openPhoto(pos: Int) {
        AppLock.beginShareGrace()
        startActivity(android.content.Intent(this, ImageViewActivity::class.java).apply {
            putExtra(ImageViewActivity.EXTRA_REFS, ArrayList(items))
            putExtra(ImageViewActivity.EXTRA_START_INDEX, pos)
        })
    }

    private fun openLink(url: String) {
        try {
            AppLock.beginShareGrace()
            val u = if (url.startsWith("http", true)) url else "http://$url"
            startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)))
        } catch (_: Exception) {}
    }

    private fun toggleVoice(ref: String, playIcon: ImageView) {
        val key = "ml_$ref"
        if (VoicePlayer.isPlaying(key)) {
            VoicePlayer.pause()
            playIcon.setImageResource(R.drawable.ic_play)
            activeVoiceIcon = null
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { loadVoiceFile(ref) } ?: return@launch
            activeVoiceIcon?.setImageResource(R.drawable.ic_play)
            activeVoiceIcon = playIcon
            playIcon.setImageResource(R.drawable.ic_pause)
            VoicePlayer.toggle(key, file, { _, _, _ -> }, { _ ->
                runOnUiThread {
                    playIcon.setImageResource(R.drawable.ic_play)
                    if (activeVoiceIcon === playIcon) activeVoiceIcon = null
                }
            })
        }
    }

    private suspend fun loadVoiceFile(ref: String): java.io.File? {
        val dir = java.io.File(cacheDir, "voice_play").apply { mkdirs() }
        val f = java.io.File(dir, "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
        if (f.exists() && f.length() > 0) return f
        val bytes = loader.loadRawBytes(ref) ?: return null
        return try { f.writeBytes(bytes); f } catch (_: Exception) { null }
    }

    private fun linkHost(url: String): String = try {
        android.net.Uri.parse(if (url.startsWith("http", true)) url else "http://$url").host ?: url
    } catch (_: Exception) { url }

    private fun formatDur(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

    // ── Adapter ────────────────────────────────────────────────────────────────
    private inner class MediaAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (mode) {
                "photos" -> PhotoVH(inf.inflate(R.layout.item_media_photo, parent, false))
                "voice"  -> VoiceVH(inf.inflate(R.layout.item_voice_row, parent, false))
                else     -> LinkVH(inf.inflate(R.layout.item_link_row, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is PhotoVH -> holder.bind(position)
                is VoiceVH -> holder.bind(position)
                is LinkVH  -> holder.bind(position)
            }
        }
    }

    private fun applyRowSelection(itemView: View, position: Int) {
        if (position == selectedPos) {
            itemView.setBackgroundColor(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(itemView.context, R.color.accent), 0x33))
        } else {
            itemView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }
    }

    private inner class PhotoVH(v: View) : RecyclerView.ViewHolder(v) {
        private val iv: ImageView = v.findViewById(R.id.iv_photo_cell)
        private val sel: View = v.findViewById(R.id.v_photo_sel)
        private val cell: View = v.findViewById(R.id.fl_photo_cell)
        fun bind(position: Int) {
            sel.visibility = if (position == selectedPos) View.VISIBLE else View.GONE
            iv.setImageDrawable(null)
            val ref = items[position]
            if (ref.startsWith("base64:")) {
                AvatarUtils.fromBase64(ref.removePrefix("base64:"))?.let { iv.setImageBitmap(it) }
            } else {
                lifecycleScope.launch {
                    val bmp: Bitmap? = withContext(Dispatchers.IO) { try { loader.loadBitmap(ref) } catch (_: Exception) { null } }
                    if (bmp != null && bindingAdapterPosition == position) iv.setImageBitmap(bmp)
                }
            }
            cell.setOnClickListener {
                if (selectedPos >= 0) clearSelection() else openPhoto(position)
            }
            cell.setOnLongClickListener { select(position); true }
        }
    }

    private inner class LinkVH(v: View) : RecyclerView.ViewHolder(v) {
        private val letter: TextView = v.findViewById(R.id.tv_link_letter)
        private val title: TextView = v.findViewById(R.id.tv_link_title)
        private val url: TextView = v.findViewById(R.id.tv_link_url)
        fun bind(position: Int) {
            val u = items[position]
            val host = linkHost(u)
            letter.text = host.firstOrNull()?.uppercase() ?: "#"
            title.text = u.removePrefix("https://").removePrefix("http://").trimEnd('/')
            url.text = host
            applyRowSelection(itemView, position)
            itemView.setOnClickListener {
                if (selectedPos >= 0) clearSelection() else openLink(u)
            }
            itemView.setOnLongClickListener { select(position); true }
        }
    }

    private inner class VoiceVH(v: View) : RecyclerView.ViewHolder(v) {
        private val play: ImageView = v.findViewById(R.id.iv_voice_play)
        private val label: TextView = v.findViewById(R.id.tv_voice_label)
        private val dur: TextView = v.findViewById(R.id.tv_voice_dur)
        fun bind(position: Int) {
            val parts = items[position].split('')
            val ref = parts.getOrNull(0) ?: ""
            val durSec = parts.getOrNull(1)?.toIntOrNull() ?: 0
            label.text = getString(R.string.msg_preview_voice)
            dur.text = formatDur(durSec)
            play.setImageResource(if (VoicePlayer.isPlaying("ml_$ref")) R.drawable.ic_pause else R.drawable.ic_play)
            applyRowSelection(itemView, position)
            itemView.setOnClickListener {
                if (selectedPos >= 0) clearSelection() else toggleVoice(ref, play)
            }
            itemView.setOnLongClickListener { select(position); true }
        }
    }
}
