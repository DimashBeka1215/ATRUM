package com.atrum.chat

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.data.displayName
import com.atrum.chat.data.displayAvatarBase64
import com.atrum.chat.databinding.ActivityChatsSettingsBinding
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Отдельный экран «Настройки → Чаты» (пункт со стрелкой, по образцу «Уведомления»).
 *
 * Сейчас содержит одну настройку — «устойчивая доставка медиа» ([Chat.resilientMedia]),
 * но НЕ глобальную, а отдельно для КАЖДОГО чата: включённый режим режет фото и голосовые
 * на заметно более мелкие части и шлёт их с паузами (см. NostrTransport.chunkChars). Это
 * помогает, когда провайдер собеседника режет крупные пакеты с медиа, но ощутимо замедляет
 * отправку — поэтому включать его глобально смысла нет, и список чатов здесь именно для
 * того, чтобы включить режим точечно.
 *
 * «Избранное» в список НЕ попадает: этот чат работает через LocalTransport (файл на диске),
 * по сети не ходит, и размер частей для него не имеет смысла.
 *
 * Изменение применяется сразу — пишем в БД и обновляем строку на месте, без выхода и
 * повторного входа (CLAUDE.md §1.5).
 *
 * Про «когда подействует» в самом чате: настройки открываются ТОЛЬКО из списка чатов
 * (ChatsListActivity), из ChatActivity в них не попасть — значит, экран чата не может
 * висеть в стеке за этим экраном. Чтобы вернуться в чат, надо пройти через список, а
 * оттуда ChatActivity создаётся заново и читает чат из БД уже с новым значением. Поэтому
 * «протухшего» транспорта со старым размером частей тут не возникает.
 */
class ChatsSettingsActivity : SecureActivity() {

    private lateinit var binding: ActivityChatsSettingsBinding

    /** Текущее состояние строк: чат → его актуальный флаг (для мгновенного отклика UI). */
    private val rowState = mutableMapOf<Long, Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        loadChats()
    }

    private fun loadChats() {
        lifecycleScope.launch {
            val dao = AppDatabase.get(this@ChatsSettingsActivity).chatDao()
            // «Избранное» отфильтровано — см. doc-comment класса.
            val chats = withContext(Dispatchers.IO) {
                dao.getAll().filterNot { it.isFavorites }
            }
            if (isDestroyed) return@launch
            renderChats(chats)
        }
    }

    /** Строит карточку со строками чатов либо показывает пустое состояние. */
    private fun renderChats(chats: List<Chat>) {
        val empty = chats.isEmpty()
        binding.contentContainer.visibility = if (empty) View.GONE else View.VISIBLE
        binding.emptyContainer.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) return

        binding.chatsCard.removeAllViews()
        rowState.clear()

        chats.forEachIndexed { index, chat ->
            rowState[chat.id] = chat.resilientMedia
            val row = layoutInflater.inflate(R.layout.item_chat_resilient, binding.chatsCard, false)
            bindRow(row, chat)
            binding.chatsCard.addView(row)

            // Разделитель между строками, выровненный под текст (аватар 36dp + отступы).
            if (index < chats.lastIndex) binding.chatsCard.addView(makeDivider())
        }
    }

    private fun bindRow(row: View, chat: Chat) {
        val name = row.findViewById<TextView>(R.id.tv_chat_name)
        val initial = row.findViewById<TextView>(R.id.tv_avatar_initial)
        val avatar = row.findViewById<ShapeableImageView>(R.id.iv_avatar)
        val switch = row.findViewById<SwitchCompat>(R.id.switch_resilient)

        val title = chat.displayName()
        name.text = title

        // Аватар — тем же способом, что и в списке чатов (ChatsAdapter): есть фото —
        // показываем его, иначе инициал на тёмной заглушке.
        val avatarBase64 = if (chat.isGroup) chat.displayAvatarBase64() else chat.partnerAvatarBase64
        val bitmap = AvatarUtils.fromBase64(avatarBase64)
        if (bitmap != null) {
            avatar.setImageBitmap(bitmap)
            avatar.visibility = View.VISIBLE
            initial.visibility = View.GONE
        } else {
            avatar.visibility = View.GONE
            initial.visibility = View.VISIBLE
            initial.text = title.trim().firstOrNull()?.uppercase() ?: "?"
        }

        switch.isChecked = rowState[chat.id] ?: chat.resilientMedia
        // Тап по ВСЕЙ строке (сам тумблер некликабелен, см. item_chat_resilient.xml).
        row.setOnClickListener { toggle(chat, switch) }
    }

    /**
     * Переключает режим для чата. Тумблер двигается СРАЗУ (оптимистично, §1.5), запись в
     * БД идёт следом в фоне; если запись не удалась — возвращаем тумблер назад и говорим
     * об этом, а не оставляем UI врать о несохранённом состоянии.
     */
    private fun toggle(chat: Chat, switch: SwitchCompat) {
        val next = !(rowState[chat.id] ?: chat.resilientMedia)
        rowState[chat.id] = next
        switch.isChecked = next

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.get(this@ChatsSettingsActivity).chatDao()
                        .updateResilientMedia(chat.id, next)
                }.isSuccess
            }
            if (isDestroyed) return@launch
            if (!ok) {
                val reverted = !next
                rowState[chat.id] = reverted
                switch.isChecked = reverted
                android.widget.Toast.makeText(
                    this@ChatsSettingsActivity, R.string.chats_settings_save_error,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** Разделитель 1dp между строками, с отступом слева под текст (DESIGN.md §4.5). */
    private fun makeDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        ).apply {
            marginStart = (66 * resources.displayMetrics.density).toInt()
        }
        setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.border))
    }
}
