package com.atrum.chat

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.nostr.NostrRelayPool
import com.atrum.chat.transport.NostrTransport
import com.atrum.chat.transport.TransportFactory
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityChatsListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatsListActivity : SecureActivity() {

    private lateinit var binding: ActivityChatsListBinding
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private val adapter = ChatsAdapter(
        onClick = { chat -> openChat(chat) },
        onLongClick = { chat -> showChatMenu(chat) }
    )

    /** Полный список чатов из базы — для фильтрации при поиске */
    private var allChats: List<Chat> = emptyList()
    private var isSearchActive = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkDotAnimator: ObjectAnimator? = null
    private var isConnected = true

    /** Раз в N миллисекунд опрашиваем все чаты на новые сообщения. */
    private val unreadPollMs = 8_000L
    private var unreadPollJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatsListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = AppDatabase.get(this)

        // Статус подъёма встроенного Tor — баннер «Подключение к Tor…»
        lifecycleScope.launch {
            TorManager.status.collect { st -> updateTorBanner(st) }
        }

        // Проверка обязательного обновления (молча если нет сети)
        lifecycleScope.launch {
            val update = ForceUpdateChecker.check(this@ChatsListActivity)
            if (update != null) {
                ForceUpdateChecker.showBlockingDialog(this@ChatsListActivity, update)
                return@launch  // не показываем опциональный диалог поверх блокирующего
            }
            // Опциональное обновление — открываем полноэкранный экран
            val release = ForceUpdateChecker.checkLatestRelease(this@ChatsListActivity)
            if (release != null) {
                UpdateActivity.startWithRelease(this@ChatsListActivity, release)
            }
        }

        binding.rvChats.layoutManager = LinearLayoutManager(this)
        binding.rvChats.adapter = adapter

        binding.fabNewChat.setOnClickListener {
            startActivity(Intent(this, CreateChatActivity::class.java))
        }

        binding.myAvatarContainer.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.tvHeaderTitle.setOnClickListener { toggleSearch(true) }

        binding.btnSearch.setOnClickListener {
            toggleSearch(true)
        }

        binding.btnSearchCancel.setOnClickListener {
            toggleSearch(false)
        }

        binding.etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applySearchFilter(s?.toString() ?: "")
            }
        })

        ensureFavoritesChat()
        observeChats()
        setupNetworkMonitoring()

    }

    private fun ensureFavoritesChat() {
        lifecycleScope.launch {
            val exists = withContext(Dispatchers.IO) {
                db.chatDao().getFavoritesChat()
            }
            if (exists == null) {
                val localizedName = getString(R.string.favorites_name)
                withContext(Dispatchers.IO) {
                    db.chatDao().insert(
                        Chat(
                            gistId = "favorites",
                            gistToken = "",
                            chatPassword = "",
                            partnerName = localizedName,
                            isFavorites = true
                        )
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Прогреваем соединения с Nostr-реле заранее: пока пользователь в списке
        // чатов, TLS-рукопожатия уже выполнены — открытие чата и подключение по
        // приглашению происходят без задержки на установку соединения.
        if (TorManager.status.value != TorManager.TorStatus.IDLE) NostrRelayPool.prewarm(NostrTransport.RELAYS)
        refreshMyAvatar()
        startUnreadPolling()
        cleanupExpiredChats()
    }

    /** Обновляет баннер статуса Tor (подключение / подключено / недоступно). */
    private fun updateTorBanner(status: TorManager.TorStatus) {
        val banner = binding.torStatusBanner
        when (status) {
            TorManager.TorStatus.IDLE -> banner.visibility = View.GONE
            TorManager.TorStatus.CONNECTING -> {
                banner.visibility = View.VISIBLE
                binding.torSpinner.visibility = View.VISIBLE
                binding.torIcon.visibility = View.GONE
                binding.torStatusText.setText(R.string.tor_connecting)
                binding.torStatusText.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
            TorManager.TorStatus.READY -> {
                banner.visibility = View.VISIBLE
                binding.torSpinner.visibility = View.GONE
                binding.torIcon.visibility = View.VISIBLE
                binding.torIcon.setImageResource(R.drawable.ic_shield_check)
                binding.torIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent))
                binding.torStatusText.setText(R.string.tor_connected)
                binding.torStatusText.setTextColor(ContextCompat.getColor(this, R.color.accent))
                // Авто-скрытие через 1.5 с после готовности.
                banner.postDelayed({ banner.visibility = View.GONE }, 1500L)
            }
            TorManager.TorStatus.FAILED -> {
                banner.visibility = View.VISIBLE
                binding.torSpinner.visibility = View.GONE
                binding.torIcon.visibility = View.VISIBLE
                binding.torIcon.setImageResource(R.drawable.ic_warning)
                binding.torIcon.imageTintList =
                    ColorStateList.valueOf(ContextCompat.getColor(this, R.color.error))
                binding.torStatusText.setText(R.string.tor_failed)
                binding.torStatusText.setTextColor(ContextCompat.getColor(this, R.color.error))
            }
        }
    }

    /**
     * Удаляет чаты у которых истёк срок жизни (expiresAtMs < now). Пытается также
     * удалить gist из GitHub чтобы не оставлять мусор. Всё в фоне, без блокировки UI.
     * Если удалить gist не получилось — локальная запись всё равно стирается.
     */
    private fun cleanupExpiredChats() {
        lifecycleScope.launch {
            try {
                val expired = withContext(Dispatchers.IO) {
                    db.chatDao().getExpired(System.currentTimeMillis())
                }
                if (expired.isEmpty()) return@launch

                withContext(Dispatchers.IO) {
                    for (chat in expired) {
                        // Nostr/DHT: серверного gist нет — только локальная очистка секретов
                        prefs.deleteChatSecrets(chat.gistId)
                        db.chatDao().delete(chat)
                    }
                }
                android.widget.Toast.makeText(
                    this@ChatsListActivity,
                    R.string.chat_expired_toast,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Throwable) {
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopNetworkDotPulse()
        networkCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        unreadPollJob?.cancel()
        unreadPollJob = null
    }

    private fun refreshMyAvatar() {
        binding.tvMyInitial.text = prefs.myName.trim().firstOrNull()?.uppercase() ?: "?"
        val avatar = AvatarUtils.fromBase64(prefs.myAvatarBase64)
        if (avatar != null) {
            binding.ivMyAvatar.setImageBitmap(avatar)
            binding.ivMyAvatar.visibility = View.VISIBLE
            binding.tvMyInitial.visibility = View.GONE
        } else {
            binding.ivMyAvatar.visibility = View.GONE
            binding.tvMyInitial.visibility = View.VISIBLE
        }
    }

    private fun observeChats() {
        lifecycleScope.launch {
            db.chatDao().observeAll().collectLatest { list ->
                allChats = list
                applySearchFilter(binding.etSearch.text?.toString() ?: "")
            }
        }
    }

    private fun applySearchFilter(query: String) {
        adapter.submitFiltered(allChats, query)
        val filtered = if (query.isBlank()) allChats
                       else allChats.filter { it.partnerName.contains(query.trim(), ignoreCase = true) }
        val noChats = allChats.isEmpty() && !isSearchActive
        val noResults = isSearchActive && filtered.isEmpty()
        binding.emptyState.visibility = if (noChats) View.VISIBLE else View.GONE
        binding.tvSearchEmpty.visibility = if (noResults) View.VISIBLE else View.GONE
        binding.rvChats.visibility = if (noChats || noResults) View.GONE else View.VISIBLE
    }

    private fun toggleSearch(active: Boolean) {
        isSearchActive = active
        // Показываем пилюлю поиска — прячем заголовок и кнопку лупы
        binding.tvHeaderTitle.visibility = if (active) View.GONE else View.VISIBLE
        binding.btnSearch.visibility = if (active) View.GONE else View.VISIBLE
        binding.pillSearch.visibility = if (active) View.VISIBLE else View.GONE
        binding.btnSearchCancel.visibility = if (active) View.VISIBLE else View.GONE
        if (active) {
            binding.etSearch.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.etSearch, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.etSearch.text?.clear()
            binding.etSearch.clearFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
            applySearchFilter("")
        }
    }

    // ── Сеть ────────────────────────────────────────────────────────────────

    private fun setupNetworkMonitoring() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Проверяем начальное состояние
        val active = cm.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null
        isConnected = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        showNetworkState(isConnected)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isConnected = true
                runOnUiThread { showNetworkState(true) }
            }
            override fun onLost(network: Network) {
                isConnected = false
                runOnUiThread { showNetworkState(false) }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private fun showNetworkState(connected: Boolean) {
        if (connected) {
            // Восстанавливаем нормальное состояние (если не в поиске)
            stopNetworkDotPulse()
            binding.pillNetwork.visibility = View.GONE
            if (!isSearchActive) {
                binding.tvHeaderTitle.visibility = View.VISIBLE
                binding.btnSearch.visibility = View.VISIBLE
            }
        } else {
            // Показываем пилюлю сети — прячем всё остальное в заголовке
            toggleSearch(false)
            binding.tvHeaderTitle.visibility = View.GONE
            binding.btnSearch.visibility = View.GONE
            binding.pillNetwork.visibility = View.VISIBLE
            startNetworkDotPulse()
        }
    }

    private fun startNetworkDotPulse() {
        networkDotAnimator?.cancel()
        networkDotAnimator = ObjectAnimator.ofFloat(binding.vNetworkDot, "alpha", 0.2f, 1f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopNetworkDotPulse() {
        networkDotAnimator?.cancel()
        networkDotAnimator = null
        binding.vNetworkDot.alpha = 1f
    }

    /**
     * Фоновое обновление непрочитанных: каждые ~8 секунд опрашиваем все чаты,
     * считаем сколько новых строк после lastSeenLineCount, из них чужих,
     * сохраняем unreadCount в Room (UI обновится автоматически через Flow).
     */
    private fun startUnreadPolling() {
        unreadPollJob?.cancel()
        unreadPollJob = lifecycleScope.launch {
            // Первое обновление — сразу
            checkUnreadAllChats()
            while (true) {
                delay(unreadPollMs)
                checkUnreadAllChats()
            }
        }
    }

    private suspend fun checkUnreadAllChats() {
        val chats = withContext(Dispatchers.IO) { db.chatDao().getAll() }
        val myName = prefs.myName
        val myUserId = prefs.myUserId
        val aliases = prefs.nameHistory

        for (chat in chats) {
            if (chat.isFavorites) continue

            try {
                val chatToken = prefs.getChatToken(chat.gistId)
                    .takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.gistToken
                val chatPassword = prefs.getChatPassword(chat.gistId)
                    .takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword
                val api = TransportFactory.forChat(applicationContext, chat.gistId, chatToken, chatPassword, myUserId)
                val content = withContext(Dispatchers.IO) { api.loadContent() }
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val totalLines = lines.size

                // В параллель: подтянуть профиль собеседника
                withContext(Dispatchers.IO) {
                    val parsedProfiles = ProfileSync.pullProfiles(api, chatPassword)
                    // «Липкий» партнёр: флаки-чтение profiles.txt не теряет аву/ник.
                    val allProfiles = ProfileSync.unionAndRemember(api.chatId, parsedProfiles)
                    val partner = ProfileSync.findPartner(allProfiles, myUserId, myName)
                    if (partner != null) {
                        val profileChanged = partner.name != chat.partnerName ||
                                partner.avatarBase64 != chat.partnerAvatarBase64
                        if (profileChanged) {
                            db.chatDao().updatePartnerProfile(chat.id, partner.name, partner.tag, partner.avatarBase64)
                        }
                        if (partner.lastReadIndex != chat.partnerLastReadIndex) {
                            db.chatDao().updatePartnerLastRead(chat.id, partner.lastReadIndex)
                        }
                        // Обновляем флаг удалённого профиля — если поменялся
                        if (partner.deleted != chat.partnerDeleted) {
                            db.chatDao().updatePartnerDeleted(chat.id, partner.deleted)
                        }
                    }
                }

                if (totalLines <= chat.lastSeenLineCount) {
                    // Ничего нового — если unreadCount был не 0, сбросим
                    if (chat.unreadCount != 0) {
                        db.chatDao().updateUnread(chat.id, 0)
                    }
                    continue
                }

                // Новые строки = последние (totalLines - lastSeenLineCount)
                val newLines = lines.drop(chat.lastSeenLineCount)
                // Из новых считаем только чужие (свои не в счёт).
                // Используем Message.fromDecrypted чтобы корректно учесть новый формат
                // (timestamp префикс, reply-маркеры и т.п.).
                val unreadFromOthers = newLines.count { line ->
                    val decrypted = CryptoHelper.decrypt(line, chatPassword, chat.gistId)
                        ?: return@count false
                    val parsed = Message.fromDecrypted(decrypted, myUserId, myName, aliases)
                    !parsed.isSelf && parsed.sender.isNotEmpty()
                }

                if (unreadFromOthers != chat.unreadCount) {
                    db.chatDao().updateUnread(chat.id, unreadFromOthers)
                }

                // Превью последнего сообщения — обновим заодно
                val lastDecrypted = CryptoHelper.decrypt(lines.last(), chatPassword, chat.gistId)
                if (lastDecrypted != null) {
                    val parsed = Message.fromDecrypted(lastDecrypted, myUserId, myName, aliases)
                    val previewBody = when {
                        parsed.isImage && parsed.text.isBlank() -> "📷 Фото"
                        parsed.isImage -> "📷 ${parsed.text}"
                        parsed.isVoice -> getString(R.string.msg_preview_voice)
                        parsed.isSticker -> getString(R.string.msg_preview_sticker)
                        parsed.isReply -> "↪ ${parsed.text}"
                        else -> parsed.text
                    }
                    val preview = if (parsed.isSelf) "Вы: $previewBody" else previewBody
                    db.chatDao().updatePreview(
                        id = chat.id,
                        preview = preview.take(80),
                        timeMs = chat.lastTimeMs
                    )
                }
            } catch (e: Exception) {
                // Игнорируем ошибки в фоновом polling — следующий цикл попробует снова
            }
        }
    }

    private fun openChat(chat: Chat) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
        })
    }

    private fun showChatMenu(chat: Chat) {
        // Если чат уже занят (partnerJoined) — share превращается в disabled-пункт
        val shareLabel = if (chat.partnerJoined) {
            getString(R.string.invite_share_action_locked)
        } else {
            getString(R.string.invite_share_action)
        }
        val pinLabel = if (chat.isPinned) getString(R.string.action_unpin) else getString(R.string.action_pin)

        NeonDialog.showMenu(
            ctx = this,
            title = if (chat.isFavorites) getString(R.string.favorites_name) else chat.partnerName,
            items = mutableListOf<NeonDialog.Item>().apply {
                add(NeonDialog.Item(pinLabel) {
                    togglePin(chat)
                })

                if (!chat.isFavorites) {
                    add(NeonDialog.Item(shareLabel, isDisabled = chat.partnerJoined) {
                        if (chat.partnerJoined) {
                            android.widget.Toast.makeText(
                                this@ChatsListActivity, R.string.chat_locked_toast,
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            shareInvite(chat)
                        }
                    })
                    add(NeonDialog.Item(getString(R.string.action_delete_chat), isDestructive = true) {
                        confirmDelete(chat)
                    })
                }
            }
        )
    }

    private fun togglePin(chat: Chat) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.chatDao().updatePinned(chat.id, !chat.isPinned)
            }
        }
    }

    /**
     * Кодирует данные чата в invite-строку и открывает Android share sheet.
     * Получатель сможет вставить этот текст в JoinChatActivity.
     */
    /**
     * Показывает предупреждение перед шарингом invite-кода.
     *
     * ⚠️ ВАЖНО: invite содержит GitHub-токен И пароль чата в одной строке.
     * Перехват invite = полный доступ к переписке. Пользователь должен
     * осознанно передавать его только через доверенные каналы.
     */
    /**
     * Окно «PIN для приглашения». При КАЖДОМ шеринге пользователь задаёт отдельный
     * код, которым шифруется invite (InviteCodec.encode). Код-замок приложения при
     * этом НЕ раскрывается — у каждого приглашения свой одноразовый код, который
     * передаётся получателю отдельным каналом.
     */
    private fun shareInvite(chat: Chat) {
        val view = layoutInflater.inflate(R.layout.dialog_invite_pin, null)
        val etPin = view.findViewById<android.widget.EditText>(R.id.et_invite_pin)
        etPin.setText(generateInviteCode())
        etPin.setSelection(etPin.text.length)

        val dialog = AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<android.widget.ImageButton>(R.id.btn_regenerate).setOnClickListener {
            etPin.setText(generateInviteCode())
            etPin.setSelection(etPin.text.length)
        }
        view.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<android.widget.Button>(R.id.btn_share).setOnClickListener {
            val pin = etPin.text.toString().trim()
            if (pin.isBlank()) {
                android.widget.Toast.makeText(
                    this, R.string.invite_pin_empty, android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            doShareInvite(chat, pin)
        }
        dialog.show()
    }

    /** Случайный код приглашения: 6 символов без неоднозначных (0/O/1/I/L). */
    private fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        return (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun doShareInvite(chat: Chat, pin: String) {
        try {
            val token = prefs.getChatToken(chat.gistId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.gistToken
            val password = prefs.getChatPassword(chat.gistId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.chatPassword

            val invite = InviteCodec.encode(
                gistId = chat.gistId,
                gistToken = token,
                chatPassword = password,
                pin = pin
            )

            val text = getString(R.string.invite_share_text_fmt, invite)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.invite_share_title)))
        } catch (e: Throwable) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.invite_create_failed),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            e.printStackTrace()
        }
    }

    override fun onBackPressed() {
        if (isSearchActive) {
            toggleSearch(false)
        } else {
            super.onBackPressed()
        }
    }

    private fun confirmDelete(chat: Chat) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.action_delete_chat),
            message = getString(R.string.confirm_delete_chat_gist),
            positiveText = getString(R.string.yes),
            positiveIsDestructive = true,
            negativeText = getString(R.string.no)
        ) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    // Nostr/DHT: серверного gist нет — только локальная очистка секретов
                    prefs.deleteChatSecrets(chat.gistId)
                    db.chatDao().delete(chat)
                }
                android.widget.Toast.makeText(
                    this@ChatsListActivity,
                    R.string.chat_deleted_toast,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
