package com.atrum.chat

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.NostrTransport
import com.atrum.chat.transport.TransportFactory
import com.google.android.material.imageview.ShapeableImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Детальная статистика активности ОДНОГО участника группы (админ-модерация):
 *  — диаграмма активности с переключателем период (день/неделя/месяц/год);
 *  — распределение по часам суток (когда чаще пишет);
 *  — сводные карточки (всего/среднее в день/пиковый час/активных дней);
 *  — список ВСЕХ его сообщений (свайп влево — удалить у всех, зажатие — перейти
 *    к сообщению в чате);
 *  — раздел удалённых сообщений (с атрибуцией кто и когда удалил).
 *
 * Всё считается из уже имеющейся локально истории (см. StatsUtil) — без отдельного
 * "канала отчётности" от профиля, см. GroupStatsActivity.
 */
class UserStatsActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_USER_NAME = "user_name"
        const val EXTRA_USER_AVATAR = "user_avatar"
        private const val LIVE_REFRESH_INTERVAL_MS = 6_000L

        /** Первая загрузка: сколько раз ждать СВОЙ настоящий ответ реле (loadAllFresh),
         *  прежде чем сдаться и подставить обычный терпеливый loadAll(). */
        private const val FIRST_LOAD_MAX_ATTEMPTS = 5
        private const val FIRST_LOAD_RETRY_DELAY_MS = 2_500L

        /** Сколько сообщений показываем сразу (и добавляем за один шаг ленивой подгрузки
         *  при скролле) — репорт: «пусть при первом заходе грузит минимум 25 сообщений,
         *  а дальше по мере необходимости ленивая прогрузка во время скролла». */
        private const val INITIAL_VISIBLE_MESSAGES = 25
        private const val PAGE_SIZE = 25
        private const val SCROLL_LOAD_THRESHOLD = 5

        /** Стартовый размер "хвостового" окна строк чата для быстрой первой отрисовки
         *  (см. ChatActivity.TAIL_FIRST — тот же принцип): не все строки общего чата
         *  принадлежат целевому участнику, поэтому окно растёт, пока не наберём хотя бы
         *  INITIAL_VISIBLE_MESSAGES ЕГО сообщений. */
        private const val TAIL_INITIAL_WINDOW = 60
        private const val TAIL_GROWTH_FACTOR = 3
    }

    // ── Строки списка ────────────────────────────────────────────────────────
    private sealed class Row {
        object Header : Row()
        data class Section(val title: String, val count: Int) : Row()
        data class MsgRow(val msg: Message) : Row()
        data class DeletedRow(val msg: Message, val deletedAtMs: Long, val deleterLabel: String) : Row()
        /** Плашка «Запрещаю за собой подсматривать» — вместо списка «Все сообщения» у
         *  верифицированного разработчика (PERSONAL_BUILD.md §Часть 3). */
        object Plaque : Row()
    }

    private var chatRoomId: Long = -1L
    private var targetUserId: String = ""
    private var targetUserName: String = "?"
    private var targetUserAvatar: String? = null

    /**
     * Урезанный вид "моя статистика" для обычного (не-админа) участника — по запросу
     * пользователя: свои графики/сводка видно, но БЕЗ раздела "Все сообщения" (значит и
     * без веток-ответов, и без свайп-удаления — секция с сообщениями просто не строится,
     * см. buildRows). Устанавливается в setupAndLoad() строго по факту "я не админ" — НЕ
     * по intent-флагу, который можно было бы подделать: раз не-админ вообще прошёл guard
     * (см. setupAndLoad), targetUserId==его собственный гарантированно.
     */
    private var isSelfRestrictedView: Boolean = false
    /** Цель — верифицированный разработчик, и смотрю НЕ я сам (PERSONAL_BUILD.md §Часть 3):
     *  раздел «Все сообщения» вообще не строится, вместо него — плашка. Неподделываемо:
     *  вычисляется по валидной подписи identity профиля цели (VerifiedBadge). */
    private var targetIsProtectedDev: Boolean = false

    private var networkChatId = ""
    private var chatPassword = ""
    private lateinit var transport: NostrTransport
    private var transportReady = false
    private var adminUserId: String? = null

    /** Живая подписка на новые события (см. ChatActivity.transportWatch) — толкает
     *  refreshData() при появлении нового сообщения в канале. Быстрый путь, но не
     *  единственный — см. liveRefreshJob ниже (репорт: «нужно перезайти, чтобы
     *  появилось новое сообщение» — подписка на свежесозданном одноразовом transport
     *  не всегда успевает подняться вовремя через Tor). */
    private var transportWatch: AutoCloseable? = null

    /** Гарантированный запасной путь обновления, ПОКА экран открыт (старт в onResume,
     *  стоп в onPause) — не полагаемся только на watchMessages. Формально это второй
     *  поллинг-цикл сверх SyncEngine (см. §1), но он строго локален этому
     *  диагностическому админ-экрану, не конкурирует с таймингами доставки в чате, и
     *  §1.5 («никаких перезаходов») здесь весомее. Интервал не короче, чем у самого
     *  чата (NOSTR_ACTIVE_INTERVAL_MS = 3с) — не бьёт по реле сильнее. */
    private var liveRefreshJob: kotlinx.coroutines.Job? = null

    /** Все сообщения ЭТОГО участника (для диаграмм и списка) — newest first для списка. */
    private var userMessages: List<Message> = emptyList()
    private var currentPeriod: StatsUtil.Period = StatsUtil.Period.WEEK

    /** Полная (все отправители) расшифрованная история — нужна ТОЛЬКО для разрешения
     *  "оригинала" цитаты у ответов (см. StatsUtil.findQuotedOriginal): собеседник, которому
     *  отвечал целевой участник, обычно не входит в [userMessages]. Обновляется в refreshData()
     *  тем же тиком, что и userMessages; до первого полного прохода (см. TAIL_INITIAL_WINDOW
     *  fast-path) может быть пустой — цитата тогда просто не резолвится, самолечится на
     *  следующем тике. */
    private var allMessagesCache: List<Message> = emptyList()

    /** Ленивый загрузчик фото/голоса для инлайн-просмотра в списке (см. MsgVH.bind) —
     *  тот же ImageLoader, что и в чате, просто отдельный инстанс на этот экран. */
    private val imageLoader: ImageLoader by lazy { ImageLoader(transport, chatPassword, networkChatId) }

    /** Сообщения, удалённые ЛОКАЛЬНО (свайпом) в этой сессии, но ещё не подтверждённые
     *  реле (репорт: «удаляется странно и не всегда» — надгробие через Tor может идти до
     *  ~30с (см. NOSTR_ACTION_TIMEOUT_MS), а периодический live-refresh каждые 6с успевает
     *  переспросить реле раньше и вернуть ещё НЕ удалённую копию, из-за чего сообщение на
     *  секунду возвращалось обратно в "все сообщения"). refreshData() всегда исключает эти
     *  raw из свежепрочитанного списка и держит соответствующую строку в "удалённых",
     *  пока сама история не подтвердит удаление. */
    private val pendingDeletedRaw = HashSet<String>()
    private val pendingDeletedRows = HashMap<String, Row.DeletedRow>()

    /** Memo-кэш расшифровки по сырой строке — переживает refreshData(), см.
     *  StatsUtil.decodeAllCached (репорт: «первая загрузка долгая, даже если грузить
     *  нечего» — без кэша ВЕСЬ чат перерасшифровывался заново на каждый тик). */
    private val decodeCache = HashMap<String, Message?>()

    /** Сколько сообщений сейчас материализовано в список (пагинация рендера — не сетевая
     *  пагинация: данные уже расшифрованы кэшем, просто не все сразу превращены в строки
     *  RecyclerView). Растёт при скролле к концу списка, см. onCreate/onScrolled. */
    private var visibleLimit = INITIAL_VISIBLE_MESSAGES

    /** Последний набор "удалённых" строк — нужен, чтобы пересобрать список при ленивой
     *  подгрузке (скролл) без повторного похода в сеть. */
    private var lastDeletedRows: List<Row.DeletedRow> = emptyList()

    private lateinit var adapter: RowsAdapter
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_stats)

        chatRoomId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        targetUserId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        targetUserName = intent.getStringExtra(EXTRA_USER_NAME)?.takeIf { it.isNotBlank() } ?: targetUserId.take(8)
        targetUserAvatar = intent.getStringExtra(EXTRA_USER_AVATAR)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = targetUserName

        val avatarImg = findViewById<ShapeableImageView>(R.id.iv_header_avatar)
        val letterTv = findViewById<TextView>(R.id.tv_header_letter)
        val bmp = AvatarUtils.fromBase64(targetUserAvatar)
        if (bmp != null) {
            avatarImg.setImageBitmap(bmp); avatarImg.visibility = View.VISIBLE; letterTv.visibility = View.GONE
        } else {
            avatarImg.visibility = View.GONE; letterTv.visibility = View.VISIBLE
            letterTv.text = targetUserName.trim().firstOrNull()?.uppercase() ?: "?"
        }

        adapter = RowsAdapter()
        val rv = findViewById<RecyclerView>(R.id.rv_user_stats)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback(this) { position -> onSwipeAction(position) }).attachToRecyclerView(rv)

        // Ленивая подгрузка при скролле вниз (к более старым сообщениям) — см.
        // INITIAL_VISIBLE_MESSAGES/PAGE_SIZE. Данные уже расшифрованы (decodeCache) и
        // лежат в userMessages целиком — тут только добавляем ещё строк в отрисовку,
        // без похода в сеть.
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                if (lastVisible == RecyclerView.NO_POSITION) return
                if (lastVisible >= adapter.itemCount - SCROLL_LOAD_THRESHOLD && visibleLimit < userMessages.size) {
                    visibleLimit += PAGE_SIZE
                    buildRows(lastDeletedRows)
                }
            }
        })

        swipeRefresh = findViewById(R.id.swipe_refresh)
        swipeRefresh.setColorSchemeResources(R.color.accent)
        // ⚠️ Фикс (репорт: "кружок загрузки белый на тёмной теме"): setColorSchemeResources
        // красит только вращающуюся дугу — круглый ФОН под ней у SwipeRefreshLayout по
        // умолчанию хардкожен белым и не подхватывает тему сам по себе (см. CLAUDE.md §5.1).
        swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.surface_elevated)
        swipeRefresh.setOnRefreshListener {
            if (transportReady) lifecycleScope.launch { refreshData() } else swipeRefresh.isRefreshing = false
        }

        if (chatRoomId < 0 || targetUserId.isBlank()) { finish(); return }
        setupAndLoad()
    }

    override fun onResume() {
        super.onResume()
        if (transportReady) startLiveRefreshLoop()
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

    /** Периодически освежает данные, пока экран реально на переднем плане — гарантирует
     *  обновление «на месте» (§1.5) независимо от того, успела ли подняться watchMessages. */
    private fun startLiveRefreshLoop() {
        liveRefreshJob?.cancel()
        liveRefreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(LIVE_REFRESH_INTERVAL_MS)
                refreshData()
            }
        }
    }

    /** Разовая инициализация: находим чат/транспорт, поднимаем живую подписку, затем первая загрузка. */
    private fun setupAndLoad() {
        lifecycleScope.launch {
            val db = AppDatabase.get(this@UserStatsActivity)
            val chatEntity = withContext(Dispatchers.IO) { db.chatDao().getById(chatRoomId) }
            if (chatEntity == null || !chatEntity.isGroup) { finish(); return@launch }
            val isAdmin = !chatEntity.adminUserId.isNullOrBlank() && chatEntity.adminUserId == prefs.myUserId
            // ⚠️ Не-админ допускается СЮДА только на СВОЮ же статистику (см. кнопку в
            // PartnerProfileActivity.renderGroupMembersRows, isMe && !groupIsAdmin) — прямой
            // запуск Intent'ом с чужим EXTRA_USER_ID всё равно отклоняется. isSelfRestrictedView
            // выводится из факта "не админ", а не из отдельного intent-флага — так его нельзя
            // подделать отдельно от targetUserId.
            val isSelf = targetUserId == prefs.myUserId
            if (!isAdmin && !isSelf) { finish(); return@launch }
            isSelfRestrictedView = !isAdmin

            networkChatId = chatEntity.chatId
            chatPassword = prefs.getChatPassword(networkChatId).takeIf { it.isNotEmpty() } ?: chatEntity.chatPassword
            val transportToken = prefs.getChatToken(networkChatId).takeIf { it.isNotEmpty() } ?: chatEntity.transportToken
            adminUserId = chatEntity.adminUserId

            transport = TransportFactory.forChat(
                this@UserStatsActivity, networkChatId, transportToken, chatPassword, prefs.myUserId, adminUserId
            ) as? NostrTransport ?: run { finish(); return@launch }
            transportReady = true
            // onResume уже мог отработать до этого момента (transportReady был false) —
            // запускаем цикл сейчас, а не полагаемся только на следующий onResume.
            startLiveRefreshLoop()

            // Живая подписка (тот же REQ-стрим, что ChatActivity.transportWatch) — новое
            // сообщение в канале сразу дёргает пересчёт статистики, без ожидания
            // повторного открытия экрана.
            transportWatch = transport.watchMessages {
                lifecycleScope.launch { refreshData() }
            }

            // Существующий индикатор SwipeRefreshLayout (не новый UI-элемент) — видимая
            // обратная связь, пока идут попытки дождаться СВОЕГО ответа реле (см.
            // fetchFirstFresh) и/или растёт "хвостовое" окно первой быстрой отрисовки.
            swipeRefresh.isRefreshing = true
            refreshData(isFirstLoad = true)
        }
    }

    /**
     * Первая загрузка: ждём СВОЙ настоящий ответ реле (loadAllFresh), а не молча
     * подставляем то, что уже накопил общий стор благодаря чужой сессии (репорт §16:
     * «пользователь считается вошедшим по странному паттерну — то тогда, когда обновился
     * чат у админа»). После нескольких попыток — обычный терпеливый loadAll(), чтобы
     * экран не завис в вечной загрузке при полном отказе реле.
     */
    private suspend fun fetchFirstFresh(): com.atrum.chat.transport.AllChannelData? {
        repeat(FIRST_LOAD_MAX_ATTEMPTS) { attempt ->
            val fresh = withContext(Dispatchers.IO) { runCatching { transport.loadAllFresh() }.getOrNull() }
            if (fresh != null) return fresh
            if (attempt < FIRST_LOAD_MAX_ATTEMPTS - 1) delay(FIRST_LOAD_RETRY_DELAY_MS)
        }
        return withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }
    }

    /**
     * Повторно читает историю канала и пересобирает список — вызывается из onResume,
     * pull-to-refresh и живой подписки; переиспользует уже поднятый [transport].
     *
     * [isFirstLoad]: (а) ждём СВОЙ независимый от админа ответ реле (fetchFirstFresh);
     * (б) сначала декодируем только "хвост" — растущее окно строк с конца чата, пока не
     * наберём хотя бы INITIAL_VISIBLE_MESSAGES сообщений ЦЕЛЕВОГО участника — и сразу
     * показываем (быстрая первая отрисовка, тот же принцип, что ChatActivity.TAIL_FIRST),
     * а полную точную историю досчитываем следом тем же тиком, без блокировки UI. Дальше
     * (не первая загрузка) — один проход по кэшу decodeCache, уже дешёвый.
     */
    private suspend fun refreshData(isFirstLoad: Boolean = false) {
        try {
            val allData = if (isFirstLoad) fetchFirstFresh()
                else withContext(Dispatchers.IO) { runCatching { transport.loadAll() }.getOrNull() }

            // Верифицированный разработчик как цель (и смотрю НЕ я сам): раздел «Все
            // сообщения» вообще не строим — вместо него плашка (PERSONAL_BUILD.md §Часть 3).
            // Профиль цели берём из свежего снимка; проверка подписи identity неподделываема
            // (VerifiedBadge), домен — networkChatId (= crypto chat.chatId, как для identitySig).
            if (allData != null && targetUserId != prefs.myUserId) {
                val profs = withContext(Dispatchers.Default) {
                    if (ChatActivity.SLOT_UNION_PROFILES && allData.profileSlots.isNotEmpty())
                        ProfileSync.unionProfileSlots(allData.profileSlots, chatPassword, networkChatId)
                    else ProfileSync.parseProfiles(allData.profilesContent, chatPassword, networkChatId)
                }
                // Единая точка правды (VerifiedBadge.isVerifiedDev): свежий профиль ИЛИ ранее
                // подтверждённая память. Раз защищён — остаётся защищённым (не «протухает» от
                // одного пустого/неполного чтения профиля). Неподделываемо (identity-подпись).
                targetIsProtectedDev = VerifiedBadge.isVerifiedDev(networkChatId, targetUserId, profs[targetUserId]) ||
                    targetIsProtectedDev
            }

            // Галочка «Разработчик ATRUM» рядом с ником в шапке статистики — как только
            // подтверждён верифиц-статус цели. Кликабельна → окно-пояснение «кто я».
            VerifiedBadge.applyNameBadge(findViewById(R.id.tv_title), targetUserName, targetIsProtectedDev)

            if (isFirstLoad && allData != null) {
                val allLines = allData.chatContent.split("\n").filter { it.isNotEmpty() }
                var windowSize = TAIL_INITIAL_WINDOW
                while (true) {
                    val windowLines = allLines.takeLast(windowSize.coerceAtMost(allLines.size))
                    val tailDecoded = withContext(Dispatchers.Default) {
                        StatsUtil.decodeAllCached(
                            windowLines.joinToString("\n"), chatPassword, networkChatId,
                            prefs.myUserId, prefs.myName, decodeCache
                        )
                    }
                    val tailUserMsgs = tailDecoded.filter { it.senderUserId == targetUserId && it.rawEncrypted !in pendingDeletedRaw }
                    val exhausted = windowSize >= allLines.size
                    if (tailUserMsgs.size >= INITIAL_VISIBLE_MESSAGES || exhausted) {
                        userMessages = tailUserMsgs
                        buildRows(emptyList())
                        break
                    }
                    windowSize *= TAIL_GROWTH_FACTOR
                }
            }

            val allMessages = if (allData != null) withContext(Dispatchers.Default) {
                StatsUtil.decodeAllCached(allData.chatContent, chatPassword, networkChatId, prefs.myUserId, prefs.myName, decodeCache)
            } else emptyList()
            if (allMessages.isNotEmpty()) allMessagesCache = allMessages

            // Исключаем то, что сами только что удалили локально (см. pendingDeletedRaw) —
            // надгробие может ещё не долететь до реле, свежий фетч иначе вернёт сообщение
            // обратно в "все сообщения" на один цикл обновления.
            userMessages = allMessages.filter { it.senderUserId == targetUserId && it.rawEncrypted !in pendingDeletedRaw }

            val deleted = withContext(Dispatchers.IO) { runCatching { transport.deletedMessages() }.getOrDefault(emptyList()) }
            val deletedDecoded = withContext(Dispatchers.Default) {
                StatsUtil.decodeDeleted(deleted, chatPassword, networkChatId, prefs.myUserId, prefs.myName)
            }.filter { it.message.senderUserId == targetUserId }

            val authorPub = transport.pubkeyForUserId(targetUserId)
            val adminPub = adminUserId?.let { transport.pubkeyForUserId(it) }
            val confirmedRows = deletedDecoded
                .sortedByDescending { it.deletedAtMs }
                .map { dr ->
                    val label = when {
                        dr.deleterPubkey.isBlank() -> getString(R.string.stats_deleted_by_unknown)
                        dr.deleterPubkey.equals(adminPub, ignoreCase = true) && adminPub != null && adminPub != authorPub ->
                            getString(R.string.stats_deleted_by_admin)
                        dr.deleterPubkey.equals(authorPub, ignoreCase = true) -> getString(R.string.stats_deleted_by_author)
                        else -> getString(R.string.stats_deleted_by_unknown)
                    }
                    Row.DeletedRow(dr.message, dr.deletedAtMs, label)
                }
            // Реле подтвердило удаление этих raw — больше не нужно держать их "в ожидании".
            pendingDeletedRaw.removeAll(confirmedRows.map { it.msg.rawEncrypted }.toSet())
            pendingDeletedRows.keys.retainAll(pendingDeletedRaw)
            // Показываем подтверждённые реле + всё ещё ожидающие (чтобы строка не пропадала
            // из "удалённых" между свайпом и реальным подтверждением надгробия).
            val stillPendingRows = pendingDeletedRows.values.filter { row ->
                confirmedRows.none { it.msg.rawEncrypted == row.msg.rawEncrypted }
            }
            // ⚠️ Защитный дедуп (репорт: "быстро удалил → восстановил → в удалённых дубликат").
            // confirmedRows идёт ПЕРВЫМ, поэтому distinctBy оставляет именно подтверждённую
            // реле версию строки, а не потенциально устаревшую "ожидающую" — на случай гонки
            // между refreshData() и restoreMessage(), когда pendingDeletedRows ещё не успел
            // синхронизироваться с тем, что реле уже подтвердило.
            val deletedRows = (confirmedRows + stillPendingRows)
                .distinctBy { it.msg.rawEncrypted }
                .sortedByDescending { it.deletedAtMs }

            buildRows(deletedRows)
        } finally {
            swipeRefresh.isRefreshing = false
        }
    }

    /**
     * Пересобирает список строк (шапка+диаграммы, «все сообщения», «удалённые»).
     * Диаграммы и счётчик секции считаются от ПОЛНОГО [userMessages] (точность —
     * см. §16 репорт), а материализуется в RecyclerView только [visibleLimit] строк
     * «Все сообщения» — остальные подгружаются при скролле (см. onScrolled в onCreate).
     */
    private fun buildRows(deletedRows: List<Row.DeletedRow>) {
        lastDeletedRows = deletedRows
        val rows = ArrayList<Row>()
        rows.add(Row.Header)
        // Верифицированный разработчик (PERSONAL_BUILD.md §Часть 3): раздел «Все сообщения»
        // не строим вообще — вместо него плашка «Запрещаю за собой подсматривать».
        // "Моя статистика" обычного участника — только шапка (графики/сводка), без списка
        // сообщений: ни веток-ответов, ни свайп-удаления (см. isSelfRestrictedView).
        if (targetIsProtectedDev) {
            rows.add(Row.Plaque)
        } else if (!isSelfRestrictedView) {
            val sortedUser = userMessages.sortedByDescending { it.timestampMs }
            val visibleUser = sortedUser.take(visibleLimit)
            rows.ensureCapacity(1 + visibleUser.size + deletedRows.size + 2)
            rows.add(Row.Section(getString(R.string.stats_section_all), userMessages.size))
            visibleUser.forEach { rows.add(Row.MsgRow(it)) }
            if (deletedRows.isNotEmpty()) {
                rows.add(Row.Section(getString(R.string.stats_section_deleted), deletedRows.size))
                rows.addAll(deletedRows)
            }
        }
        adapter.submit(rows)
    }

    private fun onPeriodChanged(period: StatsUtil.Period) {
        if (currentPeriod == period) return
        currentPeriod = period
        adapter.notifyItemChanged(0)
    }

    // ── Свайп: удалить (MsgRow) или восстановить (DeletedRow) ───────────────────

    private fun onSwipeAction(position: Int) {
        when (val row = adapter.rowAt(position)) {
            is Row.MsgRow -> {
                val msg = row.msg
                NeonDialog.showConfirm(
                    ctx = this,
                    title = getString(R.string.dialog_delete_title),
                    message = getString(R.string.dialog_delete_message),
                    positiveText = getString(R.string.action_delete),
                    positiveIsDestructive = true,
                    negativeText = getString(R.string.btn_cancel)
                ) { performDelete(msg) }
            }
            is Row.DeletedRow -> restoreMessage(row.msg)
            else -> {}
        }
    }

    private fun performDelete(msg: Message) {
        // Оптимистично убираем из "всех сообщений" и сразу показываем в "удалённых" —
        // это МОДЕРАЦИЯ админом (только админ видит этот экран/свайп), поэтому атрибуция
        // однозначна без пересчёта pubkey. Запоминаем raw как "ожидает подтверждения" —
        // иначе следующий live-refresh (см. pendingDeletedRaw) вернёт сообщение обратно,
        // пока надгробие не долетело до реле.
        val deletedRow = Row.DeletedRow(msg, System.currentTimeMillis(), getString(R.string.stats_deleted_by_admin))
        pendingDeletedRaw.add(msg.rawEncrypted)
        pendingDeletedRows[msg.rawEncrypted] = deletedRow

        userMessages = userMessages.filter { it.rawEncrypted != msg.rawEncrypted }
        val currentRows = adapter.currentRows().toMutableList()
        val idx = currentRows.indexOfFirst { it is Row.MsgRow && it.msg.rawEncrypted == msg.rawEncrypted }
        if (idx >= 0) currentRows.removeAt(idx)
        val sectionDeletedIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_deleted) }
        if (sectionDeletedIdx >= 0) {
            currentRows.add(sectionDeletedIdx + 1, deletedRow)
            val old = currentRows[sectionDeletedIdx] as Row.Section
            currentRows[sectionDeletedIdx] = old.copy(count = old.count + 1)
        } else {
            currentRows.add(Row.Section(getString(R.string.stats_section_deleted), 1))
            currentRows.add(deletedRow)
        }
        val sectionAllIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_all) }
        if (sectionAllIdx >= 0) {
            val old = currentRows[sectionAllIdx] as Row.Section
            currentRows[sectionAllIdx] = old.copy(count = max(0, old.count - 1))
        }
        adapter.submit(currentRows)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { transport.deleteLine(msg.rawEncrypted) }
            } catch (e: Exception) {
                Toast.makeText(this@UserStatsActivity, R.string.error_delete, Toast.LENGTH_SHORT).show()
                // Не откатываем локально — следующий refreshData() (onResume/pull/live-стрим)
                // подтянет реальное состояние с реле; надгробие best-effort ретраится транспортом.
            }
        }
    }

    /**
     * Восстанавливает удалённое сообщение — свайп в секции «Удалённые».
     *
     * Протокольное ограничение: надгробие (del) в Nostr привязано к ХЕШУ содержимого
     * (см. NostrMessageStore.delHash) и не снимается — повторная публикация БАЙТ-В-БАЙТ
     * той же строки так и останется скрытой. Поэтому "восстановление" — это расшифровка
     * исходного текста и публикация НОВЫМ событием (свежий IV даёт другой шифртекст →
     * другой delHash → сообщение снова видно всем). Видимое содержимое (текст, время,
     * автор) не меняется — они закодированы в самом plaintext, не в оболочке события.
     */
    private fun restoreMessage(msg: Message) {
        pendingDeletedRaw.remove(msg.rawEncrypted)
        pendingDeletedRows.remove(msg.rawEncrypted)

        // Оптимистично: убираем строку из "удалённых", возвращаем в "все сообщения".
        val currentRows = adapter.currentRows().toMutableList()
        val idx = currentRows.indexOfFirst { it is Row.DeletedRow && it.msg.rawEncrypted == msg.rawEncrypted }
        if (idx >= 0) currentRows.removeAt(idx)
        val sectionDeletedIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_deleted) }
        if (sectionDeletedIdx >= 0) {
            val old = currentRows[sectionDeletedIdx] as Row.Section
            if (old.count <= 1) {
                // Секция и заголовок больше не нужны — убираем полностью.
                currentRows.removeAt(sectionDeletedIdx)
            } else {
                currentRows[sectionDeletedIdx] = old.copy(count = old.count - 1)
            }
        }
        val sectionAllIdx = currentRows.indexOfFirst { it is Row.Section && it.title == getString(R.string.stats_section_all) }
        val msgRow = Row.MsgRow(msg)
        if (sectionAllIdx >= 0) {
            val old = currentRows[sectionAllIdx] as Row.Section
            currentRows[sectionAllIdx] = old.copy(count = old.count + 1)
            // Вставляем сразу после заголовка секции — порядок внутри секции всё равно
            // пересортируется по времени на следующем buildRows() из настоящих данных.
            currentRows.add(sectionAllIdx + 1, msgRow)
        }
        userMessages = userMessages + msg
        adapter.submit(currentRows)

        lifecycleScope.launch {
            try {
                val plaintext = withContext(Dispatchers.Default) {
                    CryptoHelper.decrypt(msg.rawEncrypted, chatPassword, networkChatId)
                } ?: throw IllegalStateException("decrypt failed")
                val freshCiphertext = withContext(Dispatchers.Default) {
                    CryptoHelper.encrypt(plaintext, chatPassword, networkChatId)
                }
                withContext(Dispatchers.IO) { transport.appendLine(freshCiphertext) }
            } catch (e: Exception) {
                Toast.makeText(this@UserStatsActivity, R.string.error_restore, Toast.LENGTH_SHORT).show()
                // Не откатываем локально — следующий refreshData() подтянет реальное
                // состояние с реле (если публикация не удалась, сообщение снова уедет
                // в "удалённые" на очередном обновлении).
            }
        }
    }

    // ── Переход к сообщению в чате (долгое нажатие) ─────────────────────────────

    private fun jumpToMessage(msg: Message) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chatRoomId)
            putExtra(ChatActivity.EXTRA_SCROLL_TO_MSGID, msg.msgId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
    }

    /** Полноэкранный просмотр фото прямо из списка сообщений участника — тот же принцип,
     *  что и ChatActivity.openImageFullscreenByRef (см. §11: используем уже проверенный путь,
     *  не изобретаем новый транспорт для медиа). */
    private fun openImageFullscreenByRef(refs: List<String>, startIndex: Int) {
        val ref = refs[startIndex]

        fun openViewer() {
            startActivity(Intent(this, ImageViewActivity::class.java).apply {
                putExtra(ImageViewActivity.EXTRA_REFS, ArrayList(refs))
                putExtra(ImageViewActivity.EXTRA_START_INDEX, startIndex)
            })
        }

        if (ImageCache.getBitmap(ref) != null) { openViewer(); return }

        val base64 = ImageCache.getBase64(ref)
        if (base64 != null) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { ImageUtils.fromBase64(base64) }
                if (bitmap != null) ImageCache.put(ref, base64, bitmap)
                openViewer()
            }
            return
        }

        lifecycleScope.launch {
            val bitmap = imageLoader.loadBitmap(ref)
            if (bitmap != null) openViewer()
            else Toast.makeText(this@UserStatsActivity, R.string.error_image_load, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Adapter ──────────────────────────────────────────────────────────────

    private inner class RowsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()
        private val density = resources.displayMetrics.density
        private fun dp(v: Int) = (v * density).toInt()

        fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }
        fun currentRows(): List<Row> = rows
        fun rowAt(position: Int): Row? = rows.getOrNull(position)

        override fun getItemCount(): Int = rows.size

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> 0
            is Row.Section -> 1
            is Row.MsgRow -> 2
            is Row.DeletedRow -> 3
            is Row.Plaque -> 4
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inf.inflate(R.layout.item_stats_header, parent, false))
                1 -> SectionVH(inf.inflate(R.layout.item_stats_section, parent, false))
                2 -> MsgVH(inf.inflate(R.layout.item_stats_message, parent, false))
                3 -> DeletedVH(inf.inflate(R.layout.item_stats_deleted, parent, false))
                else -> PlaqueVH(inf.inflate(R.layout.item_stats_plaque, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> (holder as HeaderVH).bind()
                is Row.Section -> (holder as SectionVH).bind(row)
                is Row.MsgRow -> (holder as MsgVH).bind(row.msg)
                is Row.DeletedRow -> (holder as DeletedVH).bind(row)
                is Row.Plaque -> Unit // статичная плашка, привязывать нечего
            }
        }

        /** Свайпать можно активные сообщения (удалить) и удалённые (восстановить) —
         *  не шапку/раздел. */
        fun isSwipeable(position: Int): Boolean =
            rows.getOrNull(position).let { it is Row.MsgRow || it is Row.DeletedRow }

        // ── Header: сегменты + диаграммы + сводка ───────────────────────────────
        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            private val segDay: TextView = v.findViewById(R.id.seg_day)
            private val segWeek: TextView = v.findViewById(R.id.seg_week)
            private val segMonth: TextView = v.findViewById(R.id.seg_month)
            private val segYear: TextView = v.findViewById(R.id.seg_year)
            private val barChart: LinearLayout = v.findViewById(R.id.bar_chart)
            private val barLabels: LinearLayout = v.findViewById(R.id.bar_labels)
            private val hourChart: LinearLayout = v.findViewById(R.id.hour_chart)
            private val chartHint: TextView = v.findViewById(R.id.tv_chart_hint)
            private val peakHint: TextView = v.findViewById(R.id.tv_peak_hint)
            private val statTotal: TextView = v.findViewById(R.id.stat_total)
            private val statPerDay: TextView = v.findViewById(R.id.stat_per_day)
            private val statPeakHour: TextView = v.findViewById(R.id.stat_peak_hour)
            private val statActiveDays: TextView = v.findViewById(R.id.stat_active_days)
            private val gestureHint: TextView = v.findViewById(R.id.tv_gesture_hint)

            fun bind() {
                // Подсказка про свайп/удаление относится только к разделу "Все сообщения",
                // которого нет ни в урезанной "моей статистике" (isSelfRestrictedView), ни у
                // верифицированного разработчика (targetIsProtectedDev — там плашка).
                gestureHint.visibility =
                    if (isSelfRestrictedView || targetIsProtectedDev) View.GONE else View.VISIBLE
                val segs = listOf(
                    segDay to StatsUtil.Period.DAY, segWeek to StatsUtil.Period.WEEK,
                    segMonth to StatsUtil.Period.MONTH, segYear to StatsUtil.Period.YEAR
                )
                for ((tv, period) in segs) {
                    val selected = period == currentPeriod
                    tv.setBackgroundResource(if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_default)
                    tv.setTextColor(ContextCompat.getColor(itemView.context, if (selected) R.color.accent_light else R.color.text_secondary))
                    tv.setOnClickListener { onPeriodChanged(period) }
                }

                val bucketCount = when (currentPeriod) {
                    StatsUtil.Period.DAY -> 14
                    StatsUtil.Period.WEEK -> 8
                    StatsUtil.Period.MONTH -> 12
                    StatsUtil.Period.YEAR -> 5
                }
                chartHint.text = itemView.context.getString(when (currentPeriod) {
                    StatsUtil.Period.DAY -> R.string.stats_activity_hint_day
                    StatsUtil.Period.WEEK -> R.string.stats_activity_hint_week
                    StatsUtil.Period.MONTH -> R.string.stats_activity_hint_month
                    StatsUtil.Period.YEAR -> R.string.stats_activity_hint_year
                })

                val buckets = StatsUtil.buckets(userMessages, currentPeriod, bucketCount)
                val maxVal = (buckets.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
                barChart.removeAllViews()
                barLabels.removeAllViews()
                val chartHeightPx = dp(90)
                for ((label, value) in buckets) {
                    val bar = View(itemView.context).apply {
                        val h = if (value == 0) dp(2) else max(dp(2), (chartHeightPx * value / maxVal))
                        layoutParams = LinearLayout.LayoutParams(0, h, 1f).also { it.marginEnd = dp(2) }
                        setBackgroundColor(ContextCompat.getColor(
                            context, if (value == maxVal && value > 0) R.color.accent else R.color.accent_dark
                        ))
                    }
                    barChart.addView(bar)
                    val lbl = TextView(itemView.context).apply {
                        text = label
                        textSize = 9f
                        gravity = android.view.Gravity.CENTER
                        setTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    barLabels.addView(lbl)
                }

                val hourCounts = StatsUtil.hourHistogram(userMessages)
                val maxHour = (hourCounts.maxOrNull() ?: 0).coerceAtLeast(1)
                val peakHourIdx = hourCounts.indices.maxByOrNull { hourCounts[it] } ?: 0
                hourChart.removeAllViews()
                val hourChartHeightPx = dp(60)
                for (h in 0 until 24) {
                    val v = hourCounts[h]
                    val bar = View(itemView.context).apply {
                        val hh = if (v == 0) dp(2) else max(dp(2), (hourChartHeightPx * v / maxHour))
                        layoutParams = LinearLayout.LayoutParams(0, hh, 1f).also { it.marginEnd = dp(1) }
                        setBackgroundColor(ContextCompat.getColor(
                            context, if (h == peakHourIdx && v > 0) R.color.accent else R.color.accent_dark
                        ))
                    }
                    hourChart.addView(bar)
                }
                peakHint.text = if (userMessages.isEmpty()) itemView.context.getString(R.string.stats_no_data)
                    else itemView.context.getString(R.string.stats_peak_hint, "%02d:00".format(peakHourIdx))

                val activeDays = StatsUtil.activeDaysCount(userMessages)
                statTotal.text = userMessages.size.toString()
                statPerDay.text = if (activeDays > 0) "%.1f".format(userMessages.size.toDouble() / activeDays) else "0"
                statPeakHour.text = if (userMessages.isEmpty()) "—" else "%02d:00".format(peakHourIdx)
                statActiveDays.text = activeDays.toString()
            }
        }

        inner class SectionVH(v: View) : RecyclerView.ViewHolder(v) {
            private val title: TextView = v.findViewById(R.id.tv_section_title)
            private val count: TextView = v.findViewById(R.id.tv_section_count)
            fun bind(row: Row.Section) {
                title.text = row.title
                count.text = row.count.toString()
            }
        }

        /** Плашка «Запрещаю за собой подсматривать» — статична, вся вёрстка в layout. */
        inner class PlaqueVH(v: View) : RecyclerView.ViewHolder(v)

        inner class MsgVH(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(R.id.tv_msg_time)
            private val text: TextView = v.findViewById(R.id.tv_msg_text)
            private val quoteBlock: LinearLayout = v.findViewById(R.id.ll_msg_quote)
            private val quoteSender: TextView = v.findViewById(R.id.tv_msg_quote_sender)
            private val quoteText: TextView = v.findViewById(R.id.tv_msg_quote_text)
            private val flPhoto: FrameLayout = v.findViewById(R.id.fl_msg_photo)
            private val ivPhoto: ShapeableImageView = v.findViewById(R.id.iv_msg_photo)
            private val llVoice: LinearLayout = v.findViewById(R.id.ll_msg_voice)
            private val ivVoicePlay: ImageView = v.findViewById(R.id.iv_msg_voice_play)
            private val voiceProgress: View = v.findViewById(R.id.v_msg_voice_progress)
            private val tvVoiceDur: TextView = v.findViewById(R.id.tv_msg_voice_dur)

            fun bind(msg: Message) {
                time.text = StatsUtil.formatMessageTime(itemView.context, msg.timestampMs)

                // Ветка ответа — цитата оригинала прямо в общей хронологии (по запросу
                // пользователя, см. CLAUDE.md-сессию: "ветки ответов там же по хронологии").
                if (msg.isReply) {
                    quoteBlock.visibility = View.VISIBLE
                    quoteSender.text = msg.quotedSender
                    quoteText.text = msg.quotedText
                } else {
                    quoteBlock.visibility = View.GONE
                }

                flPhoto.visibility = View.GONE
                llVoice.visibility = View.GONE
                text.visibility = View.GONE

                when {
                    msg.isVoice -> bindVoice(msg)
                    msg.isImage || msg.isMultiImage -> bindPhoto(msg)
                    else -> {
                        text.visibility = View.VISIBLE
                        text.text = previewText(itemView.context, msg)
                    }
                }

                // Стандартный OnLongClickListener — уже корректно уживается с ItemTouchHelper
                // (свайп) на уровне фреймворка, в отличие от ручного отслеживания ACTION_MOVE
                // (тот же паттерн долгого нажатия, что и в MediaListActivity.select()).
                itemView.setOnLongClickListener {
                    it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    jumpToMessage(msg)
                    true
                }
            }

            /** Миниатюра фото — тап открывает полноэкранный просмотрщик (тот же путь, что и
             *  в чате, см. UserStatsActivity.openImageFullscreenByRef). Подпись к фото (если
             *  есть) показывается отдельной строкой под миниатюрой. */
            private fun bindPhoto(msg: Message) {
                flPhoto.visibility = View.VISIBLE
                if (msg.text.isNotBlank()) {
                    text.visibility = View.VISIBLE
                    text.text = msg.text
                }
                val refs = msg.imageFileNames?.takeIf { it.isNotEmpty() }
                    ?: msg.imageFileName?.let { listOf(it) }
                val ref = refs?.firstOrNull()
                ivPhoto.setImageDrawable(null)
                if (ref != null) {
                    ImageCache.getBitmap(ref)?.let { ivPhoto.setImageBitmap(it) }
                        ?: run {
                            ivPhoto.tag = ref
                            lifecycleScope.launch {
                                val bmp = imageLoader.loadBitmap(ref)
                                if (bmp != null && ivPhoto.tag == ref) ivPhoto.setImageBitmap(bmp)
                            }
                        }
                    flPhoto.setOnClickListener { openImageFullscreenByRef(refs, 0) }
                } else if (msg.imageBase64 != null) {
                    val b64 = msg.imageBase64
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.Default) { ImageUtils.fromBase64(b64) }
                        if (bmp != null) ivPhoto.setImageBitmap(bmp)
                    }
                    flPhoto.setOnClickListener {
                        ImageViewActivity.pendingBase64 = b64
                        startActivity(Intent(this@UserStatsActivity, ImageViewActivity::class.java))
                    }
                } else {
                    flPhoto.setOnClickListener(null)
                }
            }

            /** Воспроизведение голосового прямо в списке — тот же VoicePlayer/паттерн, что
             *  и лента сообщений-оснований мута (ChatActivity.addMuteEvidenceBubble). */
            private fun bindVoice(msg: Message) {
                llVoice.visibility = View.VISIBLE
                tvVoiceDur.text = "0:%02d".format(msg.voiceDurationSec.coerceAtLeast(0))
                fun refreshIcon() {
                    ivVoicePlay.setImageResource(if (VoicePlayer.isPlaying(msg.msgId)) R.drawable.ic_pause else R.drawable.ic_play)
                }
                refreshIcon()
                voiceProgress.layoutParams = voiceProgress.layoutParams.apply { width = 0 }
                llVoice.setOnClickListener {
                    val ref = msg.voiceFileName ?: return@setOnClickListener
                    lifecycleScope.launch {
                        val dir = File(cacheDir, "voice_play").apply { mkdirs() }
                        val f = File(dir, "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
                        val file = if (f.exists() && f.length() > 0) f else {
                            val bytes = withContext(Dispatchers.IO) { imageLoader.loadRawBytes(ref) }
                            if (bytes == null) null else {
                                try { f.writeBytes(bytes); f } catch (_: Exception) { null }
                            }
                        }
                        if (file == null) {
                            Toast.makeText(this@UserStatsActivity, R.string.voice_load_failed, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        VoicePlayer.toggle(
                            key = msg.msgId,
                            file = file,
                            onProgress = { _, posMs, durMs ->
                                voiceProgress.layoutParams = voiceProgress.layoutParams.apply {
                                    width = (voiceProgress.parent as View).width * posMs / durMs.coerceAtLeast(1)
                                }
                                voiceProgress.requestLayout()
                                refreshIcon()
                            },
                            onComplete = {
                                voiceProgress.layoutParams = voiceProgress.layoutParams.apply { width = 0 }
                                voiceProgress.requestLayout()
                                refreshIcon()
                            }
                        )
                        refreshIcon()
                    }
                }
            }
        }

        inner class DeletedVH(v: View) : RecyclerView.ViewHolder(v) {
            private val time: TextView = v.findViewById(R.id.tv_del_time)
            private val tag: TextView = v.findViewById(R.id.tv_del_tag)
            private val text: TextView = v.findViewById(R.id.tv_del_text)
            private val meta: TextView = v.findViewById(R.id.tv_del_meta)
            fun bind(row: Row.DeletedRow) {
                time.text = StatsUtil.formatMessageTime(itemView.context, row.msg.timestampMs)
                text.text = previewText(itemView.context, row.msg)
                tag.background = tag.background?.mutate()
                (tag.background as? android.graphics.drawable.GradientDrawable)?.setColor(
                    ColorUtils.setAlphaComponent(ContextCompat.getColor(itemView.context, R.color.error), 0x24)
                )
                meta.text = itemView.context.getString(
                    R.string.stats_deleted_meta, row.deleterLabel, StatsUtil.formatMessageTime(itemView.context, row.deletedAtMs)
                )
            }
        }

        private fun previewText(ctx: android.content.Context, msg: Message): String = when {
            msg.isVoice -> ctx.getString(R.string.msg_preview_voice)
            msg.isSticker -> ctx.getString(R.string.msg_preview_sticker)
            msg.isMultiImage || msg.isImage -> ctx.getString(R.string.msg_preview_photo)
            msg.text.isNotBlank() -> msg.text
            else -> ""
        }
    }

    /**
     * Свайп влево — удалить (MsgRow) или восстановить (DeletedRow); тот же паттерн, что
     * SwipeToReplyCallback: порог, вибро-триггер и снэп-бэк, НЕ постоянное открытие —
     * после срабатывания строка сама возвращается на место. Иконка/цвет подсказки
     * подбираются под тип строки под пальцем (красный+корзина для удаления, фиолетовый+
     * восстановление для уже удалённых) — см. RowsAdapter.isSwipeable для разрешённых типов.
     */
    private class SwipeToDeleteCallback(
        context: android.content.Context,
        private val onDelete: (position: Int) -> Unit
    ) : ItemTouchHelper.Callback() {

        private val density = context.resources.displayMetrics.density
        private val trashIcon = ContextCompat.getDrawable(context, R.drawable.ic_trash_menu)!!
        private val restoreIcon = ContextCompat.getDrawable(context, R.drawable.ic_restore)!!
        private val deleteColor = ContextCompat.getColor(context, R.color.error)
        private val restoreColor = ContextCompat.getColor(context, R.color.accent_light)
        private val deleteBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(deleteColor, 0x33)
        }
        private val restoreBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(restoreColor, 0x33)
        }

        private var hasTriggered = false
        private var activeViewHolder: RecyclerView.ViewHolder? = null

        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
            val adapter = recyclerView.adapter as? RowsAdapter
            if (adapter != null && !adapter.isSwipeable(viewHolder.bindingAdapterPosition)) return makeMovementFlags(0, 0)
            if (activeViewHolder != null && activeViewHolder != viewHolder) return makeMovementFlags(0, 0)
            return makeMovementFlags(0, ItemTouchHelper.LEFT)
        }

        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false
        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 10f
        override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 20f
        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onChildDraw(
            c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
            dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
        ) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                val itemView = viewHolder.itemView
                val isRestore = (recyclerView.adapter as? RowsAdapter)
                    ?.rowAt(viewHolder.bindingAdapterPosition) is Row.DeletedRow
                val icon = if (isRestore) restoreIcon else trashIcon
                val tintColor = if (isRestore) restoreColor else deleteColor
                val bgPaint = if (isRestore) restoreBgPaint else deleteBgPaint

                val maxTranslation = 80f * density
                val clamped = if (abs(dX) > maxTranslation) -(maxTranslation + (abs(dX) - maxTranslation) * 0.2f) else dX
                val finalDx = clamped.coerceAtMost(0f)
                itemView.translationX = finalDx

                if (isCurrentlyActive && abs(finalDx) >= maxTranslation * 0.8f && !hasTriggered) {
                    hasTriggered = true
                    itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) onDelete(pos)
                }

                if (abs(finalDx) > 0) {
                    val progress = (abs(finalDx) / maxTranslation).coerceIn(0f, 1f)
                    c.drawRect(itemView.right + finalDx, itemView.top.toFloat(), itemView.right.toFloat(), itemView.bottom.toFloat(), bgPaint)
                    val iconSize = (22f * density).toInt()
                    val margin = (18f * density).toInt()
                    val cx = itemView.right - margin - iconSize / 2f
                    val cy = itemView.top + itemView.height / 2f
                    val half = (iconSize / 2f * (0.5f + 0.5f * progress)).toInt()
                    icon.setTint(tintColor)
                    icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                    icon.alpha = (255 * progress).toInt().coerceIn(0, 255)
                    icon.draw(c)
                }
            } else {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                activeViewHolder = viewHolder
                hasTriggered = false
            }
            super.onSelectedChanged(viewHolder, actionState)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            viewHolder.itemView.translationX = 0f
            super.clearView(recyclerView, viewHolder)
            if (activeViewHolder == viewHolder) { activeViewHolder = null; hasTriggered = false }
        }
    }
}
