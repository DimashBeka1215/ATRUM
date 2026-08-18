package com.atrum.chat

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.atrum.chat.data.Chat
import com.atrum.chat.data.displayAvatarBase64
import com.atrum.chat.data.displayName
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatsAdapter(
    private var chats: List<Chat> = emptyList(),
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit,
    /** Тап по бейджу «@N» — открыть экран упоминаний беседы (см. MentionsActivity).
     *  null → бейдж некликабельный, поведение как раньше. */
    private val onMentionsClick: ((Chat) -> Unit)? = null
) : RecyclerView.Adapter<ChatsAdapter.VH>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM", Locale.getDefault())

    /** Текущий поисковый запрос — используется для подсветки совпадений в bindViewHolder */
    var searchQuery: String = ""

    fun submit(list: List<Chat>) {
        submitFiltered(list, "")
    }

    /**
     * Обновляет список с учётом поискового запроса.
     * Несовпадающие чаты не передаются — они убираются из RecyclerView через DiffUtil
     * (DefaultItemAnimator анимирует удаление/добавление).
     */
    fun submitFiltered(allChats: List<Chat>, query: String) {
        searchQuery = query.trim()
        val filtered = if (searchQuery.isBlank()) allChats
        else allChats.filter { it.displayName().contains(searchQuery, ignoreCase = true) }
        val oldList = chats
        chats = filtered
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldList.size
            override fun getNewListSize() = filtered.size
            override fun areItemsTheSame(o: Int, n: Int) = oldList[o].id == filtered[n].id
            override fun areContentsTheSame(o: Int, n: Int): Boolean {
                // При активном поиске подсветка совпадений может меняться — перерисовываем всегда.
                if (searchQuery.isNotBlank()) return false
                val a = oldList[o]; val b = filtered[n]
                // Раньше здесь был полный data-class `==`, который char-by-char сравнивал и
                // тяжёлые base64-аватары (30–50 КБ) — на ГЛАВНОМ потоке при каждом апдейте БД
                // во время синка. На слабых устройствах это тормозило прокрутку списка и
                // «съедало» клики/зажатия (репорт: фризы + странные нажатия). Теперь сравниваем
                // только поля, влияющие на отрисовку строки (см. VH.bind), а аватары — по
                // дешёвой сигнатуре (длина + hashCode строки кэширован), без char-by-char.
                return a.isPinned == b.isPinned &&
                    a.partnerVerified == b.partnerVerified &&
                    a.isFavorites == b.isFavorites &&
                    a.partnerDeleted == b.partnerDeleted &&
                    a.isGroup == b.isGroup &&
                    a.chatId == b.chatId &&
                    a.partnerName == b.partnerName &&
                    a.partnerNickname == b.partnerNickname &&
                    a.partnerTag == b.partnerTag &&
                    a.groupName == b.groupName &&
                    a.lastMessage == b.lastMessage &&
                    a.lastTimeMs == b.lastTimeMs &&
                    a.unreadCount == b.unreadCount &&
                    a.mentionMsgIds == b.mentionMsgIds &&
                    avatarSig(a.partnerAvatarBase64) == avatarSig(b.partnerAvatarBase64) &&
                    avatarSig(a.groupAvatarBase64) == avatarSig(b.groupAvatarBase64)
            }
        }).dispatchUpdatesTo(this)
    }

    /** Дешёвая сигнатура base64-аватара: длина + кэшированный hashCode строки, без сравнения
     *  всех 30–50 КБ посимвольно (см. areContentsTheSame). */
    private fun avatarSig(s: String?): Long =
        if (s.isNullOrEmpty()) 0L else (s.length.toLong() shl 32) xor (s.hashCode().toLong() and 0xffffffffL)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chat = chats[position]
        holder.bind(chat, formatTime(chat.lastTimeMs), searchQuery, onMentionsClick)
        holder.itemView.setOnClickListener { onClick(chat) }
        holder.itemView.setOnLongClickListener {
            onLongClick(chat); true
        }
    }

    override fun getItemCount(): Int = chats.size

    private fun formatTime(ms: Long): String {
        val now = Calendar.getInstance()
        val msgCal = Calendar.getInstance().apply { timeInMillis = ms }
        return if (now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)) {
            timeFmt.format(Date(ms))
        } else {
            dateFmt.format(Date(ms))
        }
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewById(R.id.tv_avatar_initial)
        private val avatarIcon: ImageView = itemView.findViewById(R.id.iv_avatar_icon)
        private val avatar: ShapeableImageView = itemView.findViewById(R.id.iv_avatar)
        private val name: TextView = itemView.findViewById(R.id.tv_partner_name)
        private val lastMessage: TextView = itemView.findViewById(R.id.tv_last_message)
        private val time: TextView = itemView.findViewById(R.id.tv_time)
        private val unreadBadge: TextView = itemView.findViewById(R.id.tv_unread_badge)
        private val mentionBadge: TextView = itemView.findViewById(R.id.tv_mention_badge)
        private val pinIcon: View = itemView.findViewById(R.id.iv_pin)
        private val verifiedBadge: VerifiedBadgeView = itemView.findViewById(R.id.verified_badge_list)

        /**
         * ⚠️ [onMentionsClick] приходит ПАРАМЕТРОМ, а не читается из адаптера напрямую:
         * VH — вложенный (не inner) класс, поэтому свойства конструктора ChatsAdapter
         * отсюда не видны (сборка падала на «Unresolved reference 'onMentionsClick'»).
         */
        fun bind(
            chat: Chat,
            formattedTime: String,
            query: String = "",
            onMentionsClick: ((Chat) -> Unit)? = null
        ) {
            time.text = formattedTime
            pinIcon.visibility = if (chat.isPinned) View.VISIBLE else View.GONE
            // Галочка верификации у ника собеседника (только 1:1; partnerVerified считается
            // криптографически фоновым опросом, см. VerifiedBadge). Групп/избранного не касается.
            verifiedBadge.setVerified(chat.partnerVerified, animate = false)

            if (chat.isSystemNotifications) {
                // Системный чат «Уведомления» (SystemNotifications, мокап одобрен):
                // колокольчик на фиолетовой заглушке, имя/подпись из ресурсов.
                initial.setBackgroundResource(R.drawable.bg_avatar_placeholder)
                initial.visibility = View.VISIBLE
                avatarIcon.visibility = View.VISIBLE
                avatarIcon.setImageResource(R.drawable.ic_bell)
                avatar.visibility = View.GONE
                initial.text = ""
                name.text = itemView.context.getString(R.string.notif_chat_name)
                lastMessage.text = chat.lastMessage.ifBlank {
                    itemView.context.getString(R.string.notif_chat_subtitle)
                }
                name.setTextColor(itemView.context.getColor(R.color.text_primary))
                lastMessage.setTextColor(itemView.context.getColor(R.color.text_secondary))
            } else if (chat.isFavorites) {
                initial.setBackgroundResource(R.drawable.bg_avatar_favorites)
                initial.visibility = View.VISIBLE
                avatarIcon.visibility = View.VISIBLE
                avatarIcon.setImageResource(R.drawable.ic_sparkle) // Используем ic_sparkle как замену ★
                avatar.visibility = View.GONE
                initial.text = ""
                name.text = itemView.context.getString(R.string.favorites_name)
                lastMessage.text = chat.lastMessage.ifBlank {
                    itemView.context.getString(R.string.favorites_description)
                }
                name.setTextColor(itemView.context.getColor(R.color.text_primary))
                lastMessage.setTextColor(itemView.context.getColor(R.color.text_secondary))
            } else if (!chat.isGroup && chat.partnerDeleted) {
                // ── Удалённый профиль (только 1:1 — у групп нет одного "собеседника",
                //    чей профиль мог бы удалиться и погасить весь чат) ───────────
                // Показываем серую заглушку вместо аватарки
                avatar.visibility = View.GONE
                initial.visibility = View.VISIBLE
                avatarIcon.visibility = View.VISIBLE
                avatarIcon.setImageResource(R.drawable.ic_close) // Используем ic_close как замену ✕
                initial.text = ""
                initial.setBackgroundResource(R.drawable.bg_avatar_deleted)
                // Имя зачёркнуто — профиль был удалён. Локальный ник (если задан) всё ещё
                // осмыслен — это МОЙ ярлык для этого человека, профиль удалился, а не ник.
                name.text = chat.displayName().ifBlank { "?" }
                name.setTextColor(
                    itemView.context.getColor(R.color.text_secondary)
                )
                if (!chat.partnerTag.isNullOrBlank()) {
                    lastMessage.text = chat.partnerTag
                } else {
                    lastMessage.text = itemView.context.getString(R.string.profile_deleted_label)
                }
                lastMessage.setTextColor(
                    itemView.context.getColor(android.R.color.holo_red_light)
                )
            } else {
                // ── Обычный активный профиль (1:1) или групповой чат ─────────
                // Для группы источник имени/авы — groupName/groupAvatarBase64
                // (актуальны, приходят через members.txt), НЕ partnerName/
                // partnerAvatarBase64 — те для группы не поддерживаются
                // автоматически и остаются на creation-time значении.
                initial.setBackgroundResource(R.drawable.bg_avatar_placeholder)
                name.setTextColor(
                    itemView.context.getColor(R.color.text_primary)
                )
                // chat.displayName() уже отдаёт приоритет локальному нику собеседника
                // (Chat.partnerNickname) над синканным partnerName для 1:1 — см. Chat.kt.
                val effectiveName = if (!chat.isGroup && !chat.partnerTag.isNullOrBlank()) {
                    "${chat.displayName()} ${chat.partnerTag}"
                } else {
                    chat.displayName()
                }
                val displayText = effectiveName.ifBlank { "?" }
                name.text = highlightQuery(displayText, query,
                    ContextCompat.getColor(itemView.context, R.color.accent_light))
                lastMessage.text = chat.lastMessage.ifBlank { "—" }
                lastMessage.setTextColor(
                    itemView.context.getColor(R.color.text_secondary)
                )

                val effectiveAvatarBase64 = if (chat.isGroup) chat.displayAvatarBase64() else chat.partnerAvatarBase64
                val avatarBitmap = AvatarUtils.fromBase64(effectiveAvatarBase64)
                if (avatarBitmap != null) {
                    avatar.setImageBitmap(avatarBitmap)
                    avatar.visibility = View.VISIBLE
                    initial.visibility = View.GONE
                    avatarIcon.visibility = View.GONE
                } else {
                    avatar.visibility = View.GONE
                    initial.visibility = View.VISIBLE
                    avatarIcon.visibility = View.GONE
                    initial.text = effectiveName.trim().firstOrNull()?.uppercase() ?: "?"
                }
            }

            // Непрочитанные @упоминания — акцентный бейдж «@N» слева от счётчика.
            val mentionCount = chat.mentionMsgIds?.split(",")?.count { it.isNotBlank() } ?: 0
            if (mentionCount > 0) {
                mentionBadge.text = "@" + (if (mentionCount > 99) "99+" else mentionCount.toString())
                mentionBadge.visibility = View.VISIBLE
                // Бейдж — вход в экран упоминаний. Тап по нему НЕ должен открывать чат,
                // поэтому обработчик свой; отсутствие колбэка оставляет прежнее поведение.
                if (onMentionsClick != null) {
                    mentionBadge.isClickable = true
                    mentionBadge.setOnClickListener { onMentionsClick.invoke(chat) }
                } else {
                    mentionBadge.isClickable = false
                    mentionBadge.setOnClickListener(null)
                }
            } else {
                mentionBadge.visibility = View.GONE
                mentionBadge.setOnClickListener(null)
            }

            if (chat.unreadCount > 0) {
                unreadBadge.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
                unreadBadge.visibility = View.VISIBLE
                // При наличии @-бейджа счётчик непрочитанных приглушаем в серый — чтобы «@» выделялся.
                unreadBadge.backgroundTintList = if (mentionCount > 0)
                    android.content.res.ColorStateList.valueOf(
                        androidx.core.content.ContextCompat.getColor(itemView.context, R.color.text_quaternary))
                else null
            } else {
                unreadBadge.visibility = View.GONE
            }
        }

        /** Подсвечивает первое вхождение [query] в [text] цветом [color]. */
        private fun highlightQuery(text: String, query: String, color: Int): CharSequence {
            if (query.isBlank()) return text
            val idx = text.indexOf(query, ignoreCase = true)
            if (idx < 0) return text
            val spannable = SpannableString(text)
            spannable.setSpan(
                ForegroundColorSpan(color),
                idx, idx + query.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            return spannable
        }
    }
}
