package com.atrum.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.atrum.chat.data.Chat
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class ChatsAdapter(
    private var chats: List<Chat> = emptyList(),
    private val onClick: (Chat) -> Unit,
    private val onLongClick: (Chat) -> Unit
) : RecyclerView.Adapter<ChatsAdapter.VH>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("dd.MM", Locale.getDefault())

    fun submit(list: List<Chat>) {
        chats = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_chat, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val chat = chats[position]
        holder.bind(chat, formatTime(chat.lastTimeMs))
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
        private val avatar: ShapeableImageView = itemView.findViewById(R.id.iv_avatar)
        private val name: TextView = itemView.findViewById(R.id.tv_partner_name)
        private val lastMessage: TextView = itemView.findViewById(R.id.tv_last_message)
        private val time: TextView = itemView.findViewById(R.id.tv_time)
        private val unreadBadge: TextView = itemView.findViewById(R.id.tv_unread_badge)
        private val pinIcon: View = itemView.findViewById(R.id.iv_pin)

        fun bind(chat: Chat, formattedTime: String) {
            time.text = formattedTime
            pinIcon.visibility = if (chat.isPinned) View.VISIBLE else View.GONE

            if (chat.isFavorites) {
                initial.setBackgroundResource(R.drawable.bg_avatar_favorites)
                initial.visibility = View.VISIBLE
                avatar.visibility = View.GONE
                initial.text = "★"
                name.text = itemView.context.getString(R.string.favorites_name)
                lastMessage.text = chat.lastMessage.ifBlank {
                    itemView.context.getString(R.string.favorites_description)
                }
                name.setTextColor(itemView.context.getColor(R.color.text_primary))
                lastMessage.setTextColor(itemView.context.getColor(R.color.text_secondary))
            } else if (chat.partnerDeleted) {
                // ── Удалённый профиль ────────────────────────────────────────
                // Показываем серую заглушку вместо аватарки
                avatar.visibility = View.GONE
                initial.visibility = View.VISIBLE
                initial.text = "✕"
                initial.setBackgroundResource(R.drawable.bg_avatar_deleted)
                // Имя зачёркнуто — профиль был удалён
                name.text = chat.partnerName.ifBlank { "?" }
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
                // ── Обычный активный профиль ─────────────────────────────────
                initial.setBackgroundResource(R.drawable.bg_avatar_placeholder)
                name.setTextColor(
                    itemView.context.getColor(R.color.text_primary)
                )
                val displayName = if (!chat.partnerTag.isNullOrBlank()) {
                    "${chat.partnerName} ${chat.partnerTag}"
                } else {
                    chat.partnerName
                }
                name.text = displayName.ifBlank { "?" }
                lastMessage.text = chat.lastMessage.ifBlank { "—" }
                lastMessage.setTextColor(
                    itemView.context.getColor(R.color.text_secondary)
                )

                val avatarBitmap = AvatarUtils.fromBase64(chat.partnerAvatarBase64)
                if (avatarBitmap != null) {
                    avatar.setImageBitmap(avatarBitmap)
                    avatar.visibility = View.VISIBLE
                    initial.visibility = View.GONE
                } else {
                    avatar.visibility = View.GONE
                    initial.visibility = View.VISIBLE
                    initial.text = chat.partnerName.trim().firstOrNull()?.uppercase() ?: "?"
                }
            }

            if (chat.unreadCount > 0) {
                unreadBadge.text = if (chat.unreadCount > 99) "99+" else chat.unreadCount.toString()
                unreadBadge.visibility = View.VISIBLE
            } else {
                unreadBadge.visibility = View.GONE
            }
        }
    }
}
