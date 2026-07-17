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
        /** Права делегированного админа (маска). isAdmin=true — это создатель (главный). */
        val permissions: Int,
        val messageCount: Int,
        val lastMessageAtMs: Long,
        /** Верифицированный разработчик (галочка рядом с ником). Считается по подписи identity. */
        val verified: Boolean = false
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
    private lateinit var adminsAdapter: AdminsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var sectionUsers: View
    private lateinit var sectionMuteHistory: View
    private lateinit var sectionChat: View
    private lateinit var sectionAdmins: View
    private lateinit var pillUsers: ImageButton
    private lateinit var pillModeration: ImageButton
    private lateinit var pillChat: ImageButton
    private lateinit var pillAdmins: ImageButton
    private var chatRoomId: Long = -1L
    /** Я — ГЛАВНЫЙ админ этого чата? Управление админами («+»/правка) только для него. */
    private var isPrimaryAdmin: Boolean = false

    /** Одна строка списка «Админы»: главный (с щитком) или делегированный (с правами). */
    data class AdminInfo(
        val userId: String,
        val name: String,
        val avatarBase64: String?,
        val isPrimary: Boolean,
        val permissions: Int,
        /** Верифицированный разработчик (галочка рядом с ником). */
        val verified: Boolean = false
    )

    /** Снимок участников/имён/аватаров с последнего refreshStats — питает список админов и пикер. */
    private var lastParticipants: List<com.atrum.chat.data.ChatParticipant> = emptyList()
    private val nameMap = HashMap<String, String>()
    private val avatarMap = HashMap<String, String?>()
    /** userId → верифицированный разработчик? (для галочки в списке админов/пикере). */
    private val verifiedMap = HashMap<String, Boolean>()

    /** Все события группы (join/leave), отсортированы по времени — источник раздела «Беседа». */
    private var allEvents: List<com.atrum.chat.data.GroupEventEntry> = emptyList()
    /** Период отчёта: 0 — с создания, иначе миллисекунды окна (7/30 дней) или кастом. */
    private var periodStartMs: Long = 0L
    private var periodEndMs: Long = Long.MAX_VALUE
    private var calMonth = java.util.Calendar.getInstance()
    private var customStartMs: Long? = null
    private val dayFmt = java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault())
    /** userId → отображаемое имя (для таймлайна событий). Обновляется в refreshStats. */
    private val lastKnownNames = HashMap<String, String>()
    /** Актуальное число участников (ground truth) — для счётчика «сейчас», не зависит от периода. */
    private var currentMemberCount = 0

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

        adminsAdapter = AdminsAdapter { info -> onAdminClick(info) }
        findViewById<RecyclerView>(R.id.rv_admins).apply {
            layoutManager = LinearLayoutManager(this@GroupStatsActivity)
            adapter = adminsAdapter
        }
        findViewById<ImageButton>(R.id.btn_add_admin).setOnClickListener { showAppointPicker() }

        sectionUsers = findViewById(R.id.section_users)
        sectionMuteHistory = findViewById(R.id.section_mute_history)
        sectionChat = findViewById(R.id.section_chat)
        sectionAdmins = findViewById(R.id.section_admins)
        pillUsers = findViewById(R.id.pill_users)
        pillModeration = findViewById(R.id.pill_moderation)
        pillChat = findViewById(R.id.pill_chat)
        pillAdmins = findViewById(R.id.pill_admins)
        pillUsers.setOnClickListener { showSection(0) }
        pillModeration.setOnClickListener { showSection(1) }
        pillChat.setOnClickListener { showSection(2) }
        pillAdmins.setOnClickListener { showSection(3) }
        setupChatSection()
        highlightChips()
        showSection(0) // стартуем с «Участников» + подсветка активной иконки пилюли

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
        // §1.5: возврат с экрана назначения прав (AdminPermissionsActivity) — сразу
        // перечитать участников из Room и перерисовать список «Админы», не дожидаясь
        // сетевого тика (6с). Локально, без сети: назначение уже записано в Room.
        refreshAdminsLocal()
    }

    /** Быстрое локальное обновление списка «Админы» из Room (после назначения/снятия прав). */
    private fun refreshAdminsLocal() {
        if (!::chatEntity.isInitialized) return
        lifecycleScope.launch {
            val db = AppDatabase.get(this@GroupStatsActivity)
            val participants = withContext(Dispatchers.IO) { db.chatParticipantDao().getForChat(chatEntity.id) }
            lastParticipants = participants
            participants.forEach { p -> if (!nameMap.containsKey(p.userId)) nameMap[p.userId] = p.userId.take(8) }
            if (sectionAdmins.visibility == View.VISIBLE) renderAdmins()
        }
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
            // Защита от прямого запуска Intent'ом в обход кнопки. Доступ: ГЛАВНЫЙ админ ИЛИ
            // делегат с правом STATS (мультиподпись, Этап 2). Управление админами («+»
            // назначить) остаётся только у главного — прячем кнопку ниже (isPrimaryAdmin).
            isPrimaryAdmin = !entity.adminUserId.isNullOrBlank() && entity.adminUserId == prefs.myUserId
            val myPerms = withContext(Dispatchers.IO) {
                db.chatParticipantDao().getOne(entity.id, prefs.myUserId)?.permissions ?: 0
            }
            // Личная сборка (PERSONAL): доступ к статистике любой беседы локально.
            val canStats = PersonalFeatures.enabled || isPrimaryAdmin || AdminPermissions.has(myPerms, AdminPermissions.STATS)
            if (!canStats) { finish(); return@launch }
            // «+» назначения админа — только у главного.
            findViewById<ImageButton>(R.id.btn_add_admin).visibility =
                if (isPrimaryAdmin) View.VISIBLE else View.GONE
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
            val allData = if (isFirstLoad) fetchFirstFresh()
                else withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }

            // ⚡ Реалтайм раздела «Беседа» при ОТКРЫТОМ окне: сам применяем свежий
            // members.txt (членство + журнал приходов/уходов + уведомления), не полагаясь
            // только на фоновый сервис. Так события/счётчики/графики обновляются каждый
            // тик обновления экрана (6с) и по live-подписке, а не с задержкой сервиса.
            // Анти-откат по версии внутри applyIncoming делает повтор безопасным.
            if (allData != null) withContext(Dispatchers.IO) {
                runCatching {
                    GroupProfileSync.applyIncoming(chatEntity, allData.groupProfileContent, chatPassword, db.chatDao(), prefs)
                }
                runCatching {
                    MembersSync.applyIncoming(
                        chat = chatEntity,
                        membersContentEncrypted = allData.membersContent,
                        password = chatPassword,
                        participantDao = db.chatParticipantDao(),
                        chatDao = db.chatDao(),
                        myUserId = prefs.myUserId,
                        appContext = applicationContext,
                        groupEventDao = db.groupEventDao(),
                        memberSlots = allData.memberSlots,
                        pubkeyForUserId = transport::pubkeyForUserId
                    )
                }
            }
            // Свежий chatEntity — чтобы membersVersion/имя не устаревали между тиками
            // (иначе applyIncoming повторно применял бы один и тот же members.txt).
            withContext(Dispatchers.IO) { db.chatDao().getById(chatEntity.id) }?.let { chatEntity = it }
            // Участники — ПОСЛЕ применения members.txt, чтобы список и «сейчас» были свежими.
            val participants = withContext(Dispatchers.IO) { db.chatParticipantDao().getForChat(chatEntity.id) }
            // Seed журнала при первом открытии устоявшейся группы: если members.txt давно
            // не менялся, applyIncoming не сработал (анти-откат) и не засеял приходы — тогда
            // графики были бы пустыми. Засеиваем сами из дат присоединения (идемпотентно —
            // тот же гвард countForChat==0, что и в applyIncoming).
            withContext(Dispatchers.IO) {
                if (db.groupEventDao().countForChat(chatEntity.id) == 0 && participants.isNotEmpty()) {
                    db.groupEventDao().insertAll(participants.map {
                        com.atrum.chat.data.GroupEventEntry(
                            ownerId = chatEntity.id, userId = it.userId,
                            type = com.atrum.chat.data.GroupEventEntry.TYPE_JOIN, atMs = it.joinedAtMs
                        )
                    })
                }
            }

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
                    permissions = p.permissions,
                    messageCount = counts[p.userId] ?: 0,
                    lastMessageAtMs = lastTs[p.userId] ?: 0L,
                    // Галочка: свой ряд — по своему ключу (профиль себя тут может ещё не быть),
                    // остальные — неподделываемо по подписи identity (единая точка правды).
                    verified = if (isMe) VerifiedBadge.isVerifiedSelf(prefs.myIdentityPubKey)
                               else VerifiedBadge.isVerifiedDev(networkChatId, p.userId, prof)
                )
            }.sortedWith(compareByDescending<UserStat> { it.messageCount }.thenByDescending { it.lastMessageAtMs })

            findViewById<TextView>(R.id.tv_subtitle).text =
                resources.getQuantityString(R.plurals.group_stats_participants_count, stats.size, stats.size)
            adapter.submit(stats)

            // Снимок для раздела «Админы» и пикера назначения (имена/аватары/права).
            lastParticipants = participants
            stats.forEach { nameMap[it.userId] = it.name; avatarMap[it.userId] = it.avatarBase64; verifiedMap[it.userId] = it.verified }
            if (sectionAdmins.visibility == View.VISIBLE) renderAdmins()

            // Раздел «Беседа»: журнал событий + карта имён для таймлайна.
            currentMemberCount = participants.size
            allEvents = withContext(Dispatchers.IO) { db.groupEventDao().getForChat(chatEntity.id) }
            stats.forEach { lastKnownNames[it.userId] = it.name }
            allEvents.forEach { if (!lastKnownNames.containsKey(it.userId)) lastKnownNames[it.userId] = nameFor(it.userId) }
            if (sectionChat.visibility == View.VISIBLE) renderChatStats()
        } finally {
            swipeRefresh.isRefreshing = false
        }
    }

    /** Переключение разделов пилюли (0 участники / 1 модерация / 2 беседа / 3 админы). */
    private fun showSection(which: Int) {
        sectionUsers.visibility = if (which == 0) View.VISIBLE else View.GONE
        sectionMuteHistory.visibility = if (which == 1) View.VISIBLE else View.GONE
        sectionChat.visibility = if (which == 2) View.VISIBLE else View.GONE
        sectionAdmins.visibility = if (which == 3) View.VISIBLE else View.GONE
        // Подсветка активной иконки: кружок-фон + accent tint, остальные приглушены.
        listOf(pillUsers, pillModeration, pillChat, pillAdmins).forEachIndexed { i, btn ->
            val active = i == which
            btn.setBackgroundResource(if (active) R.drawable.bg_stats_pill_active else android.R.color.transparent)
            btn.setColorFilter(ContextCompat.getColor(this, if (active) R.color.accent_light else R.color.text_tertiary))
        }
        if (which == 2) renderChatStats()
        if (which == 3) renderAdmins()
    }

    // ── Раздел «Админы» ──────────────────────────────────────────────────────────

    /** Человекочитаемая маска прав → строка «изменение данных беседы, мут и бан, …». */
    private fun permNames(mask: Int): String = AdminPermissions.names(mask).joinToString(", ") {
        getString(
            when (it) { // индексы = порядок AdminPermissions.names()
                0 -> R.string.perm_edit; 1 -> R.string.perm_moderate; 2 -> R.string.perm_stats
                3 -> R.string.perm_pin; else -> R.string.perm_delete
            }
        )
    }

    /** Пересобирает список админов из последнего снимка участников. */
    private fun renderAdmins() {
        if (!::chatEntity.isInitialized) return
        val primaryId = chatEntity.adminUserId
        val list = lastParticipants
            .filter { !it.banned && (it.userId == primaryId || AdminPermissions.isAdmin(it.permissions)) }
            .map {
                AdminInfo(
                    userId = it.userId,
                    name = nameMap[it.userId] ?: it.userId.take(8),
                    avatarBase64 = avatarMap[it.userId],
                    isPrimary = it.userId == primaryId,
                    permissions = it.permissions,
                    verified = verifiedMap[it.userId] ?: VerifiedBadge.isConfirmedDev(networkChatId, it.userId)
                )
            }
            .sortedWith(compareByDescending<AdminInfo> { it.isPrimary }.thenBy { it.name.lowercase() })
        adminsAdapter.submit(list)
    }

    /** Тап по строке админа: главного не трогаем, делегированному — экран правки прав.
     *  Правка/назначение доступны только ГЛАВНОМУ (делегат со STATS лишь смотрит список). */
    private fun onAdminClick(info: AdminInfo) {
        if (!isPrimaryAdmin) return
        if (info.isPrimary) {
            android.widget.Toast.makeText(this, getString(R.string.admins_primary_note), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        openPermissions(info.userId, info.name, info.permissions)
    }

    /** «+»: выбрать участника (не админа, не забаненного) для назначения. */
    private fun showAppointPicker() {
        if (!isPrimaryAdmin) return
        if (!::chatEntity.isInitialized) return
        val primaryId = chatEntity.adminUserId
        val candidates = lastParticipants.filter {
            !it.banned && it.userId != primaryId && !AdminPermissions.isAdmin(it.permissions)
        }
        if (candidates.isEmpty()) {
            android.widget.Toast.makeText(this, getString(R.string.admins_no_candidates), android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = candidates.map { p ->
            NeonDialog.Item(label = nameMap[p.userId] ?: p.userId.take(8)) {
                openPermissions(p.userId, nameMap[p.userId] ?: p.userId.take(8), 0)
            }
        }
        NeonDialog.showMenu(this, title = getString(R.string.admins_pick_title), items = items)
    }

    private fun openPermissions(userId: String, name: String, permissions: Int) {
        startActivity(Intent(this, AdminPermissionsActivity::class.java).apply {
            putExtra(AdminPermissionsActivity.EXTRA_CHAT_ID, chatRoomId)
            putExtra(AdminPermissionsActivity.EXTRA_USER_ID, userId)
            putExtra(AdminPermissionsActivity.EXTRA_USER_NAME, name)
            putExtra(AdminPermissionsActivity.EXTRA_PERMISSIONS, permissions)
        })
    }

    // ── Раздел «Беседа» — статистика по журналу событий (GroupEventEntry) ──

    private fun setupChatSection() {
        findViewById<View>(R.id.chip_all).setOnClickListener { setPeriod(0L) }
        findViewById<View>(R.id.chip_month).setOnClickListener { setPeriod(30L * 24 * 3600_000L) }
        findViewById<View>(R.id.chip_week).setOnClickListener { setPeriod(7L * 24 * 3600_000L) }
        findViewById<ImageButton>(R.id.btn_calendar).setOnClickListener {
            val c = findViewById<View>(R.id.cal_container)
            c.visibility = if (c.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (c.visibility == View.VISIBLE) renderCalendar()
        }
        findViewById<ImageButton>(R.id.cal_prev).setOnClickListener { calMonth.add(java.util.Calendar.MONTH, -1); renderCalendar() }
        findViewById<ImageButton>(R.id.cal_next).setOnClickListener { calMonth.add(java.util.Calendar.MONTH, 1); renderCalendar() }
        findViewById<StatsChartView>(R.id.chart_line).onBucketClick = { i -> onBucketSelected(i) }
        findViewById<StatsChartView>(R.id.chart_bars).onBucketClick = { i -> onBucketSelected(i) }
    }

    private var chipSel = 0L
    private fun setPeriod(windowMs: Long) {
        chipSel = windowMs
        customStartMs = null
        val now = System.currentTimeMillis()
        periodEndMs = Long.MAX_VALUE
        periodStartMs = if (windowMs <= 0L) 0L else now - windowMs
        highlightChips()
        renderChatStats()
    }

    private fun highlightChips() {
        val map = mapOf(R.id.tv_chip_all to 0L, R.id.tv_chip_month to 30L * 24 * 3600_000L, R.id.tv_chip_week to 7L * 24 * 3600_000L)
        map.forEach { (id, w) ->
            val on = customStartMs == null && chipSel == w
            findViewById<TextView>(id).setTextColor(ContextCompat.getColor(this, if (on) R.color.accent_light else R.color.text_secondary))
            (findViewById<TextView>(id).parent as? View)?.setBackgroundResource(if (on) R.drawable.bg_chip_selected else R.drawable.bg_pill)
        }
    }

    /** Бакеты по дням в выбранном периоде: (label, members-на-конец-дня, joins, leaves). */
    private var currentBuckets: List<StatsChartView.Bucket> = emptyList()

    private fun renderChatStats() {
        val evts = allEvents.filter { it.type == com.atrum.chat.data.GroupEventEntry.TYPE_JOIN || it.type == com.atrum.chat.data.GroupEventEntry.TYPE_LEAVE }
        val createdMs = evts.minOfOrNull { it.atMs } ?: System.currentTimeMillis()
        val startMs = if (periodStartMs <= 0L) createdMs else maxOf(periodStartMs, createdMs)
        val endMs = if (periodEndMs == Long.MAX_VALUE) System.currentTimeMillis() else periodEndMs

        // Диапазон дней.
        val dayMs = 24 * 3600_000L
        fun dayStart(ms: Long): Long { val c = java.util.Calendar.getInstance(); c.timeInMillis = ms; c.set(java.util.Calendar.HOUR_OF_DAY,0); c.set(java.util.Calendar.MINUTE,0); c.set(java.util.Calendar.SECOND,0); c.set(java.util.Calendar.MILLISECOND,0); return c.timeInMillis }
        val d0 = dayStart(startMs); val d1 = dayStart(endMs)
        val nDays = (((d1 - d0) / dayMs).toInt() + 1).coerceIn(1, 60)

        // members на начало периода = join'ы до startMs минус leave'ы до startMs.
        var running = evts.count { it.type == "join" && it.atMs < d0 } - evts.count { it.type == "leave" && it.atMs < d0 }
        if (running < 0) running = 0

        val buckets = ArrayList<StatsChartView.Bucket>(nDays)
        for (i in 0 until nDays) {
            val ds = d0 + i * dayMs; val de = ds + dayMs
            val j = evts.count { it.type == "join" && it.atMs in ds until de }
            val l = evts.count { it.type == "leave" && it.atMs in ds until de }
            running += j - l; if (running < 0) running = 0
            val label = if (i == nDays - 1 && d1 >= dayStart(System.currentTimeMillis())) getString(R.string.chat_today_short) else dayFmt.format(java.util.Date(ds))
            buckets.add(StatsChartView.Bucket(label, running, j, l))
        }
        currentBuckets = buckets

        val totalJoin = evts.count { it.type == "join" && it.atMs in d0..(d1 + dayMs) }
        val totalLeft = evts.count { it.type == "leave" && it.atMs in d0..(d1 + dayMs) }

        // «Сейчас» — актуальное число участников (ground truth), не зависит от периода.
        findViewById<TextView>(R.id.tv_now).text = currentMemberCount.toString()
        findViewById<TextView>(R.id.tv_joined).text = totalJoin.toString()
        findViewById<TextView>(R.id.tv_left).text = totalLeft.toString()
        findViewById<TextView>(R.id.tv_created).text = dayFmt.format(java.util.Date(createdMs))
        findViewById<TextView>(R.id.tv_range_label).text = if (customStartMs == null && chipSel <= 0L)
            getString(R.string.stats_range_all_fmt, dayFmt.format(java.util.Date(createdMs)))
        else getString(R.string.stats_range_custom_fmt, dayFmt.format(java.util.Date(startMs)), dayFmt.format(java.util.Date(endMs)))

        findViewById<StatsChartView>(R.id.chart_line).setData(buckets, StatsChartView.MODE_LINE)
        findViewById<StatsChartView>(R.id.chart_bars).setData(buckets, StatsChartView.MODE_BARS)

        // Таймлайн событий (последние сверху).
        val container = findViewById<LinearLayout>(R.id.events_container)
        container.removeAllViews()
        val periodEvents = evts.filter { it.atMs in d0..(d1 + dayMs) }.sortedByDescending { it.atMs }.take(60)
        if (periodEvents.isEmpty()) {
            val tv = TextView(this).apply { text = getString(R.string.stats_events_empty); setTextColor(ContextCompat.getColor(this@GroupStatsActivity, R.color.text_tertiary)); textSize = 12f; setPadding(0, 8, 0, 8) }
            container.addView(tv)
        } else {
            val evDateFmt = java.text.SimpleDateFormat("dd.MM, HH:mm", java.util.Locale.getDefault())
            periodEvents.forEach { e -> container.addView(buildEventRow(e, evDateFmt)) }
        }
        // Сброс детали.
        findViewById<TextView>(R.id.tv_detail_title).text = getString(R.string.stats_detail_hint_title)
        findViewById<TextView>(R.id.tv_detail_body).text = getString(R.string.stats_detail_hint_body)
    }

    private fun buildEventRow(e: com.atrum.chat.data.GroupEventEntry, fmt: java.text.SimpleDateFormat): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.TOP; setPadding(0, (5 * resources.displayMetrics.density).toInt(), 0, (5 * resources.displayMetrics.density).toInt()) }
        val join = e.type == com.atrum.chat.data.GroupEventEntry.TYPE_JOIN
        val icon = ImageView(this).apply {
            setImageResource(if (join) R.drawable.ic_arrow_down_left else R.drawable.ic_arrow_up_right)
            setColorFilter(ContextCompat.getColor(this@GroupStatsActivity, if (join) R.color.accent_light else R.color.accent_dark))
            val s = (14 * resources.displayMetrics.density).toInt(); layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8 * resources.displayMetrics.density).toInt(); topMargin = (1 * resources.displayMetrics.density).toInt() }
        }
        val name = lastKnownNames[e.userId] ?: e.userId.take(8)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = getString(if (join) R.string.stats_event_joined_fmt else R.string.stats_event_left_fmt, name); setTextColor(ContextCompat.getColor(this@GroupStatsActivity, R.color.text_primary)); textSize = 12f })
        col.addView(TextView(this).apply { text = fmt.format(java.util.Date(e.atMs)); setTextColor(ContextCompat.getColor(this@GroupStatsActivity, R.color.text_tertiary)); textSize = 9f })
        row.addView(icon); row.addView(col)
        return row
    }

    private fun onBucketSelected(i: Int) {
        val b = currentBuckets.getOrNull(i) ?: return
        findViewById<StatsChartView>(R.id.chart_line).setSelected(i)
        findViewById<StatsChartView>(R.id.chart_bars).setSelected(i)
        findViewById<TextView>(R.id.tv_detail_title).text = getString(R.string.stats_detail_fmt, b.label, b.members)
        findViewById<TextView>(R.id.tv_detail_body).text = getString(R.string.stats_detail_body_fmt, b.joins, b.leaves, b.members)
    }

    private fun renderCalendar() {
        findViewById<TextView>(R.id.cal_month).text = java.text.SimpleDateFormat("LLLL yyyy", java.util.Locale.getDefault()).format(calMonth.time)
        val grid = findViewById<LinearLayout>(R.id.cal_grid)
        grid.removeAllViews()
        val c = calMonth.clone() as java.util.Calendar
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        val firstDow = (c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Пн=0
        val daysInMonth = c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
        var day = 1
        val dp = resources.displayMetrics.density
        var week: LinearLayout? = null
        for (cell in 0 until 42) {
            if (cell % 7 == 0) { week = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; grid.addView(week) }
            val tv = TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, (30 * dp).toInt(), 1f); gravity = android.view.Gravity.CENTER; textSize = 11f
            }
            if (cell >= firstDow && day <= daysInMonth) {
                val d = day
                val cc = calMonth.clone() as java.util.Calendar
                cc.set(java.util.Calendar.DAY_OF_MONTH, d); cc.set(java.util.Calendar.HOUR_OF_DAY, 0); cc.set(java.util.Calendar.MINUTE, 0); cc.set(java.util.Calendar.SECOND, 0); cc.set(java.util.Calendar.MILLISECOND, 0)
                val ms = cc.timeInMillis
                tv.text = d.toString()
                val inRange = customStartMs != null && ms in (customStartMs!!)..(periodEndMs.takeIf { it != Long.MAX_VALUE } ?: customStartMs!!)
                tv.setTextColor(ContextCompat.getColor(this, if (inRange) R.color.accent_light else R.color.text_primary))
                if (inRange) tv.setBackgroundResource(R.drawable.bg_stats_pill_active)
                tv.setOnClickListener { onCalendarDay(ms) }
                day++
            }
            week!!.addView(tv)
        }
    }

    private fun onCalendarDay(ms: Long) {
        val start = customStartMs
        if (start == null || periodEndMs != Long.MAX_VALUE) {
            // Начинаем новый выбор.
            customStartMs = ms; periodStartMs = ms; periodEndMs = Long.MAX_VALUE
        } else {
            // Второй тап — конец периода.
            if (ms >= start) { periodStartMs = start; periodEndMs = ms + 24 * 3600_000L - 1 }
            else { periodStartMs = ms; periodEndMs = start + 24 * 3600_000L - 1; customStartMs = ms }
        }
        highlightChips()
        renderCalendar()
        renderChatStats()
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
                val displayName = when {
                    stat.isAdmin -> itemView.context.getString(R.string.group_stats_name_with_admin, stat.name) // создатель
                    AdminPermissions.isAdmin(stat.permissions) -> itemView.context.getString(R.string.group_stats_name_with_delegate, stat.name) // админ
                    else -> stat.name
                }
                // Галочка «Разработчик ATRUM» рядом с ником (кликабельна → пояснение).
                VerifiedBadge.applyNameBadge(nameTv, displayName, stat.verified)
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
     * Список раздела «Админы»: главный админ (со щитком, подпись «Главный администратор»)
     * и делегированные (с перечислением выданных прав). Тап по делегированному ведёт в
     * [AdminPermissionsActivity]; по главному — нейтральный тост (его права снять нельзя).
     */
    private inner class AdminsAdapter(
        private val onClick: (AdminInfo) -> Unit
    ) : RecyclerView.Adapter<AdminsAdapter.VH>() {

        private var items: List<AdminInfo> = emptyList()

        fun submit(list: List<AdminInfo>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_stats_admin, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val avatarImg: ShapeableImageView = v.findViewById(R.id.iv_avatar)
            private val letterTv: TextView = v.findViewById(R.id.tv_letter)
            private val nameTv: TextView = v.findViewById(R.id.tv_name)
            private val ownerIv: ImageView = v.findViewById(R.id.iv_owner)
            private val subTv: TextView = v.findViewById(R.id.tv_sub)
            private val chevronIv: ImageView = v.findViewById(R.id.iv_chevron)

            fun bind(info: AdminInfo) {
                val ctx = itemView.context
                val bmp = AvatarUtils.fromBase64(info.avatarBase64)
                if (bmp != null) {
                    avatarImg.setImageBitmap(bmp)
                    avatarImg.visibility = View.VISIBLE
                    letterTv.visibility = View.GONE
                } else {
                    avatarImg.visibility = View.GONE
                    letterTv.visibility = View.VISIBLE
                    letterTv.text = info.name.trim().firstOrNull()?.uppercase() ?: "?"
                }
                // Галочка «Разработчик ATRUM» рядом с ником админа (кликабельна → пояснение).
                VerifiedBadge.applyNameBadge(nameTv, info.name, info.verified)
                ownerIv.visibility = if (info.isPrimary) View.VISIBLE else View.GONE
                chevronIv.visibility = if (info.isPrimary) View.GONE else View.VISIBLE
                subTv.text = if (info.isPrimary) ctx.getString(R.string.admins_primary_label)
                    else permNames(info.permissions).ifBlank { ctx.getString(R.string.admins_no_rights) }
                itemView.setOnClickListener { onClick(info) }
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

                // ── Бан / разбан (объединённая история модерации) — отдельный рендер:
                // нет срока/причины/оснований, только статус-чип и «кто выдал». ──
                if (e.type == MuteHistoryEntry.TYPE_BAN || e.type == MuteHistoryEntry.TYPE_UNBAN) {
                    val isBan = e.type == MuteHistoryEntry.TYPE_BAN
                    statusTv.setBackgroundResource(if (isBan) R.drawable.bg_mute_status_active else R.drawable.bg_mute_status_inactive)
                    statusTv.setTextColor(ContextCompat.getColor(ctx, if (isBan) R.color.error else R.color.accent_light))
                    statusTv.text = ctx.getString(if (isBan) R.string.mute_history_status_banned else R.string.mute_history_status_unbanned)

                    val expandedBan = expandedMuteHistoryIds.contains(e.id)
                    expandedContainer.visibility = if (expandedBan) View.VISIBLE else View.GONE
                    chevronIv.setImageResource(if (expandedBan) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right)
                    header.setOnClickListener { onToggle(e.id) }

                    issuedByTv.text = ctx.getString(R.string.mute_history_issued_by_fmt, stat.issuedByName)
                    untilTv.text = ctx.getString(if (isBan) R.string.mute_history_event_ban else R.string.mute_history_event_unban)
                    reasonLabelTv.visibility = View.GONE
                    reasonTv.visibility = View.GONE
                    evidenceLabelTv.visibility = View.GONE
                    evidenceList.removeAllViews()
                    return
                }

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
