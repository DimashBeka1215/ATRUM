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
import com.atrum.chat.data.displayName
import com.atrum.chat.databinding.ActivityChatsListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
    // Foreground-стрим СООБЩЕНИЙ: реле само пушит новое kind:1 (watchMessages уже смёржил
    // его в NostrMessageStore) → мгновенный пересчёт непрочитанных/превью из локального
    // стора, без ожидания 8-секундного опроса. Тот же приём делает мгновенными открытый чат
    // и пуш-сервис; работает одинаково для 1:1 и бесед.
    private val messageWatches = java.util.concurrent.ConcurrentHashMap<Long, AutoCloseable>()
    private val messageBusy = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
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
            // Системный чат «Уведомления» — создаём сразу при старте (тем же приёмом,
            // что и «Избранное» выше), не дожидаясь первого события модерации: пользователь
            // видит, куда будут приходить новости (см. SystemNotifications).
            withContext(Dispatchers.IO) {
                runCatching { SystemNotifications.ensureChat(applicationContext) }
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
                    // Стрим сообщений с САМОЛЕЧЕНИЕМ (fastReopen): реле само пушит новое kind:1
                    // (уже смёржено в NostrMessageStore) → пересчёт непрочитанных/превью на
                    // месте. Принудительное переоткрытие подписки переживает «тихую смерть»
                    // подписки на реле, поэтому МИНИМАЛЬНАЯ задержка не только на первом, но и
                    // на каждом последующем сообщении (репорт: «первое быстро, остальные ~30с»).
                    if (!messageWatches.containsKey(chatId)) {
                        messageWatches[chatId] = api.watchMessages(fastReopen = true) {
                            onMessageStream(chatId, api, password)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun stopProfileWatches() {
        profileWatches.values.forEach { runCatching { it.close() } }
        profileWatches.clear()
        messageWatches.values.forEach { runCatching { it.close() } }
        messageWatches.clear()
    }

    /** Слот профиля партнёра из стрима → расшифровка + обновление БД (→ Flow → UI). */
    private fun onProfileStream(chatId: Long, api: com.atrum.chat.transport.ChatTransport, password: String, content: String) {
        // Защита от наложения дорогих Argon2-расшифровок одного чата.
        if (profileBusy.putIfAbsent(chatId, true) != null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val fresh = db.chatDao().getById(chatId)
                // Групповой чат: у него нет одного "партнёра" — findPartner() ниже
                // подобрал бы СЛУЧАЙНОГО участника и затёр бы partnerName/avatarBase64
                // его личными данными (это и было причиной бага "не подхватывается
                // переименование группы в списке чатов" — см. ChatsAdapter.displayName()
                // и §16 CLAUDE.md). Для групп имя/аватар идут только через members.txt
                // (см. MembersSync/ChatDao.updateGroupProfile), тут делать нечего.
                if (fresh?.isGroup == true) return@launch
                val parsed = ProfileSync.parseProfiles(content, password, api.chatId)
                if (parsed.isNotEmpty()) {
                    val all = ProfileSync.unionAndRemember(api.chatId, parsed)
                    val partner = ProfileSync.findPartner(all, prefs.myUserId, prefs.myName)
                    if (partner != null && fresh != null && partner.name.isNotBlank()) {
                        // ⚠️ Не затираем аву/тег ПУСТЫМ значением (presence/частичное чтение
                        // партнёра приходит без avatar → иначе ава пропадала у собеседника,
                        // когда я оффлайн). Пустое поле = «нет обновления», как в ChatActivity.
                        val nameToSave = partner.name
                        val tagToSave = if (!partner.tag.isNullOrBlank()) partner.tag else fresh.partnerTag
                        val avatarToSave = if (!partner.avatarBase64.isNullOrBlank()) partner.avatarBase64 else fresh.partnerAvatarBase64
                        if (nameToSave != fresh.partnerName || tagToSave != fresh.partnerTag ||
                            avatarToSave != fresh.partnerAvatarBase64) {
                            db.chatDao().updatePartnerProfile(chatId, nameToSave, tagToSave, avatarToSave)
                        }
                    }
                }
            } catch (_: Exception) {} finally {
                profileBusy.remove(chatId)
            }
        }
    }

    /**
     * Реле прислало новое сообщение (watchMessages уже смёржил его в NostrMessageStore) →
     * мгновенный пересчёт непрочитанных и превью ЭТОГО чата из ЛОКАЛЬНОГО стора, без сети и
     * без ожидания 8-секундного опроса (§1.5). «Реле само говорит приложению» — тот же
     * механизм, что делает мгновенным открытый чат и пуш-сервис; работает и для бесед.
     * Пишем в Room → Flow сам перерисует строку списка.
     */
    private fun onMessageStream(chatId: Long, api: com.atrum.chat.transport.ChatTransport, password: String) {
        if (messageBusy.putIfAbsent(chatId, true) != null) return
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                delay(60) // склеиваем всплеск событий в один пересчёт (коротко — ради скорости)
                val chat = db.chatDao().getById(chatId) ?: return@launch
                if (chat.isFavorites) return@launch
                // 1:1: сообщения — forward-secrecy (V4-S). Локальный пересчёт по возможно
                // устаревшему сессионному ключу их НЕ расшифрует — нужен СВЕЖИЙ эфемерный ключ
                // собеседника из profiles.txt. Делаем точечный сетевой рефреш (профиль→ключ→
                // сессия→непрочитанные), тот же путь, что опрос, но СРАЗУ на приход сообщения,
                // а не через 8с. Беседы (общий пароль, без FS) считаются локально ниже — мгновенно.
                if (!chat.isGroup) {
                    runCatching { checkUnreadForChat(chat, prefs.myName, prefs.myUserId, prefs.nameHistory) }
                    return@launch
                }
                val myName = prefs.myName
                val myUserId = prefs.myUserId
                val aliases = prefs.nameHistory
                val myTag = prefs.myTag
                // FS: для 1:1 нужен сессионный ключ (V4-S) собеседника — ставим, если ещё нет.
                if (!CryptoHelper.hasSessionKey(chat.chatId)) {
                    CryptoHelper.ensureSessionKey(
                        chat.chatId, prefs.getEphemeralPriv(chat.chatId), chat.partnerEphemeralPubKeyB64
                    )
                }
                val content = com.atrum.chat.transport.NostrMessageStore.render(api.chatId)
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                // Непрочитанные — только чужие среди новых строк (после lastSeenLineCount).
                // Заодно собираем @упоминания меня (в беседе) → бейдж «@N» в списке.
                val mentionIds = ArrayList<String>()
                val unread = if (lines.size <= chat.lastSeenLineCount) 0 else {
                    var cnt = 0
                    for (line in lines.drop(chat.lastSeenLineCount)) {
                        val dec = CryptoHelper.decrypt(line, password, chat.chatId) ?: continue
                        val parsed = Message.fromDecrypted(dec, myUserId, myName, aliases)
                        if (MentionUtil.ENABLED && chat.isGroup && !parsed.isSelf &&
                            MentionUtil.mentionsMe(parsed.text, myTag, myName)) {
                            if (parsed.msgId.isNotBlank()) mentionIds.add(parsed.msgId)
                        }
                        if (!parsed.isSelf && parsed.sender.isNotEmpty()) cnt++
                    }
                    cnt
                }
                if (unread != chat.unreadCount) db.chatDao().updateUnread(chat.id, unread)
                // Непрочитанные @упоминания → бейдж «@N» (см. ChatsAdapter). Пишем только при
                // реальном изменении, чтобы не дёргать Flow списка лишний раз.
                if (MentionUtil.ENABLED && chat.isGroup) {
                    val csv = mentionIds.joinToString(",").ifEmpty { null }
                    if (csv != chat.mentionMsgIds) db.chatDao().updateMentionMsgIds(chat.id, csv)
                }
                // Превью последнего сообщения.
                if (lines.isNotEmpty()) {
                    val lastDec = CryptoHelper.decrypt(lines.last(), password, chat.chatId)
                    if (lastDec != null) {
                        val pm = Message.fromDecrypted(lastDec, myUserId, myName, aliases)
                        val body = when {
                            pm.isImage && pm.text.isBlank() -> getString(R.string.msg_preview_photo)
                            pm.isImage -> "${getString(R.string.msg_preview_photo)} ${pm.text}"
                            pm.isVoice -> getString(R.string.msg_preview_voice)
                            pm.isSticker -> getString(R.string.msg_preview_sticker)
                            pm.isReply -> getString(R.string.msg_preview_reply_format, pm.text)
                            else -> pm.text
                        }
                        val preview = (if (pm.isSelf) "Вы: $body" else body).take(80)
                        db.chatDao().updatePreview(chat.id, preview, chat.lastTimeMs)
                    }
                }
            } catch (_: Exception) {} finally {
                messageBusy.remove(chatId)
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
                // Прячем группы, где Я забанен: бан больше не удаляет чат (чтобы разбан
                // остался наблюдаемым, см. MembersSync/ChatActivity.checkSelfBanned), а
                // просто скрывает его из списка по флагу participant.banned. Пересчёт на
                // каждый re-emit Flow; applyIncoming бампает membersVersion и на бане, и на
                // разбане → чат исчезает/возвращается сам, без ручного обновления.
                val myUserId = prefs.myUserId
                // Верифицированный разработчик неприкосновенен (PERSONAL_BUILD.md §Часть 3):
                // его беседы не прячутся из списка, даже если members.txt пометил его banned.
                val meImmune = VerifiedBadge.isVerifiedSelf(prefs.myIdentityPubKey)
                val visible = withContext(Dispatchers.IO) {
                    list.filter { c ->
                        !c.isGroup || meImmune || db.chatParticipantDao().getOne(c.id, myUserId)?.banned != true
                    }
                }
                allChats = visible
                applySearchFilter(binding.etSearch.text?.toString() ?: "")
            }
        }
    }

    private fun applySearchFilter(query: String) {
        adapter.submitFiltered(allChats, query)
        val filtered = if (query.isBlank()) allChats
                       else allChats.filter { it.displayName().contains(query.trim(), ignoreCase = true) }
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

    /**
     * Ограничитель одновременных сетевых опросов чатов. Обход стал ПАРАЛЛЕЛЬНЫМ (см.
     * checkUnreadAllChats), чтобы один тяжёлый чат (беседа: расшифровка слотов, members.txt,
     * ростер) не задерживал обновление бейджа у остальных — раньше последовательный цикл
     * растягивал эффективный интервал каждого чата на сумму времени ВСЕХ, и бейдж беседы
     * появлялся с задержкой в десятки секунд (репорт). Потолок 6 — чтобы не устроить шторм
     * запросов к реле при большом числе чатов (union-чтение всё равно мультиплексируется
     * по subId на общих сокетах, см. NostrRelayPool).
     */
    private val unreadPollSemaphore = Semaphore(6)

    private suspend fun checkUnreadAllChats() = coroutineScope {
        val chats = withContext(Dispatchers.IO) { db.chatDao().getAll() }
        val myName = prefs.myName
        val myUserId = prefs.myUserId
        val aliases = prefs.nameHistory

        // Каждый чат опрашивается СВОЕЙ корутиной под общим потолком — независимо, чтобы
        // тяжёлая беседа не блокировала обновление бейджа у 1:1 и наоборот (§1.5).
        chats.filter { !it.isFavorites }.map { chat ->
            launch {
                unreadPollSemaphore.withPermit {
                    runCatching { checkUnreadForChat(chat, myName, myUserId, aliases) }
                }
            }
        }.forEach { it.join() }
    }

    /**
     * Опрос одного чата: непрочитанные, превью, профиль партнёра (1:1) / members.txt+ростер
     * (беседа). ⚠️ ПОРЯДОК (фикс задержки бейджа): счётчик непрочитанных и превью пишутся
     * СРАЗУ после установки сессионного ключа — ДО тяжёлого группового блока (members.txt,
     * ростер), чтобы бейдж «N» появлялся мгновенно, а не ждал модерационной синхронизации.
     */
    private suspend fun checkUnreadForChat(
        chat: Chat,
        myName: String,
        myUserId: String,
        aliases: Set<String>
    ) {
        val chatToken = prefs.getChatToken(chat.chatId)
            .takeIf { it.isNotEmpty() }
            ?: @Suppress("DEPRECATION") chat.transportToken
        val chatPassword = prefs.getChatPassword(chat.chatId)
            .takeIf { it.isNotEmpty() }
            ?: @Suppress("DEPRECATION") chat.chatPassword
        // adminUserId — иначе NostrTransport.adminPubkeyHex всегда null и
        // MembersSync.applyIncoming ниже получает membersContent = "" (no-op) даже
        // для группы: счётчик участников/имя/аватар группы не обновлялись в списке.
        val api = TransportFactory.forChat(applicationContext, chat.chatId, chatToken, chatPassword, myUserId, chat.adminUserId)
        // Один запрос на всё (chat + profiles) — надёжнее и легче для реле, чем
        // loadContent + отдельный pull profiles.txt. Аватар/профиль обновляются в
        // том же снапшоте, что и сообщения → авто-обновление аватара в списке.
        val all = withContext(Dispatchers.IO) { api.loadAll() }
        val content = all.chatContent
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val totalLines = lines.size

        // ── 1:1: профиль собеседника (нужен для сессионного ключа) ──
        // ⚠️ ТОЛЬКО для 1:1 — у группового чата нет одного «партнёра». findPartner()
        // подобрал бы СЛУЧАЙНОГО участника и затёр бы partner*-поля чата (см. §16). Для
        // групп имя/аватар идут через members.txt/groupprofile, FS-сессия не используется.
        val partnerEphPub = if (chat.isGroup) null else withContext(Dispatchers.IO) {
            val parsedProfiles = if (ChatActivity.SLOT_UNION_PROFILES && all.profileSlots.isNotEmpty())
                ProfileSync.unionProfileSlots(all.profileSlots, chatPassword, api.chatId)
            else ProfileSync.parseProfiles(all.profilesContent, chatPassword, api.chatId)
            val allProfiles = ProfileSync.unionAndRemember(api.chatId, parsedProfiles)
            val partner = ProfileSync.findPartner(allProfiles, myUserId, myName)
            var ephPub: String? = chat.partnerEphemeralPubKeyB64
            if (partner != null) {
                // ⚠️ Пустое имя/ава/тег не затирают сохранённые (presence/частичное чтение
                // партнёра приходит без этих полей → иначе у собеседника пропадала ава,
                // когда я оффлайн). Пустое = «нет обновления», как в ChatActivity 2227.
                val nameToSave = if (partner.name.isNotBlank()) partner.name else chat.partnerName
                val tagToSave = if (!partner.tag.isNullOrBlank()) partner.tag else chat.partnerTag
                val avatarToSave = if (!partner.avatarBase64.isNullOrBlank()) partner.avatarBase64 else chat.partnerAvatarBase64
                val profileChanged = nameToSave != chat.partnerName ||
                        tagToSave != chat.partnerTag ||
                        avatarToSave != chat.partnerAvatarBase64
                if (profileChanged) {
                    db.chatDao().updatePartnerProfile(chat.id, nameToSave, tagToSave, avatarToSave)
                }
                if (partner.lastReadIndex != chat.partnerLastReadIndex) {
                    db.chatDao().updatePartnerLastRead(chat.id, partner.lastReadIndex)
                }
                if (partner.deleted != chat.partnerDeleted) {
                    db.chatDao().updatePartnerDeleted(chat.id, partner.deleted)
                }
                if (!partner.ephemeralPubKey.isNullOrBlank() &&
                    partner.ephemeralPubKey != chat.partnerEphemeralPubKeyB64) {
                    db.chatDao().updatePartnerEphemeralKey(chat.id, partner.ephemeralPubKey)
                }
                if (!partner.ephemeralPubKey.isNullOrBlank()) ephPub = partner.ephemeralPubKey
                // Галочка верификации у ника собеседника в списке. Неподделываемо: проверяем
                // подпись identity партнёра по крипто-домену chat.chatId (VerifiedBadge).
                // ⚠️ Обновляем ТОЛЬКО когда чтение реально содержит identity-данные
                // (identityPubKey != null). Presence/частичное чтение приходит без них, когда
                // я оффлайн — иначе галочка гасла у собеседника. Неподделываемость цела:
                // true ставится только по валидной подписи, а сброс — лишь при реальной
                // смене identity (поле присутствует, но подпись не сходится).
                if (partner.identityPubKey != null) {
                    val verified = VerifiedBadge.isVerifiedProfile(partner, chat.chatId)
                    if (verified != chat.partnerVerified) {
                        db.chatDao().updatePartnerVerified(chat.id, verified)
                    }
                }
            }
            ephPub
        }

        // FS: сессионный ключ, чтобы список мог расшифровать V4-S сообщения собеседника.
        CryptoHelper.ensureSessionKey(chat.chatId, prefs.getEphemeralPriv(chat.chatId), partnerEphPub)

        // ── Бейдж «N» и превью пишем СРАЗУ — ДО тяжёлого группового блока (фикс задержки) ──
        if (totalLines <= chat.lastSeenLineCount) {
            if (chat.unreadCount != 0) db.chatDao().updateUnread(chat.id, 0)
        } else {
            val newLines = lines.drop(chat.lastSeenLineCount)
            val myTag = prefs.myTag
            // Из новых считаем только чужие (свои не в счёт). Message.fromDecrypted —
            // чтобы учесть новый формат (timestamp-префикс, reply-маркеры и т.п.). Заодно
            // собираем @упоминания меня в беседе → бейдж «@N» в списке (см. ChatsAdapter).
            val mentionIds = ArrayList<String>()
            var unreadFromOthers = 0
            for (line in newLines) {
                val decrypted = CryptoHelper.decrypt(line, chatPassword, chat.chatId) ?: continue
                val parsed = Message.fromDecrypted(decrypted, myUserId, myName, aliases)
                if (MentionUtil.ENABLED && chat.isGroup && !parsed.isSelf &&
                    MentionUtil.mentionsMe(parsed.text, myTag, myName)) {
                    if (parsed.msgId.isNotBlank()) mentionIds.add(parsed.msgId)
                }
                if (!parsed.isSelf && parsed.sender.isNotEmpty()) unreadFromOthers++
            }
            if (unreadFromOthers != chat.unreadCount) {
                db.chatDao().updateUnread(chat.id, unreadFromOthers)
            }
            if (MentionUtil.ENABLED && chat.isGroup) {
                val csv = mentionIds.joinToString(",").ifEmpty { null }
                if (csv != chat.mentionMsgIds) db.chatDao().updateMentionMsgIds(chat.id, csv)
            }

            // Превью последнего сообщения — обновим заодно.
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
        }

        // ── Групповой блок (members.txt + децентрализованный ростер) — ПОСЛЕ бейджа ──
        // Тяжёлая часть (расшифровка слотов, проверки подписи) не задерживает бейдж выше и,
        // благодаря параллельному обходу чатов, не тормозит другие чаты (§16, ADR-001).
        if (chat.isGroup) {
            withContext(Dispatchers.IO) {
                runCatching {
                    GroupProfileSync.applyIncoming(
                        chat, all.groupProfileContent, chatPassword, db.chatDao(), prefs
                    )
                }
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    MembersSync.applyIncoming(
                        chat = chat,
                        membersContentEncrypted = all.membersContent,
                        password = chatPassword,
                        participantDao = db.chatParticipantDao(),
                        chatDao = db.chatDao(),
                        myUserId = myUserId,
                        appContext = applicationContext,
                        groupEventDao = db.groupEventDao(),
                        memberSlots = all.memberSlots,
                        pubkeyForUserId = api::pubkeyForUserId
                    )
                }
            }
            // Децентрализованный ростер (ADR-001): членство/счётчик — из самоопубликованных
            // профилей, БЕЗ зависимости от админа в сети. Строго ПОСЛЕ MembersSync-оверлея.
            withContext(Dispatchers.IO) {
                runCatching {
                    GroupRosterSync.applyProfileRoster(
                        chat = chat,
                        signedSlots = all.profileSlotsSigned,
                        password = chatPassword,
                        participantDao = db.chatParticipantDao(),
                        myUserId = myUserId,
                        adminUserId = chat.adminUserId,
                        pubkeyForUserId = api::pubkeyForUserId
                    )
                }
            }
            // ⚠️ Бан больше НЕ удаляет чат — забаненный остаётся в БД и опрашивается
            // (бан/разбан наблюдаемы), из списка прячется по флагу (см. observeChats).
        }
    }

    private fun openChat(chat: Chat) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chat.id)
        })
    }

    private fun showChatMenu(chat: Chat) {
        // Если чат уже занят (partnerJoined) — share превращается в disabled-пункт.
        // ⚠️ Только для 1:1 — partnerJoined рассчитан на ровно ОДНОГО собеседника.
        // Для групп это НЕ должно блокировать инвайт уже после второго участника:
        // лимит участников проверяется на джойне (см. JoinChatActivity, ADR-001),
        // а не гейтится здесь по факту "кто-то уже пришёл".
        val locked = !chat.isGroup && chat.partnerJoined
        val shareLabel = if (locked) {
            getString(R.string.invite_share_action_locked)
        } else {
            getString(R.string.invite_share_action)
        }
        val pinLabel = if (chat.isPinned) getString(R.string.action_unpin) else getString(R.string.action_pin)

        NeonDialog.showMenu(
            ctx = this,
            title = when {
                chat.isSystemNotifications -> getString(R.string.notif_chat_name)
                chat.isFavorites -> getString(R.string.favorites_name)
                else -> chat.displayName()
            },
            items = mutableListOf<NeonDialog.Item>().apply {
                add(NeonDialog.Item(pinLabel) {
                    togglePin(chat)
                })

                // BT-чат — локальный по Bluetooth: присоединение по приглашению невозможно,
                // пункт «Поделиться приглашением» не показываем вовсе.
                val isBtChat = prefs.getChatToken(chat.chatId) == BluetoothTransport.BT_TOKEN
                if (!chat.isFavorites && !isBtChat) {
                    add(NeonDialog.Item(shareLabel, isDisabled = locked) {
                        if (locked) {
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

        // Степпер срока действия приглашения — только для групп (см. ADR-001,
        // dialog_invite_pin.xml). Для 1:1 остаётся дефолт InviteCodec.DEFAULT_TTL_MS.
        val ttlRow = view.findViewById<View>(R.id.ttl_group_row)
        val tvTtlValue = view.findViewById<android.widget.TextView>(R.id.tv_ttl_value)
        val tvTtlSub = view.findViewById<android.widget.TextView>(R.id.tv_ttl_sub)
        val btnTtlMinus = view.findViewById<android.widget.ImageButton>(R.id.btn_ttl_minus)
        val btnTtlPlus = view.findViewById<android.widget.ImageButton>(R.id.btn_ttl_plus)
        // 1..24 = часы, 25 = "без ограничений" (см. TTL_STEP_INFINITE).
        var ttlStep = TTL_STEP_DEFAULT

        fun renderTtl() {
            val infinite = ttlStep >= TTL_STEP_INFINITE
            if (infinite) {
                tvTtlValue.text = getString(R.string.invite_ttl_unlimited)
                tvTtlValue.setTextColor(ContextCompat.getColor(this, R.color.warning))
                tvTtlSub.text = getString(R.string.invite_ttl_unlimited_sub)
                ttlRow.setBackgroundResource(R.drawable.bg_ttl_card_infinite)
            } else {
                tvTtlValue.text = resources.getQuantityString(R.plurals.invite_ttl_hours, ttlStep, ttlStep)
                tvTtlValue.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                val expiryAt = System.currentTimeMillis() + ttlStep * 3_600_000L
                val fmt = java.text.SimpleDateFormat("dd.MM.yy, HH:mm", java.util.Locale.getDefault())
                tvTtlSub.text = getString(R.string.invite_ttl_expiry_sub, fmt.format(java.util.Date(expiryAt)))
                ttlRow.setBackgroundResource(R.drawable.bg_ttl_card)
            }
            btnTtlMinus.isEnabled = ttlStep > 1
            btnTtlMinus.alpha = if (btnTtlMinus.isEnabled) 1f else 0.35f
            btnTtlPlus.isEnabled = ttlStep < TTL_STEP_INFINITE
            btnTtlPlus.alpha = if (btnTtlPlus.isEnabled) 1f else 0.35f
        }

        if (chat.isGroup) {
            ttlRow.visibility = View.VISIBLE
            renderTtl()
        } else {
            ttlRow.visibility = View.GONE
        }

        btnTtlMinus.setOnClickListener {
            if (ttlStep > 1) {
                ttlStep--
                renderTtl()
            }
        }
        btnTtlPlus.setOnClickListener {
            if (ttlStep < TTL_STEP_INFINITE) {
                ttlStep++
                renderTtl()
            }
        }

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
            val ttlMillis = if (ttlStep >= TTL_STEP_INFINITE) TTL_INFINITE_MS else ttlStep * 3_600_000L
            doShareInvite(chat, pin, ttlMillis)
        }
        dialog.show()
    }

    /** Случайный код приглашения: 6 символов без неоднозначных (0/O/1/I/L). */
    private fun generateInviteCode(): String {
        val alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val rnd = java.security.SecureRandom()
        return (1..6).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    private fun doShareInvite(chat: Chat, pin: String, groupTtlMillis: Long = InviteCodec.DEFAULT_TTL_MS) {
        try {
            val token = prefs.getChatToken(chat.chatId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.transportToken
            val password = prefs.getChatPassword(chat.chatId)
                .takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") chat.chatPassword

            // Групповой чат (ADR-001) — invite v4 несёт userId админа и лимит участников,
            // получателю нужны ДО первого опроса реле (проверка подписи members.txt).
            val invite = if (chat.isGroup) {
                val adminId = chat.adminUserId
                if (adminId.isNullOrBlank()) {
                    // Не должно случиться (adminUserId ставится при создании группы) —
                    // защитная проверка, чтобы не выпустить invite без админа.
                    android.widget.Toast.makeText(
                        this, getString(R.string.invite_create_failed), android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return
                }
                InviteCodec.encodeGroup(
                    channelId = chat.chatId,
                    transportToken = token,
                    chatPassword = password,
                    pin = pin,
                    adminUserId = adminId,
                    participantLimit = chat.participantLimit,
                    groupNameSeed = chat.groupName ?: chat.partnerName,
                    ttlMillis = groupTtlMillis,
                    // Вкладываем СВОЙ identity-ключ только если приглашает сам админ (Фаза 3) —
                    // тогда у получателя ключ закрепится авторитетно из инвайта (не TOFU).
                    adminIdentityPubKey = if (adminId == prefs.myUserId) prefs.myIdentityPubKey else null
                )
            } else {
                InviteCodec.encode(
                    channelId = chat.chatId,
                    transportToken = token,
                    chatPassword = password,
                    pin = pin
                )
            }

            // Открываем экран QR-приглашения: там выбор «поделиться QR» или «текстом».
            startActivity(Intent(this, InviteQrActivity::class.java).apply {
                putExtra(InviteQrActivity.EXTRA_INVITE, invite)
                putExtra(InviteQrActivity.EXTRA_PIN, pin)
                putExtra(InviteQrActivity.EXTRA_NAME, chat.groupName ?: chat.partnerName)
                putExtra(InviteQrActivity.EXTRA_AVATAR, if (chat.isGroup) chat.groupAvatarBase64 else prefs.myAvatarBase64)
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
                    // Децентрализованный ростер (ADR-001): выходя из беседы, публикуем свой
                    // профиль с left=true — тумбстоун, по которому остальные исключат меня из
                    // счётчика БЕЗ участия админа. Best-effort с коротким таймаутом, чтобы
                    // удаление не подвисало на медленной сети; секреты ещё на месте (нужны
                    // для публикации), удаляем их сразу после.
                    if (chat.isGroup) {
                        runCatching {
                            val token = prefs.getChatToken(chat.chatId).takeIf { it.isNotEmpty() }
                                ?: @Suppress("DEPRECATION") chat.transportToken
                            val password = prefs.getChatPassword(chat.chatId).takeIf { it.isNotEmpty() }
                                ?: @Suppress("DEPRECATION") chat.chatPassword
                            val api = TransportFactory.forChat(
                                applicationContext, chat.chatId, token, password,
                                prefs.myUserId, chat.adminUserId
                            )
                            val leftProfile = Profile(
                                userId = prefs.myUserId,
                                name = prefs.myName,
                                tag = prefs.myTag,
                                left = true
                            )
                            kotlinx.coroutines.withTimeoutOrNull(8_000L) {
                                ProfileSync.pushMyProfile(api, password, leftProfile)
                            }
                        }
                    }
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

    companion object {
        /** Степпер срока действия group-инвайта (dialog_invite_pin.xml): 1..24 часа шагом в час. */
        private const val TTL_STEP_DEFAULT = 24
        /** Значение шага, обозначающее "без ограничений" (см. shareInvite). */
        private const val TTL_STEP_INFINITE = 25
        /**
         * "Без ограничений" технически не бесконечность (Long.MAX_VALUE переполнил бы
         * System.currentTimeMillis() + ttlMillis в InviteCodec.encodeGroup) — 100 лет
         * практически неотличимо от "никогда не истечёт".
         */
        private const val TTL_INFINITE_MS = 100L * 365 * 24 * 60 * 60 * 1000
    }
}
