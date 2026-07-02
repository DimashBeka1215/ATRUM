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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.nostr.NostrRelayPool
import com.atrum.chat.transport.BluetoothTransport
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
    // Foreground-стримы профилей: мгновенное обновление аватара/ника партнёра в списке.
    private val profileWatches = java.util.concurrent.ConcurrentHashMap<Long, AutoCloseable>()
    private val profileBusy = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
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

        // Подписанный список реле (additive): мгновенно поднимаем сохранённый из стора.
        // Сетевой дозапрос — принудительно в фоне при каждом открытии (см. triggerRelayRefresh
        // в onResume; сам метод троттлится в транспорте, чтобы не долбить реле).
        RelayListStore.ensureLoaded(applicationContext)
        NostrTransport.extraRelays = RelayListStore.extraRelays(applicationContext)

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
                            chatId = "favorites",
                            transportToken = "",
                            chatPassword = "",
                            partnerName = localizedName,
                            isFavorites = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Принудительный фоновый дозапрос подписанного списка реле. Вызывается при каждом
     * показе списка чатов; сам запрос троттлится в транспорте (не чаще раза в 10 мин),
     * поэтому частые открытия реле не перегружают. Не блокирует UI, без отдельного цикла.
     */
    private fun triggerRelayRefresh() {
        if (!RelayListStore.publisherConfigured()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var t = 0
            while (t < 12 && TorManager.status.value != TorManager.TorStatus.READY) {
                kotlinx.coroutines.delay(1000); t++
            }
            val useTor = TorManager.status.value == TorManager.TorStatus.READY
            runCatching { NostrTransport.refreshRelayList(applicationContext, useTor) }
        }
    }

    override fun onResume() {
        super.onResume()
        RootWarning.maybeShow(this)
        triggerRelayRefresh()
        // Заранее поднимаем Tor и прогреваем соединения с реле, ПОКА пользователь в списке:
        // к моменту открытия чата сеть уже готова (Tor забутстрапился, TLS-рукопожатия к
        // реле выполнены), и свежие сообщения приходят сразу — без холодного старта Tor
        // (10–40 c) при первом открытии чата. Делаем только если есть сетевой чат
        // (не только «Избранное»), чтобы зря не поднимать Tor.
        lifecycleScope.launch {
            val hasNetChat = withContext(Dispatchers.IO) { db.chatDao().getAll().any { !it.isFavorites } }
            if (hasNetChat) {
                TorManager.start(this@ChatsListActivity)                 // идемпотентно
                NostrRelayPool.prewarm(NostrTransport.RELAYS)            // греет сокеты, когда Tor поднимется
            }
        }
        refreshMyAvatar()
        startUnreadPolling()
        startProfileWatches()
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
                        prefs.deleteChatSecrets(chat.chatId)
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
        stopProfileWatches()
    }

    /** Foreground-стрим профилей: мгновенно ловит смену аватара/ника партнёра. */
    private fun startProfileWatches() {
        lifecycleScope.launch {
            val myUserId = prefs.myUserId
            for (chat in withContext(Dispatchers.IO) { db.chatDao().getAll() }) {
                if (chat.isFavorites) continue
                if (profileWatches.containsKey(chat.id)) continue
                try {
                    val token = prefs.getChatToken(chat.chatId).takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.transportToken
                    val password = prefs.getChatPassword(chat.chatId).takeIf { it.isNotEmpty() }
                        ?: @Suppress("DEPRECATION") chat.chatPassword
                    val api = TransportFactory.forChat(applicationContext, chat.chatId, token, password, myUserId)
                    val chatId = chat.id
                    profileWatches[chatId] = api.watchProfiles { content ->
                        onProfileStream(chatId, api, password, content)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopProfileWatches() {
        profileWatches.values.forEach { runCatching { it.close() } }
        profileWatches.clear()
    }

    /** Слот профиля партнёра из стрима → расшифровка + обновление БД (→ Flow → UI). */
    private fun onProfileStream(chatId: Long, api: com.atrum.chat.transport.ChatTransport, password: String, content: String) {
        // Защита от наложения дорогих Argon2-расшифровок одного чата.
        if (profileBusy.putIfAbsent(chatId, true) != null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val parsed = ProfileSync.parseProfiles(content, password, api.chatId)
                if (parsed.isNotEmpty()) {
                    val all = ProfileSync.unionAndRemember(api.chatId, parsed)
                    val partner = ProfileSync.findPartner(all, prefs.myUserId, prefs.myName)
                    val fresh = db.chatDao().getById(chatId)
                    if (partner != null && fresh != null && partner.name.isNotBlank() &&
                        (partner.name != fresh.partnerName || partner.tag != fresh.partnerTag ||
                            partner.avatarBase64 != fresh.partnerAvatarBase64)) {
                        db.chatDao().updatePartnerProfile(chatId, partner.name, partner.tag, partner.avatarBase64)
                    }
                }
            } catch (_: Exception) {} finally {
                profileBusy.remove(chatId)
            }
        }
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
                val chatToken = prefs.getChatToken(chat.chatId)
                    .takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.transportToken
                val chatPassword = prefs.getChatPassword(chat.chatId)
                    .takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword
                val api = TransportFactory.forChat(applicationContext, chat.chatId, chatToken, chatPassword, myUserId)
                // Один запрос на всё (chat + profiles) — надёжнее и легче для реле, чем
                // loadContent + отдельный pull profiles.txt. Аватар/профиль обновляются в
                // том же снапшоте, что и сообщения → авто-обновление аватара в списке.
                val all = withContext(Dispatchers.IO) { api.loadAll() }
                val content = all.chatContent
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val totalLines = lines.size

                // В параллель: подтянуть профиль собеседника
                val partnerEphPub = withContext(Dispatchers.IO) {
                    // Фаза 1: union всех слотов profiles.txt (за флагом) — надёжный аватар/ник.
                    val parsedProfiles = if (ChatActivity.SLOT_UNION_PROFILES && all.profileSlots.isNotEmpty())
                        ProfileSync.unionProfileSlots(all.profileSlots, chatPassword, api.chatId)
                    else ProfileSync.parseProfiles(all.profilesContent, chatPassword, api.chatId)
                    // «Липкий» партнёр: флаки-чтение profiles.txt не теряет аву/ник.
                    val allProfiles = ProfileSync.unionAndRemember(api.chatId, parsedProfiles)
                    val partner = ProfileSync.findPartner(allProfiles, myUserId, myName)
                    var ephPub: String? = chat.partnerEphemeralPubKeyB64
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
                        // СОХРАНЯЕМ эфемерный ключ партнёра — чтобы фон (список/пуши) строил
                        // сессионный ключ и расшифровывал FS-сообщения БЕЗ захода в чат.
                        if (!partner.ephemeralPubKey.isNullOrBlank() &&
                            partner.ephemeralPubKey != chat.partnerEphemeralPubKeyB64) {
                            db.chatDao().updatePartnerEphemeralKey(chat.id, partner.ephemeralPubKey)
                        }
                        if (!partner.ephemeralPubKey.isNullOrBlank()) ephPub = partner.ephemeralPubKey
                    }
                    ephPub
                }

                // FS: устанавливаем сессионный ключ, чтобы список мог расшифровать V4-S
                // сообщения собеседника для подсчёта непрочитанных и превью.
                CryptoHelper.ensureSessionKey(chat.chatId, prefs.getEphemeralPriv(chat.chatId), partnerEphPub)

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
                    val decrypted = CryptoHelper.decrypt(line, chatPassword, chat.chatId)
                        ?: return@count false
                    val parsed = Message.fromDecrypted(decrypted, myUserId, myName, aliases)
                    !parsed.isSelf && parsed.sender.isNotEmpty()
                }

                if (unreadFromOthers != chat.unreadCount) {
                    db.chatDao().updateUnread(chat.id, unreadFromOthers)
                }

                // Превью последнего сообщения — обновим заодно
                val lastDecrypted = CryptoHelper.decrypt(lines.last(), chatPassword, chat.chatId)
                if (lastDecrypted != null) {
                    val parsed = Message.fromDecrypted(lastDecrypted, myUserId, myName, aliases)
                    val previewBody = when {
                        parsed.isImage && parsed.text.isBlank() -> getString(R.string.msg_preview_photo)
                        parsed.isImage -> "${getString(R.string.msg_preview_photo)} ${parsed.text}"
                        parsed.isVoice -> getString(R.string.msg_preview_voice)
                        parsed.isSticker -> getString(R.string.msg_preview_sticker)
                        parsed.isReply -> getString(R.string.msg_preview_reply_format, parsed.text)
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

                // BT-чат — локальный по Bluetooth: присоединение по приглашению невозможно,
                // пункт «Поделиться приглашением» не показываем вовсе.
                val isBtChat = prefs.getChatToken(chat.chatId) == BluetoothTransport.BT_TOKEN
                if (!chat.isFavorites && !isBtChat) {
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
                }
                if (!chat.isFavorites) {
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

        val dialog = AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
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
            val token = prefs.getChatToken(chat.chatId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.transportToken
            val password = prefs.getChatPassword(chat.chatId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.chatPassword

            val invite = InviteCodec.encode(
                channelId = chat.chatId,
                transportToken = token,
                chatPassword = password,
                pin = pin
            )

            // Открываем экран QR-приглашения: там выбор «поделиться QR» или «текстом».
            startActivity(Intent(this, InviteQrActivity::class.java).apply {
                putExtra(InviteQrActivity.EXTRA_INVITE, invite)
                putExtra(InviteQrActivity.EXTRA_PIN, pin)
                putExtra(InviteQrActivity.EXTRA_NAME, chat.partnerName)
                putExtra(InviteQrActivity.EXTRA_AVATAR, prefs.myAvatarBase64)
            })
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
                    prefs.deleteChatSecrets(chat.chatId)
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
