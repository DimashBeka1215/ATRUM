package com.atrum.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.Manifest
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.annotation.SuppressLint
import java.io.File
import android.widget.Toast
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityChatBinding
import com.atrum.chat.transport.AllChannelData
import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.BluetoothTransport
import com.atrum.chat.transport.TransportFactory
import android.text.Editable
import android.text.TextWatcher
import com.atrum.chat.stickers.StickerAdapter
import com.atrum.chat.stickers.StickerPanelController
import com.atrum.chat.stickers.StickerRepository
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.collectLatest
import android.content.res.Configuration
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ChatActivity : SecureActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private val voiceRecorder by lazy { VoiceRecorder(this) }
    private var voiceUiJob: Job? = null
    private val hideVoiceLimitRunnable = Runnable { hideVoiceLimitBanner() }
    private var recordCancelled = false
    private var recordStartX = 0f
    private var recordStartY = 0f
    private var voiceLocked = false
    private val recordLevels = ArrayList<Int>()
    private val cancelThresholdPx by lazy { 120f * resources.displayMetrics.density }
    private val lockThresholdPx by lazy { 90f * resources.displayMetrics.density }
    private val requestAudioPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.voice_need_mic, Toast.LENGTH_SHORT).show()
    }

    private lateinit var transport: ChatTransport
    private lateinit var transportFactory: TransportFactory
    private lateinit var chat: Chat
    private lateinit var adapter: MessageAdapter
    private var imageLoader: ImageLoader? = null

    /**
     * Адаптивная очередь загрузки изображений: max 3 параллельных загрузки,
     * retry с backoff, throttle при 429. Полностью изолирована от текстового pipeline.
     */
    private val imageUploadQueue = ImageUploadQueue()

    // Сторож долгой заливки фото/коллажа: если заливка не завершится (ни успехом, ни
    // ошибкой) за это время — считаем её зависшей и переводим сообщение в failSend с
    // диагностикой в логе (а НЕ роняем приложение — краш при сбое отправки запрещён,
    // см. CLAUDE.md "ATRUM — мессенджер на каждый день"). 90с — с учётом того, что
    // прямой путь через кастомный SOCKS5-прокси и Tor теперь ждут кворум до 20с НА
    // КАЖДЫЙ чанк (см. NostrTransport.viaCustomProxy), а фото может состоять из
    // нескольких чанков + манифест + одна повторная попытка на каждый.
    private val UPLOAD_HANG_CRASH_MS = 90_000L

    /**
     * Счётчик активных загрузок изображений. При count > 0 поле ввода и кнопки
     * блокируются (UI state). Текстовая очередь (MessageSendManager) не затрагивается.
     */
    private val activeImageUploads = AtomicInteger(0)

    // ── Новая архитектура: SyncEngine + PatchQueue + ChatStore ───────────────

    /** Единый ETag-polling engine. Заменяет pollJob. */
    private lateinit var syncEngine: SyncEngine
    /** BT-чат (локальный по Bluetooth): голос/медиа отключены, доставка по BLE. */
    private var btMode: Boolean = false
    /** Подписки на входящие события транспорта (сообщения/профили) для мгновенного обновления. */
    private var transportWatch: AutoCloseable? = null
    private var profilesWatch: AutoCloseable? = null
    /** Мониторинг сети: восстановление доставки при возврате связи (§1.5). */
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var networkWasLost = false

    /** Сериализованная очередь всех PATCH-запросов. Заменяет прямые saveFile/appendLine вызовы. */
    private lateinit var patchQueue: PatchQueue

    /** Local-first хранилище сообщений. Source of truth для UI. */
    private val chatStore = ChatStore()

    /** Coroutine собирающая события syncEngine в процессор данных. */
    private var syncCollectorJob: Job? = null
    /** Периодический тикер отзыва/владения (revoke.txt/owner.txt) — независим от контента чата. */
    private var ownerRevokeTickerJob: Job? = null
    /** Приветственная плашка беседы, пока показана (уходит плавно при первом сообщении). */
    private var groupWelcomeCard: android.view.View? = null

    /**
     * Лёгкий ре-рид ЛОКАЛЬНОГО чата (Избранное / системный «Уведомления»). Эти чаты не
     * ходят в сеть, поэтому SyncEngine для них не запускается (startPolling), но их файл
     * на диске может меняться извне — уведомления пишет SystemNotifications напрямую в
     * LocalTransport, минуя ChatActivity. Без этого цикла оверлей загрузки висел бы до
     * 15-сек страховки, а лента оставалась пустой (репорт: «грузится вечно, чат пустой,
     * хотя в списке уведомление видно»). Это НЕ сетевой поллинг реле (§1) — только чтение
     * локального файла; processChannelData сам делает дедуп по контенту (без мерцания).
     */
    private var localRefreshJob: Job? = null

    // ── Warning banner ────────────────────────────────────────────────────────

    /** Типы мягких предупреждений. Каждый имеет свой текст, но одинаковый визуал. */
    private enum class WarningType { TOKEN, RATE_LIMIT, NETWORK, FORWARD_SECRECY, TOR, STICKER_ANIM, GROUP_PENDING }

    /** Текущий активный тип предупреждения, null если баннер скрыт. */
    private var activeWarning: WarningType? = null
    private var rememberChecked = false

    // ── Loading state ─────────────────────────────────────────────────────────

    /** false до момента первой успешной загрузки сообщений. */
    private var firstLoadComplete = false
    private val TAIL_FIRST = 30   // сколько последних сообщений показать сразу на первой загрузке
    // Гейт показа чата: держим loading overlay пока не установится сессия (или таймаут).
    private var contentLoaded = false

    /**
     * Счётчик последовательных ошибок одного типа.
     * Баннер показывается только после [FAILURES_BEFORE_WARNING] неудачных попыток —
     * чтобы разовый сетевой сбой не вызывал мелькания.
     */
    private var consecutiveFailures = 0

    /**
     * Метка времени последнего засчитанного события сетевой ошибки.
     *
     * Проблема: несколько одновременных coroutine loadMessages (из polling-цикла
     * и из onMessageSent) могут подряд упасть из-за ОДНОГО сетевого сбоя и каждая
     * инкрементирует consecutiveFailures — порог FAILURES_BEFORE_WARNING достигается
     * за одну «реальную» ошибку, и баннер показывается ложно.
     *
     * Решение: одно «событие ошибки» засчитывается не чаще раза в 2 сек.
     * Два падения в пределах 2 сек считаются одной ошибкой.
     */
    private var lastFailureEventMs = 0L
    /** Счётчик последовательных poll'ов с V4-S/V3-сообщениями без сессионного ключа. */
    private var lockedV3ConsecutiveCount = 0
    /**
     * Сколько подряд таких тиков должно пройти, прежде чем показать FS-баннер.
     * Через Tor ECDH-handshake (обмен ephemeral-ключами через profiles.txt) может
     * занимать заметно больше прежних 18 c → баннер вылетал ложно, хотя история не
     * терялась. 24 тика ≈ 72 c (при 3-сек поллинге) — баннер только если ключ реально
     * не устанавливается долго (партнёр ушёл / старый клиент), а не из-за медленной сети.
     */
    private val FS_BANNER_MIN_TICKS = 24

    // pollJob заменён на syncEngine (см. выше)
    private var lastContent: String = ""

    /** Задержка перед пометкой "прочитано" — минимальная, чтобы галочки приходили быстро. */
    private val markAsReadDelayMs = 500L
    private var markAsReadJob: Job? = null

    /** Кэш последнего отправленного в канал значения lastReadIndex — чтобы не пушить впустую. */
    private var lastPushedReadIndex: Int = -1

    /** Сообщение, на которое мы сейчас отвечаем (null = обычное сообщение). */
    private var replyingTo: Message? = null

    /** Последний загруженный список сообщений — для selection mode (copy/delete). */
    private var currentMessages: List<Message> = emptyList()

    // ── Закреплённые сообщения (Этап 3) ──────────────────────────────────────────
    /** Показываемый набор закреплённых msgId (слитый, из Room chat.pinnedMsgIds). */
    private var pinnedIds: List<String> = emptyList()
    /** Мои вклады в закрепления (Room chat.myPinnedMsgIds) — что могу открепить сам. */
    private var myPinnedIds: List<String> = emptyList()

    // ── Подлинность авторства (ADR_MESSAGE_AUTHENTICITY.md, Фаза 2) ───────────────
    /** msgId → состояние подлинности (VERIFIED/FORGED/UNSIGNED). Заполняется best-effort
     *  на тике синка (syncMessageAuth). Пишется из IO, читается при рендере — потокобезопасно.
     *  UI ещё не использует (Фаза 4); поведение приложения не меняется. */
    private val msgAuthByMsgId = java.util.concurrent.ConcurrentHashMap<String, MsgAuth>()
    /** Троттл фоновой подписи своих сообщений (не чаще раза в N мс). */
    @Volatile private var lastAuthSignMs = 0L

    /** ts оффера передачи владения, для которого уже показано полноэкранное окно (анти-дубль). */
    @Volatile private var lastLaunchedOfferTs = 0L
    /** Троттл чтения owner.txt: передача владения редка, не дёргаем реле каждый тик (нагрузка). */
    @Volatile private var lastOwnerCertCheckMs = 0L
    @Volatile private var lastRevokeCheckMs = 0L
    /** Мои права в этой группе (маска, из моей записи ChatParticipant). */
    @Volatile private var myGroupPermissions: Int = 0
    /** Индекс текущего показываемого пина в плашке (листается тапом). */
    private var currentPinIndex: Int = 0
    /** Активны ли обои/glass сейчас (для плашки закреплённых — см. applyWallpaper). */
    private var chatHasWallpaper: Boolean = false

    /** Могу ли закреплять/откреплять: главный админ ИЛИ делегат с правом PIN. */
    private val groupCanPin: Boolean
        get() = chatIsAdmin || AdminPermissions.has(myGroupPermissions, AdminPermissions.PIN)

    // ── Упоминания (@) ───────────────────────────────────────────────────────────
    /** Один участник для меню упоминаний: userId + имя + тег (может быть null) + аватар. */
    data class MentionUser(val userId: String, val name: String, val tag: String?, val avatarBase64: String?)
    /** Кандидаты упоминания — активные участники группы с профилем (кроме меня). */
    @Volatile private var mentionCandidates: List<MentionUser> = emptyList()
    private var mentionAdapter: MentionAdapter? = null

    /** msgId упоминаний, к которым я уже перешёл в этой сессии (чтобы кнопка их не предлагала). */
    private val visitedMentionIds = HashSet<String>()
    private var mentionMenuPopup: android.widget.PopupWindow? = null

    // Отложенные действия из списка медиа (применяются после загрузки сообщений).
    private var pendingJumpMsgId: String? = null
    private var pendingDeleteMsgId: String? = null

    // deletedTombstones перенесены в ChatStore (chatStore.addTombstone / removeTombstone)

    // ── Reactions ─────────────────────────────────────────────────────────────

    /** Текущая карта реакций: msgId → emoji → Set<userId>. */
    private var currentReactions: Map<String, Map<String, Set<String>>> = emptyMap()

    /** Сырой (зашифрованный) контент reactions.txt с последнего poll'а — для ETag-сравнения. */
    private var lastReactionsRaw: String = ""

    /** Расшифрованный контент reactions.txt — для парсинга и манипуляций в handleReactionToggle. */
    private var lastReactionsContent: String = ""

    /**
     * Сырой (зашифрованный, уже проверенный транспортом по подписи админа) контент
     * members.txt с последнего poll'а — для дедупа (ADR-001, групповые чаты).
     */
    private var lastMembersRaw: String = ""

    /**
     * Дедуп слотов мультиподписи (Этап 2 «Админы»): подпись набора делегатских слотов
     * members.txt. Меняется, когда делегат опубликовал мут/бан БЕЗ роста версии главного —
     * тогда applyIncoming надо прогнать, хотя primary-контент не изменился (см. §1.5).
     */
    private var lastMemberSlotsSig: String = ""

    /** Лёгкая подпись набора слотов для дедупа (не расшифровывает — подписант + хеш контента).
     *  Хеш вместо длины: правка пинов/мута делегата ловится на первом же тике, даже если
     *  длина зашифрованного слота случайно не изменилась. */
    private fun memberSlotsSig(slots: List<com.atrum.chat.transport.MemberSlot>): String =
        slots.sortedBy { it.signerPubkey }.joinToString("|") { it.signerPubkey.take(12) + ":" + it.content.hashCode() }

    /**
     * Расшифрованный/распарсенный members.txt с реле (обновляется вместе с
     * [lastMembersRaw]) — для самопочинки админа (см. maybeAdminRepairMembersFile):
     * сравнение того, что РЕАЛЬНО лежит на реле, с локальным состоянием.
     */
    private var lastWireMembers: MembersSync.MembersFile? = null

    /** Троттл самопочинки members.txt у админа — не чаще раза в 30с. */
    private var lastMembersRepairAttemptMs: Long = 0L

    /** Троттл проверки истечения моего мута (уведомление «срок мута истёк»). */
    private var lastMuteExpiryCheckMs: Long = 0L

    /**
     * true — на реле лежит НЕПАРСИБЕЛЬНЫЙ members.txt (например, чанкованный манифест
     * "CHUNKED:N" от старой версии с тяжёлой авой — см. MembersSync.publish). Сигнал
     * самопочинке админа переопубликовать здоровую копию, иначе она молчала бы вечно
     * (lastWireMembers == null → ранний return).
     */
    private var lastWireMembersUnparseable: Boolean = false

    /** Дедуп сырого groupprofile.txt (профиль беседы, см. GroupProfileSync) — как lastMembersRaw. */
    private var lastGroupProfileRaw: String = ""

    /** ts профиля беседы, реально лежащего на реле (0 — отсутствует/не читали) — для самопочинки админа. */
    private var lastWireGroupProfileTs: Long = 0L

    /**
     * Кэш числа активных (не забаненных) участников группы — обновляется раз за
     * опрос в processChannelData(), читается синхронно каждую секунду из
     * applyGroupPresence() (тикер), чтобы не дёргать Room в горячем пути.
     */
    private var groupActiveParticipantCount: Int = 0

    /**
     * Я сам заглушён прямо сейчас (см. applySelfMuteState) — read-only режим: строка
     * ввода скрыта, реакции запрещены (см. handleReactionToggle). Обновляется на
     * каждом опросе, не только при открытии чата.
     */
    private var isSelfMuted: Boolean = false
    /** userId верифицированных разработчиков в этой беседе (PERSONAL_BUILD.md §Часть 3):
     *  их сообщения НЕ прячутся у остальных даже при бане/муте — они неприкосновенны.
     *  Заполняется там же, где считаются галочки отправителей (refreshMessageAvatars). */
    private var verifiedSenderIds: Set<String> = emptySet()

    /** msgId'ы сообщений-оснований текущего мута (см. ChatParticipant.mutedEvidenceIds). */
    private var currentMuteEvidenceIds: List<String> = emptyList()
    /** Полный декод последнего тика — источник для ленты оснований в карточке мута.
     *  ⚠️ ЧИСТО UI-кэш: на chatStore/фильтрацию ленты не влияет (мут развязан от синхрона). */
    private var lastAllDecodedMessages: List<Message> = emptyList()
    /** Мемо-ключ последней отрисованной ленты оснований — без реальных изменений не перестраиваем (без мерцания). */
    private var lastRenderedEvidenceKey: String? = null

    /** true — уже показали пользователю экран «вас исключили» и закрываем чат (анти-дубль). */
    private var groupBanHandled: Boolean = false

    /** true после первого опроса профилей группового чата в этой сессии (см. doSyncProfilesOnce). */
    private var groupJoinAnnounceInitialized: Boolean = false

    /** userId участников группы, чьё присоединение уже объявлено системным сообщением в этой сессии. */
    private val announcedJoinedUserIds = mutableSetOf<String>()

    // ── Гонка джойна в группу (ADR-001, §Пропагация бана / известное ограничение) ──
    // Приём в группу неатомарен: JoinChatActivity проверяет ёмкость по устаревшему
    // members.txt/приближённо по profiles.txt, а реальное добавление делает админ
    // фоном (maybeAdminEnrollNewMembers). Если лимит участников почти исчерпан и
    // несколько человек джойнятся одновременно — кто-то может остаться локально
    // "в чате", но так и не попасть в members.txt. Ниже — честный сигнал об этом
    // вместо тихого зависания в неопределённом состоянии (см. checkPendingGroupEnrollment).

    /** Подряд тиков, когда я (не админ) отсутствую в локальном ChatParticipant. Сбрасывается при подтверждении. */
    private var groupPendingTicks: Int = 0

    /**
     * Через сколько подряд тиков без подтверждения членства показываем баннер
     * «ожидаем подтверждения». Не сразу — обычное добавление админом занимает
     * один-два его опроса + распространение по реле, поднято, чтобы не мигать
     * баннером на нормальной, чуть более медленной, но штатной задержке
     * (тот же порядок величины, что и FS_BANNER_MIN_TICKS).
     */
    private val GROUP_PENDING_BANNER_TICKS = 20

    /** Локальные метки первого наблюдения кандидата в profiles.txt (для админа, честный FIFO-порядок enrollment'а). */
    private val groupCandidateFirstSeenMs = mutableMapOf<String, Long>()

    /**
     * ⚠️ Сериализация maybeAdminEnrollNewMembers() (найдено по репорту пользователя: у админа
     * список участников не совпадал с тем, что видели остальные — счётчик расходился на 1).
     * Функция вызывается из ДВУХ независимых мест — doSyncProfilesOnce() (при открытии чата,
     * с retry) и processChannelData() (на каждом тике SyncEngine) — оба suspend, оба могут
     * выполняться ПАРАЛЛЕЛЬНО (разные корутины). Без сериализации оба читают current/candidates
     * НЕЗАВИСИМЫМИ снимками и публикуют СВОИ версии members.txt с одним и тем же newVersion
     * (chat.membersVersion+1 из ещё не обновлённого in-memory chat) — выигрывает та, что реле
     * вернёт с более поздним created_at (см. NostrTransport.latestVerifiedMembersFile), а не
     * более полная. Именно так админ мог локально самовылечиться и увидеть себя+кандидатов,
     * а "победившая" по таймингу публикация от ВТОРОГО (параллельного) вызова — со СТАРЫМ,
     * ещё не долеченным snapshot'ом — стереть админа у всех остальных. Mutex гарантирует не
     * более одного одновременного цикла чтения-публикации на сессию чата.
     */
    private val memberEnrollMutex = kotlinx.coroutines.sync.Mutex()

    /** Менеджер отправки: token bucket + очередь + прогрессивные блокировки. */
    private lateinit var sendManager: MessageSendManager

    /** Корутина обратного отсчёта при блокировке (обновляет hint поля ввода). */
    private var countdownJob: Job? = null

    /** Корутина обратного отсчёта жёлтой плашки транспортного лимита (429 Too Many Requests). */
    private var transportLimitJob: Job? = null

    /** Оригинальный hint поля ввода (восстанавливается после блокировки). */
    private var originalHint: CharSequence = ""

    // ── Forward secrecy — X25519 ephemeral keypair ────────────────────────────

    /**
     * Приватный X25519-ключ текущей сессии. Генерируется при открытии чата,
     * уничтожается (fill(0)) в onDestroy.
     * null = сессия ещё не инициирована (до chat = loaded).
     */
    private var myEphemeralPrivKey: ByteArray? = null

    /**
     * Base64 нашего текущего X25519 публичного ключа.
     * Передаётся в Profile при каждом pushMyProfile чтобы партнёр мог
     * вычислить ECDH-общий секрет и установить сессионный ключ.
     */
    private var myCurrentEphemeralPubKey: String? = null
    /** Ed25519-подпись нашего эфемерного ключа (identity). Публикуется в профиле. */
    private var myEphemeralSig: String? = null
    /** Подпись-доказательство identity (домен+chatId), публикуется ВСЕГДА — см. computeIdentitySig. */
    private var myIdentitySig: String? = null

    /**
     * ephemeralPubKey последнего известного профиля партнёра.
     * Отслеживаем чтобы не пересчитывать сессионный ключ при каждом тике polling-а.
     */
    private var lastPartnerEphemeralPubKey: String? = null

    // ── Presence (typing + online) ────────────────────────────────────────────

    /** true = мы сейчас сигналим собеседнику что печатаем */
    private var isCurrentlyTyping = false

    /** Откладывает вызов stopTypingSignal() на 3 сек после последнего нажатия */
    private var stopTypingJob: Job? = null

    /**
     * Единый presence-цикл: каждые PRESENCE_INTERVAL_MS шлёт ОДИН PATCH с
     * обоими полями — typingTs (если печатаем) и onlineTs=now.
     *
     * Заменяет два раздельных job'а (typingPulseJob + onlineHeartbeatJob),
     * которые могли конкурентно PATCH-ать одни и те же profiles.txt и затирать
     * изменения друг друга.
     */
    private var presenceJob: Job? = null

    /**
     * true пока Activity на переднем плане (onResume → onPause).
     * Используется при создании Profile для pushMyProfile — гарантирует что
     * каждый push read-receipt не обнуляет onlineTs (без этого точка гасла бы
     * каждый раз когда пользователь читал сообщения).
     */
    private var isInForeground = false

    /** Последний полученный профиль партнёра — основа для presence-тикера. */
    @Volatile private var lastPartnerProfile: Profile? = null
    /** Тикер: раз в секунду пере-вычисляет онлайн/печать/запись по таймауту. */
    private var presenceTickerJob: Job? = null
    /** true пока МЫ записываем голосовое — шлём это партнёру в presence. */
    @Volatile private var isRecordingVoice = false

    /**
     * Последний снимок профилей из канала (обновляется в processParsedProfiles —
     * из processProfilesFromSlots/processProfilesFromContent, вызывается из
     * processChannelData() на КАЖДОМ тике SyncEngine, см. фикс "вошёл только после
     * первого сообщения" — раньше зависело от изменения chat.txt).
     * Используется для write-only typing/online пушей — 1 запрос вместо 2.
     * Максимальное «протухание» = ~4 сек (один цикл опроса).
     */
    private val lastKnownProfiles = mutableMapOf<String, Profile>()

    /** Хеш последнего полученного содержимого profiles.txt.
     * Позволяет пропустить парсинг если файл не изменился с прошлого polling-тика.
     * Снижает CPU-нагрузку при частом опросе (профили меняются только при presence-пуше).
     */
    private var lastProfilesHash: Int = 0

    /**
     * Живая карта userId → аватарка для аватарок в пузырьках сообщений (MessageAdapter).
     * Свой аватар — ВСЕГДА из prefs.myAvatarBase64: локально, без сети, поэтому меняется
     * мгновенно (включая случай "поменял аву в Настройках и вернулся в открытый чат" —
     * см. вызов в onResume()). Аватары остальных участников — из lastKnownProfiles, той
     * же карты, что уже питает шапку/presence/typing (единая точка правды, никакого
     * отдельного пути синхронизации — см. CLAUDE.md §11 и §1.5: обновление должно быть
     * видно сразу, без выхода-входа). adapter.updateAvatars() сам сравнивает старое/новое
     * значение и точечно перебиндивает только реально изменившиеся строки.
     */
    /**
     * @param source Карта профилей для аватарок. По умолчанию — [lastKnownProfiles] (для
     *   вызовов вне сетевого чтения — seed своей аватарки в onCreate/onResume). При вызове
     *   ИЗ обработки сетевого чтения (см. processParsedProfiles) ОБЯЗАН передаваться "липкий"
     *   union (ProfileSync.unionAndRemember), а НЕ сырой parsed-снимок текущего тика — иначе
     *   один флаки-Tor-тик, вернувший профиль без аватара (см. известный класс багов
     *   "гонка перезаписи / устаревшее чтение", ProfileSync.unionWithKnown), на миг стирает
     *   аватарку участника из пузырьков, и она тут же возвращается на следующем тике —
     *   ⚠️ Фикс (репорт: "аватарки собеседников мигают и исчезают и появляются периодически").
     */
    private fun refreshMessageAvatars(source: Map<String, Profile> = lastKnownProfiles) {
        val map = HashMap<String, String?>(source.size + 1)
        for ((uid, profile) in source) {
            // Межчатовый fallback (ProfileSync.getGlobalKnown): если в ЭТОМ чате у участника
            // ещё нет своего аватара (например, только что зашёл, profiles.txt этого чата
            // ещё не долетел), но он уже известен по ДРУГОМУ чату (тот же userId стабилен
            // для человека везде — см. Prefs.myUserId), используем его аватар как временный,
            // а не пустую заглушку до собственного round-trip этого чата.
            map[uid] = profile.avatarBase64 ?: ProfileSync.getGlobalKnown(uid)?.avatarBase64
        }
        map[prefs.myUserId] = prefs.myAvatarBase64
        adapter.updateAvatars(map)

        // Галочки верификации у ников отправителей в беседе. Считаем КРИПТОГРАФИЧЕСКИ:
        // VerifiedBadge сначала дёшево проверяет членство ключа в списке, и только для
        // кандидатов — подпись identity (неподделываемо). Если у участника нет валидной
        // подписи — галочки нет (безопасный дефолт). Адаптер лишь рисует.
        val verified = HashSet<String>()
        for ((uid, profile) in source) {
            // Единая точка правды (VerifiedBadge.isVerifiedDev): подтверждает подпись и
            // ЗАПОМИНАЕТ userId для этого чата, чтобы список участников/иммунитет брали тот
            // же статус, а не перепроверяли из своего (возможно неполного) чтения профиля.
            if (VerifiedBadge.isVerifiedDev(chat.chatId, uid, profile)) verified.add(uid)
        }
        adapter.updateVerifiedUsers(verified)
        // Иммунитет дева к скрытию сообщений при бане/муте (см. bannedIds/mutedIds в
        // loadMessages): его userId никогда не фильтруется у остальных.
        verifiedSenderIds = verified
    }

    /** Контроллер панели стикеров. */
    private var stickerPanel: StickerPanelController? = null

    // Подсказки стикеров по эмодзи (панель над полем ввода)
    private var suggestionAdapter: StickerAdapter? = null
    private var suggestJob: kotlinx.coroutines.Job? = null
    private val suggestRepo by lazy { StickerRepository(this) }


    /** Pick: выбор одного или нескольких изображений из галереи (макс. 10). */
    private val pickImages = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        val limited = if (uris.size > MAX_COLLAGE_IMAGES) {
            Toast.makeText(this,
                getString(R.string.error_too_many_images, MAX_COLLAGE_IMAGES),
                Toast.LENGTH_SHORT).show()
            uris.take(MAX_COLLAGE_IMAGES)
        } else uris
        addStaged(limited)
    }

    // ── Панель выбранных фото (staged bar над вводом) ───────────────────────────
    private val stagedUris = ArrayList<Uri>()
    private var stagedAdapter: StagedAdapter? = null

    private val mediaPerms: Array<String>
        get() = when {
            android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasMediaAccess(): Boolean =
        mediaPerms.any {
            ContextCompat.checkSelfPermission(this, it) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) openGalleryForStaging()
        else Toast.makeText(this, R.string.gallery_perm_needed, Toast.LENGTH_SHORT).show()
    }

    /** Скрепка / плитка «+»: проверяем доступ к фото, потом открываем галерею. */
    private fun openGalleryChecked() {
        AppLock.beginShareGrace()
        if (hasMediaAccess()) openGalleryForStaging()
        else mediaPermLauncher.launch(mediaPerms)
    }

    private fun openGalleryForStaging() {
        if (stagedUris.size >= MAX_COLLAGE_IMAGES) {
            Toast.makeText(this, getString(R.string.error_too_many_images, MAX_COLLAGE_IMAGES),
                Toast.LENGTH_SHORT).show()
            return
        }
        GalleryPicker(
            activity = this,
            scope = lifecycleScope,
            maxSelection = (MAX_COLLAGE_IMAGES - stagedUris.size).coerceAtLeast(1),
            onDone = { uris -> addStaged(uris) },
            onPickMore = { pickImages.launch("image/*") }
        ).show(showPickMore = false)
    }

    private fun setupStagedBar() {
        val a = StagedAdapter()
        stagedAdapter = a
        binding.stagedBar.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(
            this, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        binding.stagedBar.adapter = a
        binding.stagedBar.itemAnimator = null
    }

    private fun addStaged(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val before = stagedUris.size
        for (uri in uris) {
            if (stagedUris.size >= MAX_COLLAGE_IMAGES) break   // жёсткий лимит
            if (uri in stagedUris) continue                     // без дублей
            stagedUris.add(uri)
        }
        // Что-то не влезло (достигнут лимит) → короткий тост.
        if (stagedUris.size - before < uris.size) {
            Toast.makeText(this, getString(R.string.error_too_many_images, MAX_COLLAGE_IMAGES),
                Toast.LENGTH_SHORT).show()
        }
        stagedAdapter?.notifyDataSetChanged()
        refreshStagedBar()
    }

    private fun removeStaged(pos: Int) {
        if (pos in stagedUris.indices) {
            stagedUris.removeAt(pos)
            stagedAdapter?.notifyDataSetChanged()
            refreshStagedBar()
        }
    }

    private fun clearStaged() {
        if (stagedUris.isEmpty()) return
        stagedUris.clear()
        stagedAdapter?.notifyDataSetChanged()
        refreshStagedBar()
    }

    private fun refreshStagedBar() {
        binding.stagedBar.visibility = if (stagedUris.isEmpty()) View.GONE else View.VISIBLE
        updateSendVoiceButtons(binding.etMessage.text.toString())
    }

    private inner class StagedAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
        private val typePhoto = 0
        private val typeAdd = 1
        override fun getItemCount(): Int = stagedUris.size + 1
        override fun getItemViewType(position: Int): Int =
            if (position < stagedUris.size) typePhoto else typeAdd
        override fun onCreateViewHolder(
            parent: android.view.ViewGroup, viewType: Int
        ): androidx.recyclerview.widget.RecyclerView.ViewHolder {
            val inf = android.view.LayoutInflater.from(parent.context)
            return if (viewType == typePhoto)
                PhotoVH(inf.inflate(R.layout.item_staged, parent, false))
            else AddVH(inf.inflate(R.layout.item_staged_add, parent, false))
        }
        override fun onBindViewHolder(
            holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int
        ) {
            if (holder is PhotoVH) holder.bind(stagedUris[position])
        }
        inner class PhotoVH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            private val img: com.google.android.material.imageview.ShapeableImageView =
                v.findViewById(R.id.staged_img)
            private val remove: View = v.findViewById(R.id.staged_remove)
            fun bind(uri: Uri) {
                img.setImageDrawable(null)
                img.tag = uri
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.IO) {
                        ImageUtils.loadAndCompress(this@ChatActivity, uri)?.let { ImageUtils.fromBase64(it) }
                    }
                    if (img.tag == uri) img.setImageBitmap(bmp)
                }
                remove.setOnClickListener { removeStaged(bindingAdapterPosition) }
            }
        }
        inner class AddVH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            init { v.setOnClickListener { openGalleryChecked() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        // FS: подключаем локальный шифр-архив истории к CryptoHelper (fail-safe).
        try { prefs.getOrCreateArchiveKey()?.let { CryptoHelper.setArchive(FsArchive(applicationContext, it)) } } catch (_: Exception) {}
        db = AppDatabase.get(this)

        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        if (chatId < 0) {
            finish()
            return
        }
        // Отложенные действия из списка медиа — применятся, когда сообщения загрузятся.
        pendingJumpMsgId = intent.getStringExtra(EXTRA_SCROLL_TO_MSGID)
        pendingDeleteMsgId = intent.getStringExtra(EXTRA_DELETE_MSGID)

        lifecycleScope.launch {
          try {
            val loaded = db.chatDao().getById(chatId)
            if (loaded == null) {
                CrashHandler.report(
                    context = this@ChatActivity,
                    title = "ChatActivity: chat not found in DB (chatId=$chatId)",
                    throwable = IllegalStateException("getById($chatId) returned null — DB may have been recreated or ID is stale")
                )
                Toast.makeText(this@ChatActivity, R.string.error_load, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            // Restore secrets from EncryptedSharedPreferences (moved from plaintext DB in v10)
            val restoredToken = prefs.getChatToken(loaded.chatId).takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") loaded.transportToken
            val restoredPassword = prefs.getChatPassword(loaded.chatId).takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") loaded.chatPassword
            @Suppress("DEPRECATION")
            chat = loaded.copy(transportToken = restoredToken, chatPassword = restoredPassword)
            setupUi()
            // Плашка закреплённых — СРАЗУ из Room, как только чат загрузился, не дожидаясь
            // сетевого синка (репорт: закрепы появлялись через ~5с после входа). onResume
            // рисует закрепы, но на ПЕРВОМ входе он срабатывает раньше, чем инициализируется
            // chat (грузится асинхронно), поэтому там условие ::chat.isInitialized ложно и показ
            // пропускается. Дублируем здесь — работает и для бесед, созданных до правки (§17).
            if (chat.isGroup) launch { refreshPinState() }
            // Прогреваем Argon2-ключ в фоне (V2 фолбэк): первое шифрование/дешифрование
            // занимает 400–700 мс, кеш после этого делает всё мгновенным.
            // Запускаем до loadMessages — к моменту расшифровки первых строк ключ уже готов.
            launch(Dispatchers.Default) {
                CryptoHelper.warmUp(chat.chatPassword, chat.chatId)
            }
            // Восстанавливаем или генерируем X25519 пару ключей для forward secrecy.
            // FORWARD SECRECY: приватный эфемерный ключ хранится ТОЛЬКО в Keystore-
            // шифрованном Prefs (не в открытой Room-БД). Pub остаётся в БД. Сессия
            // переживает перезаход (ключ не пересоздаётся), но при краже БД он недоступен.
            //
            // ⛔ ГРУППОВЫЕ ЧАТЫ (ADR-001): ECDH forward-secrecy рукопожатие рассчитано
            // строго на двух участников (X25519(мой_privkey, ОДИН_чужой_pubkey) → один
            // общий секрет). Для группы это не имеет смысла и не запускается — группа
            // навсегда остаётся на V5 (Argon2id + общий пароль чата), см. ADR_GROUP_CHATS.md.
            if (!chat.isGroup) {
                val storedEphPriv = prefs.getEphemeralPriv(chat.chatId)
                if (storedEphPriv != null && chat.myEphemeralPubKeyB64 != null) {
                    myEphemeralPrivKey = storedEphPriv
                    myCurrentEphemeralPubKey = chat.myEphemeralPubKeyB64
                } else if (chat.myEphemeralPrivKeyB64 != null && chat.myEphemeralPubKeyB64 != null) {
                    // МИГРАЦИЯ старого чата: priv лежал в открытой БД — переносим в Keystore-Prefs
                    // и СТИРАЕМ из БД (pub оставляем). Так старые чаты тоже становятся защищёнными.
                    val migrated = Base64.decode(chat.myEphemeralPrivKeyB64, Base64.NO_WRAP)
                    myEphemeralPrivKey = migrated
                    myCurrentEphemeralPubKey = chat.myEphemeralPubKeyB64
                    prefs.setEphemeralPriv(chat.chatId, migrated)
                    db.chatDao().updateMyEphemeralKeys(chat.id, null, chat.myEphemeralPubKeyB64)
                } else {
                    val (privKey, pubKeyB64) = CryptoHelper.generateEphemeralKeyPair()
                    myEphemeralPrivKey = privKey
                    myCurrentEphemeralPubKey = pubKeyB64
                    prefs.setEphemeralPriv(chat.chatId, privKey)
                    // В БД пишем ТОЛЬКО публичный ключ; приватный — никогда.
                    db.chatDao().updateMyEphemeralKeys(chat.id, null, pubKeyB64)
                }
                // Подписываем свой эфемерный ключ долговременным identity-ключом (защита от MITM).
                myEphemeralSig = computeEphemeralSig(myCurrentEphemeralPubKey, chat.chatId)

                // Если в БД уже был ключ партнёра — сразу устанавливаем сессионный ключ,
                // чтобы первое же loadMessages() расшифровало V3-сообщения.
                if (chat.partnerEphemeralPubKeyB64 != null) {
                    tryEstablishSessionKey(chat.partnerEphemeralPubKeyB64)
                }
                // FS: периодическая ротация эфемерного ключа (раз в сутки) — настоящий
                // forward secrecy с окном; история сохраняется в локальном шифр-архиве.
                maybeRotateEphemeral()
            }
            // Подпись «доказательство identity» — ВСЕГДА (в т.ч. беседы, где нет эфемерного
            // ключа). Публикуется в профиле (identitySig) и даёт галочку верификации в группах.
            myIdentitySig = computeIdentitySig(chat.chatId)
            // Мгновенный показ из кэша прошлого захода (в этой сессии) — чат не грузится
            // с нуля; SyncEngine ниже обновит его в фоне.
            ChatSnapshotCache.get(chat.chatId)?.let { cached ->
                runCatching { processChannelData(cached) }
            }
            // Страховка: если за 15с контент так и не пришёл (нет сети / Tor не поднялся) —
            // всё равно показываем чат, чтобы не залипнуть на спиннере.
            lifecycleScope.launch {
                delay(15_000L)
                if (!firstLoadComplete) {
                    firstLoadComplete = true
                    revealMessages()
                }
            }
            startPolling()
            startPresence()
            // В параллель синхронизируем профили (имя/аватарка собеседника)
            syncProfiles()
            // Следим за статусом Tor: жёлтая плашка, если Tor не поднялся в Tor-чате.
            observeTorStatus()
          } catch (e: Exception) {
            CrashHandler.report(this@ChatActivity, "ChatActivity: onCreate loop fail", e)
            finish()
          }
        }
    }

    /**
     * Tor(-preferring) Nostr-чат — не BT, не «Избранное», не прямой NOSTR_DIRECT_TOKEN.
     * Тот же критерий, что уже используется в onResume()/registerNetworkMonitoring() для
     * решения «поднимать ли Tor» — здесь используется, чтобы решить «взводить ли
     * TorSyncWatchdog» (см. TorSyncWatchdog.kt).
     */
    private fun isTorChat(): Boolean =
        !chat.isFavorites &&
            chat.transportToken != BluetoothTransport.BT_TOKEN &&
            chat.transportToken != com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN

    /**
     * Подтягивает профиль собеседника из канала и пушит свой профиль.
     * Если имя или аватарка собеседника изменились — обновляет UI и Room.
     */
    private fun syncProfiles() = lifecycleScope.launch {
        if (chat.isFavorites) return@launch
        // Подстраховка: до 3 попыток с нарастающей паузой (3с → 6с).
        // Раньше была одна попытка, после которой всё молча игнорировалось —
        // любая кратковременная сетевая ошибка приводила к тому, что ephemeral
        // ключ не доходил до партнёра и ECDH-рукопожатие не завершалось.
        //
        // БАГ (исправлено): doSyncProfilesOnce() раньше не бросал исключение,
        // если партнёр просто ЕЩЁ не опубликовал свой профиль (например, автор
        // приглашения publish'ит pushMyProfile в фоне и просто не успел к моменту,
        // когда джойнер уже открыл чат) — это НЕ ошибка сети, поэтому catch-блок
        // ниже не срабатывал, и цикл считал первую же (успешную, но пустую)
        // попытку финалом, сразу выходя из repeat. Реальное появление авы/ника
        // партнёра откладывалось до следующего тика фонового поллинга (SyncEngine,
        // 10с), а через Tor — заметно дольше (джиттер + кворум реле), из-за чего
        // выглядело как «синхронизация не происходит, но лечится само со временем».
        // Теперь doSyncProfilesOnce() возвращает найден ли партнёр, и цикл ретраит
        // ЭТУ попытку тоже — а не только сетевые исключения.
        repeat(SYNC_PROFILES_MAX_ATTEMPTS) { attempt ->
            try {
                val partnerFound = doSyncProfilesOnce()
                if (partnerFound || attempt == SYNC_PROFILES_MAX_ATTEMPTS - 1) return@launch
                delay(SYNC_PROFILES_RETRY_BASE_MS * (attempt + 1))
            } catch (e: Exception) {
                if (isTorChat()) {
                    TorSyncWatchdog.record(
                        chat.chatId, "SYNC_PROFILES_FAIL",
                        "попытка ${attempt + 1}/$SYNC_PROFILES_MAX_ATTEMPTS: ${e::class.simpleName}: ${e.message}"
                    )
                    if (attempt == SYNC_PROFILES_MAX_ATTEMPTS - 1) {
                        // Не по сценарию: все попытки цикла profile-sync провалились подряд.
                        TorSyncWatchdog.reportDeviation(
                            applicationContext, chat.chatId, "syncProfiles исчерпал $SYNC_PROFILES_MAX_ATTEMPTS попытки", e
                        )
                    }
                }
                if (attempt < SYNC_PROFILES_MAX_ATTEMPTS - 1) {
                    delay(SYNC_PROFILES_RETRY_BASE_MS * (attempt + 1))
                }
                // После последней попытки — просто выходим, следующий вызов придёт
                // из polling loop (каждые PERIODIC_PROFILE_SYNC_MS).
            }
        }
    }

    /**
     * Тело синхронизации профилей — suspend, вызывается с retry из syncProfiles().
     * Любое исключение пробрасывается наружу для обработки retry-цикла.
     *
     * @return true если профиль партнёра найден в этом опросе (и обработан),
     *         false если партнёра пока нет в profiles.txt — вызывающий должен
     *         повторить попытку, это НЕ ошибка.
     */
    private suspend fun doSyncProfilesOnce(): Boolean {
        // Мгновенный (БЕЗ сети) вылет по уже известному локально бану — бан мог быть
        // применён фоновым опросом списка чатов (ChatsListActivity) или прошлым тиком,
        // пока этот экран был в бэкстеке/приложение свёрнуто. Проверка до loadAll():
        // забаненный не должен видеть чат ни секунды дольше необходимого (репорт:
        // «резкий вылет на экран чатов»). Сетевая проверка по свежему members.txt —
        // ниже по этой же функции, после applyIncoming.
        if (chat.isGroup && checkSelfBanned()) return false
        // Мгновенная (тоже БЕЗ сети) плашка мута по ЛОКАЛЬНОМУ состоянию — тот же приём,
        // что и с баном выше (по прямой просьбе пользователя): статус мута уже лежит в
        // Room после прошлых тиков/фонового опроса списка чатов, Room-чтение — миллисекунды.
        // Плашка и скрытие строки ввода появляются сразу при входе, не дожидаясь
        // loadAll() через Tor (секунды); свежие данные сети чуть ниже по этой же функции
        // подтвердят или снимут состояние (например, если админ успел снять мут, пока
        // приложение было свёрнуто).
        if (chat.isGroup) {
            val meLocal = db.chatParticipantDao().getOne(chat.id, prefs.myUserId)
            val untilLocal = meLocal?.mutedUntilMs
            val amMutedLocal = untilLocal != null && untilLocal > System.currentTimeMillis()
            applySelfMuteState(amMutedLocal, untilLocal, meLocal?.mutedReason,
                MembersSync.evidenceIdsFromStore(meLocal?.mutedEvidenceIds))
        }
        // ⚠️ Фикс (тот же класс бага, что уже чинили в PartnerProfileActivity): раньше
        // здесь стоял ProfileSync.pullProfiles() — читает ОДИН общий блоб profiles.txt
        // (последний записавший "выигрывает"), а несколько участников группы пишут
        // профили в СВОИ отдельные слоты именно чтобы не терять чужие правки при
        // одновременной записи. При почти одновременной публикации (например, сразу
        // после джойна) чей-то профиль мог просто потеряться в общем блобе — и у
        // группы "быстрый путь" энролла (maybeAdminEnrollNewMembers(allProfiles) ниже)
        // не видел нового участника, пока подстраховочный путь в processChannelData()
        // не подхватит его отдельным тиком. transport.loadAll() — тот же самый запрос,
        // что и обычный поллинг SyncEngine (не новая/более дорогая операция), и уже
        // отдаёт profileSlots для честного union-чтения (см. ChatActivity.SLOT_UNION_PROFILES,
        // тот же принцип, что в ChatsListActivity/PartnerProfileActivity).
        val allData = transport.loadAll()
        val allProfiles = if (SLOT_UNION_PROFILES && allData.profileSlots.isNotEmpty()) {
            ProfileSync.unionProfileSlots(allData.profileSlots, chat.chatPassword, chat.chatId)
        } else {
            ProfileSync.parseProfiles(allData.profilesContent, chat.chatPassword, chat.chatId)
        }
        val partner = ProfileSync.findPartner(allProfiles, prefs.myUserId, prefs.myName)

        // ⚠️ Переход partnerJoined=false→true и плашка «X присоединился к чату» для
        // 1:1-чатов раньше считались ЗДЕСЬ — с ограниченным числом ретраев (3с→6с→9с)
        // только при onCreate/onResume. Если партнёр вступал позже, а чат оставался
        // открытым, плашка никогда не появлялась (репорт пользователя). Перенесено в
        // processParsedProfiles() — она вызывается на КАЖДОМ тике SyncEngine (тот же
        // принцип, что уже применён к групповым чатам, см. processChannelData), поэтому
        // здесь эта проверка больше не нужна — дублировать её значило бы рисковать
        // двойной плашкой (гонка между этой suspend-функцией и тиком SyncEngine на
        // общем поле chat.partnerJoined).

        if (partner != null) {
            if (isTorChat()) {
                TorSyncWatchdog.disarm(chat.chatId, "партнёр получен через profiles.txt (doSyncProfilesOnce)")
            }
        }
        // ⚠️ Фикс (репорт: «вместо авы чата у человека может быть ава админа»): блок ниже —
        // строго 1:1-механика. findPartner() для ГРУППЫ выбирает произвольного участника
        // (обычно админа — он публикует профиль первым), и его имя/тег/аватар записывались
        // в partnerName/partnerTag/partnerAvatarBase64 группового чата. Дальше
        // Chat.displayAvatarBase64() (fallback groupAvatarBase64 ?: partnerAvatarBase64)
        // честно показывал аву АДМИНА как аву ГРУППЫ, пока настоящая ава не доехала через
        // members.txt. Для групп профиль «партнёра» не сохраняем вовсе — источник имени/авы
        // группы один: members.txt (см. MembersSync.applyIncoming).
        if (partner != null && !chat.isGroup) {
            val nameToSave = if (partner.name.isNotBlank()) partner.name else chat.partnerName
            val tagToSave = if (!partner.tag.isNullOrBlank()) partner.tag else chat.partnerTag
            val avatarToSave = if (!partner.avatarBase64.isNullOrBlank()) partner.avatarBase64 else chat.partnerAvatarBase64

            val profileChanged = nameToSave != chat.partnerName ||
                    tagToSave != chat.partnerTag ||
                    avatarToSave != chat.partnerAvatarBase64

            if (profileChanged) {
                db.chatDao().updatePartnerProfile(chat.id, nameToSave, tagToSave, avatarToSave)
                chat = chat.copy(
                    partnerName         = nameToSave,
                    partnerTag          = tagToSave,
                    partnerAvatarBase64 = avatarToSave
                )
                applyPartnerToHeader()
            }
            // Флаг удалённого профиля
            if (partner.deleted != chat.partnerDeleted) {
                db.chatDao().updatePartnerDeleted(chat.id, partner.deleted)
                chat = chat.copy(partnerDeleted = partner.deleted)
                applyPartnerToHeader()
            }
            // lastReadIndex собеседника — обновляем отдельно, может меняться чаще профиля
            if (partner.lastReadIndex != chat.partnerLastReadIndex) {
                db.chatDao().updatePartnerLastRead(chat.id, partner.lastReadIndex)
                chat = chat.copy(partnerLastReadIndex = partner.lastReadIndex)
                adapter.setPartnerLastReadIndex(partner.lastReadIndex)
            }
        }

        if (chat.isGroup) {
            // Групповой чат (ADR-001): "прочитано" ✓✓ — когда ВСЕ остальные участники
            // дочитали до сообщения (минимум lastReadIndex среди них), а не один
            // произвольный "partner", которого findPartner() выше выбрал для группы
            // (сам findPartner на группы не рассчитан — просто игнорируем его выбор здесь).
            val others = allProfiles.values.filter { it.userId != prefs.myUserId }
            val minReadIndex = others.minOfOrNull { it.lastReadIndex } ?: 0
            adapter.setPartnerLastReadIndex(minReadIndex)
        }

        // Устанавливаем сессионный ключ если партнёр опубликовал свой ephemeral pub key
        if (partner != null) {
            tryEstablishSessionKey(partner.ephemeralPubKey)
            verifyPartnerIdentity(partner)
        }

        // Пушим свой профиль (свежие данные из Settings).
        // ephemeralPubKey включаем чтобы партнёр мог вычислить ECDH и начать V3-шифрование.
        // onlineTs включаем если мы на переднем плане — иначе точка погаснет у собеседника.
        val myProfile = Profile(
            userId = prefs.myUserId,
            name = prefs.myName,
            tag = prefs.myTag,
            avatarBase64 = prefs.myAvatarBase64,
            onlineTs = if (isInForeground) System.currentTimeMillis() else 0L,
            ephemeralPubKey = myCurrentEphemeralPubKey,
            identityPubKey = prefs.myIdentityPubKey,
            ephemeralSig = myEphemeralSig,
            identitySig = myIdentitySig,
            verifiedPartnerIdk = prefs.getConfirmedPartnerIdentity(chat.chatId),
            status = prefs.myStatus.takeIf { it.isNotBlank() }
        )
        ProfileSync.pushMyProfile(transport, chat.chatPassword, myProfile, prefs.getOrCreateIdentity().first)

        // Групповой чат (ADR-001), только у админа: свежий allProfiles уже под рукой
        // (не зависит от того, менялся ли chat.txt) — самый быстрый путь заметить
        // нового участника и добавить его в members.txt. Второй, подстраховочный
        // вызов — в processChannelData() на lastKnownProfiles.
        if (chat.isGroup) {
            maybeAdminEnrollNewMembers(allProfiles)

            // ⚠️ Фикс (репорт: "замутил пользователя, а у него плашки нет даже после
            // закрытия чата и приложения"): раньше единственный путь узнать о своём
            // муте — обычный тик фонового поллинга (processChannelData, SyncEngine),
            // либо мгновенный, но потенциально УСТАРЕВШИЙ рендер из ChatSnapshotCache
            // в onCreate (кэш прошлого захода в рамках процесса — если он ещё не успел
            // обновиться свежим тиком, при быстром переоткрытии баннер мог не появиться
            // до следующего тика). Здесь мы уже сходили в сеть ЗА СВЕЖИМИ данными
            // (transport.loadAll() выше, allData) — доиспользуем тот же allData.membersContent
            // для гарантированного, не полагающегося на таймер поллинга чека своего мута
            // на КАЖДОЕ открытие чата (onCreate/onResume → syncProfiles() → сюда), и
            // сразу показываем баннер, как только он подтвердится.
            // «Профиль беседы» — быстрый источник имени/авы группы прямо на входе
            // (тот же свежий allData; см. GroupProfileSync).
            if (applyGroupProfileFromPoll(allData.groupProfileContent, allData.membersContent.isNotBlank())) {
                applyPartnerToHeader()
            }

            val slotsSig1 = memberSlotsSig(allData.memberSlots)
            if (allData.membersContent.isNotBlank() &&
                (allData.membersContent != lastMembersRaw || slotsSig1 != lastMemberSlotsSig)
            ) {
                lastMembersRaw = allData.membersContent
                lastMemberSlotsSig = slotsSig1
                // Снимок с реле для самопочинки админа — тот же, что в processChannelData
                // (иначе дедуп по lastMembersRaw оставил бы lastWireMembers устаревшим).
                lastWireMembers = runCatching {
                    CryptoHelper.decrypt(allData.membersContent, chat.chatPassword, chat.chatId)
                        ?.let { MembersSync.parse(it) }
                }.getOrNull()
                lastWireMembersUnparseable = lastWireMembers == null
                val applied = runCatching {
                    MembersSync.applyIncoming(
                        chat = chat,
                        membersContentEncrypted = allData.membersContent,
                        password = chat.chatPassword,
                        participantDao = db.chatParticipantDao(),
                        chatDao = db.chatDao(),
                        myUserId = prefs.myUserId,
                        appContext = applicationContext,
                        groupEventDao = db.groupEventDao(),
                        memberSlots = allData.memberSlots,
                        pubkeyForUserId = transport::pubkeyForUserId
                    )
                }.getOrDefault(false)
                if (applied) {
                    db.chatDao().getById(chat.id)?.let { fresh ->
                        val groupProfileChanged = fresh.groupName != chat.groupName ||
                            fresh.groupAvatarBase64 != chat.groupAvatarBase64 ||
                            fresh.groupDescription != chat.groupDescription
                        chat = chat.copy(
                            membersVersion = fresh.membersVersion,
                            groupName = fresh.groupName,
                            groupAvatarBase64 = fresh.groupAvatarBase64,
                            groupDescription = fresh.groupDescription,
                            pinnedMsgIds = fresh.pinnedMsgIds,
                            myPinnedMsgIds = fresh.myPinnedMsgIds
                        )
                        if (groupProfileChanged) applyPartnerToHeader()
                    }
                    refreshPinState() // закреплённые могли измениться (Этап 3)
                }
            }
            // Пропагация бана НА ВХОДЕ в чат (репорт: «при попытке зайти в чат должен
            // быть вылет на экран чатов уже без той беседы»): свежий members.txt только
            // что применён выше — если я забанен, немедленно удаляем чат и закрываем
            // экран, не дожидаясь первого тика поллинга (checkSelfBanned сам показывает
            // тост и делает finish()).
            if (checkSelfBanned()) return false

            val myEntryFresh = db.chatParticipantDao().getForChat(chat.id).firstOrNull { it.userId == prefs.myUserId }
            val untilFresh = myEntryFresh?.mutedUntilMs
            val amMutedFresh = untilFresh != null && untilFresh > System.currentTimeMillis()
            applySelfMuteState(amMutedFresh, untilFresh, myEntryFresh?.mutedReason,
                MembersSync.evidenceIdsFromStore(myEntryFresh?.mutedEvidenceIds))
        }

        return partner != null
    }

    /** Обновляет шапку чата с актуальными данными собеседника (имя + аватарка). */
    private fun applyPartnerToHeader() {
        // Галочка верификации по умолчанию скрыта; показывается только в 1:1-ветке ниже
        // для верифицированного собеседника (группа/система/избранное — не персона).
        binding.verifiedBadgeHeader.setVerified(false, animate = false)
        // ⛔ Групповые чаты (ADR-001): своя ветка — группового имени/аватара нет
        // единого "собеседника". Один флаг здесь покрывает ВСЕ 8 точек вызова
        // applyPartnerToHeader() по всему файлу — ничего в них менять не нужно.
        if (chat.isGroup) {
            applyGroupHeader()
            return
        }
        if (chat.isSystemNotifications) {
            // Системный чат «Уведомления» (SystemNotifications, мокап одобрен).
            binding.tvDisplayName.text = getString(R.string.notif_chat_name)
            binding.ivPartnerAvatar.visibility = View.GONE
            binding.tvPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.text = ""
            binding.tvPartnerAvatar.setBackgroundResource(R.drawable.bg_avatar_placeholder)
            binding.ivPartnerAvatar.visibility = View.VISIBLE
            binding.ivPartnerAvatar.setImageResource(R.drawable.ic_bell)
            binding.tvChatSubtitle.text = getString(R.string.notif_chat_subtitle)
            binding.vOnlineIndicator.visibility = View.GONE
            return
        }
        if (chat.isFavorites) {
            binding.tvDisplayName.text = getString(R.string.favorites_name)
            binding.ivPartnerAvatar.visibility = View.GONE
            binding.tvPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.text = ""
            binding.tvPartnerAvatar.setBackgroundResource(R.drawable.bg_avatar_favorites)
            binding.ivPartnerAvatar.visibility = View.VISIBLE
            binding.ivPartnerAvatar.setImageResource(R.drawable.ic_sparkle)
            binding.tvChatSubtitle.text = getString(R.string.favorites_description)
            binding.vOnlineIndicator.visibility = View.GONE
            return
        }

        binding.tvDisplayName.text = chat.partnerName
        // Галочка верификации рядом с ником собеседника (1:1). Неподделываемо: показываем
        // только если подпись identity партнёра валидна (IdentityState.VERIFIED) И его ключ
        // в списке верифицированных (VerifiedBadge).
        val verHeaderInfo = IdentityState.get(chat.chatId)
        val liveVerified = verHeaderInfo.state == IdentityState.State.VERIFIED &&
            VerifiedBadge.isKeyVerified(verHeaderInfo.partnerIdk)
        // ИЛИ устойчивый неподделываемый флаг из списка (chat.partnerVerified): live-сессия
        // гаснет, когда собеседник оффлайн — без этого fallback галочка пропадала у меня в
        // шапке, пока партнёр не в сети. partnerVerified ставится только по валидной подписи.
        binding.verifiedBadgeHeader.setVerified(liveVerified || chat.partnerVerified, animate = true)
        if (!chat.partnerTag.isNullOrBlank()) {
            binding.tvChatSubtitle.text = chat.partnerTag
            binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        } else {
            binding.tvChatSubtitle.text = getString(R.string.chat_subtitle_encrypted)
            binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        }

        if (chat.partnerDeleted) {
            // Серый круг с ✕ — профиль удалён
            binding.ivPartnerAvatar.visibility = View.GONE
            binding.tvPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.text = ""
            binding.tvPartnerAvatar.background =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_avatar_deleted)
            binding.ivPartnerAvatar.visibility = View.VISIBLE
            binding.ivPartnerAvatar.setImageResource(R.drawable.ic_close)
            // Subtitle — постоянная пометка "Профиль удалён"
            updateTypingIndicator(false)
            binding.tvChatSubtitle.text = getString(R.string.partner_deleted_subtitle)
            binding.tvChatSubtitle.setTextColor(
                ContextCompat.getColor(this, R.color.error)
            )
        } else {
            // Обычный аватар
            binding.tvPartnerAvatar.background =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_avatar_placeholder)
            val avatar = AvatarUtils.fromBase64(chat.partnerAvatarBase64)
            if (avatar != null) {
                binding.ivPartnerAvatar.setImageBitmap(avatar)
                binding.ivPartnerAvatar.visibility = View.VISIBLE
                binding.tvPartnerAvatar.visibility = View.GONE
            } else {
                binding.ivPartnerAvatar.visibility = View.GONE
                binding.tvPartnerAvatar.visibility = View.VISIBLE
                binding.tvPartnerAvatar.text = chat.partnerName.trim().firstOrNull()?.uppercase() ?: "?"
            }
        }
    }

    /**
     * Шапка группового чата (ADR-001): имя/аватар группы вместо одного собеседника,
     * subtitle — число активных участников (заменяется presence-индикатором в
     * applyGroupPresence()). Вызывается ТОЛЬКО из applyPartnerToHeader().
     */
    private fun applyGroupHeader() {
        val name = chat.groupName?.takeIf { it.isNotBlank() } ?: chat.partnerName
        binding.tvDisplayName.text = name

        binding.tvPartnerAvatar.background =
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_avatar_placeholder)
        val avatar = AvatarUtils.fromBase64(chat.groupAvatarBase64)
        if (avatar != null) {
            binding.ivPartnerAvatar.setImageBitmap(avatar)
            binding.ivPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.visibility = View.GONE
        } else {
            binding.ivPartnerAvatar.visibility = View.GONE
            binding.tvPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.text = name.trim().firstOrNull()?.uppercase() ?: "?"
        }

        // Начальное состояние subtitle — реальный presence-тикер (applyGroupPresence)
        // перерисует его в течение секунды поверх этого значения.
        updateGroupSubtitleParticipantCount()
    }

    /** Пишет "N участников" в subtitle шапки — используется когда никто не печатает/не в записи. */
    private fun updateGroupSubtitleParticipantCount() {
        binding.tvChatSubtitle.text = getString(R.string.group_members_count_fmt, groupActiveParticipantCount)
        binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
        binding.tvChatSubtitle.setOnClickListener(null)
    }

    private fun openPartnerProfile() {
        // Системный чат «Уведомления» — свой минимальный read-only инфо-экран
        // (аватар-колокольчик + имя + описание), см. PartnerProfileActivity.EXTRA_SYSTEM_NOTIF.
        if (chat.isSystemNotifications) {
            startActivity(
                android.content.Intent(this, PartnerProfileActivity::class.java)
                    .putExtra(PartnerProfileActivity.EXTRA_SYSTEM_NOTIF, true)
            )
            return
        }
        if (chat.isFavorites) return
        // Источник истины — объект chat, который синхронизирован с БД.
        // Не берем из lastKnownProfiles напрямую, чтобы избежать мигания/разных аватарок.
        // Групповой чат (ADR-001): имя/аватар — групповые, не одного собеседника.
        val name         = if (chat.isGroup) (chat.groupName?.takeIf { it.isNotBlank() } ?: chat.partnerName) else chat.partnerName
        val tag          = if (chat.isGroup) null else chat.partnerTag
        val avatarBase64 = if (chat.isGroup) chat.groupAvatarBase64 else chat.partnerAvatarBase64
        val partner      = lastKnownProfiles.values.firstOrNull { it.userId != prefs.myUserId }
        val status       = if (chat.isGroup) null else partner?.status
        // Выровненные массивы: каждый медиа-элемент знает своё сообщение (msgId) и
        // принадлежность (self) — нужно для перехода к сообщению и удаления из списка медиа.
        val refs = ArrayList<String>(); val refMsgIds = ArrayList<String>(); val refSelf = ArrayList<String>()
        currentMessages.forEach { msg ->
            val list: List<String>? = when {
                msg.imageFileNames != null -> msg.imageFileNames
                msg.imageFileName != null -> listOf(msg.imageFileName)
                msg.imageBase64 != null -> listOf("base64:${msg.imageBase64}")
                else -> null
            }
            list?.forEach { refs.add(it); refMsgIds.add(msg.msgId); refSelf.add(if (msg.isSelf) "1" else "0") }
        }
        val voiceItems = ArrayList<String>(); val voiceMsgIds = ArrayList<String>(); val voiceSelf = ArrayList<String>()
        currentMessages.filter { it.voiceFileName != null }.forEach { msg ->
            voiceItems.add("${msg.voiceFileName}${msg.voiceDurationSec}")
            voiceMsgIds.add(msg.msgId); voiceSelf.add(if (msg.isSelf) "1" else "0")
        }
        val linkItems = ArrayList<String>(); val linkMsgIds = ArrayList<String>(); val linkSelf = ArrayList<String>()
        val seenLinks = HashSet<String>()
        currentMessages.forEach { msg ->
            extractUrls(msg.text).forEach { url ->
                if (seenLinks.add(url)) {
                    linkItems.add(url); linkMsgIds.add(msg.msgId); linkSelf.add(if (msg.isSelf) "1" else "0")
                }
            }
        }
        val intent = android.content.Intent(this, PartnerProfileActivity::class.java).apply {
            putExtra(PartnerProfileActivity.EXTRA_NAME, name)
            putExtra(PartnerProfileActivity.EXTRA_TAG, tag)
            putExtra(PartnerProfileActivity.EXTRA_STATUS, status)
            putExtra(PartnerProfileActivity.EXTRA_AVATAR_BASE64, avatarBase64)
            putExtra(PartnerProfileActivity.EXTRA_CHANNEL_ID, chat.chatId)
            putExtra(PartnerProfileActivity.EXTRA_TRANSPORT_TOKEN, chat.transportToken)
            putExtra(PartnerProfileActivity.EXTRA_CHAT_PASSWORD, chat.chatPassword)
            putExtra(PartnerProfileActivity.EXTRA_IDENTITY_PUB, partner?.identityPubKey)
            putExtra(PartnerProfileActivity.EXTRA_EPH_PUB, partner?.ephemeralPubKey)
            putExtra(PartnerProfileActivity.EXTRA_EPH_SIG, partner?.ephemeralSig)
            putExtra(PartnerProfileActivity.EXTRA_VERIFIED_PARTNER_IDK, partner?.verifiedPartnerIdk)
            putExtra(PartnerProfileActivity.EXTRA_CHAT_ID, chat.id)
            putExtra(PartnerProfileActivity.EXTRA_IS_GROUP, chat.isGroup)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_IMAGE_REFS, refs)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_IMAGE_MSGIDS, refMsgIds)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_IMAGE_SELF, refSelf)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_VOICE_REFS, voiceItems)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_VOICE_MSGIDS, voiceMsgIds)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_VOICE_SELF, voiceSelf)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_LINKS, linkItems)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_LINK_MSGIDS, linkMsgIds)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_LINK_SELF, linkSelf)
        }
        startActivity(intent)
    }

    /** Извлекает ссылки из текста сообщения (как Linkify в чате). */
    private fun extractUrls(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val out = ArrayList<String>()
        val m = android.util.Patterns.WEB_URL.matcher(text)
        while (m.find()) out.add(m.group())
        return out
    }

    private fun setupUi() {
        transportFactory = TransportFactory(
            chatId = chat.chatId,
            transportToken = chat.transportToken,
            chatPassword = chat.chatPassword,
            myUserId = prefs.myUserId,
            isFavorites = chat.isFavorites,
            chatIdLong = chat.id,
            chatDao = db.chatDao(),
            context = applicationContext,
            // Групповой чат: без этого members.txt никогда не проходит проверку подписи
            // в NostrTransport (adminPubkeyHex был бы всегда null) — участники/имя/аватар
            // группы не обновлялись даже в открытом чате. 1:1 не тронуты (adminUserId = null).
            adminUserId = chat.adminUserId
        )
        // Стартуем с Nostr напрямую (без проверки) — UI не ждёт
        transport = transportFactory.instant()
        btMode = chat.transportToken == BluetoothTransport.BT_TOKEN
        // Взводим сторож синхронизации при открытии Tor-чата — см. TorSyncWatchdog.kt.
        // Идемпотентно относительно arm(), уже выставленного JoinChatActivity.runConnect()
        // при нажатии «Подключиться» (тот же chatId — второй arm() не сбрасывает отсчёт).
        if (isTorChat()) TorSyncWatchdog.arm(applicationContext, chat.chatId)
        // В фоне проверяем реальную доступность — переключимся на Nostr если нужно
        lifecycleScope.launch { resolveTransport() }

        applyPartnerToHeader()

        // ImageLoader — общий для адаптера и openImageFullscreen
        val imageLoader = ImageLoader(transport, chat.chatPassword, chat.chatId)

        adapter = MessageAdapter(
            onLongClick = { msg, anchor -> showMessageMenu(msg, anchor) },
            onImageClick = { msg -> openImageFullscreen(msg) },
            onQuoteClick = { msg -> scrollToOriginal(msg) },
            onCollageImageClick = { refs, startIndex -> openImageFullscreenByRef(refs, startIndex) },
            imageLoader = imageLoader,
            loadScope = lifecycleScope,
            onReactionClick = { msgId, emoji -> handleReactionToggle(msgId, emoji) }
        )
        this.imageLoader = imageLoader
        adapter.isGroupChat = chat.isGroup // подсветка @упоминаний — только в группе
        // Подлинность авторства (ADR_MESSAGE_AUTHENTICITY.md, Фаза 4): адаптер берёт состояние
        // из карты, заполняемой best-effort на тике синка (syncMessageAuth). null → UNSIGNED.
        adapter.authStateFor = { m -> msgAuthByMsgId[m.msgId] ?: MsgAuth.UNSIGNED }
        // Применяем уже известный индекс прочитанности (из Room) к адаптеру
        adapter.setPartnerLastReadIndex(chat.partnerLastReadIndex)
        // Начальные значения непрозрачности пузырьков (могут быть обновлены в applyWallpaper)
        adapter.bubbleAlphaSelf  = prefs.bubbleAlphaSelf  / 100f
        adapter.bubbleAlphaOther = prefs.bubbleAlphaOther / 100f
        // Сидируем свой аватар сразу (локально, без сети) — не ждём первого поллинга,
        // чтобы своя аватарка в пузырьках была видна с первого кадра (см. §1.5).
        refreshMessageAvatars()

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        // Отключаем анимации изменений: без этого DiffUtil при каждом новом сообщении
        // запускает fade-out → fade-in на ВСЕХ видимых айтемах → они временно пропадают.
        (binding.rvMessages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.rvMessages.adapter = adapter

        // ── Ленивая подгрузка истории вверх ───────────────────────────────────
        // Лента рендерится «окном» (последние N). При перемотке к верху закадрово
        // подгружаем следующую порцию старых сообщений. Вставка идёт в начало списка,
        // RecyclerView сохраняет позицию — скролл не прыгает.
        binding.rvMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) return // интересует только движение вверх
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                if (lm.findFirstVisibleItemPosition() <= REVEAL_THRESHOLD && adapter.canRevealOlder()) {
                    rv.post { if (adapter.canRevealOlder()) adapter.revealOlder() }
                }
            }
        })

        // ── Swipe-to-reply ────────────────────────────────────────────────────
        val swipeCallback = SwipeToReplyCallback(this) { position ->
            val msg = adapter.getItem(position)
            // Системные сообщения (isSystem, «X присоединился к чату») — не отвечаемы.
            if (msg != null && !msg.isSystem) startReply(msg)
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(binding.rvMessages)

        // ── Multi-select mode callbacks ───────────────────────────────────────
        adapter.onSelectionChanged = { selected ->
            runOnUiThread { updateSelectionBar(selected) }
        }
        binding.btnSelectionClose.setOnClickListener {
            adapter.exitSelectionMode()
        }
        binding.btnSelectionCopy.setOnClickListener {
            val texts = currentMessages
                .filter { adapter.selectedRawIds.contains(it.msgId) }
                .sortedBy { it.timestampMs }
                .joinToString("\n") { it.text }
                .trim()
            if (texts.isNotEmpty()) copyToClipboard(texts)
            adapter.exitSelectionMode()
        }
        binding.btnSelectionForward.setOnClickListener {
            // Forward to clipboard as text (full multi-share UI is future work)
            val texts = currentMessages
                .filter { adapter.selectedRawIds.contains(it.msgId) }
                .sortedBy { it.timestampMs }
                .joinToString("\n") { it.text }
                .trim()
            if (texts.isNotEmpty()) {
                val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, texts)
                }
                // Уходим во внешнее приложение — не даём автоблокировке перебить переход.
                AppLock.beginShareGrace()
                // FLAG_ACTIVITY_NEW_TASK: singleTask-активность Telegram без своего таска
                // мгновенно возвращает управление обратно (переход «отскакивает»).
                val fwdChooser = android.content.Intent.createChooser(sendIntent, "Переслать")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(fwdChooser)
            }
            adapter.exitSelectionMode()
        }
        binding.btnSelectionDelete.setOnClickListener {
            val toDelete = currentMessages.filter { adapter.selectedRawIds.contains(it.msgId) }
            if (toDelete.isEmpty()) return@setOnClickListener
            NeonDialog.showConfirm(
                ctx = this,
                title = "Удалить сообщения",
                message = "Удалить ${toDelete.size} сообщений?",
                positiveText = getString(R.string.action_delete),
                positiveIsDestructive = true,
                negativeText = getString(R.string.btn_cancel)
            ) {
                adapter.exitSelectionMode()
                toDelete.forEach { msg -> performDelete(msg) }
            }
        }

        // Сохраняем hint поля ввода чтобы восстановить после блокировки
        originalHint = binding.etMessage.hint ?: ""

        // ── Инициализируем новую архитектуру ─────────────────────────────────
        syncEngine = SyncEngine(transport)
        patchQueue = PatchQueue(transport, lifecycleScope)

        // Потоковые подписки (WebSocket для Nostr / BLE для Bluetooth)
        // transport.watchMessages зовёт callback при получении события из «трубы».
        // В ответ мы делаем forceSync(0L): SyncEngine немедленно читает канал и обновляет UI.
        transportWatch = transport.watchMessages {
            syncEngine.forceSync(0L)
            // Push события revoke.txt → применяем отзыв/возврат создателя СРАЗУ (мгновенно, как мут),
            // не дожидаясь 2.5с-тикера. consumeRevokeDirty сбрасывает флаг → тикер не прочитает дважды;
            // lastRevokeCheckMs держит его 12с-гейт. Идемпотентно (анти-откат), гонки безопасны.
            if (::chat.isInitialized && chat.isGroup && transport.consumeRevokeDirty()) {
                lastRevokeCheckMs = System.currentTimeMillis()
                lifecycleScope.launch { runCatching { readAndApplyRevokes() } }
            }
        }
        // watchProfiles специфичен для Nostr: мгновенное обновление presence/typing собеседника.
        profilesWatch = transport.watchProfiles { content ->
            lifecycleScope.launch { processProfilesFromContent(content) }
        }

        // Подписка на ChatStore: UI обновляется мгновенно при любом изменении state.
        // collect (не collectLatest): каждый emit обрабатывается до конца — нет отмены
        // посередине adapter.submit(), что предотвращает мигание при быстром потоке событий.
        lifecycleScope.launch {
            chatStore.messages.collect { messages ->
                // Закреп следует за правкой и когда её сделал ДРУГОЙ участник (я — зритель):
                // отредактированное сообщение сохраняет senderUserId+timestampMs, но меняет
                // msgId (он выводится из шифртекста). Ловим это ДО переустановки currentMessages,
                // сравнивая старую и новую ленту, и перемапливаем закреп на месте (§1.5).
                if (::chat.isInitialized && chat.isGroup &&
                    (pinnedIds.isNotEmpty() || myPinnedIds.isNotEmpty())) {
                    reconcilePinsAfterEdit(currentMessages, messages)
                }
                currentMessages = messages
                adapter.submit(messages)

                // Приветственная плашка беседы: показываем пока беседа ПУСТА, плавно убираем при
                // первом сообщении (своём или чужом). Только беседы, только пока не показывали.
                if (::chat.isInitialized && chat.isGroup && !prefs.isGroupWelcomeShown(chat.chatId)) {
                    if (messages.isEmpty()) maybeShowGroupWelcome()
                    else if (groupWelcomeCard != null) dismissGroupWelcome()
                }

                // Раскрываем чат, если есть данные.
                if (messages.isNotEmpty() || (contentLoaded && chatStore.lastRemote.isEmpty())) {
                    binding.rvMessages.post { maybeReveal() }
                }
                // Заглушку "чат пуст" показываем только после снятия загрузочного оверлея —
                // иначе спиннер виден поверх надписи "пусто". Для системного чата
                // «Уведомления» — своя богатая заглушка (иконка+заголовок+описание,
                // мокап одобрен), обычный tv_empty_placeholder при этом скрыт.
                val showEmpty = messages.isEmpty() && firstLoadComplete
                if (chat.isSystemNotifications) {
                    binding.notifEmptyState.visibility = if (showEmpty) View.VISIBLE else View.GONE
                    binding.tvEmptyPlaceholder.visibility = View.GONE
                } else {
                    binding.tvEmptyPlaceholder.visibility = if (showEmpty) View.VISIBLE else View.GONE
                }
                // Авто-скролл только если уже у дна: не прерываем чтение истории.
                // canScrollVertically(1) == false → нельзя скроллить дальше вниз = мы у дна.
                val isAtBottom = !binding.rvMessages.canScrollVertically(1)
                if (messages.isNotEmpty() && isAtBottom && adapter.itemCount > 0) {
                    // adapter.itemCount, а не messages.size: лента рендерится «окном»
                    // (ленивая подгрузка), последний элемент окна — самое новое сообщение.
                    binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                }
                // Отложенный переход/удаление из списка медиа (после рендера).
                applyPendingMediaActions()
                // Превью закреплённого могло стать резолвимым после подгрузки ленты — но
                // только если пины реально есть (иначе лишняя работа на каждый эмит).
                if (chat.isGroup && pinnedIds.isNotEmpty()) renderPinnedBar()
                // Кнопка @ — показать/обновить по упоминаниям в загруженной ленте.
                if (chat.isGroup) renderMentionButton()
            }
        }

        // ── MessageSendManager (anti-spam rate limiter — оставляем) ──────────
        sendManager = MessageSendManager(
            scope = lifecycleScope,
            doSend = { encrypted ->
                // Один appendLine за раз (строгий FIFO).
                withContext(Dispatchers.IO) { transport.appendLine(encrypted) }
                // Часы → галочка СРАЗУ при успешной публикации — не ждём round-trip
                // от реле (на публичных реле он ненадёжен). reconcile() позже заменит
                // оптимистичную строку серверной копией, когда она вернётся.
                chatStore.confirmSent(encrypted)
                // Реальная публикация на реле прошла (appendLine не бросил) — живое
                // подтверждение синхронизации Tor-чата, см. TorSyncWatchdog.kt.
                if (isTorChat()) TorSyncWatchdog.disarm(chat.chatId, "сообщение опубликовано на реле (appendLine OK)")
                if (chat.isFavorites) {
                    // Для локального чата имитируем мгновенную загрузку из "сети"
                    // сразу после appendLine, чтобы сообщение вышло из pending-статуса.
                    val data = withContext(Dispatchers.IO) { transport.loadAll() }
                    processChannelData(data)
                }
            },
            onMessageSent = {
                // Сообщение ушло — лимит снят, прячем жёлтую плашку сразу.
                runOnUiThread { hideTransportLimitBanner() }
                if (!chat.isFavorites) {
                    // Сбрасываем ETag: следующий GET обойдёт CDN-кеш и вернёт свежий контент.
                    // Это аналог ?t=Date.now() из веб-версии — cache-bust после PATCH.
                    // Без этого CDN может отдавать 304 (старый контент) 1-3 сек после PATCH,
                    // и часики висят до следующего обычного тика (10 сек).
                    lifecycleScope.launch {
                        // Немедленный форс-синк: ETag сброшен → 200 гарантирован → часики гаснут.
                        syncEngine.forceSync(delayMs = 0L)
                        // Страховка: если sync пропустил single-flight guard (предыдущий GET в полёте)
                        delay(500L)
                        syncEngine.forceSync(delayMs = 0L)
                        delay(1_000L)
                        syncEngine.forceSync(delayMs = 0L)
                    }
                }
            },
            onQueueChanged = { pendingList ->
                // ChatStore управляет pending-сообщениями; здесь только прокрутка
                runOnUiThread {
                    if (adapter.itemCount > 0) {
                        binding.rvMessages.scrollToPosition(adapter.itemCount - 1)
                    }
                }
            },
            onPunishmentStart = { durationMs ->
                runOnUiThread { startPunishmentCountdown(durationMs) }
            },
            onPunishmentEnd = {
                runOnUiThread { stopPunishmentCountdown() }
            },
            onSendFailed = { item, reason ->
                runOnUiThread {
                    // Раньше здесь был dropPending() — сообщение бесследно исчезало из чата
                    // (нарушение правила «часики → ошибка», см. CLAUDE.md). failSend() держит
                    // сообщение в ленте с явным значком ошибки — Message.isFailed, MessageAdapter,
                    // мокап согласован с пользователем.
                    chatStore.failSend(item.encrypted)
                    // Подробный лог с вылетом при ЛЮБОМ окончательном провале отправки — по
                    // прямой просьбе пользователя, без обобщений (класс/причина/транспорт).
                    if (isTorChat()) {
                        // TorSyncWatchdog уже даёт полный отчёт (класс исключения, цепочка cause,
                        // стектрейс, статус Tor, журнал сессии) и корректно разоружает сторож —
                        // второй отдельный краш-репорт здесь был бы дублем на одну и ту же причину.
                        TorSyncWatchdog.reportDeviation(
                            applicationContext, chat.chatId, "onSendFailed",
                            RuntimeException("Сообщение не доставлено после всех ретраев MessageSendManager: $reason")
                        )
                    } else {
                        // Прямой Nostr / Bluetooth / Избранное — TorSyncWatchdog тут ни при чём,
                        // отчёт даём напрямую.
                        val transportKind = when {
                            chat.isFavorites -> "Избранное (локально)"
                            chat.transportToken == BluetoothTransport.BT_TOKEN -> "Bluetooth"
                            chat.transportToken == com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN ->
                                "Nostr (прямое подключение)"
                            else -> "Nostr (неизвестный режим)"
                        }
                        runCatching {
                            CrashHandler.report(
                                this@ChatActivity, "ChatActivity: окончательный провал отправки сообщения",
                                RuntimeException(
                                    "Сообщение не доставлено после всех ретраев MessageSendManager.\n" +
                                        "chatId=${chat.chatId.take(8)}…, транспорт=$transportKind\n" +
                                        "причина: $reason"
                                )
                            )
                        }
                    }

                    val isNetworkError = reason.contains("Unable to resolve host", ignoreCase = true) ||
                        reason.contains("No address associated", ignoreCase = true) ||
                        reason.contains("Failed to connect", ignoreCase = true) ||
                        reason.contains("Network is unreachable", ignoreCase = true) ||
                        reason.contains("timeout", ignoreCase = true) ||
                        reason.contains("timed out", ignoreCase = true) ||
                        reason.contains("SocketTimeout", ignoreCase = true) ||
                        reason.contains("ConnectException", ignoreCase = true)
                    when {
                        isNetworkError ->
                            showChatWarning(WarningType.NETWORK)
                        reason.contains("token", ignoreCase = true) ||
                        reason.contains("401") || reason.contains("403") ->
                            showChatWarning(WarningType.TOKEN)
                        reason.contains("rate limit", ignoreCase = true) ||
                        reason.contains("429") ->
                            showChatWarning(WarningType.RATE_LIMIT)
                    }
                    val displayMsg = if (isNetworkError) {
                        getString(R.string.join_err_no_internet)
                    } else {
                        "${getString(R.string.error_send)}\n\n$reason"
                    }
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("send_error", reason))
                    Toast.makeText(this@ChatActivity, displayMsg, Toast.LENGTH_LONG).show()
                }
            },
            onRateLimit = { retryAfterMs ->
                runOnUiThread { showTransportLimitBanner(retryAfterMs) }
            }
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { confirmClearHistory() }
        // Плашка закреплённых (Этап 3): тап — следующий пин; чеврон — список; крестик — открепить свой.
        binding.pinnedBar.setOnClickListener { cyclePinned() }
        binding.btnPinnedList.setOnClickListener { showPinnedListSheet() }
        binding.btnPinnedUnpin.setOnClickListener { unpinCurrent() }
        binding.vAvatarContainer.setOnClickListener { openPartnerProfile() }
        if (chat.isFavorites) {
            binding.btnMore.visibility = View.GONE
        }
        if (chat.isSystemNotifications) {
            // Системный чат «Уведомления» — только чтение (мокап одобрен): вместо строки
            // ввода — подпись; счётчик непрочитанных гасится при входе. Сообщения сюда
            // пишет только SystemNotifications (локально, без сети).
            binding.inputArea.visibility = View.GONE
            binding.tvSystemReadonlyHint.visibility = View.VISIBLE
            lifecycleScope.launch(Dispatchers.IO) { db.chatDao().updateUnread(chat.id, 0) }
        }
        binding.btnSend.setOnClickListener { sendMessage() }
        if (btMode) {
            // BT-чат — только текст: ни голосовых, ни вложений.
            binding.btnVoice.visibility = View.GONE
            binding.btnAttach.visibility = View.GONE
            binding.btnSend.visibility = View.VISIBLE
        } else {
            setupVoiceInput()
            binding.btnAttach.setOnClickListener { openGalleryChecked() }
            setupStagedBar()
        }
        binding.btnCancelReply.setOnClickListener { clearReply() }

        // ── Sticker panel ────────────────────────────────────────────────────
        stickerPanel = StickerPanelController(
            context = this,
            chatBinding = binding,
            prefs = prefs,
            scope = lifecycleScope,
            onStickerSelected = { sticker ->
                sendSticker(sticker)
            }
        ).also { it.init() }

        binding.btnSticker.setOnClickListener {
            hideStickerSuggestions()
            stickerPanel?.togglePanel()
        }

        setupStickerSuggestions()

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }

        setupTypingDetection()
        applyWallpaper()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        applyWallpaper()
    }

    private fun applyWallpaper() {
        val isPortrait = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
        val base64 = if (isPortrait) prefs.wallpaperPortrait else prefs.wallpaperLandscape
        var hasWallpaper = !base64.isNullOrBlank()
        // Atmospheric Glass доступен в любом режиме — с обоями и без
        val isGlass = prefs.chatUiStyle == Prefs.CHAT_UI_GLASS

        if (hasWallpaper) {
            try {
                val bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT)
                val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                val maxDim = maxOf(
                    resources.displayMetrics.widthPixels,
                    resources.displayMetrics.heightPixels
                )
                var sample = 1
                while ((opts.outWidth / sample) > maxDim * 1.5 || (opts.outHeight / sample) > maxDim * 1.5) {
                    sample *= 2
                }
                val finalOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, finalOpts)
                if (bitmap != null) {
                    binding.ivChatWallpaper.setImageBitmap(bitmap)
                    binding.ivChatWallpaper.visibility = android.view.View.VISIBLE
                } else {
                    binding.ivChatWallpaper.visibility = android.view.View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                binding.ivChatWallpaper.visibility = android.view.View.GONE
            }
        } else {
            // Дефолтные обои-монограм (тема-зависимые: тёмная/светлая) — когда свои не заданы.
            try {
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
                val def = android.graphics.BitmapFactory.decodeResource(
                    resources,
                    if (isGlass) R.drawable.default_chat_wallpaper_glass
                    else R.drawable.default_chat_wallpaper,
                    opts
                )
                if (def != null) {
                    binding.ivChatWallpaper.setImageBitmap(def)
                    binding.ivChatWallpaper.visibility = android.view.View.VISIBLE
                    hasWallpaper = true
                } else {
                    binding.ivChatWallpaper.visibility = android.view.View.GONE
                }
            } catch (_: Throwable) {
                binding.ivChatWallpaper.visibility = android.view.View.GONE
            }
        }

        if (isGlass) {
            applyGlassStyle()
        } else if (hasWallpaper) {
            applyClassicWallpaperStyle()
        } else {
            applyClassicSolidStyle()
        }
        // Есть ли сейчас обои/glass (для плашки закреплённых — прячем разделитель поверх фото).
        chatHasWallpaper = hasWallpaper || isGlass

        // Применяем непрозрачность шапки и панели ввода
        val uiAlphaVal = prefs.uiAlpha / 100f
        binding.chatHeader.alpha = uiAlphaVal
        binding.inputArea.alpha  = uiAlphaVal

        // Sync adapter: glass mode + пользовательская непрозрачность пузырьков
        if (::adapter.isInitialized) {
            adapter.glassMode      = isGlass
            adapter.bubbleAlphaSelf  = prefs.bubbleAlphaSelf  / 100f
            adapter.bubbleAlphaOther = prefs.bubbleAlphaOther / 100f
            adapter.notifyDataSetChanged()
        }
    }

    /** Atmospheric Glass UI: ultra-transparent, cinematic, blurred. */
    private fun applyGlassStyle() {
        binding.replyPanel.setGlassMode(true)
        binding.replyPanel.setTarget(binding.rvMessages)
        val glassToolbarBg  = ContextCompat.getDrawable(this, R.drawable.bg_glass_toolbar)
        val glassPillBg     = ContextCompat.getDrawable(this, R.drawable.bg_glass_input_pill)
        val glowColor       = 0x40C77DFF.toInt()   // 25% purple
        val overlayBgColor  = 0x99000000.toInt()   // 60% black for loading overlay

        binding.chatHeader.background = glassToolbarBg
        binding.inputArea.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.inputPill.background = glassPillBg
        binding.headerDivider.setBackgroundColor(glowColor)
        binding.headerDivider.visibility = android.view.View.VISIBLE
        binding.loadingOverlay.setBackgroundColor(overlayBgColor)
        // Крупная плашка мута играет ту же роль, что loadingOverlay (перекрывает
        // область сообщений) — тот же фон, чтобы не класть непрозрачный @color/bg
        // поверх обоев (запрещено в glass mode, см. CLAUDE.md §0).
        binding.mutedBannerLarge.setBackgroundColor(overlayBgColor)

        // Cinematic scrim gradients
        binding.viewScrimTop.visibility    = android.view.View.VISIBLE
        binding.viewScrimBottom.visibility = android.view.View.VISIBLE

        // API 31+: backdrop blur for true glass effect (via reflection — avoids SDK stub gap)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val blurMethod = android.view.View::class.java
                    .getMethod("setBackgroundBlurRadius", Int::class.javaPrimitiveType)
                blurMethod.invoke(binding.chatHeader, 40)
                blurMethod.invoke(binding.inputPill, 25)
            } catch (_: Throwable) { /* not available on this device */ }
        }

        // Tint icons/text white for readability over wallpaper
        val white = android.graphics.Color.WHITE
        binding.tvDisplayName.setTextColor(white)
        binding.tvChatSubtitle.setTextColor(0xB3FFFFFF.toInt())  // 70% white
        styleNotifEmptyCard(overWallpaper = true)
        stylePinnedBar(overWallpaper = true)
    }

    /** Classic mode with wallpaper: semi-transparent overlay on toolbar/input. */
    private fun applyClassicWallpaperStyle() {
        binding.replyPanel.setGlassMode(false)
        val overlayColor = ContextCompat.getColor(this, R.color.chat_overlay)
        val pillBg       = ContextCompat.getDrawable(this, R.drawable.bg_chat_input_pill)

        binding.chatHeader.setBackgroundColor(overlayColor)
        binding.inputArea.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.inputPill.background = pillBg
        binding.headerDivider.visibility = android.view.View.GONE
        binding.loadingOverlay.setBackgroundColor(overlayColor)
        binding.mutedBannerLarge.setBackgroundColor(overlayColor)

        binding.viewScrimTop.visibility    = android.view.View.GONE
        binding.viewScrimBottom.visibility = android.view.View.GONE

        clearBackdropBlur()
        restoreDefaultTextColors()
        // Классический режим С ОБОЯМИ — тоже поверх фото, карточка пустого состояния
        // стеклянная (белый текст на тёмной подложке), чтобы читалось на любом фоне.
        styleNotifEmptyCard(overWallpaper = true)
        stylePinnedBar(overWallpaper = true)
    }

    /** Classic mode without wallpaper: solid background everywhere. */
    private fun applyClassicSolidStyle() {
        binding.replyPanel.setGlassMode(false)
        val solidColor = ContextCompat.getColor(this, R.color.bg)
        val pillBg     = ContextCompat.getDrawable(this, R.drawable.bg_chat_input_pill)

        binding.chatHeader.setBackgroundColor(solidColor)
        binding.inputArea.setBackgroundColor(solidColor)
        binding.inputPill.background = pillBg
        binding.headerDivider.visibility = android.view.View.VISIBLE
        binding.headerDivider.setBackgroundColor(ContextCompat.getColor(this, R.color.border))
        binding.loadingOverlay.setBackgroundColor(solidColor)
        binding.mutedBannerLarge.setBackgroundColor(solidColor)

        binding.viewScrimTop.visibility    = android.view.View.GONE
        binding.viewScrimBottom.visibility = android.view.View.GONE

        clearBackdropBlur()
        restoreDefaultTextColors()
        // Без обоев — обычная surface-карточка с токенами темы.
        styleNotifEmptyCard(overWallpaper = false)
        stylePinnedBar(overWallpaper = false)
    }

    /**
     * Оформление плашки закреплённых под текущий фон (Этап 3, §0 три режима):
     * поверх обоев/glass — полупрозрачная тёмная подложка + белый текст (без непрозрачных
     * токенов фона, CLAUDE.md §0); без обоев — обычная surface + токены темы.
     */
    private fun stylePinnedBar(overWallpaper: Boolean) {
        if (overWallpaper) {
            binding.pinnedBar.setBackgroundColor(0x99000000.toInt()) // 60% чёрный поверх фото
            binding.tvPinnedPreview.setTextColor(android.graphics.Color.WHITE)
            binding.tvPinnedLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_light))
            binding.btnPinnedList.setColorFilter(0xE0FFFFFF.toInt())
            binding.btnPinnedUnpin.setColorFilter(0xE0FFFFFF.toInt())
            binding.pinnedBarDivider.visibility = android.view.View.GONE
        } else {
            binding.pinnedBar.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))
            binding.tvPinnedPreview.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvPinnedLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_light))
            binding.btnPinnedList.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnPinnedUnpin.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.pinnedBarDivider.visibility =
                if (binding.pinnedBar.visibility == android.view.View.VISIBLE) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    /**
     * Оформление карточки пустого состояния чата «Уведомления» под текущий фон
     * (репорт: «текст не сочетается с кастомными обоями»). Поверх обоев — стеклянная
     * тёмная подложка + белый текст (читается на любом фото, CLAUDE.md §0); без обоев —
     * обычная surface-карточка с токенами. No-op для остальных чатов.
     */
    private fun styleNotifEmptyCard(overWallpaper: Boolean) {
        if (!::chat.isInitialized || !chat.isSystemNotifications) return
        if (overWallpaper) {
            binding.notifEmptyCard.setBackgroundResource(R.drawable.bg_notif_empty_glass)
            binding.ivNotifEmptyIcon.setColorFilter(android.graphics.Color.WHITE)
            binding.tvNotifEmptyTitle.setTextColor(android.graphics.Color.WHITE)
            binding.tvNotifEmptySub.setTextColor(0xE0FFFFFF.toInt()) // ~88% белый
        } else {
            binding.notifEmptyCard.setBackgroundResource(R.drawable.bg_settings_card)
            binding.ivNotifEmptyIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
            binding.tvNotifEmptyTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.tvNotifEmptySub.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun clearBackdropBlur() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            try {
                val blurMethod = android.view.View::class.java
                    .getMethod("setBackgroundBlurRadius", Int::class.javaPrimitiveType)
                blurMethod.invoke(binding.chatHeader, 0)
                blurMethod.invoke(binding.inputPill, 0)
            } catch (_: Throwable) { /* not available on this device */ }
        }
    }

    private fun restoreDefaultTextColors() {
        binding.tvDisplayName.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
    }

    override fun onResume() {
        super.onResume()
        // Приняли передачу владения (TransferOfferActivity) → переоткрываем чат, чтобы транспорт
        // пересоздался со СМЕНЁННЫМ adminUserId (его password-pubkey — гейт members.txt нового
        // владельца). После recreate() человек попадает в уже прогруженный чат создателем.
        if (::chat.isInitialized && pendingOwnerReloadChatId == chat.id) {
            pendingOwnerReloadChatId = -1L
            recreate()
            return
        }
        isInForeground = true
        // Фоновый синк членства пропускает открытый чат (он поллится своим SyncEngine) —
        // меньше параллельных loadAll через Tor, беседа грузится быстрее (репорт).
        if (::chat.isInitialized) App.currentOpenChatId = chat.chatId
        // Плашка закреплённых — сразу из Room (Этап 3), не дожидаясь сетевого тика.
        if (::chat.isInitialized && chat.isGroup) lifecycleScope.launch { refreshPinState() }
        updateStickerWarning()
        // Чат снова открыт — отменяем отложенную (на 30 мин) очистку Argon2-кеша.
        if (::transport.isInitialized) CryptoHelper.cancelScheduledClear(transport.chatId)
        resumeVisibleStickers()
        applyWallpaper()
        if (::chat.isInitialized) {
            // Своя аватарка — сразу и без сети (могла поменяться в Настройках, пока мы
            // были на другом экране). Остальные участники подтянутся чуть ниже через
            // syncProfiles() (сеть) — это уже мгновенно по локальным меркам (§1.5), но
            // своя аватарка не должна ждать даже одного сетевого тика.
            if (::adapter.isInitialized) refreshMessageAvatars()
            // Re-ensure: при возврате в Tor-чат поднимаем Tor, если он «уснул» в фоне.
            if (isTorChat()) {
                TorManager.start(this)
                // Возврат в Tor-чат — тоже валидный момент «подключения» для сторожа
                // синхронизации (см. TorSyncWatchdog.kt). Идемпотентно для уже идущей сессии.
                TorSyncWatchdog.arm(applicationContext, chat.chatId)
            }
            registerNetworkMonitoring()
            // Сбрасываем кэши — при возврате в чат гарантируем:
            //  1. lastContent="" → loadMessages всегда парсит заново
            //  2. lastPushedReadIndex=-1 → read receipt отправится даже если контент не изменился
            lastContent = ""
            lastPushedReadIndex = -1
            // Снимаем overlay только если нет уже загруженных сообщений.
            if (!firstLoadComplete && (chatStore.messages.value.isEmpty() && !chatStore.hasPending())) {
                firstLoadComplete = false
                binding.loadingOverlay.alpha = 1f
                binding.loadingOverlay.visibility = View.VISIBLE
                // Лента остаётся видимой ПОД непрозрачным оверлеем (alpha=1) — сообщения
                // рендерятся за экраном загрузки, а не проявляются после него (см. revealMessages).
                binding.rvMessages.alpha = 1f
            }
            startPolling()
            startPresence()
            // Немедленный форс-синк через SyncEngine: сбрасываем ETag + GET сразу.
            // Заменяет отдельный loadMessages() — избегаем параллельного GET рядом со
            // стартом SyncEngine. ETag сброшен в lastContent="" выше — следующий
            // poll вернёт свежий контент независимо от кэша.
            syncEngine.forceSync(delayMs = 0L)
            // Перетягиваем актуальные данные собеседника (вдруг он сменил аватарку/ник
            // пока мы были в Settings или другом чате).
            syncProfiles()
            // Чат «Уведомления»: возврат на экран гасит счётчик непрочитанных —
            // новые записи могли прийти, пока экран был в бэкстеке.
            if (chat.isSystemNotifications) {
                lifecycleScope.launch(Dispatchers.IO) { db.chatDao().updateUnread(chat.id, 0) }
            }
            // И перетягиваем свою аватарку — могла поменяться в Settings, и
            // если она у нас в Room — partnerName тоже мог обновиться.
            // На всякий случай перерисовываем шапку из свежей версии чата.
            // Используем безусловное обновление, чтобы подхватить любые изменения (name, avatar, tag, deleted).
            lifecycleScope.launch {
                db.chatDao().getById(chat.id)?.let { fresh ->
                    chat = fresh
                    applyPartnerToHeader()
                }
            }
        }
    }

    /** Пауза анимаций стикеров (Lottie/webm) у видимых сообщений — при уходе в фон. */
    private fun pauseVisibleStickers() {
        val rv = binding.rvMessages
        for (i in 0 until rv.childCount) {
            (rv.getChildViewHolder(rv.getChildAt(i)) as? MessageAdapter.VH)?.pauseSticker()
        }
    }

    /** Возобновление анимаций стикеров у видимых сообщений — при возврате в чат. */
    private fun resumeVisibleStickers() {
        val rv = binding.rvMessages
        for (i in 0 until rv.childCount) {
            (rv.getChildViewHolder(rv.getChildAt(i)) as? MessageAdapter.VH)?.resumeSticker()
        }
    }

    // ── Мониторинг сети ──────────────────────────────────────────────────────────
    // При ВОЗВРАТЕ связи восстанавливаем доставку прямо на экране чата, без перезахода
    // (§1.5): сбрасываем мёртвые сокеты к реле, чисто ре-bootstrap'им Tor (лечит
    // «залипший» READY на мёртвом SOCKS) и сразу форсим тик опроса.
    private fun registerNetworkMonitoring() {
        if (networkCallback != null) return                 // уже зарегистрировано
        if (!::chat.isInitialized || chat.isFavorites) return
        if (chat.transportToken == BluetoothTransport.BT_TOKEN) return  // BT — без сети
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) { networkWasLost = true }
            override fun onAvailable(network: Network) {
                // Только РЕАЛЬНЫЙ возврат после потери — не дёргаем Tor на первом коннекте.
                if (!networkWasLost) return
                networkWasLost = false
                onNetworkRegained()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
    }

    private fun unregisterNetworkMonitoring() {
        val cm = connectivityManager
        val cb = networkCallback
        if (cm != null && cb != null) runCatching { cm.unregisterNetworkCallback(cb) }
        networkCallback = null
        networkWasLost = false
    }

    /** Возврат сети: сброс мёртвых сокетов + ре-bootstrap Tor + немедленный опрос. */
    private fun onNetworkRegained() {
        runCatching { com.atrum.chat.nostr.NostrRelayPool.shutdown() }
        if (!chat.isFavorites &&
            chat.transportToken != BluetoothTransport.BT_TOKEN &&
            chat.transportToken != com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN) {
            TorManager.restart(applicationContext)
        }
        if (::syncEngine.isInitialized) syncEngine.forceSync(0L)
    }

    override fun onDestroy() {
        super.onDestroy()
        // Освобождаем видео-плееры webm-стикеров (ExoPlayer/GL). Без этого они продолжают
        // крутиться в фоне и утекают между чатами -> рост памяти и OOM (в т.ч. при Argon2).
        try { binding.rvMessages.adapter = null } catch (_: Exception) {}
        VoicePlayer.stop()
        runCatching { if (voiceRecorder.isRecording) voiceRecorder.cancel() }
        stickerPanel?.destroy()
        stickerPanel = null
        suggestJob?.cancel()
        try { binding.rvStickerSuggestions.adapter = null } catch (_: Exception) {}
        suggestionAdapter = null

        // Штатная очистка, документированная в коде, но не выполнявшаяся (onDestroy отсутствовал):
        //  1. Зануляем эфемерный приватный X25519-ключ — forward secrecy.
        myEphemeralPrivKey?.fill(0)
        myEphemeralPrivKey = null
        //  2. Чистим кеш выведенных Argon2-ключей этого чата — освобождаем память.
        if (::transport.isInitialized) {
            try { CryptoHelper.scheduleClearCachedKey(transport.chatId, chat.chatPassword) } catch (_: Exception) {}
        }
        //  3. BT-чат: закрываем подписку и рвём BLE только при реальном выходе из чата.
        runCatching { transportWatch?.close() }; transportWatch = null
        runCatching { profilesWatch?.close() }; profilesWatch = null
        if (btMode && isFinishing) runCatching { BleManager.shutdown(applicationContext) }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        mentionMenuPopup?.dismiss() // не держим попап меню упоминаний при уходе с экрана
        // Чат закрыт — фоновый синк членства снова может опрашивать эту группу.
        if (::chat.isInitialized && App.currentOpenChatId == chat.chatId) App.currentOpenChatId = null
        // Голосовые: не держим открытым микрофон и не играем вне экрана.
        if (voiceRecorder.isRecording) {
            voiceUiJob?.cancel(); voiceUiJob = null
            runCatching { voiceRecorder.cancel() }
            restoreInputAfterRecording()
        }
        VoicePlayer.stop()
        pauseVisibleStickers()
        // Останавливаем SyncEngine и коллектор событий
        if (::syncEngine.isInitialized) syncEngine.stop()
        unregisterNetworkMonitoring()
        syncCollectorJob?.cancel()
        syncCollectorJob = null
        localRefreshJob?.cancel()
        localRefreshJob = null
        markAsReadJob?.cancel()
        markAsReadJob = null
        // Останавливаем presence-цикл и немедленно сбрасываем оба статуса в 0:
        // собеседник увидит «не в сети / не печатает» через один цикл опроса (~3 сек)
        isCurrentlyTyping = false
        stopTypingJob?.cancel(); stopTypingJob = null
        presenceJob?.cancel(); presenceJob = null
        presenceTickerJob?.cancel(); presenceTickerJob = null
        lastPartnerProfile = null
        if (::transport.isInitialized) {
            val capturedTransport  = transport
            val capturedPassword   = chat.chatPassword
            val capturedUserId     = prefs.myUserId
            val capturedName       = prefs.myName
            val capturedTag        = prefs.myTag
            val capturedAvatar     = prefs.myAvatarBase64
            val capturedEphKey     = myCurrentEphemeralPubKey
            val capturedIdentity   = prefs.myIdentityPubKey
            val capturedSig        = myEphemeralSig
            val capturedIdentitySig = myIdentitySig
            val capturedConfirmed  = prefs.getConfirmedPartnerIdentity(chat.chatId)
            AppScope.launch {
                // Уход из приложения → офлайн. Ретрай: по Tor единичный PATCH часто
                // не доходит, и партнёр видел бы «в сети» ещё долго после ухода.
                // ⛔ Публикуем ПОЛНЫЙ профиль (ава + identityPubKey + ephemeralSig +
                // identitySig + tag). Без identitySig оффлайн-пуш при пустом чтении обнулял
                // подпись → у собеседников пропадала галочка И иммунитет дева (§Часть 3),
                // пока я не онлайн. Полный набор держит статус даже оффлайн.
                repeat(3) {
                    val ok = try {
                        ProfileSync.pushPresence(
                            api               = capturedTransport,
                            password          = capturedPassword,
                            myUserId          = capturedUserId,
                            typingTs          = 0L,
                            onlineTs          = 0L,
                            myEphemeralPubKey = capturedEphKey,
                            myName            = capturedName,
                            myTag             = capturedTag,
                            myAvatarBase64    = capturedAvatar,
                            myIdentityPubKey     = capturedIdentity,
                            myEphemeralSig       = capturedSig,
                            myIdentitySig        = capturedIdentitySig,
                            myVerifiedPartnerIdk = capturedConfirmed
                        )
                    } catch (_: Exception) { false }
                    if (ok) return@launch
                    delay(1_500)
                }
            }
        }
    }

    private fun startPolling() {
        // Прогрев соединений к реле ЭТОГО чата в его режиме (репорт: «первое сообщение в
        // беседе заедает, у других не видно, помогает перезаход»). Идемпотентно; вызывается
        // и при открытии, и при возврате (startPolling — из onCreate и onResume). Для
        // локального чата warmUp() — no-op. См. ChatTransport.warmUp / NostrTransport.warmUp.
        if (::transport.isInitialized) transport.warmUp()

        // Мгновенный показ из ДОЛГОВЕЧНОГО локального стора (§1.5): не ждём первого сетевого
        // чтения. Nostr/реле медленны на холодном старте, а ПУСТОЙ чат вообще ждал сеть просто
        // чтобы подтвердить «пусто» — отсюда ощущение «грузится долго, хотя грузить нечего».
        // Рендер с диска синхронный; сеть обновит на месте следующим тиком (§1.5, без мерцания —
        // дедуп по контенту). Если кэш этой сессии уже показал чат (firstLoadComplete) — пропускаем.
        if (::transport.isInitialized && !firstLoadComplete && !chat.isFavorites) {
            lifecycleScope.launch {
                val local = withContext(Dispatchers.IO) { runCatching { transport.loadLocalSnapshotOrNull() }.getOrNull() }
                if (local != null && !firstLoadComplete) {
                    runCatching { processChannelData(local) }
                    // Пустой локальный контент мог не снять оверлей через коллектор сообщений —
                    // снимаем явно, чтобы пустой чат открывался мгновенно, а не ждал сеть.
                    if (!firstLoadComplete) {
                        firstLoadComplete = true
                        revealMessages()
                    }
                }
            }
        }

        if (chat.isFavorites) {
            // Локальный чат (Избранное / «Уведомления»): сети нет, но файл на диске
            // меняется извне (SystemNotifications). Лёгкий ре-рид: первый проход снимает
            // оверлей и показывает уже записанные строки, дальше — живые новые записи,
            // пока чат открыт. Дедуп по контенту в processChannelData → без мерцания.
            localRefreshJob?.cancel()
            localRefreshJob = lifecycleScope.launch {
                while (isActive) {
                    runCatching {
                        val data = withContext(Dispatchers.IO) { transport.loadAll() }
                        processChannelData(data)
                    }
                    delay(2_000L)
                }
            }
            return
        }

        // ── SyncEngine: единый single-flight ETag-опрос ───────────────────────
        // • 10с интервал (был 8с, но single-flight = нет overlapping GET)
        // • 304 Not Modified = 0 парсинга, 0 обновления UI
        // • Rate limit: engine сам паузирует на Retry-After
        // • forceSync() вместо дополнительного loadMessages() после отправки
        syncEngine.start(lifecycleScope)

        // Подписка на данные из SyncEngine → обработка в одном месте
        syncCollectorJob?.cancel()
        syncCollectorJob = lifecycleScope.launch {
            syncEngine.events.collect { data ->
                processChannelData(data)
            }
        }

        // Отзыв/возврат создателя (revoke.txt) и передача владения (owner.txt) читают ОТДЕЛЬНЫЕ
        // файлы, а не контент чата. SyncEngine вызывает processChannelData только при изменении
        // контента (304 → пропуск), поэтому в простаивающем чате отзыв применялся лишь при
        // следующем сообщении/перезаходе. Свой лёгкий тикер решает это: он крутится независимо
        // от контента; внутренние гейты (push/12с у revoke, 30с у owner) делают его почти
        // бесплатным (в большинстве тиков — мгновенный no-op без сети). Только для бесед.
        ownerRevokeTickerJob?.cancel()
        ownerRevokeTickerJob = lifecycleScope.launch {
            while (isActive) {
                if (::chat.isInitialized && chat.isGroup) {
                    // Отложенная перезагрузка после принятия передачи владения в TransferOfferActivity:
                    // пересоздаём ПОКА окно передачи ещё сверху → чат грузится ЗА окном (§1.5), без
                    // отдельного экрана загрузки после. onResume — запасной путь, если recreate в
                    // stopped-состоянии отложится. Сбрасываем флаг, чтобы не пересоздать дважды.
                    if (pendingOwnerReloadChatId == chat.id) {
                        pendingOwnerReloadChatId = -1L
                        withContext(Dispatchers.Main) { runCatching { recreate() } }
                        return@launch
                    }
                    runCatching { syncOwnerCerts() }
                    runCatching { syncRevokes() }
                }
                delay(2500L)
            }
        }
    }

    /**
     * Обрабатывает уже загруженный зашифрованный контент profiles.txt — без сетевого вызова.
     *
     * Вызывается из loadMessages() когда данные пришли вместе с chat.txt в одном GET
     * (preloadedData.profilesContent или результат transport.loadAll()).
     *
     * ⚠️ Удалены doRefreshPartnerReadIndex()/refreshPartnerReadIndex() (мёртвый код —
     * ни один вызывающий по всему проекту не найден, см. аудит по репорту пользователя
     * "авы/ники везде"). Дублировали ровно эту же логику, но читали profiles.txt старым
     * lossy-способом — ОДИН общий блоб вместо union слотов (см. ProfileSync.unionProfileSlots),
     * из-за чего при одновременной публикации профиля несколькими участниками группы
     * чей-то профиль мог потеряться. Судя по всему остались от архитектуры до перехода
     * на единый SyncEngine (§1 CLAUDE.md — весь polling в одном месте); заново подключать
     * их нельзя — это был бы отдельный, второй polling-цикл, что прямо запрещено §1.
     *
     * Результат: обновлены lastKnownProfiles, typing/online индикаторы, галочки прочтения,
     * имя/аватар партнёра, ephemeral ключ (V3 forward secrecy).
     */
    private suspend fun processProfilesFromContent(rawEncrypted: String) {
        if (rawEncrypted.isBlank()) return
        val newHash = rawEncrypted.hashCode()
        if (newHash == lastProfilesHash && lastKnownProfiles.isNotEmpty()) return
        lastProfilesHash = newHash
        processParsedProfiles(ProfileSync.parseProfiles(rawEncrypted, chat.chatPassword, transport.chatId))
    }

    /** Фаза 1: union-чтение всех слотов profiles.txt (по одному на участника) — убирает lost-update. */
    private suspend fun processProfilesFromSlots(slots: List<String>) {
        if (slots.isEmpty()) return
        val newHash = slots.hashCode()
        if (newHash == lastProfilesHash && lastKnownProfiles.isNotEmpty()) return
        lastProfilesHash = newHash
        processParsedProfiles(ProfileSync.unionProfileSlots(slots, chat.chatPassword, transport.chatId))
    }

    private suspend fun processParsedProfiles(parsed: Map<String, Profile>) {
        // Сырой снимок — для presence-записей (чтобы не реинжектить устаревшего партнёра).
        lastKnownProfiles.clear()
        lastKnownProfiles.putAll(parsed)
        // Для отображения и сессионного ключа — «липкий» партнёр (флаки-чтение не теряет его).
        val allProfiles = ProfileSync.unionAndRemember(transport.chatId, parsed)
        // Кандидаты упоминания (@) — из «липкого» union (аватары/теги не пропадают).
        if (chat.isGroup) rebuildMentionCandidates(allProfiles)
        // ⚠️ Фикс (репорт: "аватарки собеседников мигают и исчезают и появляются
        // периодически"): аватарки в пузырьках сообщений ОБЯЗАНЫ строиться из "липкого"
        // allProfiles, а НЕ из сырого lastKnownProfiles/parsed этого конкретного тика —
        // иначе один флаки-Tor-тик без аватара в ответе на миг гасит уже показанную
        // картинку, и она тут же возвращается на следующем тике (мерцание). Считается
        // ДО ветвления по partner (для групп partner может быть null, а аватарки
        // участников всё равно должны обновиться).
        refreshMessageAvatars(allProfiles)

        val partner = ProfileSync.findPartner(allProfiles, prefs.myUserId, prefs.myName)
        if (partner == null) {
            updateTypingIndicator(false)
            updateOnlineIndicator(false)
            return
        }

        // ⚠️ Фикс (репорт: «зашёл в чат — плашка что партнёр присоединился не
        // появилась»): переход partnerJoined=false→true и сама плашка раньше считались
        // ТОЛЬКО в doSyncProfilesOnce() — с ограниченным числом ретраев (3с→6с→9с) при
        // onCreate/onResume. Если партнёр публиковал профиль ПОЗЖЕ этого окна, а чат всё
        // это время оставался открытым (без выхода-входа) — плашка никогда не появлялась.
        // processParsedProfiles() уже вызывается на КАЖДОМ тике (тот же принцип, что уже
        // применён к групповым чатам, см. processChannelData) — считаем джойн здесь же.
        if (!chat.isGroup && !chat.partnerJoined) {
            db.chatDao().markPartnerJoined(chat.id)
            chat = chat.copy(partnerJoined = true)
            val joinedName = partner.name.takeIf { it.isNotBlank() }
                ?: chat.partnerName.takeIf { it.isNotBlank() }
                ?: getString(R.string.join_default_partner_name)
            chatStore.addSystemMessage(
                Message.system(getString(R.string.cc_system_partner_joined, joinedName))
            )
        }

        // Обновляем V3-сессионный ключ если партнёр опубликовал новый ephemeral ключ
        tryEstablishSessionKey(partner.ephemeralPubKey)
        verifyPartnerIdentity(partner)

        // Галочка верификации в шапке — обновляем СРАЗУ после проверки подписи, а не ждём
        // смены имени/авы (репорт: галочка подхватывалась через ~7с после захода собеседника).
        // Дёшево: анимация проиграется только на ПЕРВОМ показе (см. VerifiedBadgeView).
        if (!chat.isGroup) {
            val vInfo = IdentityState.get(chat.chatId)
            val liveVerified = vInfo.state == IdentityState.State.VERIFIED &&
                VerifiedBadge.isKeyVerified(vInfo.partnerIdk)
            // Устойчивый fallback — см. applyPartnerToHeader: оффлайн-сессия не гасит галочку.
            binding.verifiedBadgeHeader.setVerified(liveVerified || chat.partnerVerified, animate = true)
            // Персистим неподделываемый флаг при первой живой верификации (партнёр онлайн),
            // чтобы галочка держалась оффлайн даже если список чатов ещё не синкал профиль.
            if (liveVerified && !chat.partnerVerified) {
                db.chatDao().updatePartnerVerified(chat.id, true)
                chat = chat.copy(partnerVerified = true)
            }
        }

        // Обновляем имя/аватар партнёра — ТОЛЬКО 1:1 (см. подробный комментарий в
        // doSyncProfilesOnce: для группы findPartner() выбирает произвольного участника,
        // и его аватар/имя затирали карточку группы; галочки «прочитано» группы считаются
        // отдельно — по минимуму lastReadIndex всех остальных, ниже).
        if (!chat.isGroup) {
            val nameToSave = if (partner.name.isNotBlank()) partner.name else chat.partnerName
            val tagToSave = if (!partner.tag.isNullOrBlank()) partner.tag else chat.partnerTag
            val avatarToSave = if (!partner.avatarBase64.isNullOrBlank()) partner.avatarBase64 else chat.partnerAvatarBase64

            if (nameToSave != chat.partnerName || tagToSave != chat.partnerTag || avatarToSave != chat.partnerAvatarBase64) {
                db.chatDao().updatePartnerProfile(chat.id, nameToSave, tagToSave, avatarToSave)
                chat = chat.copy(partnerName = nameToSave, partnerTag = tagToSave, partnerAvatarBase64 = avatarToSave)
                applyPartnerToHeader()
            }

            if (partner.deleted != chat.partnerDeleted) {
                db.chatDao().updatePartnerDeleted(chat.id, partner.deleted)
                chat = chat.copy(partnerDeleted = partner.deleted)
                applyPartnerToHeader()
            }

            if (partner.lastReadIndex != chat.partnerLastReadIndex) {
                db.chatDao().updatePartnerLastRead(chat.id, partner.lastReadIndex)
                chat = chat.copy(partnerLastReadIndex = partner.lastReadIndex)
                adapter.setPartnerLastReadIndex(partner.lastReadIndex)
            }
        } else {
            // Группа: «прочитано» ✓✓ — когда ВСЕ остальные дочитали (минимум по всем),
            // а не произвольный partner (тот же принцип, что в doSyncProfilesOnce).
            val others = allProfiles.values.filter { it.userId != prefs.myUserId }
            val minReadIndex = others.minOfOrNull { it.lastReadIndex } ?: 0
            if (minReadIndex != chat.partnerLastReadIndex) {
                db.chatDao().updatePartnerLastRead(chat.id, minReadIndex)
                chat = chat.copy(partnerLastReadIndex = minReadIndex)
                adapter.setPartnerLastReadIndex(minReadIndex)
            }
        }

        lastPartnerProfile = partner
        applyPresence()
    }

    /**
     * Загружает и отображает сообщения.
     *
     * [silent]        = true  → без spinner; ошибки не показываем тостом.
     * [useEtag]       = true  → ETag-оптимизация (legacy, используется только при форсированном вызове).
     * [preloadedData] = not null → данные уже есть (legacy compatibility, для doClearHistory).
     *
     * В новой архитектуре основной поток данных идёт через SyncEngine → processChannelData().
     * loadMessages() используется только для первой загрузки (silent=true из onCreate).
     */
    private fun loadMessages(
        silent: Boolean = false,
        useEtag: Boolean = false,
        preloadedData: AllChannelData? = null
    ) {
        if (!silent) showLoading(true)
        
        // Попытка загрузить из кэша для мгновенного отображения
        if (preloadedData == null) {
            ChatSnapshotCache.get(chat.chatId)?.let { cached ->
                lifecycleScope.launch { processChannelData(cached) }
            }
        }

        lifecycleScope.launch {
            try {
                val data: AllChannelData = when {
                    preloadedData != null -> preloadedData
                    else -> withContext(Dispatchers.IO) { transport.loadAll() }
                }
                processChannelData(data)
            } catch (e: TokenExpiredException) {
                recordLoadFailure()
                if (consecutiveFailures >= FAILURES_BEFORE_WARNING) showChatWarning(WarningType.TOKEN)
                if (!silent) Toast.makeText(this@ChatActivity, getString(R.string.error_load), Toast.LENGTH_SHORT).show()
                if (!firstLoadComplete) { firstLoadComplete = true; revealMessages() }
            } catch (e: RateLimitException) {
                recordLoadFailure()
                if (consecutiveFailures >= FAILURES_BEFORE_WARNING) showChatWarning(WarningType.RATE_LIMIT)
                if (!silent) Toast.makeText(this@ChatActivity, getString(R.string.error_load), Toast.LENGTH_SHORT).show()
                if (!firstLoadComplete) { firstLoadComplete = true; revealMessages() }
            } catch (e: Exception) {
                recordLoadFailure()
                if (consecutiveFailures >= FAILURES_BEFORE_WARNING) showChatWarning(WarningType.NETWORK)
                if (!silent) Toast.makeText(this@ChatActivity, getString(R.string.error_load), Toast.LENGTH_SHORT).show()
                if (!firstLoadComplete) { firstLoadComplete = true; revealMessages() }
            } finally {
                if (!silent) showLoading(false)
            }
        }
    }

    /**
     * Центральная точка обработки данных из канала.
     * Вызывается из:
     *  • SyncEngine.events collector (основной поток)
     *  • loadMessages() (первая загрузка)
     *
     * Всё в одном месте: парсинг → reconcile → UI → read receipt → profiles.
     */
    private suspend fun processChannelData(data: AllChannelData) {
        ChatSnapshotCache.put(chat.chatId, data)
        val chatContent     = data.chatContent
        val profilesContent = data.profilesContent

        // ── Реакции: обрабатываем ВСЕГДА, независимо от chat content ─────────
        // ВАЖНО: этот блок стоит ДО раннего return. Если партнёр поставил реакцию
        // без новых сообщений — chatContent не меняется, но reactions.txt изменился,
        // gist ETag изменился → SyncEngine получает 200 → мы сюда попадаем.
        // Ранний return ниже убил бы эти реакции — партнёр никогда бы их не увидел.
        val reactionsRaw = data.reactionsContent
        if (reactionsRaw != lastReactionsRaw) {
            lastReactionsRaw = reactionsRaw
            // Расшифровываем и парсим реакции в фоновом потоке (V4/Argon2id тяжелый)
            val decrypted = withContext(Dispatchers.Default) {
                if (reactionsRaw.isBlank()) ""
                else CryptoHelper.decrypt(reactionsRaw, chat.chatPassword, chat.chatId) ?: ""
            }
            val parsedReactions = withContext(Dispatchers.Default) { parseReactions(decrypted) }
            currentReactions = parsedReactions
            // ВАЖНО: храним РАСШИФРОВАННЫЙ текущий набор реакций, а НЕ "".
            // Раньше сбрасывали в "" → следующий локальный toggle строил новый
            // reactions.txt с нуля и затирал ВСЕ остальные реакции. Теперь toggle
            // делает read-modify-write поверх реального актуального набора.
            lastReactionsContent = decrypted
            // Обновляем реакции в адаптере ТОЛЬКО при реальном изменении — иначе
            // notifyDataSetChanged на каждый опрос перерисовывает список и стикеры мигают.
            withContext(Dispatchers.Main) {
                adapter.setReactions(currentReactions, prefs.myUserId)
            }
        }

        // ── Profiles (typing / online / partner data) ─────────────────────────
        // ⚠️ ПЕРЕНЕСЕНО СЮДА (было ниже, после декодирования сообщений) — найдено по
        // репорту пользователя: "человек считается вошедшим только после первого его
        // сообщения". Раньше этот блок стоял ПОСЛЕ раннего return по "chatContent ==
        // lastContent" (см. ниже) — точно та же ловушка, что уже описана в комментарии
        // у блока реакций выше: если новый участник только опубликовал profiles.txt
        // (вступил, прислал свой профиль), но ещё не написал ни одного сообщения,
        // chatContent не менялся → ранний return срабатывал ДО того, как lastKnownProfiles
        // успевал обновиться → maybeAdminEnrollNewMembers() ниже видел СТАРЫЙ снимок
        // lastKnownProfiles без нового участника и не мог его добавить. Только когда
        // кто-то (неважно кто) отправлял сообщение — chatContent менялся, early return
        // не срабатывал, профили наконец обновлялись, и УЖЕ НА СЛЕДУЮЩЕМ тике админ
        // видел кандидата. Теперь профили читаются ДО early return и ДО enrollment —
        // тот же тик подхватывает и профиль, и добавление в members.txt.
        if (SLOT_UNION_PROFILES && data.profileSlots.isNotEmpty()) {
            processProfilesFromSlots(data.profileSlots)   // Фаза 1: union всех слотов
        } else if (profilesContent.isNotBlank()) {
            processProfilesFromContent(profilesContent)
        }

        // ── members.txt: членство/бан группового чата (ADR-001) ──────────────
        // data.membersContent транспорт УЖЕ отфильтровал по подписи админа
        // (см. NostrTransport.latestVerifiedMembersFile) — здесь только расшифровка
        // и применение к локальному кэшу ChatParticipant. Полная реакция на бан
        // (счётчик участников в шапке, авто-удаление у забаненного) — отдельные шаги.
        if (chat.isGroup) {
            // Истечение срока моего мута — нет события members.txt, ловим по времени
            // (троттл ~10с, чтобы не дёргать список чатов каждую секунду тика).
            val nowMs0 = System.currentTimeMillis()
            if (nowMs0 - lastMuteExpiryCheckMs > 10_000L) {
                lastMuteExpiryCheckMs = nowMs0
                lifecycleScope.launch(Dispatchers.IO) { SystemNotifications.checkMuteExpiry(applicationContext) }
            }
            // «Профиль беседы» — ПЕРЕД members.txt: быстрый источник имени/авы/описания
            // (см. GroupProfileSync), защищён своим анти-откатом по ts.
            if (applyGroupProfileFromPoll(data.groupProfileContent, data.membersContent.isNotBlank())) {
                withContext(Dispatchers.Main) { applyPartnerToHeader() }
            }

            val membersRaw = data.membersContent
            val slotsSig2 = memberSlotsSig(data.memberSlots)
            if (membersRaw.isNotBlank() && (membersRaw != lastMembersRaw || slotsSig2 != lastMemberSlotsSig)) {
                lastMembersRaw = membersRaw
                lastMemberSlotsSig = slotsSig2
                // Снимок того, что лежит на реле, — для самопочинки админа ниже
                // (maybeAdminRepairMembersFile). Расшифровка дёшева: метаданные идут
                // V4 с детерминированной солью, ключ уже в тёплом кэше CryptoHelper.
                lastWireMembers = withContext(Dispatchers.IO) {
                    runCatching {
                        CryptoHelper.decrypt(membersRaw, chat.chatPassword, chat.chatId)
                            ?.let { MembersSync.parse(it) }
                    }.getOrNull()
                }
                // Контент есть, но не парсится (чанкованный манифест от старой версии
                // с тяжёлой авой) — сигнал самопочинке переопубликовать здоровую копию.
                lastWireMembersUnparseable = lastWireMembers == null
                val applied = withContext(Dispatchers.IO) {
                    try {
                        MembersSync.applyIncoming(
                            chat = chat,
                            membersContentEncrypted = membersRaw,
                            password = chat.chatPassword,
                            participantDao = db.chatParticipantDao(),
                            chatDao = db.chatDao(),
                            myUserId = prefs.myUserId,
                            appContext = applicationContext,
                            memberSlots = data.memberSlots,
                            pubkeyForUserId = transport::pubkeyForUserId
                        )
                    } catch (_: Exception) {
                        false
                    }
                }
                if (applied) {
                    // membersVersion уже обновлена в БД (анти-откат внутри applyIncoming) —
                    // подтягиваем свежее значение в in-memory chat, чтобы повторные
                    // проверки версии в этой же сессии видели актуальное число.
                    //
                    // ⚠️ Фикс (репорт: "аватарка чата внезапно не подхватилась" — без
                    // перезахода): applyIncoming() выше УЖЕ записал новые groupName/
                    // groupAvatarBase64/groupDescription в Room (chatDao.updateGroupProfile),
                    // но раньше отсюда в in-memory chat копировался ТОЛЬКО membersVersion —
                    // свежие имя/аватар/описание молча терялись до перезахода в чат (новый
                    // Chat читался из БД только в onCreate). Теперь копируем все три поля и,
                    // если хоть одно реально изменилось, сразу перерисовываем шапку.
                    withContext(Dispatchers.IO) { db.chatDao().getById(chat.id) }?.let { fresh ->
                        val groupProfileChanged = fresh.groupName != chat.groupName ||
                            fresh.groupAvatarBase64 != chat.groupAvatarBase64 ||
                            fresh.groupDescription != chat.groupDescription
                        chat = chat.copy(
                            membersVersion = fresh.membersVersion,
                            groupName = fresh.groupName,
                            groupAvatarBase64 = fresh.groupAvatarBase64,
                            groupDescription = fresh.groupDescription,
                            pinnedMsgIds = fresh.pinnedMsgIds,
                            myPinnedMsgIds = fresh.myPinnedMsgIds
                        )
                        if (groupProfileChanged) {
                            withContext(Dispatchers.Main) { applyPartnerToHeader() }
                        }
                    }
                    refreshPinState() // закреплённые могли измениться (Этап 3)
                }
            }

            // Забанены? — локально удаляем чат и выходим (ADR-001, §Пропагация бана).
            if (withContext(Dispatchers.IO) { checkSelfBanned() }) return

            // Не забанен, но ещё не подтверждён (гонка джойна) — честный баннер вместо
            // тихого зависания в неопределённом состоянии (ADR-001, §Известное ограничение).
            withContext(Dispatchers.IO) { checkPendingGroupEnrollment() }

            // Только у админа: заметили в profiles.txt участника, которого ещё нет
            // в members.txt (и он не забанен) — добавляем, пока есть свободные слоты
            // (честный FIFO-порядок — см. groupCandidateFirstSeenMs).
            //
            // ⚠️ Фикс (репорт: "после бана/сообщения синхронизация вся зависает,
            // сообщения появляются с опозданием"): раньше здесь стоял БЛОКИРУЮЩИЙ
            // withContext(Dispatchers.IO) — publish() внутри (до 20с через Tor, см.
            // MembersSync.publish/NostrTransport) держал ВЕСЬ этот тик, и decode/показ
            // входящих сообщений ниже (после блока chat.isGroup) ждали, пока он не
            // закончится. Хуже: chat.membersVersion в памяти раньше не обновлялся сразу
            // после успешной публикации — только когда админ на одном из СЛЕДУЮЩИХ тиков
            // читал обратно собственный members.txt (eventual consistency реле, может
            // занять несколько тиков). До этого момента тот же кандидат снова считался
            // "не добавленным" → publish() той же версии повторялся на КАЖДОМ тике, до
            // 20с блокировки каждый раз — итого реальный "зависон" синхронизации на
            // десятки секунд. Теперь: (1) enrollment уходит в фоновую корутину и не
            // блокирует decode/показ сообщений ниже; (2) maybeAdminEnrollNewMembers()
            // сама сразу обновляет chat.membersVersion и ChatParticipantDao после
            // успешной публикации, не дожидаясь обратного чтения (см. её код) — цикл
            // повторных блокирующих попыток больше не возникает.
            lifecycleScope.launch(Dispatchers.IO) { maybeAdminEnrollNewMembers() }

            // Только у админа: самопочинка members.txt (репорт: «я отменил мут, но у
            // пользователя всё также»). Админ-действия (мут/анмут/бан/имя/ава) публикуются
            // одним выстрелом из PartnerProfileActivity — если кворум реле через Tor не
            // собрался, локально состояние уже новое, а на реле навсегда остаётся старое,
            // и НИКТО его больше не переопубликует. Здесь на каждом тике сравниваем то,
            // что реально лежит на реле (lastWireMembers), с локальной истиной и, если
            // реле отстали, переопубликовываем локальное состояние с версией+1.
            lifecycleScope.launch(Dispatchers.IO) { maybeAdminRepairMembersFile() }

            // Децентрализованный ростер (ADR-001, запрос пользователя «беседа работает без
            // админа»): наполняем участников из САМООПУБЛИКОВАННЫХ профилей на КАЖДОМ тике —
            // не только у админа и не только когда меняется members.txt. Так новый участник
            // виден и посчитан у всех сразу после публикации своего профиля, а вышедший
            // (profiles.txt left=true) — исчезает, без участия админа. Строго ПОСЛЕ
            // MembersSync-оверлея выше (бан/мут/роли), см. GroupRosterSync.
            withContext(Dispatchers.IO) {
                runCatching {
                    GroupRosterSync.applyProfileRoster(
                        chat = chat,
                        signedSlots = data.profileSlotsSigned,
                        password = chat.chatPassword,
                        participantDao = db.chatParticipantDao(),
                        myUserId = prefs.myUserId,
                        adminUserId = chat.adminUserId,
                        pubkeyForUserId = transport::pubkeyForUserId
                    )
                }
            }

            // Подписи авторства (ADR_MESSAGE_AUTHENTICITY.md, Фаза 2): проверяем подписи всех
            // сообщений против закреплённых ключей (Фаза 1) и best-effort дописываем свои.
            // Строго ПОСЛЕ TOFU-пиннинга ключей выше. Никогда не влияет на доставку/отправку —
            // при любой ошибке подписи просто нет (UNSIGNED), см. syncMessageAuth.
            runCatching { syncMessageAuth() }

            // Передача владения (owner.txt) и отзыв/возврат создателя (revoke.txt) вынесены в
            // отдельный ownerRevokeTicker (см. startPolling): они читают ОТДЕЛЬНЫЕ файлы, а не
            // контент чата, а processChannelData вызывается лишь при изменении контента (304 →
            // пропуск). В простаивающем чате здесь они бы не срабатывали (репорт: «вторая попытка
            // забрать права — только через перезаход»).

            // Кэш числа активных участников для presence-тикера (см. applyGroupPresence) —
            // обновляем раз за опрос, а не на каждый тик (~1с), чтобы не дёргать Room. Тот
            // же снимок переиспользуем ниже для баннера «X присоединился к чату».
            val activeParticipants = withContext(Dispatchers.IO) {
                db.chatParticipantDao().getForChat(chat.id).filter { !it.banned }
            }
            groupActiveParticipantCount = activeParticipants.size
            withContext(Dispatchers.Main) { applyGroupPresence() }

            // Мой собственный мут — read-only режим (§ запрос пользователя: "только
            // права на чтение"). Проверяем на КАЖДОМ опросе (не только при открытии),
            // чтобы и наложение, и снятие/истечение мута отражались без перезахода
            // в чат (§1.5 CLAUDE.md).
            val myEntryNow = activeParticipants.firstOrNull { it.userId == prefs.myUserId }
            val untilNow = myEntryNow?.mutedUntilMs
            val amMutedNow = untilNow != null && untilNow > System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                applySelfMuteState(amMutedNow, untilNow, myEntryNow?.mutedReason,
                    MembersSync.evidenceIdsFromStore(myEntryNow?.mutedEvidenceIds))
            }

            // Баннер «X присоединился к чату» (найдено и исправлено по репорту
            // пользователя: раньше объявление группового джойна считалось ТОЛЬКО при
            // открытии чата — doSyncProfilesOnce, несколько retry и всё — если участник
            // вступал позже, пока чат уже был открыт, баннер никогда не появлялся).
            // Теперь считаем на КАЖДОМ опросе (SyncEngine, 3с) — ловит и поздних
            // джойнеров. На первом тике в этой сессии только запоминаем, кто уже в
            // чате — иначе при каждом открытии уже существующей группы посыпались бы
            // объявления про всех, кто в ней уже состоит.
            val othersNow = activeParticipants.filter { it.userId != prefs.myUserId }
            if (!groupJoinAnnounceInitialized) {
                groupJoinAnnounceInitialized = true
                othersNow.forEach { announcedJoinedUserIds.add(it.userId) }
            } else {
                for (p in othersNow) {
                    if (announcedJoinedUserIds.add(p.userId)) {
                        val joinedName = lastKnownProfiles[p.userId]?.name?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.join_default_partner_name)
                        withContext(Dispatchers.Main) {
                            chatStore.addSystemMessage(
                                Message.system(getString(R.string.cc_system_partner_joined, joinedName))
                            )
                        }
                    }
                }
            }
        }

        // ETag content dedup: пропускаем парсинг сообщений если chat.txt не изменился
        // и read receipt уже был отправлен. lastPushedReadIndex = -1 форсирует
        // обработку даже при совпадении контента (первый запуск, onResume).
        if (chatContent == lastContent && lastPushedReadIndex != -1) {
            contentLoaded = true
            if (!firstLoadComplete) {
                firstLoadComplete = true
                revealMessages()
            }
            return
        }
        hideChatWarning()
        consecutiveFailures = 0
        lastContent = chatContent

        val me      = prefs.myName
        val myUid   = prefs.myUserId
        val aliases = prefs.nameHistory
        val pass    = chat.chatPassword

        // allLines — все непустые строки gist (включая нерасшифрованные).
        // Используется для read receipt по позиции в файле.
        val allLines = chatContent.split("\n").filter { it.isNotEmpty() }

        // Декодер строк → сообщения (V5/Argon2id тяжёлый → Dispatchers.Default).
        suspend fun decodeLines(lines: List<String>): List<Message> = withContext(Dispatchers.Default) {
            lines.mapNotNull { rawLine ->
                val line = rawLine.trim()
                CryptoHelper.decrypt(line, pass, chat.chatId)?.let { decrypted ->
                    // Фильтруем мусор: V1 (AES-CBC без аутентификации) может
                    // "дешифровать" чужой контент → строка из управляющих символов.
                    // Разделители формата (0x01,0x02,0x11,0x1E,0x1F) — НЕ мусор.
                    val garbage = decrypted.count { c ->
                        c.code in 0x80..0x9F ||
                        (c.code < 0x20 && c != '\n' && c != '\r' && c != '\t' &&
                         c.code !in setOf(0x01, 0x02, 0x11, 0x1E, 0x1F))
                    }
                    if (decrypted.length > 8 && garbage * 100 / decrypted.length > 25) null
                    else Message.fromDecrypted(decrypted, myUid, me, aliases, raw = line)
                }
            }
        }

        // ── Групповой чат (ADR-001, §Пропагация бана): скрываем сообщения забаненных ──
        // "Мягкий бан" (members.txt banned=true) не отзывает крипто-доступ — забаненный
        // технически ещё может расшифровать общий пароль, если продолжит слушать реле
        // (см. известное и задокументированное ограничение MVP в ADR_GROUP_CHATS.md).
        // Но у ОСТАЛЬНЫХ участников такие сообщения не должны появляться в ленте —
        // иначе бан выглядит сломанным, даже если крипто-доступ технически ещё жив.
        // Сам чат у забаненного удаляется отдельно (см. checkSelfBanned() выше). Считаем
        // ДО хвостовой оптимизации ниже — иначе сообщение забаненного мелькнёт в хвосте
        // и тут же исчезнет при полном reconcile (нарушение §1.5 "всё на месте").
        // ⚠️ Мут (тот же мягкий принцип, что и бан выше, но временный): пока
        // mutedUntilMs у отправителя ещё не наступил, его сообщения скрыты у ОСТАЛЬНЫХ
        // (включая уже отправленные им ранее — как только мут снят/истёк, они
        // снова становятся видимы всем без каких-либо действий, крипто-доступ не трогаем).
        //
        // ⚠️ На СВОЁМ ЖЕ устройстве заглушённый видит ленту через этот же общий код.
        // По прямому требованию пользователя (репорт: "у заглушённого меняется
        // синхронизация и не даёт читать сообщения — статус мута на человеке никак
        // не должен влиять на синхрон") статус СВОЕГО мута ПОЛНОСТЬЮ убран из этого
        // пайплайна: myUid никогда не попадает в mutedIds (фильтруем только чужих),
        // и никакого отдельного вырезания "своих сообщений-оснований" из общей ленты
        // здесь больше нет (раньше был myEvidenceMsgIds — убран целиком, см.
        // applySelfMuteState). Свой мут отражается ТОЛЬКО в UI (плашка + скрытая
        // строка ввода) и никак не решает, что попадёт в chatStore. Статус ДРУГИХ
        // участников (bannedIds/mutedIds) — отдельная, не связанная с этим механика:
        // она не про то, что видит заглушённый сам, а про то, что видят ОСТАЛЬНЫЕ
        // про забаненного/заглушённого отправителя, и её не трогаем.
        val groupParticipantsNow = if (!chat.isGroup) emptyList() else withContext(Dispatchers.IO) {
            db.chatParticipantDao().getForChat(chat.id)
        }
        // ⛔ Верифицированный разработчик неприкосновенен (PERSONAL_BUILD.md §Часть 3):
        // его userId исключаем из bannedIds/mutedIds, чтобы его сообщения НЕ прятались у
        // остальных даже если чей-то members.txt пометил его забаненным/заглушённым.
        val bannedIds: Set<String> = groupParticipantsNow
            .filter { it.banned && it.userId !in verifiedSenderIds }
            .map { it.userId }.toSet()
        val nowMs = System.currentTimeMillis()
        val mutedIds: Set<String> = groupParticipantsNow
            .filter { !it.banned && it.mutedUntilMs != null && it.mutedUntilMs > nowMs && it.userId != myUid && it.userId !in verifiedSenderIds }
            .map { it.userId }
            .toSet()
        fun List<Message>.withoutBanned(): List<Message> =
            if (bannedIds.isEmpty() && mutedIds.isEmpty()) this
            else filterNot { msg ->
                msg.senderUserId != null && (msg.senderUserId in bannedIds || msg.senderUserId in mutedIds)
            }

        // ── Оптимизация первой загрузки: "хвост" ─────────────────────────────
        // Если чат длинный (> 30 строк), сначала декодируем последние 30
        // сообщений и показываем сразу (снимая оверлей), остальную историю догружаем
        // следом. Argon2id по сообщению очень тяжёлый — убирает ожидание всей истории.
        if (!firstLoadComplete && allLines.size > TAIL_FIRST) {
            val tailMsgs = decodeLines(allLines.takeLast(TAIL_FIRST)).withoutBanned()
            if (tailMsgs.isNotEmpty()) {
                contentLoaded = true
                chatStore.reconcile(tailMsgs)   // коллектор покажет хвост и снимет оверлей
            }
        }

        val allDecoded = decodeLines(allLines)
        // UI-кэш для ленты оснований в карточке мута (см. renderMuteEvidenceFeed) —
        // на состав chatStore не влияет ни при каком статусе мута.
        withContext(Dispatchers.Main) {
            lastAllDecodedMessages = allDecoded
            if (isSelfMuted) renderMuteEvidenceFeed()
        }
        val messages: List<Message> = allDecoded.withoutBanned()

        // Forward secrecy баннер (V4-S/V3 сообщения без активного сессионного ключа).
        // ВАЖНО: это окно — НОРМА на старте, пока идёт ECDH-handshake (обмен ephemeral
        // ключами через profiles.txt). Через Tor он легко занимает > 18 c (бутстрап +
        // раунд-трипы профилей), поэтому прежний порог 6 тиков (~18 c) давал ЛОЖНЫЙ
        // баннер: ключ ещё не установлен, но история не потеряна — расшифруется, как
        // только handshake завершится. Поднят до FS_BANNER_MIN_TICKS, чтобы баннер
        // показывался только если ключ реально НЕ устанавливается долго (партнёр ушёл/
        // старый клиент), а не из-за медленного рукопожатия.
        if (CryptoHelper.hasLockedV3Messages(chatContent, chat.chatId)) {
            if (firstLoadComplete) lockedV3ConsecutiveCount++
            if (lockedV3ConsecutiveCount >= FS_BANNER_MIN_TICKS && activeWarning != WarningType.FORWARD_SECRECY) {
                showChatWarning(WarningType.FORWARD_SECRECY)
            }
        } else {
            lockedV3ConsecutiveCount = 0
            if (activeWarning == WarningType.FORWARD_SECRECY) hideChatWarning()
        }

        // contentLoaded ставим здесь, но оверлей снимаем ИЗ КОЛЛЕКТОРА сообщений
        // (после adapter.submit, через rvMessages.post) — иначе оверлей гаснет до
        // того, как сообщения окажутся в списке, и видна пустая вспышка.
        contentLoaded = true

        // ── КЛЮЧЕВОЕ: stable reconciliation через ChatStore ───────────────────
        // reconcile() сохраняет pending-сообщения, убирает tombstones.
        // НЕ делает messages = remoteMessages (это источник бага с исчезновением).
        chatStore.reconcile(messages)

        // ── Preview в Room ────────────────────────────────────────────────────
        if (messages.isNotEmpty()) {
            val last = messages.last()
            val photoLabel = getString(R.string.msg_preview_photo)
            val previewBody = when {
                last.isMultiImage && last.text.isBlank() -> "$photoLabel (${last.imageFileNames?.size ?: 2})"
                last.isMultiImage  -> "$photoLabel ${last.text}"
                last.isImage && last.text.isBlank() -> photoLabel
                last.isImage       -> "$photoLabel ${last.text}"
                last.isSticker     -> getString(R.string.msg_preview_sticker)
                last.isReply       -> getString(R.string.msg_preview_reply_format, last.text)
                else               -> last.text
            }
            val preview = if (last.isSelf) "Вы: $previewBody" else previewBody
            db.chatDao().updatePreview(
                id      = chat.id,
                preview = preview.take(80),
                timeMs  = System.currentTimeMillis()
            )
        }

        // ── Read receipt ──────────────────────────────────────────────────────
        scheduleMarkAsRead(allLines.size)
        // Профили (typing/online/partner data) теперь читаются ВЫШЕ, до early return
        // по chatContent — см. комментарий там же (фикс "вошёл только после сообщения").
    }

    /**
     * Групповой чат (ADR-001): проверяет, не забанен ли я по локальному кэшу
     * ChatParticipant (уже обновлён из members.txt на этом же тике). Если да —
     * подчищает секреты/участников/саму запись чата и закрывает экран. Идемпотентно
     * (groupBanHandled) — на случай если несколько тиков успеют наложиться.
     * Возвращает true, если чат был удалён (вызывающий код обязан сразу return).
     */
    private suspend fun checkSelfBanned(): Boolean {
        // Верифицированный разработчик (PERSONAL_BUILD.md §Часть 3) неприкосновенен: бан в мою
        // сторону игнорируется — не выкидываемся с экрана. Неподделываемо: проверка по моему
        // identity-ключу (VerifiedBadge), чужой её не получит.
        if (VerifiedBadge.isVerifiedSelf(prefs.myIdentityPubKey)) return false
        if (groupBanHandled) return true
        val me = db.chatParticipantDao().getOne(chat.id, prefs.myUserId) ?: return false
        if (!me.banned) return false
        groupBanHandled = true
        // ⚠️ БАН БОЛЬШЕ НЕ УДАЛЯЕТ чат/секреты/участников (репорт: «забаненные невидимы
        // системе — разбан не виден, бан иногда без уведомления»). Раньше здесь стоял
        // delete + deleteChatSecrets — забаненный терял доступ к members.txt и НАВСЕГДА
        // переставал наблюдать за группой, поэтому разбан был невидим, а гонка удаления
        // могла съесть и уведомление о бане. Теперь чат СОХРАНЯЕТСЯ, просто прячется из
        // списка по флагу participant.banned (см. ChatsListActivity), а фоновый сервис
        // продолжает опрашивать членство по стабильному userId — бан/разбан всегда
        // наблюдаемы и уведомляются. Здесь только выкидываем пользователя с экрана чата
        // (читать заглушённую беседу нельзя), запись остаётся.
        withContext(Dispatchers.Main) {
            android.widget.Toast.makeText(this@ChatActivity, R.string.group_banned_toast, android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
        return true
    }

    /**
     * Групповой чат (ADR-001, §Известное ограничение — гонка джойна): приём в группу
     * неатомарен — JoinChatActivity проверяет ёмкость по (возможно устаревшему)
     * members.txt/profiles.txt, а реальное добавление делает клиент админа фоном
     * (см. [maybeAdminEnrollNewMembers]). Если лимит участников почти исчерпан и
     * несколько человек джойнятся одновременно — кто-то из них может остаться
     * локально «в чате» (запись Chat уже создана), но так и не попасть в members.txt.
     *
     * Эта проверка даёт ЧЕСТНЫЙ сигнал вместо тихого зависания: если я (не админ)
     * подряд много тиков отсутствую в локальном ChatParticipant — показываем мягкий
     * информационный баннер. Автоудаления НЕТ — офлайн-админ (а не переполненный чат)
     * тоже даёт такую же картину, и агрессивное авто-исключение по таймауту рисковало
     * бы выкинуть законного участника только из-за того, что админ временно не в сети.
     * Если место реально не найдётся — участник может сам удалить чат из списка
     * (существующее действие «Удалить чат», см. ChatsListActivity).
     *
     * Не блокирует использование чата (не return-guard как checkSelfBanned) — только
     * управляет баннером-подсказкой.
     */
    private suspend fun checkPendingGroupEnrollment() {
        if (chat.adminUserId == prefs.myUserId) return // админ — участник с момента создания
        val me = db.chatParticipantDao().getOne(chat.id, prefs.myUserId)
        if (me != null) {
            // Подтверждён — сбрасываем счётчик и прячем баннер, если он был показан.
            if (groupPendingTicks > 0) {
                groupPendingTicks = 0
                withContext(Dispatchers.Main) {
                    if (activeWarning == WarningType.GROUP_PENDING) hideChatWarning()
                }
            }
            return
        }
        groupPendingTicks++
        if (groupPendingTicks >= GROUP_PENDING_BANNER_TICKS && activeWarning == null) {
            withContext(Dispatchers.Main) { showChatWarning(WarningType.GROUP_PENDING) }
        }
    }

    /**
     * Применяет «профиль беседы» groupprofile.txt из снапшота опроса (см. GroupProfileSync):
     * дедуп по сырому контенту, парсинг ts для самопочинки админа, применение с
     * анти-откатом. Общая точка для processChannelData (каждый тик) и doSyncProfilesOnce
     * (вход в чат). @return true — имя/ава/описание реально изменились (шапку надо
     * перерисовать вызывающим, в его контексте потока).
     */
    private suspend fun applyGroupProfileFromPoll(groupProfileRaw: String, membersPresent: Boolean): Boolean {
        if (!chat.isGroup) return false
        if (groupProfileRaw.isBlank()) {
            // Профиля нет в ответе. Если members.txt в ЭТОМ ЖЕ ответе пришёл — канал
            // читался успешно, значит профиля на реле действительно нет (бутстрап для
            // самопочинки админа). Флаки-тик без обоих файлов ничего не помечает.
            if (membersPresent) lastWireGroupProfileTs = 0L
            return false
        }
        if (groupProfileRaw == lastGroupProfileRaw) return false
        lastGroupProfileRaw = groupProfileRaw
        val parsedTs = withContext(Dispatchers.IO) {
            runCatching {
                CryptoHelper.decrypt(groupProfileRaw, chat.chatPassword, chat.chatId)
                    ?.let { GroupProfileSync.parse(it) }?.ts
            }.getOrNull() ?: 0L
        }
        lastWireGroupProfileTs = parsedTs
        val applied = withContext(Dispatchers.IO) {
            runCatching {
                GroupProfileSync.applyIncoming(chat, groupProfileRaw, chat.chatPassword, db.chatDao(), prefs)
            }.getOrDefault(false)
        }
        if (!applied) return false
        val fresh = withContext(Dispatchers.IO) { db.chatDao().getById(chat.id) } ?: return false
        val changed = fresh.groupName != chat.groupName ||
            fresh.groupAvatarBase64 != chat.groupAvatarBase64 ||
            fresh.groupDescription != chat.groupDescription
        chat = chat.copy(
            groupName = fresh.groupName,
            groupAvatarBase64 = fresh.groupAvatarBase64,
            groupDescription = fresh.groupDescription
        )
        return changed
    }

    /**
     * Только для админа группы: самопочинка members.txt (репорт: «я отменил мут, но у
     * пользователя всё также»). Все админ-действия публикуются одним выстрелом из
     * PartnerProfileActivity — при сорванном кворуме реле (обычное дело через Tor)
     * локальное состояние уже новое, а на реле навсегда остаётся старая версия, и без
     * этой функции её никто никогда не переопубликует: у остальных участников действие
     * «теряется» без единой ошибки.
     *
     * Сравниваем то, что реально лежит на реле ([lastWireMembers]), с локальной истиной
     * (ChatParticipantDao + chat.membersVersion) и, если реле отстали, публикуем
     * локальное состояние с версией+1 через maybeAdminEnrollNewMembers(forceRepublish).
     * Расхождения ловим по двум сигналам:
     *  1) версия на реле МЕНЬШЕ локальной (наш последний publish не дошёл вовсе);
     *  2) версия та же, но по (banned, mutedUntilMs) участники отличаются — publish упал
     *     ДО persist'а версии (см. publishMembersAsAdmin: persist только после успеха).
     * Сознательно НЕ сравниваем reason/evidence/имя/аву — их нормализация неоднозначна
     * (null vs пустое), а ложный republish-цикл хуже, чем недолеченный второстепенный
     * атрибут: ключевые поля (кто в муте/бане) чинят и всё остальное, т.к. публикация
     * всегда несёт полный снимок с именем/авой/описанием.
     * Троттл [lastMembersRepairAttemptMs] (30с) — защита от шторма публикаций, пока
     * свежая версия ещё едет до реле/обратно (eventual consistency).
     */
    private suspend fun maybeAdminRepairMembersFile() {
        if (!chat.isGroup || chat.adminUserId != prefs.myUserId) return
        val wire = lastWireMembers
        // С реле ещё ничего не читали И нечитаемого там тоже не видели — не лечим вслепую.
        if (wire == null && !lastWireMembersUnparseable) return
        val now = System.currentTimeMillis()
        if (now - lastMembersRepairAttemptMs < MEMBERS_REPAIR_THROTTLE_MS) return
        val local = db.chatParticipantDao().getForChat(chat.id)
        if (local.isEmpty()) return // нечего публиковать (свежий вход/самолечение сделает enroll)
        val needMembersRepair = when {
            // На реле лежит НЕПАРСИБЕЛЬНАЯ копия (чанкованный манифест старой версии
            // с тяжёлой авой, см. MembersSync.publish) — переопубликовать здоровую:
            // без этого вся группа навсегда без бана/мута/имени/авы.
            wire == null -> true
            wire.version < chat.membersVersion -> true
            wire.version == chat.membersVersion -> {
                val wireState = wire.participants.associate { it.userId to (it.banned to it.mutedUntilMs) }
                val localState = local.associate { it.userId to (it.banned to it.mutedUntilMs) }
                wireState != localState
            }
            else -> false // реле новее — сейчас доедет обычным applyIncoming
        }
        // «Профиль беседы» (groupprofile.txt, GroupProfileSync): реле держат копию старее
        // локальной (или профиля там нет вовсе — группа создана до этой фичи либо
        // публикация сорвалась) — переопубликовать. maxOf(localTs, 1L) — бутстрап для
        // старых групп: localTs=0 и на реле пусто (0 < 1) → публикуем впервые.
        val localGpTs = prefs.getGroupProfileTs(chat.chatId)
        val haveGroupData = chat.groupName != null || chat.groupAvatarBase64 != null || chat.groupDescription != null
        val needProfileRepair = haveGroupData && lastWireGroupProfileTs < maxOf(localGpTs, 1L)
        if (!needMembersRepair && !needProfileRepair) return
        lastMembersRepairAttemptMs = now
        // Обе починки — через планировщик (PublishScheduler): сериализация с остальными
        // публикациями, персистентный ретрай, без гонок версий.
        if (needMembersRepair) maybeAdminEnrollNewMembers(forceRepublish = true)
        if (needProfileRepair) PublishScheduler.markProfileDirty(applicationContext, chat.chatId)
    }

    /**
     * Только для админа группы (ADR-001): если в profiles.txt засветился участник,
     * которого ещё нет в members.txt и который не забанен — добавляет его, пока есть
     * свободные слоты (participantLimit), и публикует новую версию members.txt.
     * Обычный (не-админ) участник тут не пишет НИЧЕГО — событие всё равно отбросят
     * все остальные клиенты по несовпадению подписи (см. NostrTransport).
     *
     * Кандидаты сортируются по времени ПЕРВОГО наблюдения этим (админским) клиентом
     * ([groupCandidateFirstSeenMs]) — честный, детерминированный FIFO вместо
     * нестабильного порядка итерации `Map`. Это не защита от гонки (несколько
     * джойнеров всё ещё могут одновременно пройти клиентскую проверку ёмкости в
     * JoinChatActivity до того, как кто-либо из них попадёт сюда), а именно
     * справедливость: кто раньше стал виден админу — тот раньше и получает
     * свободный слот, вместо произвольного порядка на каждый тик.
     *
     * @param seenProfiles список известных профилей канала. По умолчанию — lastKnownProfiles
     *   (кэш из processChannelData, может отставать на один тик, если chat.txt не менялся —
     *   см. вызов из doSyncProfilesOnce() ниже, который передаёт СВЕЖИЙ снимок и не зависит
     *   от изменений chat.txt, поэтому основной, быстрый путь enrollment'а — именно там).
     */
    private suspend fun maybeAdminEnrollNewMembers(
        seenProfiles: Map<String, Profile> = lastKnownProfiles,
        /**
         * true — опубликовать ТЕКУЩЕЕ локальное состояние с версией+1 даже без новых
         * кандидатов (самопочинка: реле держат состояние старее локального, см.
         * maybeAdminRepairMembersFile). Публикация идемпотентна для приёмников.
         */
        forceRepublish: Boolean = false
    ) {
        if (!chat.isGroup || chat.adminUserId != prefs.myUserId) return
        // Сериализация против гонки двух параллельных вызывающих (см. memberEnrollMutex).
        // ⚠️ Было withLock (очередь) — теперь tryLock() со skip-если-занято. Вызов этой
        // функции теперь fire-and-forget с каждого тика (см. processChannelData), а сам
        // publish() внутри может идти до 20с через Tor — если бы мы по-прежнему
        // ставились в очередь через withLock, параллельные тики копили бы очередь
        // ожидающих корутин поверх уже идущей попытки той же версии. При занятом
        // мьютексе просто выходим — следующий тик (через ~1-3с) попробует снова и
        // либо застанет мьютекс свободным, либо кандидаты уже будут закрыты предыдущей
        // успешной попыткой.
        if (!memberEnrollMutex.tryLock()) return
        try {
        var current = db.chatParticipantDao().getForChat(chat.id)
        var selfHealed = false
        if (current.isEmpty()) {
            // ⚠️ Самолечение для ГРУПП, СОЗДАННЫХ ДО ФИКСА membersVersion (см.
            // CreateChatActivity.createGroupChat): у них локально уже стоит
            // Chat.membersVersion = 1, из-за чего наш собственный v1 members.txt
            // отбрасывался анти-откатом в MembersSync.applyIncoming НАВСЕГДА (parsed.version
            // 1 <= chat.membersVersion 1). Раньше здесь стоял простой return — админ
            // вечно ждал "версию 1", а ChatParticipantDao никогда не заполнялась → счётчик
            // участников не рос, ни один новый участник не подтверждался (баннер "Ожидаем
            // подтверждения" висел бесконечно). Чинить прошлые версии members.txt задним
            // числом смысла нет — вместо этого раз это МОЙ (админский) чат и я администратор,
            // я по определению первый гарантированный участник. Заводим себя локально ПРЯМО
            // СЕЙЧАС и продолжаем в этом же тике — следующий maybeAdminEnrollNewMembers()
            // (или этот же вызов чуть ниже) увидит непустой current и опубликует версию > 1,
            // которая уже нормально пройдёт анти-откат у всех клиентов.
            db.chatParticipantDao().upsert(
                com.atrum.chat.data.ChatParticipant(
                    ownerId = chat.id,
                    userId = prefs.myUserId,
                    banned = false
                )
            )
            current = db.chatParticipantDao().getForChat(chat.id)
            selfHealed = true
        }
        val knownIds = current.map { it.userId }.toSet()
        val bannedIds = current.filter { it.banned }.map { it.userId }.toSet()
        val activeCount = current.count { !it.banned }

        // Не зачисляем обратно тех, кто вышел сам (profiles.txt left=true) или удалил
        // профиль — иначе админский members.txt воскрешал бы их в счётчике (ADR-001).
        val candidates = seenProfiles.keys.filter { uid ->
            uid !in knownIds && uid !in bannedIds &&
                seenProfiles[uid]?.let { !it.left && !it.deleted } != false
        }
        // Если только что самовылечились (см. выше) — публикуем исправленную версию ДАЖЕ
        // без новых кандидатов, иначе локальный фикс останется только у меня: остальные
        // участники (и я сам при следующей переустановке) так и не увидят membersVersion,
        // который реально проходит анти-откат.
        if (candidates.isEmpty() && !selfHealed && !forceRepublish) return

        // Запоминаем момент первого наблюдения — используется только для сортировки
        // ниже (честный FIFO), не синхронизируется по сети и не переживает выход из чата.
        val now = System.currentTimeMillis()
        candidates.forEach { id -> groupCandidateFirstSeenMs.putIfAbsent(id, now) }

        val limit = chat.participantLimit
        val freeSlots = limit?.let { (it - activeCount).coerceAtLeast(0) } ?: candidates.size
        // ⚠️ freeSlots <= 0 значит "некого добавить" — НЕ значит "нечего публиковать".
        // Если candidates пуст (freeSlots вычислится в 0 и без лимита) но мы только что
        // selfHealed — всё равно идём публиковать версию с самим собой, иначе фикс
        // останется только локальным (см. комментарий выше).
        if (freeSlots <= 0 && candidates.isNotEmpty()) return

        val toAdd = candidates.sortedBy { groupCandidateFirstSeenMs[it] ?: Long.MAX_VALUE }.take(freeSlots)
        // ⚠️ ПУБЛИКАЦИЮ ДЕЛАЕТ ПЛАНИРОВЩИК (PublishScheduler, запрос пользователя:
        // «планировщик событий, чтобы ставил действия в очередь»): кандидаты заводятся
        // в Room СРАЗУ (следующий вызов увидит их в knownIds — повторной публикации не
        // будет), а сам members.txt публикуется воркером очереди — сериализованно с
        // остальными админ-действиями (мут/бан/имя), с персистентным ретраем и
        // коалесценцией. Версию и снимок участников воркер берёт из Room в момент
        // выполнения — гонка версий между энроллом и действиями из профиля группы
        // исключена по построению.
        withContext(Dispatchers.IO) {
            db.chatParticipantDao().upsertAll(
                toAdd.map { id -> com.atrum.chat.data.ChatParticipant(ownerId = chat.id, userId = id, banned = false) }
            )
        }
        PublishScheduler.markMembersDirty(applicationContext, chat.chatId)
        } finally {
            memberEnrollMutex.unlock()
        }
    }

    /**
     * Откладывает пометку "прочитано" на 500 мс. Если за это время пользователь
     * уйдёт из чата — корутина отменится в onPause(), и lastReadIndex не уедет на gist.
     */
    private fun scheduleMarkAsRead(totalLines: Int) {
        // Если уже была запланирована пометка с таким же totalLines — пропустим.
        // Иначе перезапускаем таймер с актуальным значением.
        markAsReadJob?.cancel()
        markAsReadJob = lifecycleScope.launch {
            delay(markAsReadDelayMs)
            db.chatDao().markAsRead(chat.id, totalLines)
            // Прочитали чат — гасим бейдж «@N» в списке (кнопка в чате работает по снимку).
            if (chat.isGroup && !chat.mentionMsgIds.isNullOrEmpty()) db.chatDao().updateMentionMsgIds(chat.id, null)
            chat = chat.copy(lastSeenLineCount = totalLines, unreadCount = 0, mentionMsgIds = null)

            // Пушим обновлённый профиль если индекс реально вырос
            if (totalLines > lastPushedReadIndex) {
                lastPushedReadIndex = totalLines
                val myProfile = Profile(
                    userId = prefs.myUserId,
                    name = prefs.myName,
                    tag = prefs.myTag,
                    avatarBase64 = prefs.myAvatarBase64,
                    lastReadIndex = totalLines,
                    onlineTs = if (isInForeground) System.currentTimeMillis() else 0L,
                    ephemeralPubKey = myCurrentEphemeralPubKey,
                    // ВАЖНО: identity-поля обязательно дублируем здесь. pushMyProfile
                    // делает ПОЛНУЮ замену объекта профиля, поэтому без них read receipt
                    // обнулял бы identityPubKey/ephemeralSig/verifiedPartnerIdk у меня в gist
                    // → у партнёра мигал бы щит верификации до следующего presence-тика.
                    identityPubKey = prefs.myIdentityPubKey,
                    ephemeralSig = myEphemeralSig,
                    identitySig = myIdentitySig,
                    verifiedPartnerIdk = prefs.getConfirmedPartnerIdentity(chat.chatId),
                    status = prefs.myStatus.takeIf { it.isNotBlank() }
                )
                val currentTransport = transport
                val chatPassword = chat.chatPassword
                // 3 попытки с паузой 2 сек — гарантируем что read receipt дойдёт
                // даже при временных сетевых сбоях.
                AppScope.launch {
                    repeat(3) { attempt ->
                        val ok = try {
                            ProfileSync.pushMyProfile(currentTransport, chatPassword, myProfile, prefs.getOrCreateIdentity().first)
                        } catch (_: Exception) { false }
                        if (ok) {
                            // Обновляем кэш немедленно — иначе следующий write-only
                            // typing/online пуш прочитает старый lastReadIndex и откатит
                            // галочку назад до ближайшего опроса (~1.5 сек).
                            withContext(Dispatchers.Main) {
                                lastKnownProfiles[myProfile.userId] = myProfile
                            }
                            return@launch
                        }
                        if (attempt < 2) delay(2_000L)
                    }
                }
            }
        }
    }

    // ── Подсказки стикеров по эмодзи ─────────────────────────────────────────

    private fun setupStickerSuggestions() {
        val adapter = StickerAdapter(
            stickers = emptyList(),
            onStickerClick = { sticker ->
                sendSticker(sticker)
                binding.etMessage.setText("")
                hideStickerSuggestions()
            },
            onStickerLongClick = null
        )
        suggestionAdapter = adapter
        binding.rvStickerSuggestions.layoutManager = GridLayoutManager(this, 5)
        binding.rvStickerSuggestions.adapter = adapter
    }

    /** На каждый ввод: если в поле только эмодзи — показать стикеры с этим смайлом. */
    private fun updateStickerSuggestions(text: String) {
        val q = text.trim()
        // Запрос подсказок — когда в поле только эмодзи/символы (нет букв и цифр).
        val emojiOnly = q.isNotEmpty() && q.length <= 24 && q.none { it.isLetterOrDigit() }
        if (!emojiOnly) { hideStickerSuggestions(); return }

        suggestJob?.cancel()
        suggestJob = lifecycleScope.launch {
            val list = suggestRepo.stickersForEmoji(q)
            // Текст мог измениться, пока шёл поиск.
            if (binding.etMessage.text.toString().trim() != q) return@launch
            if (list.isEmpty()) { hideStickerSuggestions(); return@launch }

            suggestionAdapter?.update(list)
            // Высота панели: 1..3 ряда по 5 в ряд (ячейка ~80dp).
            val rows = ((list.size + 4) / 5).coerceIn(1, 3)
            val cell = (80 * resources.displayMetrics.density).toInt()
            binding.rvStickerSuggestions.layoutParams =
                binding.rvStickerSuggestions.layoutParams.apply { height = rows * cell }
            binding.rvStickerSuggestions.scrollToPosition(0)
            binding.stickerSuggestionsContainer.visibility = View.VISIBLE
        }
    }

    private fun hideStickerSuggestions() {
        suggestJob?.cancel()
        if (binding.stickerSuggestionsContainer.visibility != View.GONE) {
            binding.stickerSuggestionsContainer.visibility = View.GONE
            // Освобождаем стикеры скрытой панели — иначе их тикеры крутятся вхолостую.
            suggestionAdapter?.update(emptyList())
        }
    }

    private fun sendSticker(sticker: com.atrum.chat.stickers.Sticker) {
        if (sendManager.isPunished()) return
        val now = System.currentTimeMillis()

        // Расширение типа (без точки) — webm/tgs/webp. Идёт в имя стикер-сообщения,
        // чтобы MessageAdapter понял тип без обращения к контенту.
        val extNoDot = when (sticker.type) {
            com.atrum.chat.stickers.StickerType.ANIMATED -> "tgs"
            com.atrum.chat.stickers.StickerType.VIDEO -> "webm"
            else -> "webp"
        }

        lockForUpload()
        lifecycleScope.launch {
            try {
                stickerPanel?.setSendingState(true)
                val stickerFile = sticker.localPath?.let { java.io.File(it) }
                if (stickerFile == null || !stickerFile.exists()) {
                    throw RuntimeException("Sticker file not found")
                }

                // 1. Заливаем КОНТЕНТ стикера в ОТДЕЛЬНЫЙ gist (как изображения) — чтобы
                //    не раздувать chat.txt и не упираться в rate-limit GitHub. Повторная
                //    отправка того же стикера переиспользует ссылку (дедуп по chat+fileId),
                //    то есть resend полностью бесплатный по сети.
                val b64 = withContext(Dispatchers.Default) {
                    android.util.Base64.encodeToString(stickerFile.readBytes(), android.util.Base64.NO_WRAP)
                }

                val existingRef = prefs.getStickerContentRef(transport.chatId, sticker.fileId)
                val contentRef: String = if (existingRef != null) existingRef else {
                    val encryptedSticker = withContext(Dispatchers.Default) {
                        CryptoHelper.encrypt(b64, chat.chatPassword, chat.chatId)
                    }
                    val uploaded = imageUploadQueue.execute {
                        withContext(Dispatchers.IO) {
                            transport.uploadImage(encryptedSticker, chat.chatPassword)
                        }
                    }
                    prefs.setStickerContentRef(transport.chatId, sticker.fileId, uploaded)
                    uploaded
                }

                // Имя стикер-сообщения: уникальная левая часть (для reconcile) + ссылка на контент.
                val stickerFileName = Message.stickerRefName(extNoDot, contentRef)
                // Ключ кеша/кадров — общая ссылка на контент (стабильна между отправками).
                val cacheKey = Message.stickerContentRef(stickerFileName)

                // 2. Прогреваем кеш ДО отправки, чтобы UI подхватил мгновенно (ключ = cacheKey).
                withContext(Dispatchers.Default) {
                    if (extNoDot == "tgs") {
                        try {
                            val gzis = java.util.zip.GZIPInputStream(
                                java.io.ByteArrayInputStream(stickerFile.readBytes()))
                            val jsonString = gzis.bufferedReader().use { it.readText() }
                            val comp = com.airbnb.lottie.LottieCompositionFactory
                                .fromJsonStringSync(jsonString, cacheKey).value
                            if (comp != null) ImageCache.putComposition(cacheKey, comp)
                        } catch (_: Exception) {}
                    }
                    if (ImageCache.getBitmap(cacheKey) == null) {
                        val preview = com.atrum.chat.stickers.StickerRepository(this@ChatActivity)
                            .renderFirstFrame(sticker, maxSize = 256)
                        ImageCache.put(cacheKey, b64, preview)
                    }
                    ImageCache.markShownConfirmation(cacheKey)
                }

                // 3. Текстовое сообщение-заголовок (с именем стикера). Шифруем один раз и
                //    переиспользуем как отправляемую строку — иначе reconcile-by-raw не сматчит.
                val plaintext = Message.composePlaintext(
                    senderName = prefs.myName,
                    senderUserId = prefs.myUserId,
                    text = "",
                    imageFileName = stickerFileName,
                    timestampMs = now
                )
                val encryptedMessage = withContext(Dispatchers.Default) {
                    encryptChatLine(plaintext, transport.chatId)
                }

                // 4. Оптимистичное добавление в UI
                val pendingMsg = Message(
                    sender = prefs.myName,
                    text = "",
                    isSelf = true,
                    rawEncrypted = encryptedMessage,
                    timestampMs = now,
                    imageFileName = stickerFileName,
                    senderUserId = prefs.myUserId,
                    isPending = true
                )
                chatStore.addOptimistic(pendingMsg)

                // 5. Отправка ТОЛЬКО короткой строки-заголовка (без extraFiles — контент уже в
                //    отдельном gist). Маленький PATCH вместо тяжёлого инлайна стикера.
                withContext(Dispatchers.IO) {
                    transport.appendLine(encryptedLine = encryptedMessage)
                }

                // Cache-bust и синхронизация
                chatStore.confirmSent(encryptedMessage)
                syncEngine.forceSync(delayMs = 0L)
                stopTypingSignal()

                stickerPanel?.hidePanel()
            } catch (e: Exception) {
                val reason = e.message?.take(120) ?: "unknown"
                android.widget.Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    android.widget.Toast.LENGTH_LONG).show()
            } finally {
                stickerPanel?.setSendingState(false)
                unlockAfterUpload()
            }
        }
    }

    /**
     * Шифрует строку-анонс сообщения (текст/подпись к медиа/правка) с учётом типа чата.
     * Найдено по репорту пользователя: в группах «часики» долго висели на КАЖДОМ
     * текстовом сообщении, хотя в 1:1 отправка мгновенная. Причина — обычный
     * CryptoHelper.encrypt() для групп всегда падает на V5 (Argon2id со случайной
     * солью на каждый вызов, несжимаемо), см. подробный докстринг
     * CryptoHelper.encryptGroupMessage(). 1:1 не затронуты — там как раньше encrypt().
     */
    /**
     * Best-effort синхронизация подписей авторства (ADR_MESSAGE_AUTHENTICITY.md, Фаза 2).
     * На тике синка: (1) грузит и расшифровывает blob подписей (sigs.txt), проверяет подписи
     * всех сообщений против закреплённых ключей участников (Фаза 1) и заполняет
     * [msgAuthByMsgId] (для показа в Фазе 4); (2) дописывает подписи к СВОИМ ещё неподписанным
     * сообщениям и публикует обновлённый (зашифрованный) blob.
     *
     * ⛔ Строго best-effort и НЕ на пути отправки: всё в runCatching, любая ошибка = «подписи
     * нет» (UNSIGNED). Строка сообщения (chat.txt) не меняется, доставка не затрагивается (§1).
     * Пока только группы. Blob шифруется доменом чата — реле не видит связку ключ↔сообщение.
     */
    private suspend fun syncMessageAuth() {
        if (!::chat.isInitialized || !chat.isGroup) return
        val msgs = currentMessages
        if (msgs.isEmpty()) return
        runCatching {
            // 1) Загрузка + расшифровка blob подписей (может отсутствовать).
            val rawBlob = withContext(Dispatchers.IO) { transport.loadSignatures() }.trim()
            val plain = if (rawBlob.isEmpty()) "" else
                (CryptoHelper.decrypt(rawBlob, chat.chatPassword, chat.chatId) ?: "")
            val sigs = MessageAuthSync.parse(plain)

            // 2) Закреплённые identity-ключи участников (TOFU, Фаза 1).
            val participants = withContext(Dispatchers.IO) { db.chatParticipantDao().getForChat(chat.id) }
            val pinned = HashMap<String, String>()
            for (p in participants) p.pinnedIdentityPubKey?.let { if (it.isNotBlank()) pinned[p.userId] = it }

            // 3) Проверка → карта состояний. Обновляем UI ТОЛЬКО если что-то реально
            // изменилось (иначе лишние ребайнды на каждом тике = мерцание, §14).
            val newStates = MessageAuthSync.computeAuthStates(msgs, sigs, pinned, chat.chatId)
            var authChanged = false
            for ((k, v) in newStates) if (msgAuthByMsgId[k] != v) { authChanged = true; break }
            msgAuthByMsgId.putAll(newStates)
            if (authChanged) withContext(Dispatchers.Main) {
                if (::adapter.isInitialized) runCatching { adapter.notifyItemRangeChanged(0, adapter.itemCount) }
            }

            // 4) Подписываем свои ещё неподписанные сообщения (троттл, только при изменении).
            val now = System.currentTimeMillis()
            if (now - lastAuthSignMs >= 5_000L) {
                val (priv, pub) = prefs.getOrCreateIdentity()
                try {
                    val updated = MessageAuthSync.buildOwnSignatures(
                        msgs, prefs.myUserId, pub, priv, chat.chatId, sigs
                    )
                    if (updated != null) {
                        lastAuthSignMs = now
                        val blob = encryptChatLine(MessageAuthSync.serialize(updated), chat.chatId)
                        withContext(Dispatchers.IO) { transport.saveSignatures(blob) }
                    }
                } finally {
                    priv.fill(0)
                }
            }
        }
    }

    /**
     * Best-effort применение цепочки передачи владения (ADR_MESSAGE_AUTHENTICITY.md §10).
     * Грузит owner.txt, расшифровывает, применяет валидные переходы (OwnerSync): смена
     * adminUserId + перепиннинг ключа владельца + понижение роли прежнего. Владелец меняется
     * ТОЛЬКО по подписи текущего владельца — подделать нельзя. При смене — перечитываем чат из
     * БД, чтобы adminUserId в памяти обновился. Пока owner.txt пуст — полный no-op.
     */
    private suspend fun syncOwnerCerts() {
        if (!::chat.isInitialized || !chat.isGroup) return
        // Передача владения — редкое событие. НЕ читаем owner.txt каждый тик (это была лишняя
        // нагрузка на реле у всех участников), а раз в ~30с. На первом тике после открытия чата
        // проверяем сразу (lastOwnerCertCheckMs=0) — ожидающий оффер всплывёт быстро. Сам оффер
        // персистит на реле (Argon2id-шифрование) и ждёт получателя сколько угодно, не завися от
        // того, в сети ли владелец.
        val now = System.currentTimeMillis()
        if (now - lastOwnerCertCheckMs < 30_000L) return
        lastOwnerCertCheckMs = now
        runCatching {
            val rawBlob = withContext(Dispatchers.IO) { transport.loadOwnerCerts() }.trim()
            if (rawBlob.isEmpty()) return
            val plain = CryptoHelper.decrypt(rawBlob, chat.chatPassword, chat.chatId) ?: return
            val changed = withContext(Dispatchers.IO) {
                OwnerSync.applyOwnerChain(chat, plain, db.chatDao(), db.chatParticipantDao())
            }
            if (changed) {
                // Владелец сменился (завершённая передача владения) — переоткрываем чат, чтобы
                // транспорт пересоздался со СМЕНЁННЫМ adminUserId (его password-pubkey — гейт
                // members.txt нового владельца). Идемпотентно: applyOwnerChain двигает владельца
                // ровно один раз, поэтому recreate() срабатывает однократно на смену.
                withContext(Dispatchers.Main) { runCatching { recreate() } }
                return@runCatching
            }

            // Входящий НЕПРИНЯТЫЙ оффер лично мне → полноэкранное окно (двусторонняя передача).
            val adminUid = chat.adminUserId
            val pinnedAdminIdk = if (!adminUid.isNullOrBlank())
                withContext(Dispatchers.IO) { db.chatParticipantDao().getOne(chat.id, adminUid)?.pinnedIdentityPubKey }
            else null
            val pending = OwnerSync.findPendingOfferForMe(chat, plain, prefs.myUserId, prefs.myIdentityPubKey, pinnedAdminIdk)
            if (pending != null && pending.ts != lastLaunchedOfferTs &&
                prefs.getDeclinedOwnerOffer(chat.chatId) != pending.ts) {
                lastLaunchedOfferTs = pending.ts
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@ChatActivity, TransferOfferActivity::class.java).apply {
                        putExtra(TransferOfferActivity.EXTRA_CHAT_ID, chat.id)
                        putExtra(TransferOfferActivity.EXTRA_OFFER_TS, pending.ts)
                    })
                }
            }
        }
    }

    /**
     * Отзыв/возврат создателя root'ом (RevokeSync). Читаем revoke.txt: при открытии чата (поймать
     * уже существующий отзыв — live-стрим шлёт только НОВЫЕ события), по ПУШУ реле
     * (transport.consumeRevokeDirty, revoke.txt заведён в members-стрим — мгновенно в обычном
     * случае), И как СТРАХОВКА раз в ~12с. Страховка нужна потому, что между отзывом и возвратом
     * чат перезагружается (переход + recreate), и restore-событие реле может прийти в это окно и
     * не попасть в push → без страховки возврат «завис» бы. Файл крошечный, страховка лёгкая.
     * Если сменился владелец — показываем экран перехода. Best-effort.
     */
    private suspend fun syncRevokes() {
        if (!::chat.isInitialized || !chat.isGroup) return
        val now = System.currentTimeMillis()
        val firstOpen = lastRevokeCheckMs == 0L
        val pushed = !firstOpen && transport.consumeRevokeDirty()
        if (!firstOpen && !pushed && now - lastRevokeCheckMs < 12_000L) return
        lastRevokeCheckMs = now
        readAndApplyRevokes()
    }

    /**
     * Чтение revoke.txt + применение (без гейтинга по интервалу). Зовётся тикером [syncRevokes]
     * (страховка/простой) И НАПРЯМУЮ по push-событию revoke.txt (watchMessages callback) — чтобы
     * отзыв/возврат применялся push-МГНОВЕННО, как мут, а не ждал 2.5с-тикер. Идемпотентно
     * (RevokeSync.applyRevokes — анти-откат по ts), поэтому двойной вызов безопасен.
     */
    private suspend fun readAndApplyRevokes() {
        if (!::chat.isInitialized || !chat.isGroup) return
        runCatching {
            val rawBlob = withContext(Dispatchers.IO) { transport.loadRevokes() }.trim()
            if (rawBlob.isEmpty()) return
            val plain = CryptoHelper.decrypt(rawBlob, chat.chatPassword, chat.chatId) ?: return
            val changed = withContext(Dispatchers.IO) {
                RevokeSync.applyRevokes(chat, plain, db.chatDao(), db.chatParticipantDao(), prefs)
            }
            if (changed) {
                // Вместо резкого recreate() — полноэкранный переход с описанием (мокап одобрен).
                // revoke → adminUserId очистился (пусто); restore → вернулся непустым.
                val wasRevoke = withContext(Dispatchers.IO) { db.chatDao().getById(chat.id)?.adminUserId }.isNullOrBlank()
                withContext(Dispatchers.Main) {
                    runCatching {
                        // Окно перехода поверх, а чат СРАЗУ перезагружается ПОД ним (recreate).
                        startActivity(android.content.Intent(this@ChatActivity, RevokeTransitionActivity::class.java).apply {
                            putExtra(RevokeTransitionActivity.EXTRA_CHAT_ID, chat.id)
                            putExtra(RevokeTransitionActivity.EXTRA_REVOKE, wasRevoke)
                        })
                        recreate()
                    }
                }
            }
        }
    }

    /**
     * Приветственная плашка беседы (view_group_welcome, мокап одобрен) — один раз при первом заходе
     * (флаг в prefs). Glass поверх обоев: возможности бесед + права администратора (раздел прав —
     * только у создателя). Закрывается «Понятно»; тап по затемнению поглощается (не проваливается в чат).
     */
    private fun maybeShowGroupWelcome() {
        if (!::chat.isInitialized || !chat.isGroup) return
        if (prefs.isGroupWelcomeShown(chat.chatId)) return
        if (groupWelcomeCard != null) return // уже показана
        if (currentMessages.isNotEmpty()) return // беседа не пуста — плашка не нужна
        val contentRoot = findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        // Снимок чата ДО добавления карточки — иначе блюр захватил бы саму карточку.
        val snapshot = ScreenBlur.capture(this, radius = 10)
        val card = layoutInflater.inflate(R.layout.view_group_welcome, contentRoot, false)
        val amCreator = !chat.adminUserId.isNullOrBlank() && chat.adminUserId == prefs.myUserId
        card.findViewById<TextView>(R.id.tv_welcome_title).setText(
            if (amCreator) R.string.group_welcome_title_created else R.string.group_welcome_title_joined
        )
        card.findViewById<View>(R.id.welcome_admin_section).visibility =
            if (amCreator) View.VISIBLE else View.GONE
        // Тап по карточке НЕ закрывает (clickable=true в layout поглощает тап) — уходит только при
        // первом сообщении (см. коллектор сообщений → dismissGroupWelcome), плавно.
        groupWelcomeCard = card
        card.alpha = 0f // проявится входной анимацией (после позиционирования и блюра в post)
        // Позиция по центру чата. Сообщение не «застревает» под плашкой, потому что скрытие
        // плашки привязано к моменту появления первого сообщения (см. коллектор → dismissGroupWelcome):
        // плашка гаснет в тот же кадр, что и приходит сообщение → кроссфейд, а не статичное перекрытие.
        val lp = android.widget.FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.CENTER
        )
        contentRoot.addView(card, lp)
        card.post {
            runCatching {
                // Матовый блюр области ПОД карточкой (как в ТГ, только за ней).
                if (snapshot != null) {
                    val loc = IntArray(2); card.getLocationOnScreen(loc)
                    val x = loc[0].coerceIn(0, (snapshot.width - 1).coerceAtLeast(0))
                    val y = loc[1].coerceIn(0, (snapshot.height - 1).coerceAtLeast(0))
                    val w = card.width.coerceAtMost(snapshot.width - x)
                    val h = card.height.coerceAtMost(snapshot.height - y)
                    if (w > 0 && h > 0) {
                        val cropped = android.graphics.Bitmap.createBitmap(snapshot, x, y, w, h)
                        val blurD = android.graphics.drawable.BitmapDrawable(resources, cropped)
                        val tintD = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_group_welcome_tint)
                        card.background = android.graphics.drawable.LayerDrawable(arrayOf(blurD, tintD))
                    }
                }
                // Входная анимация (зеркально уходу): проявляется + чуть увеличивается + мягко
                // «опускается» на место сверху, ~380 мс. Больше не появляется резко.
                card.pivotX = card.width / 2f
                card.pivotY = card.height / 2f
                card.scaleX = 0.94f; card.scaleY = 0.94f
                card.translationY = -card.height * 0.14f
                card.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
                    .setDuration(380)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
                    .start()
            }
        }
    }

    /** Плавно убирает приветственную плашку беседы (при первом сообщении): гаснет + чуть уменьшается
     *  + мягко оседает, ~450 мс. После — удаляет вью и ставит флаг «показано» (больше не появится). */
    private fun dismissGroupWelcome() {
        val card = groupWelcomeCard ?: return
        groupWelcomeCard = null
        if (::chat.isInitialized) prefs.setGroupWelcomeShown(chat.chatId)
        card.pivotX = card.width / 2f
        card.pivotY = card.height / 2f
        card.animate()
            .alpha(0f)
            .scaleX(0.94f).scaleY(0.94f)
            .translationY(-card.height * 0.14f) // приподнимается вверх, освобождая ленту
            .setDuration(380)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.4f))
            .withEndAction { (card.parent as? android.view.ViewGroup)?.removeView(card) }
            .start()
    }

    private fun encryptChatLine(plaintext: String, chatId: String): String =
        if (chat.isGroup) CryptoHelper.encryptGroupMessage(plaintext, chat.chatPassword, chatId)
        else CryptoHelper.encrypt(plaintext, chat.chatPassword, chatId)

    private fun sendMessage() {
        // Заглушённый — read-only (защита в глубину: строка ввода и так скрыта, см.
        // applySelfMuteState, но на случай отложенного IME-события "отправить").
        if (isSelfMuted) return
        // Есть выбранные фото в баре → шлём их (с подписью из поля) и очищаем бар.
        if (stagedUris.isNotEmpty()) {
            if (sendManager.isPunished()) return
            val toSend = stagedUris.toList()
            clearStaged()
            if (toSend.size == 1) sendImage(toSend[0]) else sendImages(toSend)
            return
        }
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return

        // Активная блокировка — отдельного уведомления не нужно, обратный отсчёт уже виден
        if (sendManager.isPunished()) return

        val now = System.currentTimeMillis()
        val reply = replyingTo

        lifecycleScope.launch {
            val plaintext = Message.composePlaintext(
                senderName = prefs.myName,
                senderUserId = prefs.myUserId,
                text = text,
                quotedSender = reply?.sender,
                quotedText = reply?.let { quoteLabel(it) },
                timestampMs = now
            )
            val encrypted = withContext(Dispatchers.Default) {
                encryptChatLine(plaintext, chat.chatId)
            }

            val pendingMsg = Message(
                sender = prefs.myName,
                text = text,
                isSelf = true,
                rawEncrypted = encrypted,
                timestampMs = now,
                quotedSender = reply?.sender,
                quotedText = reply?.let { quoteLabel(it) },
                senderUserId = prefs.myUserId,
                isPending = true
            )

            val item = MessageSendManager.QueueItem(
                text = text,
                encrypted = encrypted,
                pendingMsg = pendingMsg
            )

            val accepted = sendManager.tryEnqueue(item)
            if (accepted) {
                // Мгновенный optimistic UI — сообщение появляется без ожидания сервера
                chatStore.addOptimistic(pendingMsg)
                // Очищаем поле, onQueueChanged обновит адаптер
                binding.etMessage.setText("")
                clearReply()
                // Сообщение ушло — больше не «печатаем»
                stopTypingSignal()
                // Превью ссылки (best-effort, через Tor) — отдельным файлом lp_<hash>.
                maybeBuildLinkPreview(text)
            }
        }
        // Если отклонено (очередь полна) — текст остаётся в поле, пользователь видит отказ
    }

    private val builtPreviewUrls = HashSet<String>()

    /**
     * Превью ссылки: ОТПРАВИТЕЛЬ тянет og: через Tor и заливает отдельным файлом
     * lp_<hash(url)>. Получатель грузит этот файл с реле (не сам сайт) — без утечки.
     * Только при готовом Tor, чтобы не палить реальный IP отправителя на сайт.
     */
    private fun maybeBuildLinkPreview(text: String) {
        val url = LinkPreview.firstUrl(text) ?: return
        if (com.atrum.chat.TorManager.status.value != com.atrum.chat.TorManager.TorStatus.READY) return
        if (!builtPreviewUrls.add(url)) return
        val fileName = LinkPreview.fileName(url)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val data = LinkPreview.fetch(url, useTor = true) ?: return@launch
                val enc = CryptoHelper.encrypt(data.toJson(), chat.chatPassword, chat.chatId)
                transport.saveFile(fileName, enc)
            } catch (_: Exception) {}
        }
    }

    /** Запускает обратный отсчёт в hint поля ввода и блокирует кнопку отправки. */
    private fun startPunishmentCountdown(durationMs: Long) {
        binding.btnSend.isEnabled = false
        countdownJob?.cancel()
        val endMs = System.currentTimeMillis() + durationMs
        countdownJob = lifecycleScope.launch {
            while (true) {
                val remaining = endMs - System.currentTimeMillis()
                if (remaining <= 0) break
                val sec = (remaining + 999) / 1000   // округляем вверх
                val mm = sec / 60
                val ss = sec % 60
                binding.etMessage.hint = String.format(Locale.ROOT, "Подождите %02d:%02d", mm, ss)
                delay(500L)
            }
        }
    }

    /** Снимает блокировку: восстанавливает hint и включает кнопку отправки. */
    private fun stopPunishmentCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        binding.etMessage.hint = originalHint
        binding.btnSend.isEnabled = true
    }

    // ── Голосовые сообщения ────────────────────────────────────────────────────

    /** Пустое поле → кнопка микрофона; есть текст → кнопка отправки. */
    private fun updateSendVoiceButtons(text: String) {
        if (btMode) {
            // В BT-чате микрофона нет — всегда кнопка отправки.
            binding.btnSend.visibility = View.VISIBLE
            binding.btnVoice.visibility = View.GONE
            return
        }
        val hasText = text.trim().isNotEmpty()
        val showSend = hasText || stagedUris.isNotEmpty()
        binding.btnSend.visibility = if (showSend) View.VISIBLE else View.GONE
        binding.btnVoice.visibility = if (showSend) View.GONE else View.VISIBLE
    }

    private fun hasAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    @SuppressLint("ClickableViewAccessibility")
    private fun setupVoiceInput() {
        updateSendVoiceButtons(binding.etMessage.text.toString())
        binding.btnRecPause.setOnClickListener { togglePauseRecording() }
        binding.btnRecDelete.setOnClickListener { finishVoiceRecording(cancel = true) }
        binding.btnRecSend.setOnClickListener { finishVoiceRecording(cancel = false) }
        binding.btnVoice.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    recordStartX = ev.rawX; recordStartY = ev.rawY
                    recordCancelled = false
                    if (!hasAudioPermission()) {
                        requestAudioPerm.launch(Manifest.permission.RECORD_AUDIO)
                        return@setOnTouchListener true
                    }
                    if (sendManager.isPunished()) return@setOnTouchListener true
                    beginVoiceRecording()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (voiceRecorder.isRecording && !voiceLocked) {
                        val dx = ev.rawX - recordStartX
                        val dy = ev.rawY - recordStartY
                        if (dy < -lockThresholdPx && dx > -cancelThresholdPx) {
                            setVoiceLocked()
                        } else if (dx < -cancelThresholdPx) {
                            recordCancelled = true
                            binding.tvRecHint.setText(R.string.voice_release_to_cancel)
                            binding.tvRecHint.setTextColor(ContextCompat.getColor(this, R.color.error))
                        } else {
                            recordCancelled = false
                            binding.tvRecHint.setText(R.string.voice_lock_hint)
                            binding.tvRecHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!voiceLocked && voiceRecorder.isRecording) {
                        finishVoiceRecording(cancel = recordCancelled || ev.action == MotionEvent.ACTION_CANCEL)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** Фиксирует запись (hands-free): палец можно убрать, управляют кнопки. */
    private fun setVoiceLocked() {
        if (voiceLocked) return
        voiceLocked = true
        recordCancelled = false
        binding.btnVoice.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.btnVoice.visibility = View.GONE
        binding.btnRecDelete.visibility = View.VISIBLE
        binding.btnRecPause.visibility = View.VISIBLE
        binding.btnRecSend.visibility = View.VISIBLE
        binding.btnRecPause.setImageResource(R.drawable.ic_pause)
        binding.btnRecPause.contentDescription = getString(R.string.voice_pause_cd)
        binding.tvRecHint.visibility = View.GONE
        binding.waveformRecord.visibility = View.VISIBLE
        binding.waveformRecord.setColors(
            ContextCompat.getColor(this, R.color.accent),
            ContextCompat.getColor(this, R.color.text_tertiary)
        )
    }

    /** Пауза/продолжение записи в зафиксированном режиме. */
    private fun togglePauseRecording() {
        if (!voiceRecorder.isRecording) return
        if (voiceRecorder.isPaused) {
            voiceRecorder.resume()
            binding.btnRecPause.setImageResource(R.drawable.ic_pause)
            binding.btnRecPause.contentDescription = getString(R.string.voice_pause_cd)
            binding.recDot.setBackgroundResource(R.drawable.bg_voice_circle_rec)
            binding.tvRecHint.visibility = View.GONE
            binding.waveformRecord.visibility = View.VISIBLE
        } else {
            voiceRecorder.pause()
            binding.btnRecPause.setImageResource(R.drawable.ic_mic)
            binding.btnRecPause.contentDescription = getString(R.string.voice_resume_cd)
            binding.recDot.setBackgroundResource(R.drawable.bg_voice_circle_paused)
            binding.waveformRecord.visibility = View.GONE
            binding.tvRecHint.visibility = View.VISIBLE
            binding.tvRecHint.setText(R.string.voice_paused)
            binding.tvRecHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun beginVoiceRecording() {
        if (!voiceRecorder.start()) {
            Toast.makeText(this, R.string.voice_record_failed, Toast.LENGTH_SHORT).show()
            return
        }
        voiceLocked = false
        recordCancelled = false
        isRecordingVoice = true
        lifecycleScope.launch { doPushPresence(0L, System.currentTimeMillis(), System.currentTimeMillis()) }
        recordLevels.clear()
        binding.waveformRecord.reset()
        binding.btnVoice.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        binding.btnAttach.visibility = View.GONE
        binding.btnSticker.visibility = View.GONE
        binding.etMessage.visibility = View.GONE
        binding.recordingRow.visibility = View.VISIBLE
        binding.recDot.setBackgroundResource(R.drawable.bg_voice_circle_rec)
        binding.tvRecTime.text = "0:00"
        binding.tvRecHint.visibility = View.VISIBLE
        binding.waveformRecord.visibility = View.GONE
        binding.tvRecHint.setText(R.string.voice_lock_hint)
        binding.tvRecHint.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.btnRecDelete.visibility = View.GONE
        binding.btnRecPause.visibility = View.GONE
        binding.btnRecSend.visibility = View.GONE
        voiceUiJob?.cancel()
        voiceUiJob = lifecycleScope.launch {
            var warnedLimit = false
            while (voiceRecorder.isRecording) {
                val ms = voiceRecorder.elapsedMs()
                // Предупреждение за минуту до лимита записи (один раз).
                if (!warnedLimit && ms >= MAX_VOICE_MS - 60_000L && ms < MAX_VOICE_MS) {
                    warnedLimit = true
                    showVoiceLimitBanner()
                }
                val sec = (ms / 1000).toInt()
                binding.tvRecTime.text = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
                if (!voiceRecorder.isPaused) {
                    val lvl = (voiceRecorder.amplitude() * 100f).toInt().coerceIn(0, 100)
                    recordLevels.add(lvl)
                    if (voiceLocked) binding.waveformRecord.pushLevel(lvl)
                }
                if (ms >= MAX_VOICE_MS) { finishVoiceRecording(cancel = false); break }
                delay(80L)
            }
        }
    }

    private fun restoreInputAfterRecording() {
        if (isRecordingVoice) {
            isRecordingVoice = false
            lifecycleScope.launch {
                doPushPresence(0L, if (isInForeground) System.currentTimeMillis() else 0L, 0L)
            }
        }
        hideVoiceLimitBanner()
        binding.recordingRow.visibility = View.GONE
        binding.btnAttach.visibility = View.VISIBLE
        binding.btnSticker.visibility = View.VISIBLE
        binding.etMessage.visibility = View.VISIBLE
        binding.tvRecTime.text = "0:00"
        binding.tvRecHint.visibility = View.VISIBLE
        binding.waveformRecord.visibility = View.GONE
        binding.waveformRecord.reset()
        binding.btnRecDelete.visibility = View.GONE
        binding.btnRecPause.visibility = View.GONE
        binding.btnRecSend.visibility = View.GONE
        voiceLocked = false
        updateSendVoiceButtons(binding.etMessage.text.toString())
    }

    private fun finishVoiceRecording(cancel: Boolean) {
        voiceUiJob?.cancel(); voiceUiJob = null
        val levelsSnapshot = ArrayList(recordLevels)
        val estDurMs = runCatching { voiceRecorder.elapsedMs() }.getOrDefault(0L)
        restoreInputAfterRecording()
        if (cancel) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { voiceRecorder.cancel() } }
            return
        }
        if (estDurMs < 700L) {
            // Слишком коротко — просто останавливаем, пузырёк не показываем.
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { runCatching { voiceRecorder.stop(minMs = 700L) } }
                Toast.makeText(this@ChatActivity, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
            }
            return
        }
        // Длительность и дорожка известны сразу → пузырёк показываем мгновенно,
        // обработку и загрузку делаем после (видно прогресс).
        val durationSec = ((estDurMs + 500L) / 1000L).toInt().coerceAtLeast(1)
        val wf = Message.encodeWaveform(downsampleLevels(levelsSnapshot, 40))
        sendVoice(durationSec, wf)
    }

    /** Сжимает накопленные уровни до target столбиков (берём пик в каждой корзине). */
    private fun downsampleLevels(levels: List<Int>, target: Int): IntArray {
        if (levels.isEmpty()) return IntArray(0)
        if (levels.size <= target) return levels.toIntArray()
        val out = IntArray(target)
        val bucket = levels.size.toFloat() / target
        for (i in 0 until target) {
            val start = (i * bucket).toInt()
            val end = ((i + 1) * bucket).toInt().coerceAtMost(levels.size)
            var peak = 0
            for (j in start until end) if (levels[j] > peak) peak = levels[j]
            out[i] = peak
        }
        return out
    }

    private fun sendVoice(durationSec: Int, waveform: String) {
        if (sendManager.isPunished()) return
        val now = System.currentTimeMillis()
        val contentRef = Message.newImageFileName()
        lockForUpload()
        lifecycleScope.launch {
            var pendingRaw: String? = null
            try {
                // 1) Шифруем строку и показываем пузырёк СРАЗУ — до обработки и загрузки (§1.5).
                val plaintext = Message.composePlaintext(
                    senderName = prefs.myName,
                    senderUserId = prefs.myUserId,
                    text = "",
                    voiceFileName = contentRef,
                    voiceDurationSec = durationSec,
                    voiceWaveform = waveform,
                    timestampMs = now
                )
                val encryptedMessage = withContext(Dispatchers.Default) {
                    encryptChatLine(plaintext, chat.chatId)
                }
                pendingRaw = encryptedMessage
                chatStore.addOptimistic(
                    Message(
                        sender = prefs.myName,
                        text = "",
                        isSelf = true,
                        rawEncrypted = encryptedMessage,
                        timestampMs = now,
                        voiceFileName = contentRef,
                        voiceDurationSec = durationSec,
                        voiceWaveform = waveform,
                        senderUserId = prefs.myUserId,
                        isPending = true,
                        voiceProgress = Message.VP_PROCESSING
                    )
                )
                stopTypingSignal()

                // 2) Тяжёлая обработка (нейрошумодав + кодек) в фоне — кольцо «обработка».
                val result = withContext(Dispatchers.IO) { voiceRecorder.stop(minMs = 700L) }
                if (result == null) {
                    chatStore.dropPending(encryptedMessage)
                    Toast.makeText(this@ChatActivity, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val file = result.first
                val b64 = withContext(Dispatchers.Default) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                ImageCache.put(contentRef, b64, null) // своё голосовое сразу доступно для прослушивания
                withContext(Dispatchers.IO) {
                    runCatching {
                        val playDir = File(cacheDir, "voice_play").apply { mkdirs() }
                        file.copyTo(File(playDir, "v_" + Integer.toHexString(contentRef.hashCode()) + ".m4a"), overwrite = true)
                    }
                }

                // 3) Голос доставляем ТЕМ ЖЕ путём, что и фото (проверенный, работает):
                //    зашифрованное аудио летит ВЛОЖЕНИЕМ в той же appendLine. Отдельного
                //    saveFileChunked + второго fetch у получателя нет — раз строка дошла,
                //    дошёл и файл (одна публикация, одна сборка как у изображений).
                val encryptedContent = withContext(Dispatchers.Default) {
                    CryptoHelper.encrypt(b64, chat.chatPassword, chat.chatId)
                }
                imageUploadQueue.execute {
                    withContext(Dispatchers.IO) {
                        transport.appendLine(
                            encryptedLine = encryptedMessage,
                            extraFiles = mapOf(contentRef to encryptedContent)
                        )
                    }
                }
                chatStore.confirmSent(encryptedMessage)
                syncEngine.forceSync(delayMs = 0L)
                runCatching { file.delete() }
            } catch (e: Exception) {
                pendingRaw?.let { chatStore.dropPending(it) }
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + (e.message?.take(120) ?: "unknown"),
                    Toast.LENGTH_LONG).show()
            } finally {
                stickerPanel?.setSendingState(false)
                unlockAfterUpload()
            }
        }
    }

    // ── Жёлтая плашка лимита GitHub ────────────────────────────────────────────

    /**
     * Показывает жёлтую плашку «Слишком много запросов» с обратным отсчётом до момента,
     * когда снова можно отправлять. Срабатывает по коллбэку MessageSendManager.onRateLimit
     * (RateLimitException несёт точную паузу из заголовка Retry-After). Стиль адаптируется
     * под тёмную/светлую тему (сплошной янтарь) и glass-режим (тёмный оверлей + янтарный текст).
     * Всё обновляется на месте, без перезахода (§1.5).
     */
    /**
     * Жёлтая плашка «скоро лимит записи» (за минуту до MAX_VOICE_MS). Выезжает над полем
     * ввода, сама прячется через 5 с. Адаптируется под тёмную/светлую (сплошной жёлтый)
     * и glass-режим (тёмный оверлей + жёлтый текст/иконка) — §0/§5.
     */
    private fun showVoiceLimitBanner() {
        val isGlass = prefs.chatUiStyle == Prefs.CHAT_UI_GLASS
        binding.voiceLimitBanner.setBackgroundResource(
            if (isGlass) R.drawable.bg_voice_limit_banner_glass else R.drawable.bg_voice_limit_banner)
        val onColor = ContextCompat.getColor(
            this, if (isGlass) R.color.voice_limit_bg else R.color.voice_limit_on)
        binding.ivVoiceLimitIcon.setColorFilter(onColor)
        binding.tvVoiceLimitTitle.setTextColor(onColor)
        binding.tvVoiceLimitMsg.setTextColor(onColor)
        if (binding.voiceLimitBanner.visibility != View.VISIBLE) {
            binding.voiceLimitBanner.alpha = 0f
            binding.voiceLimitBanner.visibility = View.VISIBLE
            binding.voiceLimitBanner.animate().alpha(1f).setDuration(200L).start()
        }
        binding.voiceLimitBanner.removeCallbacks(hideVoiceLimitRunnable)
        binding.voiceLimitBanner.postDelayed(hideVoiceLimitRunnable, 5000L)
    }

    private fun hideVoiceLimitBanner() {
        binding.voiceLimitBanner.removeCallbacks(hideVoiceLimitRunnable)
        if (binding.voiceLimitBanner.visibility != View.VISIBLE) return
        binding.voiceLimitBanner.animate().alpha(0f).setDuration(180L)
            .withEndAction { binding.voiceLimitBanner.visibility = View.GONE }.start()
    }

    private fun showTransportLimitBanner(durationMs: Long) {
        if (durationMs <= 0L) { hideTransportLimitBanner(); return }
        val isGlass = prefs.chatUiStyle == Prefs.CHAT_UI_GLASS
        val bannerBg = if (isGlass) R.drawable.bg_transport_limit_banner_glass
                       else R.drawable.bg_transport_limit_banner
        val contentColor = ContextCompat.getColor(
            this, if (isGlass) R.color.warning else R.color.warning_on)

        binding.transportLimitBanner.setBackgroundResource(bannerBg)
        binding.ivTransportLimitIcon.setColorFilter(contentColor)
        binding.tvTransportLimitTitle.setTextColor(contentColor)
        binding.tvTransportLimitMessage.setTextColor(contentColor)

        if (binding.transportLimitBanner.visibility != View.VISIBLE) {
            binding.transportLimitBanner.alpha = 0f
            binding.transportLimitBanner.visibility = View.VISIBLE
            binding.transportLimitBanner.animate().alpha(1f).setDuration(220L).start()
        }
        // Пока действует лимит — отправка приглушена и заблокирована.
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.4f

        transportLimitJob?.cancel()
        val endMs = System.currentTimeMillis() + durationMs
        transportLimitJob = lifecycleScope.launch {
            while (true) {
                val remaining = endMs - System.currentTimeMillis()
                if (remaining <= 0) break
                val sec = (remaining + 999) / 1000   // округляем вверх
                val mmss = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
                binding.tvTransportLimitMessage.text = getString(R.string.transport_limit_retry_in, mmss)
                delay(500L)
            }
            hideTransportLimitBanner()
        }
    }

    /** Прячет плашку лимита и возвращает кнопку отправки (если нет активной спам-блокировки). */
    private fun hideTransportLimitBanner() {
        transportLimitJob?.cancel()
        transportLimitJob = null
        if (binding.transportLimitBanner.visibility == View.VISIBLE) {
            binding.transportLimitBanner.animate().alpha(0f).setDuration(180L).withEndAction {
                binding.transportLimitBanner.visibility = View.GONE
            }.start()
        }
        binding.btnSend.alpha = 1f
        // Не включаем отправку, если параллельно активна спам-блокировка (countdownJob).
        if (countdownJob == null) binding.btnSend.isEnabled = true
    }

    // ── Forward secrecy ──────────────────────────────────────────────────────

    /**
     * Проверяет identity партнёра (пункт 7, аддитивно — НЕ блокирует сессию).
     * Сверяет подпись эфемерного ключа и сравнивает identity-ключ с запомненным (TOFU).
     */
    private fun verifyPartnerIdentity(partner: Profile) {
        val idk = partner.identityPubKey
        val sig = partner.ephemeralSig
        val eph = partner.ephemeralPubKey
        // Партнёр опубликовал, что лично подтвердил НАШ identity-ключ?
        val partnerVerifiedMe = partner.verifiedPartnerIdk != null &&
            partner.verifiedPartnerIdk == prefs.myIdentityPubKey

        if (idk == null || sig == null || eph == null) {
            IdentityState.set(chat.chatId, IdentityState.Info(IdentityState.State.UNVERIFIED, idk, partnerVerifiedMe))
            return
        }
        val data = Base64.decode(eph, Base64.NO_WRAP) + chat.chatId.toByteArray(Charsets.UTF_8)
        if (!CryptoHelper.verifyIdentitySignature(idk, data, sig)) {
            IdentityState.set(chat.chatId, IdentityState.Info(IdentityState.State.UNVERIFIED, idk, partnerVerifiedMe))
            return
        }
        val known = prefs.getKnownPartnerIdentity(chat.chatId)
        val state = when (known) {
            null -> {
                prefs.setKnownPartnerIdentity(chat.chatId, idk)   // trust on first use
                IdentityState.State.VERIFIED
            }
            idk -> IdentityState.State.VERIFIED
            else -> IdentityState.State.CHANGED
        }
        IdentityState.set(chat.chatId, IdentityState.Info(state, idk, partnerVerifiedMe))
    }

    /**
     * Подписывает эфемерный ключ долговременным Ed25519 identity-ключом.
     * Данные подписи: ephPubBytes ‖ chatId (привязка к чату от replay).
     */
    private fun computeEphemeralSig(ephPubB64: String?, chatId: String): String? {
        if (ephPubB64 == null) return null
        val (priv, _) = prefs.getOrCreateIdentity()
        return try {
            val data = Base64.decode(ephPubB64, Base64.NO_WRAP) + chatId.toByteArray(Charsets.UTF_8)
            CryptoHelper.signWithIdentity(priv, data)
        } finally {
            priv.fill(0)
        }
    }

    /**
     * Подпись «доказательство identity» (домен+chatId моим identity-ключом). В отличие от
     * [computeEphemeralSig] работает и в БЕСЕДАХ (нет эфемерного ключа) — публикуется в
     * profiles.txt как Profile.identitySig и даёт неподделываемую галочку в группах.
     * priv затирается сразу после подписи (§1).
     */
    private fun computeIdentitySig(chatId: String): String? {
        val (priv, _) = prefs.getOrCreateIdentity()
        return try {
            CryptoHelper.signWithIdentity(priv, VerifiedBadge.identitySigData(chatId))
        } catch (_: Exception) {
            null
        } finally {
            priv.fill(0)
        }
    }

    private val ephemeralRotationMs = 24L * 60 * 60 * 1000  // ротация эфемерного ключа раз в сутки
    // ВРЕМЕННО ВЫКЛ: ротация требует надёжной доставки нового pub через profiles.txt
    // (общий файл с гонкой lost-update на флаки-реле). Потеря обновления pub →
    // расхождение сессионного ключа → сообщения одной стороны не доходят. Включить
    // обратно только после усиления синхронизации профиля (union слотов на пользователя).
    private val ephemeralRotationEnabled = false

    /**
     * Периодическая ротация эфемерного X25519-ключа (forward secrecy с окном).
     * Новые сообщения шифруются новым ключом; прошлый сессионный ключ ещё живёт в
     * кольце CryptoHelper для расшифровки присланного в окне рукопожатия; вся история
     * читается из локального FS-архива. Старый шифртекст на реле становится
     * нерасшифровываемым — это и есть FS против реле/сети. Fail-safe.
     */
    private fun maybeRotateEphemeral() {
        if (!ephemeralRotationEnabled) return
        try {
            val now = System.currentTimeMillis()
            val last = prefs.getEphemeralRotatedAt(chat.chatId)
            if (last == 0L) { prefs.setEphemeralRotatedAt(chat.chatId, now); return }  // первый раз — только метка
            if (now - last < ephemeralRotationMs) return
            val (newPriv, newPub) = CryptoHelper.generateEphemeralKeyPair()
            val old = myEphemeralPrivKey
            myEphemeralPrivKey = newPriv
            myCurrentEphemeralPubKey = newPub
            myEphemeralSig = computeEphemeralSig(newPub, chat.chatId)
            prefs.setEphemeralPriv(chat.chatId, newPriv)
            prefs.setEphemeralRotatedAt(chat.chatId, now)
            // Пересчитываем сессионный ключ под текущий pub партнёра (добавится в кольцо).
            lastPartnerEphemeralPubKey = null
            chat.partnerEphemeralPubKeyB64?.let { tryEstablishSessionKey(it) }
            old?.fill(0)
            // В БД пишем только новый публичный ключ; приватный — никогда.
            lifecycleScope.launch {
                try { db.chatDao().updateMyEphemeralKeys(chat.id, null, newPub) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Устанавливает сессионный ключ (V3) если партнёр опубликовал новый ephemeral pub key.
     *
     * Вызывается из doSyncProfilesOnce() и processParsedProfiles() при каждом получении
     * профиля партнёра. Пересчёт происходит только при смене ключа.
     *
     * ⚠️ Комментарий раньше был оторван от этой функции (стоял на ~90 строк выше, сразу
     * после НЕсвязанного unlockAfterSend-блока, перед доккомментарием verifyPartnerIdentity —
     * похоже, остался на месте после переноса самой функции при более раннем рефакторинге).
     * Перенесён обратно вплотную к функции, которую описывает.
     *
     * После setSessionKey() все новые encrypt() автоматически используют V3 (forward secrecy).
     */
    private fun tryEstablishSessionKey(partnerPubKey: String?) {
        // ⛔ Групповые чаты (ADR-001): ECDH-сессия рассчитана строго на двух участников,
        // для группы не устанавливается — см. комментарий в onCreate. myEphemeralPrivKey
        // для группы и так остаётся null (см. ниже), но проверяем явно для ясности.
        if (chat.isGroup) return
        if (partnerPubKey == null) return
        if (partnerPubKey == lastPartnerEphemeralPubKey) return   // ключ не изменился
        val privKey = myEphemeralPrivKey ?: return                // наш ключ ещё не готов

        val sessionKey = CryptoHelper.computeSessionKey(privKey, partnerPubKey, chat.chatId)
        if (sessionKey != null) {
            CryptoHelper.setSessionKey(chat.chatId, sessionKey)
            sessionKey.fill(0)   // очищаем локальную копию
            lastPartnerEphemeralPubKey = partnerPubKey

            // Сохраняем публичный ключ партнёра в БД для восстановления сессии.
            // Делаем это асинхронно, чтобы не блокировать UI/polling поток.
            if (partnerPubKey != chat.partnerEphemeralPubKeyB64) {
                lifecycleScope.launch {
                    db.chatDao().updatePartnerEphemeralKey(chat.id, partnerPubKey)
                    chat = chat.copy(partnerEphemeralPubKeyB64 = partnerPubKey)
                }
            }

            // Сессионный ключ установлен → принудительно сбрасываем кэш контента,
            // чтобы следующий тик loadMessages перепарсил все строки заново.
            // Без этого V3-сообщения партнёра, которые раньше давали null (ключа не было),
            // так и остаются невидимыми — content == lastContent, парсинг скипается.
            lastContent = ""
            lockedV3ConsecutiveCount = 0
        }
    }

    /**
     * Восстанавливает subtitle после typing-индикатора.
     */
    private fun updateSubtitle() {
        if (chat.isSystemNotifications) {
            // Системный чат «Уведомления» — фиксированный подзаголовок, не «Зашифрованный
            // чат» (репорт: подзаголовок перетирался этим методом из presence-пути).
            binding.tvChatSubtitle.text = getString(R.string.notif_chat_subtitle)
            binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            binding.tvChatSubtitle.setOnClickListener(null)
            return
        }
        if (chat.partnerDeleted) {
            binding.tvChatSubtitle.text = getString(R.string.partner_deleted_subtitle)
            binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.error))
            return
        }

        if (!chat.partnerTag.isNullOrBlank()) {
            binding.tvChatSubtitle.text = chat.partnerTag
        } else {
            binding.tvChatSubtitle.text = getString(R.string.chat_subtitle_encrypted)
        }
        binding.tvChatSubtitle.setTextColor(
            ContextCompat.getColor(this, R.color.text_tertiary)
        )
        binding.tvChatSubtitle.setOnClickListener(null)
    }

    /**
     * Показывает диалог подтверждения очистки истории.
     * При подтверждении: перезаписывает Gist пустым манифестом + сбрасывает локальный кеш.
     */
    private fun confirmClearHistory() {
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle("Очистить историю?")
            .setMessage("Все сообщения будут удалены у обоих участников. Это действие необратимо.")
            .setPositiveButton("Удалить") { _, _ -> doClearHistory() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun doClearHistory() {
        lifecycleScope.launch {
            binding.progress.visibility = android.view.View.VISIBLE
            try {
                withContext(Dispatchers.IO) {
                    // Очистка истории: маркер "clear" + NIP-09 (NostrTransport) — у обоих.
                    transport.clearHistory()
                }
                // Сбрасываем локальные кеши и ETag — иначе следующий polling вернёт 304
                // (GitHub CDN может ещё не обновиться) и сообщения "воскреснут" на 1 тик
                lastContent = ""
                lastPushedReadIndex = -1
                ChatSnapshotCache.clear(chat.chatId)
                db.chatDao().updatePreview(chat.id, "", System.currentTimeMillis())
                adapter.submit(emptyList(), emptyList())
                Toast.makeText(this@ChatActivity, "История очищена", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity, "Ошибка: ${e.message?.take(60)}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.progress.visibility = android.view.View.GONE
            }
        }
    }

    // ── Online presence ───────────────────────────────────────────────────────

    /**
     * Запускает единый presence-цикл: один PATCH каждые PRESENCE_INTERVAL_MS
     * с обоими полями (typingTs + onlineTs) вместо двух раздельных job'ов.
     * Первый тик — немедленно, собеседник видит точку сразу при открытии.
     */
    private fun startPresence() {
        if (chat.isFavorites) return
        presenceJob?.cancel()
        presenceJob = lifecycleScope.launch {
            while (true) {
                val typingTs = if (isCurrentlyTyping) System.currentTimeMillis() else 0L
                val recTs = if (isRecordingVoice) System.currentTimeMillis() else 0L
                doPushPresence(typingTs, System.currentTimeMillis(), recTs)
                // ±20% джиттер скрывает ритм presence-пульса от анализа трафика
                val jitter = (PRESENCE_INTERVAL_MS * 0.2 * (Math.random() * 2 - 1)).toLong()
                delay(PRESENCE_INTERVAL_MS + jitter)
            }
        }
        // Локальный тикер: гасит/показывает статусы по таймауту даже без новых данных.
        presenceTickerJob?.cancel()
        presenceTickerJob = lifecycleScope.launch {
            while (true) {
                delay(PRESENCE_TICK_MS)
                applyPresence()
            }
        }
    }

    /**
     * Шлёт один presence-PATCH (typingTs + onlineTs).
     *
     * ВСЕГДА делает полный GET+merge+PATCH (НЕ write-only из кэша). Причина:
     * profiles.txt — общий файл, который пишут оба участника, а Nostr-чтение берёт
     * одно последнее replaceable-событие. Запись из устаревшего кэша затирала бы
     * профиль собеседника (его ephemeralPubKey/onlineTs) → ломала forward-secrecy
     * handshake и «зависала» онлайн партнёра. Свежий pull+merge сохраняет обе стороны.
     *
     * Расход: 1 GET + 1 PATCH каждые PRESENCE_INTERVAL_MS (5с). Историческая
     * «write-only из кэша» оптимизация удалена именно из-за порчи данных партнёра.
     */
    private suspend fun doPushPresence(typingTs: Long, onlineTs: Long, recordingTs: Long = 0L) {
        if (!::transport.isInitialized) return
        val myUserId = prefs.myUserId

        // ВСЕГДА GET+merge (как Gist): profiles.txt — общий файл, который пишут оба
        // участника, а Nostr-чтение берёт одно последнее событие. Запись из кэша
        // затирала бы профиль собеседника. Свежий pull+merge сохраняет обе стороны.
        val ok = withContext(Dispatchers.IO) {
            ProfileSync.pushPresence(
                api               = transport,
                password          = chat.chatPassword,
                myUserId          = myUserId,
                typingTs          = typingTs,
                onlineTs          = onlineTs,
                recordingTs       = recordingTs,
                myEphemeralPubKey = myCurrentEphemeralPubKey,
                myName            = prefs.myName,
                myTag             = prefs.myTag,
                myAvatarBase64    = prefs.myAvatarBase64,
                myIdentityPubKey     = prefs.myIdentityPubKey,
                myEphemeralSig       = myEphemeralSig,
                myIdentitySig        = myIdentitySig,
                myVerifiedPartnerIdk = prefs.getConfirmedPartnerIdentity(chat.chatId)
            )
        }

        // Обновляем локальный кэш сразу — следующий write-only push будет корректным
        if (ok) {
            lastKnownProfiles[myUserId]?.let { current ->
                lastKnownProfiles[myUserId] = current.copy(typingTs = typingTs, onlineTs = onlineTs, recordingTs = recordingTs)
            }
        }
    }

    /**
     * Показывает/скрывает фиолетовую точку на аватаре собеседника.
     */
    private fun updateOnlineIndicator(isOnline: Boolean) {
        binding.vOnlineIndicator.visibility = if (isOnline) View.VISIBLE else View.GONE
    }

    // ── Typing indicator ──────────────────────────────────────────────────────

    /**
     * Вешает TextWatcher на поле ввода.
     * Логика: любое изменение текста → onTypingDetected().
     *         Пустое поле (стёрли всё) → stopTypingSignal().
     */
    private fun setupTypingDetection() {
        setupMentionStrip()
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) stopTypingSignal() else onTypingDetected()
                updateStickerSuggestions(s?.toString() ?: "")
                updateSendVoiceButtons(s?.toString() ?: "")
                updateMentionStrip() // автодополнение @ (Этап упоминаний)
            }
        })
    }

    /**
     * Вызывается при каждом изменении текста.
     * Перезапускает таймер остановки (TYPING_STOP_DELAY_MS → stopTypingSignal).
     * Флаг isCurrentlyTyping подхватывается presenceJob на следующем тике (≤12 сек).
     * Убран немедленный push при начале печати: он давал +1 GET+PATCH на каждый
     * раз когда пользователь начинал набирать текст и сильно нагружал GitHub API
     * при активном общении. TYPING_EXPIRY_MS=20s перекрывает presence interval с запасом.
     */
    private fun onTypingDetected() {
        val wasTyping = isCurrentlyTyping
        isCurrentlyTyping = true
        stopTypingJob?.cancel()
        stopTypingJob = lifecycleScope.launch {
            delay(TYPING_STOP_DELAY_MS)
            stopTypingSignal()
        }
        // Telegram-like: при НАЧАЛЕ печати — мгновенный push (не ждём heartbeat).
        if (!wasTyping) {
            lifecycleScope.launch {
                doPushPresence(System.currentTimeMillis(), System.currentTimeMillis())
            }
        }
    }

    /**
     * Останавливает typing-сигнал: сбрасывает флаг.
     * Presense-цикл на следующем тике отправит typingTs=0 партнёру.
     * Убран немедленный push: экономит один GET+PATCH при каждой паузе в наборе.
     * Партнёр увидит конец печати максимум через PRESENCE_INTERVAL_MS (12 сек),
     * что вполне приемлемо и не нагружает API.
     */
    private fun stopTypingSignal() {
        val wasTyping = isCurrentlyTyping
        stopTypingJob?.cancel()
        stopTypingJob = null
        isCurrentlyTyping = false
        // Telegram-like: при ОСТАНОВКЕ печати — мгновенный push typingTs=0.
        if (wasTyping) {
            lifecycleScope.launch {
                doPushPresence(0L, if (isInForeground) System.currentTimeMillis() else 0L)
            }
        }
    }

    /**
     * Показывает / скрывает индикатор «печатает» в subtitle шапки чата.
     * «Печатает» — акцентный цвет; обычное состояние — tertiary.
     */
    private fun updateTypingIndicator(isTyping: Boolean) {
        if (isTyping) {
            binding.tvChatSubtitle.text = getString(R.string.typing_indicator)
            binding.tvChatSubtitle.setTextColor(
                ContextCompat.getColor(this, R.color.accent)
            )
            binding.tvChatSubtitle.setOnClickListener(null)
        } else {
            updateSubtitle()
        }
    }

    /**
     * Единая отрисовка presence партнёра (онлайн / печатает / записывает голосовое)
     * по последнему профилю и ТЕКУЩЕМУ времени. Вызывается и при приходе данных, и
     * раз в секунду из presence-тикера — статусы гаснут по таймауту даже без новых
     * данных (точность) и не «залипают». Приоритет подписи: запись > печать > обычная.
     */
    private fun applyPresence() {
        // ⛔ Групповые чаты (ADR-001): presence — агрегат по ВСЕМ участникам, не по
        // одному lastPartnerProfile. Отдельная ветка, единая точка вызова (presenceTickerJob).
        if (chat.isGroup) {
            applyGroupPresence()
            return
        }
        val p = lastPartnerProfile
        val now = System.currentTimeMillis()
        val alive = p != null && !p.deleted
        val isRecording = alive && p!!.recordingTs > 0L && now - p.recordingTs < RECORDING_EXPIRY_MS
        val isTyping    = alive && p!!.typingTs   > 0L && now - p.typingTs   < TYPING_EXPIRY_MS
        val isOnline    = alive && p!!.onlineTs   > 0L && now - p.onlineTs   < ONLINE_EXPIRY_MS
        if (isRecording) {
            binding.tvChatSubtitle.text = getString(R.string.recording_indicator)
            binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
            binding.tvChatSubtitle.setOnClickListener(null)
        } else {
            updateTypingIndicator(isTyping)
        }
        updateOnlineIndicator(isOnline)
    }

    /**
     * Presence для группового чата: печатает/записывает — если хотя бы ОДИН другой
     * участник активен сейчас; иначе — "N участников". Онлайн-индикатор — если хотя
     * бы один другой участник онлайн. lastKnownProfiles уже поддерживается в актуальном
     * состоянии существующим profile-sync кодом (processProfilesFromContent/Slots) —
     * эта функция только читает его, ничего не меняет.
     */
    private fun applyGroupPresence() {
        val now = System.currentTimeMillis()
        val others = lastKnownProfiles.values.filter { it.userId != prefs.myUserId && !it.deleted }
        val isRecording = others.any { it.recordingTs > 0L && now - it.recordingTs < RECORDING_EXPIRY_MS }
        val isTyping    = others.any { it.typingTs   > 0L && now - it.typingTs   < TYPING_EXPIRY_MS }
        val isOnline    = others.any { it.onlineTs   > 0L && now - it.onlineTs   < ONLINE_EXPIRY_MS }
        when {
            isRecording -> {
                binding.tvChatSubtitle.text = getString(R.string.recording_indicator)
                binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.tvChatSubtitle.setOnClickListener(null)
            }
            isTyping -> {
                binding.tvChatSubtitle.text = getString(R.string.typing_indicator)
                binding.tvChatSubtitle.setTextColor(ContextCompat.getColor(this, R.color.accent))
                binding.tvChatSubtitle.setOnClickListener(null)
            }
            else -> updateGroupSubtitleParticipantCount()
        }
        updateOnlineIndicator(isOnline)
    }

    /**
     * Мут (read-only режим, запрос пользователя: "только права на чтение чата").
     * Вызывается на КАЖДОМ опросе (см. processChannelData) — наложение/снятие/истечение
     * мута отражается сразу, без перезахода в чат (§1.5 CLAUDE.md).
     *
     * Пока [muted] — строка ввода полностью скрыта (input_area, вместе с вложениями/
     * стикерами/голосом — там же), а жёлтая карточка мута показывается НАД лентой —
     * сама лента сообщений остаётся видимой и живой (правки по мокапу этой сессии).
     * Плашку можно свернуть в компактную (тап по крестику/самой плашке переключает),
     * но она никогда не исчезает совсем, пока мут действует — свёрнутое состояние лишь
     * персистентно на конкретный untilMs (см. Prefs.isMuteBannerCollapsed).
     */
    private fun applySelfMuteState(muted: Boolean, untilMs: Long?, reason: String?, evidenceIds: List<String> = emptyList()) {
        // Верифицированный разработчик (PERSONAL_BUILD.md §Часть 3) неприкосновенен: мут в мою
        // сторону игнорируется — ввод не блокируется. Неподделываемо (VerifiedBadge, мой ключ).
        @Suppress("NAME_SHADOWING")
        val muted = muted && !VerifiedBadge.isVerifiedSelf(prefs.myIdentityPubKey)
        isSelfMuted = muted
        currentMuteEvidenceIds = evidenceIds
        if (!muted || untilMs == null) {
            binding.mutedBannerLarge.visibility = View.GONE
            binding.mutedBannerCompact.visibility = View.GONE
            binding.inputArea.visibility = View.VISIBLE
            binding.mutedEvidenceSection.visibility = View.GONE
            lastRenderedEvidenceKey = null
            return
        }
        binding.inputArea.visibility = View.GONE
        renderMuteEvidenceFeed()

        val untilFmt = java.text.SimpleDateFormat("dd.MM.yy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(untilMs))
        val reasonText = reason?.takeIf { it.isNotBlank() } ?: getString(R.string.muted_banner_no_reason)

        binding.tvMutedBannerUntil.text = getString(R.string.muted_banner_until_fmt, untilFmt)
        binding.tvMutedBannerReason.text = reasonText
        binding.tvMutedBannerCompactText.text = getString(R.string.muted_banner_title) + " " + getString(R.string.muted_banner_until_fmt, untilFmt)

        fun showCollapsed() {
            prefs.setMuteBannerCollapsed(chat.chatId, untilMs, true)
            binding.mutedBannerLarge.visibility = View.GONE
            binding.mutedBannerCompact.visibility = View.VISIBLE
        }
        fun showExpanded() {
            prefs.setMuteBannerCollapsed(chat.chatId, untilMs, false)
            binding.mutedBannerCompact.visibility = View.GONE
            binding.mutedBannerLarge.visibility = View.VISIBLE
        }

        binding.btnMutedBannerCollapse.setOnClickListener { showCollapsed() }
        binding.mutedBannerCompact.setOnClickListener { showExpanded() }

        // Если оба уже видны (просто обновление текста на очередном тике) — не дёргаем
        // видимость заново, иначе выбор пользователя (свёрнуто/развёрнуто) на этом же
        // тике сбросился бы обратно к сохранённому предпочтению.
        if (binding.mutedBannerLarge.visibility == View.VISIBLE || binding.mutedBannerCompact.visibility == View.VISIBLE) return

        if (prefs.isMuteBannerCollapsed(chat.chatId, untilMs)) showCollapsed() else showExpanded()
    }

    /**
     * Лента "сообщения-основание" внутри баннера мута — по запросу пользователя:
     * админ при муте может прикрепить любое число сообщений (текст/фото/голос),
     * заглушённый видит их у себя в баннере, фото открывается, голос проигрывается.
     * Сами сообщения НЕ дублируются — только msgId (см. ChatParticipant.mutedEvidenceIds);
     * находим их в уже расшифрованной [lastAllDecodedMessages] (полный декод БЕЗ фильтра
     * бана/мута — свои же скрытые сообщения иначе не найти, см. processChannelData).
     * Мемо по [lastRenderedEvidenceKey] — не перестраиваем views без реальных изменений.
     */
    private fun renderMuteEvidenceFeed() {
        if (!isSelfMuted || currentMuteEvidenceIds.isEmpty()) {
            binding.mutedEvidenceSection.visibility = View.GONE
            lastRenderedEvidenceKey = null
            return
        }
        val evidenceSet = currentMuteEvidenceIds.toHashSet()
        val evidenceMsgs = lastAllDecodedMessages.filter { it.msgId in evidenceSet }.sortedBy { it.timestampMs }
        if (evidenceMsgs.isEmpty()) {
            // Ещё не было полного декода в этой сессии (см. processChannelData) — самолечится
            // на следующем тике/после первой реальной загрузки, ничего не показываем пока.
            binding.mutedEvidenceSection.visibility = View.GONE
            return
        }
        // ⚠️ Фикс (репорт: «при активном муте сообщения других приходят у заглушённого
        // на ~тик позже»): раньше ключ мемо включал размер ВСЕЙ ленты
        // (lastAllDecodedMessages.size) — каждое новое сообщение в чате инвалидировало
        // ключ и полностью перестраивало ленту оснований: removeAllViews + inflate +
        // ПОВТОРНАЯ загрузка фото-оснований через реле (когда битмап не осел в ImageCache).
        // Эти фоновые файловые запросы делят сокеты реле/Tor с обычным поллингом и
        // оттягивали доставку входящих. Теперь ключ — только состав РЕАЛЬНО НАЙДЕННЫХ
        // оснований (msgId + rawEncrypted, чтобы правка текста-основания обновляла цитату):
        // лента перестраивается лишь когда сами основания изменились, а не на каждое
        // сообщение в чате. Синхронизацию/чужие устройства фикс не касается вообще.
        val key = evidenceMsgs.joinToString(",") { it.msgId + ":" + it.rawEncrypted.hashCode() }
        if (key == lastRenderedEvidenceKey) return
        lastRenderedEvidenceKey = key
        binding.mutedEvidenceSection.visibility = View.VISIBLE
        binding.mutedEvidenceFeed.removeAllViews()
        evidenceMsgs.forEach { addMuteEvidenceBubble(it) }
    }

    /**
     * Один пузырёк ленты сообщений-оснований — текст/фото (по тапу открывается)/голос (по
     * тапу играет). Ветка-переписка (см. PartnerProfileActivity.addEvidenceThreadRow) может
     * включать реплику ДРУГОГО человека, на которую отвечал заглушённый — такие пузырьки
     * рисуются нейтральным (серым) цветом слева + подпись с именем, чтобы не выглядело,
     * будто это сказал сам заглушённый (см. §16-репорт этой сессии). Собственные сообщения
     * заглушённого — без изменений (жёлтый, справа, без подписи).
     */
    private fun addMuteEvidenceBubble(msg: Message) {
        val row = layoutInflater.inflate(R.layout.item_muted_evidence_bubble, binding.mutedEvidenceFeed, false)
        val tvSender = row.findViewById<TextView>(R.id.tv_evidence_bubble_sender)
        val tvText = row.findViewById<TextView>(R.id.tv_evidence_bubble_text)
        val flPhoto = row.findViewById<FrameLayout>(R.id.fl_evidence_bubble_photo)
        val ivPhoto = row.findViewById<ImageView>(R.id.iv_evidence_bubble_photo)
        val llVoice = row.findViewById<LinearLayout>(R.id.ll_evidence_bubble_voice)
        val ivVoicePlay = row.findViewById<ImageView>(R.id.iv_evidence_voice_play)
        val voiceProgress = row.findViewById<View>(R.id.v_evidence_voice_progress)
        val voiceTrackBg = row.findViewById<View>(R.id.v_evidence_voice_track_bg)
        val tvVoiceDur = row.findViewById<TextView>(R.id.tv_evidence_voice_dur)

        val isOwn = msg.senderUserId == null || msg.senderUserId == prefs.myUserId
        if (isOwn) {
            tvSender.visibility = View.GONE
        } else {
            tvSender.visibility = View.VISIBLE
            tvSender.text = msg.sender
        }
        val bubbleBg = if (isOwn) R.drawable.bg_message_muted_evidence else R.drawable.bg_message_muted_evidence_other
        val bubbleTextColor = ContextCompat.getColor(this, if (isOwn) R.color.warning_on else R.color.text_primary)
        val bubbleGravity = if (isOwn) android.view.Gravity.END else android.view.Gravity.START
        listOf<View>(tvText, flPhoto, llVoice).forEach { bubble ->
            bubble.setBackgroundResource(bubbleBg)
            (bubble.layoutParams as? FrameLayout.LayoutParams)?.let { it.gravity = bubbleGravity; bubble.layoutParams = it }
        }
        tvText.setTextColor(bubbleTextColor)
        tvVoiceDur.setTextColor(bubbleTextColor)
        ivVoicePlay.setColorFilter(bubbleTextColor, android.graphics.PorterDuff.Mode.SRC_IN)
        voiceProgress.setBackgroundColor(bubbleTextColor)
        voiceTrackBg.setBackgroundColor(
            ContextCompat.getColor(this, if (isOwn) R.color.warning_on_track_bg else R.color.border)
        )

        when {
            msg.isVoice -> {
                llVoice.visibility = View.VISIBLE
                tvVoiceDur.text = "0:%02d".format(msg.voiceDurationSec.coerceAtLeast(0))
                fun refreshIcon() {
                    ivVoicePlay.setImageResource(if (VoicePlayer.isPlaying(msg.msgId)) R.drawable.ic_pause else R.drawable.ic_play)
                }
                refreshIcon()
                llVoice.setOnClickListener {
                    val ref = msg.voiceFileName ?: return@setOnClickListener
                    val loader = imageLoader ?: return@setOnClickListener
                    lifecycleScope.launch {
                        val dir = File(cacheDir, "voice_play").apply { mkdirs() }
                        val f = File(dir, "v_" + Integer.toHexString(ref.hashCode()) + ".m4a")
                        val file = if (f.exists() && f.length() > 0) f else {
                            val bytes = withContext(Dispatchers.IO) { loader.loadRawBytes(ref) }
                            if (bytes == null) null else {
                                try { f.writeBytes(bytes); f } catch (_: Exception) { null }
                            }
                        }
                        if (file == null) {
                            Toast.makeText(this@ChatActivity, R.string.voice_load_failed, Toast.LENGTH_SHORT).show()
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
            msg.isImage -> {
                flPhoto.visibility = View.VISIBLE
                // Коллаж (несколько фото) — открываем тем же вьювером, что и в обычной ленте,
                // с первого кадра; одиночное фото/старый inline base64 — как раньше.
                val collageRefs = msg.imageFileNames
                if (!collageRefs.isNullOrEmpty()) {
                    flPhoto.setOnClickListener { openImageFullscreenByRef(collageRefs, 0) }
                } else {
                    flPhoto.setOnClickListener { openImageFullscreen(msg) }
                }
                val fileName = collageRefs?.firstOrNull() ?: msg.imageFileName
                val cachedBmp = fileName?.let { ImageCache.getBitmap(it) }
                if (cachedBmp != null) {
                    ivPhoto.setImageBitmap(cachedBmp)
                } else if (fileName != null) {
                    val loader = imageLoader
                    if (loader != null) {
                        lifecycleScope.launch {
                            val bmp = loader.loadBitmap(fileName)
                            if (bmp != null) ivPhoto.setImageBitmap(bmp)
                        }
                    }
                } else if (msg.imageBase64 != null) {
                    lifecycleScope.launch {
                        val bmp = withContext(Dispatchers.Default) { ImageUtils.fromBase64(msg.imageBase64) }
                        if (bmp != null) ivPhoto.setImageBitmap(bmp)
                    }
                }
            }
            else -> {
                tvText.visibility = View.VISIBLE
                tvText.text = msg.text
            }
        }
        binding.mutedEvidenceFeed.addView(row)
    }

    // ====== UI-блокировка во время загрузки изображений ======

    /**
     * Блокирует поле ввода текста и кнопки на время загрузки изображений.
     * Текстовая очередь (MessageSendManager) при этом не останавливается —
     * уже поставленные в очередь сообщения продолжают отправляться в фоне.
     */
    private fun lockForUpload() {
        activeImageUploads.incrementAndGet()
        binding.btnAttach.isEnabled = false
        binding.etMessage.isEnabled = false
        binding.btnSend.isEnabled = false
        binding.etMessage.hint = getString(R.string.image_upload_wait)
    }

    /**
     * Снимает блокировку поля ввода. Восстанавливает кнопки только если
     * нет других активных загрузок и нет активного punishment (rate limit текста).
     */
    private fun unlockAfterUpload() {
        val remaining = activeImageUploads.decrementAndGet()
        binding.btnAttach.isEnabled = (remaining <= 0)
        if (remaining <= 0) {
            if (!sendManager.isPunished()) {
                binding.etMessage.isEnabled = true
                binding.btnSend.isEnabled = true
                binding.etMessage.hint = originalHint
            }
        }
    }

    // ====== ОТПРАВКА КАРТИНКИ ======

    private fun sendImage(uri: Uri) {
        val caption = binding.etMessage.text.toString().trim()
        val now = System.currentTimeMillis()

        // Мгновенно очищаем UI
        binding.etMessage.setText("")
        binding.etMessage.clearFocus()
        clearReply()
        stopTypingSignal()
        lockForUpload()

        lifecycleScope.launch {
            var tempEncrypted: String? = null
            try {
                // 1. Подготовка данных для оптимистичного сообщения
                val base64 = withContext(Dispatchers.IO) {
                    ImageUtils.loadAndCompress(this@ChatActivity, uri)
                } ?: throw RuntimeException(getString(R.string.error_image_load))

                val bitmap = withContext(Dispatchers.Default) { ImageUtils.fromBase64(base64) }
                val imageFileName = Message.newImageFileName()
                ImageCache.put(imageFileName, base64, bitmap)
                ImageCache.markShownConfirmation(imageFileName)

                // 2. Добавляем оптимистичное сообщение в ChatStore
                val plaintext = Message.composePlaintext(
                    senderName = prefs.myName,
                    senderUserId = prefs.myUserId,
                    text = caption,
                    imageFileName = imageFileName,
                    timestampMs = now
                )
                val encryptedMessage = withContext(Dispatchers.Default) {
                    encryptChatLine(plaintext, chat.chatId)
                }
                tempEncrypted = encryptedMessage

                val pendingMsg = Message(
                    sender = prefs.myName,
                    text = caption,
                    isSelf = true,
                    rawEncrypted = encryptedMessage,
                    timestampMs = now,
                    imageFileName = imageFileName,
                    senderUserId = prefs.myUserId,
                    imageUploadIndex = 0,
                    imageUploadPct = 0,
                    isPending = true
                )
                chatStore.addOptimistic(pendingMsg)

                // 3. Контент фото (шифрование + заливка чанков по Tor) — в ФОНЕ. НЕ держим
                //    ввод заблокированным на всё время заливки: иначе при чанковом фото через
                //    медленный Tor сообщение «зависает в обработке». Оптимистичное сообщение
                //    уже показано; часики снимутся при reconcile, как только опубликуется
                //    чат-строка (это происходит в начале appendLine, до заливки чанков).
                lifecycleScope.launch {
                    // ⚠️ ВРЕМЕННАЯ ДИАГНОСТИКА: сторож — ОТДЕЛЬНАЯ корутина в том же
                    // lifecycleScope (НЕ внутри try/catch ниже — иначе её краш тихо
                    // превратился бы в обычный failSend). Если заливка не завершится
                    // (ни успех, ни ошибка) за UPLOAD_HANG_CRASH_MS — роняем с диагностикой:
                    // какой % успел долиться, useTor, сколько реле.
                    var lastPctSeen = 0
                    val uploadWatchdog = lifecycleScope.launch {
                        delay(UPLOAD_HANG_CRASH_MS)
                        // Зависла — переводим в ошибку МЯГКО (часики → крестик), не крашим
                        // приложение (краш при сбое отправки запрещён, см. CLAUDE.md).
                        android.util.Log.e("AtrumUpload",
                            "UPLOAD_HANG file=$imageFileName lastPct=$lastPctSeen " +
                            "useTor=${transport.useTor} relays=${com.atrum.chat.transport.NostrTransport.relayCount()} " +
                            "elapsedMs=$UPLOAD_HANG_CRASH_MS — заливка одиночного фото зависла")
                        chatStore.failSend(encryptedMessage)
                        Toast.makeText(this@ChatActivity,
                            getString(R.string.error_send) + "\n" + getString(R.string.error_upload_timeout),
                            Toast.LENGTH_LONG).show()
                    }
                    try {
                        val encryptedImage = withContext(Dispatchers.Default) {
                            CryptoHelper.encrypt(base64, chat.chatPassword, chat.chatId)
                        }
                        imageUploadQueue.execute {
                            withContext(Dispatchers.IO) {
                                transport.appendLine(
                                    encryptedLine = encryptedMessage,
                                    extraFiles = mapOf(imageFileName to encryptedImage),
                                    onFileProgress = { _, cur, tot ->
                                        val pct = if (tot > 0) (cur * 100 / tot).coerceIn(0, 99) else 0
                                        lastPctSeen = pct
                                        chatStore.updateImageProgress(encryptedMessage, 0, pct)
                                    }
                                )
                            }
                        }
                        uploadWatchdog.cancel()   // заливка завершилась — сторож больше не нужен
                        // Cache-bust и форс-синк для быстрого скрытия часиков
                        lastContent = ""
                        syncEngine.forceSync(delayMs = 0L)
                    } catch (e: Exception) {
                        uploadWatchdog.cancel()   // и при ошибке тоже гасим сторож
                        // Заливка контента не удалась — помечаем сообщение как несработавшее
                        // (часики → ошибка), а не оставляем его «в обработке» навсегда.
                        chatStore.failSend(encryptedMessage)
                        val reason = e.message?.take(120) ?: "unknown"
                        runOnUiThread {
                            Toast.makeText(this@ChatActivity,
                                getString(R.string.error_send) + "\n" + reason,
                                Toast.LENGTH_LONG).show()
                        }
                    }
                }

            } catch (e: Exception) {
                tempEncrypted?.let { chatStore.failSend(it) }
                val reason = e.message?.take(120) ?: "unknown"
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    Toast.LENGTH_LONG).show()
            } finally {
                stickerPanel?.setSendingState(false)
                unlockAfterUpload()
            }
        }
    }

    private fun sendImages(uris: List<Uri>) {
        val caption = binding.etMessage.text.toString().trim()
        val now = System.currentTimeMillis()

        // Мгновенно очищаем UI
        binding.etMessage.setText("")
        binding.etMessage.clearFocus()
        clearReply()
        stopTypingSignal()
        lockForUpload()

        val total = uris.size

        lifecycleScope.launch {
            var tempEncrypted: String? = null
            // ⚠️ ВРЕМЕННАЯ ДИАГНОСТИКА (см. TODO_REMOVE_EMPTY_MEDIA_CRASH): сторож заливки
            // коллажа — та же идея, что и в sendImage(). Хостится ВНЕ try ниже, поэтому её
            // краш не проглатывается общим catch(Exception).
            var uploadWatchdog: kotlinx.coroutines.Job? = null
            var lastPctSeen = 0
            try {
                val imageFileNames = List(total) { Message.newImageFileName() }
                val ratios   = arrayOfNulls<Float>(total)
                val base64s  = arrayOfNulls<String>(total)
                val bitmaps  = arrayOfNulls<android.graphics.Bitmap>(total)

                // 1. Предварительная подготовка для мгновенного оптимистичного UI
                coroutineScope {
                    uris.forEachIndexed { index, uri ->
                        launch {
                            val ar = withContext(Dispatchers.IO) { ImageUtils.getAspectRatio(this@ChatActivity, uri) }
                            val b64 = withContext(Dispatchers.IO) { ImageUtils.loadAndCompress(this@ChatActivity, uri) }
                                ?: throw RuntimeException("Image load failed at index $index")
                            val bm = withContext(Dispatchers.Default) { ImageUtils.fromBase64(b64) }

                            ratios[index]  = ar
                            base64s[index] = b64
                            bitmaps[index] = bm

                            // Кладём в кеш под окончательным именем
                            val fileName = imageFileNames[index]
                            ImageCache.put(fileName, b64, bm)
                            ImageCache.markShownConfirmation(fileName)
                        }
                    }
                }

                val finalRatios = ratios.filterNotNull()
                if (finalRatios.size != total) throw RuntimeException(getString(R.string.error_image_load))

                // 2. Добавляем единое MULTI: сообщение в ChatStore
                val plaintext = Message.composePlaintext(
                    senderName     = prefs.myName,
                    senderUserId   = prefs.myUserId,
                    text           = caption,
                    imageFileNames = imageFileNames,
                    aspectRatios   = finalRatios,
                    timestampMs    = now
                )
                val encryptedMessage = withContext(Dispatchers.Default) {
                    encryptChatLine(plaintext, chat.chatId)
                }
                tempEncrypted = encryptedMessage

                val pendingMsg = Message(
                    sender         = prefs.myName,
                    text           = caption,
                    isSelf         = true,
                    rawEncrypted   = encryptedMessage,
                    timestampMs    = now,
                    imageFileNames = imageFileNames,
                    aspectRatios   = finalRatios,
                    senderUserId   = prefs.myUserId,
                    imageUploadIndex = 0,
                    imageUploadPct = 0,
                    isPending      = true
                )
                chatStore.addOptimistic(pendingMsg)

                // 3. Шифруем все изображения для пакетной отправки
                val extraFiles = mutableMapOf<String, String>()
                coroutineScope {
                    imageFileNames.forEachIndexed { index, fileName ->
                        launch {
                            val b64 = base64s[index] ?: return@launch
                            val encrypted = withContext(Dispatchers.Default) {
                                CryptoHelper.encrypt(b64, chat.chatPassword, chat.chatId)
                            }
                            synchronized(extraFiles) {
                                extraFiles[fileName] = encrypted
                            }
                        }
                    }
                }

                if (extraFiles.size != total) throw RuntimeException(getString(R.string.error_image_load))

                // ⚠️ ФИКС (баг: фото в коллаже грузились в случайном порядке): extraFiles
                // заполнялся ПАРАЛЛЕЛЬНЫМИ корутинами (шифрование каждого фото — гонка),
                // поэтому порядок вставки в map получался случайным — каким успело
                // зашифроваться первым. transport.appendLine() публикует/грузит extraFiles
                // строго В ПОРЯДКЕ ИТЕРАЦИИ мапы, поэтому и реальная заливка на реле, и
                // прыжки кольца прогресса по ячейкам шли вразнобой с видимым порядком фото.
                // Пересобираем map строго в порядке imageFileNames (= порядок коллажа).
                val orderedExtraFiles = LinkedHashMap<String, String>()
                imageFileNames.forEach { name -> extraFiles[name]?.let { orderedExtraFiles[name] = it } }

                // ⚠️ ВРЕМЕННАЯ ДИАГНОСТИКА: сторож на UPLOAD_HANG_CRASH_MS — отдельная
                // корутина в lifecycleScope, не дочерняя для текущего try, чтобы её краш
                // не был проглочен catch(Exception) ниже.
                uploadWatchdog = lifecycleScope.launch {
                    delay(UPLOAD_HANG_CRASH_MS)
                    // Зависла — переводим в ошибку МЯГКО (часики → крестик), не крашим
                    // приложение (краш при сбое отправки запрещён, см. CLAUDE.md).
                    android.util.Log.e("AtrumUpload",
                        "UPLOAD_HANG collage=${imageFileNames.size} lastPct=$lastPctSeen " +
                        "useTor=${transport.useTor} relays=${com.atrum.chat.transport.NostrTransport.relayCount()} " +
                        "elapsedMs=$UPLOAD_HANG_CRASH_MS — заливка коллажа зависла")
                    tempEncrypted?.let { chatStore.failSend(it) }
                    Toast.makeText(this@ChatActivity,
                        getString(R.string.error_send) + "\n" + getString(R.string.error_upload_timeout),
                        Toast.LENGTH_LONG).show()
                }

                // 4. Отправляем сообщение и все изображения ОДНИМ запросом (Batch PATCH)
                imageUploadQueue.execute {
                    withContext(Dispatchers.IO) {
                        transport.appendLine(
                            encryptedLine = encryptedMessage,
                            extraFiles = orderedExtraFiles,
                            onFileProgress = { name, cur, tot ->
                                val idx = imageFileNames.indexOf(name)
                                if (idx >= 0) {
                                    val pct = if (tot > 0) (cur * 100 / tot).coerceIn(0, 99) else 0
                                    lastPctSeen = pct
                                    chatStore.updateImageProgress(encryptedMessage, idx, pct)
                                }
                            }
                        )
                    }
                }
                uploadWatchdog?.cancel()   // заливка завершилась — сторож больше не нужен

                lastContent = ""
                syncEngine.forceSync(delayMs = 0L)

            } catch (e: Exception) {
                uploadWatchdog?.cancel()   // и при ошибке тоже гасим сторож
                tempEncrypted?.let { chatStore.failSend(it) }
                val reason = e.message?.take(120) ?: "unknown"
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    Toast.LENGTH_LONG).show()
            } finally {
                stickerPanel?.setSendingState(false)
                unlockAfterUpload()
            }
        }
    }

    private fun openImageFullscreen(msg: Message) {
        // Inline base64 (старый формат) — используем статический холдер чтобы не
        // передавать многомегабайтную строку через Binder (TransactionTooLargeException).
        msg.imageBase64?.let { data ->
            ImageViewActivity.pendingBase64 = data
            startActivity(Intent(this, ImageViewActivity::class.java))
            return
        }

        // Gist ref (новый формат) — передаём только короткую строку-ссылку через EXTRA_REFS.
        val fileName = msg.imageFileName ?: return

        // Bitmap уже в LruCache — viewer откроется мгновенно
        if (ImageCache.getBitmap(fileName) != null) {
            startActivity(Intent(this, ImageViewActivity::class.java).apply {
                putExtra(ImageViewActivity.EXTRA_REFS, arrayListOf(fileName))
            })
            return
        }

        // Base64 в кеше, но bitmap вытеснен — декодируем в фоне, потом открываем
        val base64 = ImageCache.getBase64(fileName)
        if (base64 != null) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { ImageUtils.fromBase64(base64) }
                if (bitmap != null) ImageCache.put(fileName, base64, bitmap)
                startActivity(Intent(this@ChatActivity, ImageViewActivity::class.java).apply {
                    putExtra(ImageViewActivity.EXTRA_REFS, arrayListOf(fileName))
                })
            }
            return
        }

        // Ничего нет — грузим через ImageLoader (loadBitmap кладёт в кеш сам)
        val loader = imageLoader ?: return
        lifecycleScope.launch {
            val bitmap = loader.loadBitmap(fileName)
            if (bitmap != null) {
                startActivity(Intent(this@ChatActivity, ImageViewActivity::class.java).apply {
                    putExtra(ImageViewActivity.EXTRA_REFS, arrayListOf(fileName))
                })
            } else {
                Toast.makeText(this@ChatActivity, R.string.error_image_load, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Открывает полноэкранный просмотр коллажа начиная с [startIndex].
     * Принимает полный список refs — ViewPager2 позволит листать все изображения.
     * Использует EXTRA_REFS (короткие строки) → нет лимита Binder-транзакции.
     */
    private fun openImageFullscreenByRef(refs: List<String>, startIndex: Int) {
        val ref = refs[startIndex]

        fun openViewer() {
            startActivity(Intent(this, ImageViewActivity::class.java).apply {
                putExtra(ImageViewActivity.EXTRA_REFS, ArrayList(refs))
                putExtra(ImageViewActivity.EXTRA_START_INDEX, startIndex)
            })
        }

        // Bitmap уже в LruCache — viewer откроется мгновенно
        if (ImageCache.getBitmap(ref) != null) { openViewer(); return }

        // Base64 в кеше, bitmap вытеснен — декодируем в фоне
        val base64 = ImageCache.getBase64(ref)
        if (base64 != null) {
            lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { ImageUtils.fromBase64(base64) }
                if (bitmap != null) ImageCache.put(ref, base64, bitmap)
                openViewer()
            }
            return
        }

        // Ничего нет — грузим (loadBitmap кладёт в кеш сам)
        val loader = imageLoader ?: return
        lifecycleScope.launch {
            val bitmap = loader.loadBitmap(ref)
            if (bitmap != null) openViewer()
            else Toast.makeText(this@ChatActivity, R.string.error_image_load, Toast.LENGTH_SHORT).show()
        }
    }

    // ====== REPLY ======

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SCROLL_TO_MSGID)?.let { pendingJumpMsgId = it }
        intent.getStringExtra(EXTRA_DELETE_MSGID)?.let { pendingDeleteMsgId = it }
        if (::adapter.isInitialized) applyPendingMediaActions()
    }

    /**
     * Применяет отложенные действия из списка медиа: переход к сообщению (с подсветкой)
     * и удаление. Вызывается после каждого рендера сообщений и из onNewIntent — поэтому
     * безопасно, если сообщения ещё грузятся (повторится на следующем тике, когда найдём).
     */
    private fun applyPendingMediaActions() {
        if (!::adapter.isInitialized) return
        pendingDeleteMsgId?.let { id ->
            val msg = currentMessages.firstOrNull { it.msgId == id }
            if (msg != null) {
                pendingDeleteMsgId = null
                if (msg.isSelf) performDelete(msg)
                else Toast.makeText(this, R.string.media_delete_only_own, Toast.LENGTH_SHORT).show()
            }
        }
        pendingJumpMsgId?.let { id ->
            val idx = adapter.indexOfMsgId(id)
            if (idx >= 0) {
                pendingJumpMsgId = null
                jumpToAdapterIndex(idx, id)
            }
        }
    }

    /** Скроллит к сообщению и подсвечивает его акцентом на ~2.5 сек. */
    private fun jumpToAdapterIndex(index: Int, msgId: String) {
        (binding.rvMessages.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(index, 100)
        adapter.highlightMessage(msgId)
        lifecycleScope.launch {
            delay(2500)
            if (adapter.highlightedMsgId == msgId) adapter.highlightMessage(null)
        }
    }

    private fun scrollToOriginal(msg: Message) {
        val qText = msg.quotedText ?: return
        val qSender = msg.quotedSender ?: return

        // Ищем сообщение в текущем списке
        val targetIndex = currentMessages.indexOfFirst {
            it.sender == qSender && it.text.take(120) == qText
        }

        if (targetIndex != -1) {
            val targetMsg = currentMessages[targetIndex]
            // Нашли — скроллим
            (binding.rvMessages.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(targetIndex, 100)
            
            // Подсвечиваем сообщение акцентом
            adapter.highlightMessage(targetMsg.msgId)
            
            // Убираем подсветку через 1.5 секунды
            lifecycleScope.launch {
                delay(1500)
                if (adapter.highlightedMsgId == targetMsg.msgId) {
                    adapter.highlightMessage(null)
                }
            }
        } else {
            Toast.makeText(this, "Сообщение не найдено", Toast.LENGTH_SHORT).show()
        }
    }

    /** Текст для цитаты/превью: сам текст, либо метка типа (стикер/фото) если текста нет. */
    private fun quoteLabel(msg: Message): String = when {
        msg.text.isNotBlank()           -> msg.text
        msg.isVoice                     -> getString(R.string.msg_preview_voice)
        msg.isSticker                   -> getString(R.string.msg_preview_sticker)
        msg.isMultiImage || msg.isImage -> getString(R.string.msg_preview_photo)
        else                            -> msg.text
    }

    private fun startReply(msg: Message) {
        replyingTo = msg
        binding.tvReplySender.text = msg.sender.ifBlank { "?" }
        binding.tvReplyText.text = quoteLabel(msg)
        binding.replyPanel.visibility = View.VISIBLE
        binding.etMessage.requestFocus()
    }

    private fun clearReply() {
        replyingTo = null
        binding.replyPanel.visibility = View.GONE
    }

    // ====== МЕНЮ ДЕЙСТВИЙ НАД СООБЩЕНИЕМ ======

    /**
     * true — я админ ЭТОЙ группы (тот же принцип, что PartnerProfileActivity.groupIsAdmin).
     * Используется для пункта «Удалить у всех» на ЧУЖИХ сообщениях в обычном чате (см.
     * showMessageMenu) — протокол это уже поддерживал ДО этой правки: «надгробие»
     * (transport.deleteLine) подтверждается знанием ПАРОЛЯ чата, а не подписью автора
     * (см. NostrMessageStore.verifyCtrl/ctrlToken), так что расширение видимости пункта
     * меню — чисто клиентское изменение, без единой правки формата/протокола.
     */
    private val chatIsAdmin: Boolean
        get() = ::chat.isInitialized && chat.isGroup &&
            // Личная сборка (PERSONAL): все админ-права в любой беседе локально (см.
            // PersonalFeatures/PERSONAL_BUILD.md). В релизе — обычная проверка главного админа.
            (PersonalFeatures.enabled ||
                (!chat.adminUserId.isNullOrBlank() && chat.adminUserId == prefs.myUserId))

    /**
     * Отправитель сообщения — верифицированный разработчик (его сообщения нельзя удалять
     * чужим, PERSONAL_BUILD.md §Часть 3)? Надёжно, через ЕДИНУЮ точку правды: набор адаптера
     * (быстро) ИЛИ VerifiedBadge.isVerifiedDev по свежему профилю + подтверждённой памяти —
     * не зависит от того, успел ли адаптер обновить свой набор к моменту показа меню.
     * Неподделываемо (проверка по identity-подписи).
     */
    private fun isSenderVerifiedDev(msg: Message): Boolean {
        val uid = msg.senderUserId ?: return false
        return adapter.senderIsVerified(msg) ||
            VerifiedBadge.isVerifiedDev(chat.chatId, uid, lastKnownProfiles[uid])
    }

    private fun showMessageMenu(msg: Message, anchor: View) {
        TelegramMenu.show(
            ctx      = this,
            anchor   = anchor,
            items    = buildList {
                add(TelegramMenu.Item(getString(R.string.action_reply),  R.drawable.ic_reply_menu)   { startReply(msg) })
                add(TelegramMenu.Item(getString(R.string.action_copy),   R.drawable.ic_copy_menu)    { copyToClipboard(msg.text) })
                add(TelegramMenu.Item("Выбрать",                          R.drawable.ic_select_mode)  { adapter.enterSelectionMode(msg) })
                // Закрепить/открепить — только в группе и только уполномоченным (Этап 3).
                if (chat.isGroup && !chat.isFavorites && groupCanPin && msg.msgId.isNotBlank()) {
                    val pinned = pinnedIds.contains(msg.msgId)
                    add(TelegramMenu.Item(
                        getString(if (pinned) R.string.action_unpin else R.string.action_pin),
                        R.drawable.ic_pin
                    ) { togglePin(msg) })
                }
                if (msg.isSelf) {
                    add(TelegramMenu.Item(getString(R.string.action_edit),   R.drawable.ic_edit_menu)  { showEditDialog(msg) })
                    add(TelegramMenu.Item(getString(R.string.action_delete), R.drawable.ic_trash_menu, isDestructive = true) { confirmDelete(msg) })
                } else if (chatIsAdmin && !isSenderVerifiedDev(msg)) {
                    // Модерация: админ может удалить ЧУЖОЕ сообщение у всех участников.
                    // ⛔ КРОМЕ сообщений верифицированного разработчика — их удалять нельзя
                    // никому (PERSONAL_BUILD.md §Часть 3). Неподделываемо (VerifiedBadge),
                    // проверка через единую точку правды isSenderVerifiedDev (см. выше).
                    add(TelegramMenu.Item(getString(R.string.action_delete_for_all_admin), R.drawable.ic_trash_menu, isDestructive = true) { confirmDelete(msg) })
                }
            },
            onReaction = { emoji -> handleReactionToggle(msg.msgId, emoji) }
        )
    }

    // ====== ЗАКРЕПЛЁННЫЕ СООБЩЕНИЯ (Этап 3) ======

    /**
     * Перечитывает состояние закреплений из Room (показываемый набор, мои вклады, мои права)
     * и перерисовывает плашку. Вызывается после применения members.txt и после моих действий.
     */
    private suspend fun refreshPinState() {
        if (!::chat.isInitialized || !chat.isGroup) return
        val db = com.atrum.chat.data.AppDatabase.get(this)
        val fresh = withContext(Dispatchers.IO) { db.chatDao().getById(chat.id) } ?: return
        val perms = withContext(Dispatchers.IO) {
            db.chatParticipantDao().getOne(chat.id, prefs.myUserId)?.permissions ?: 0
        }
        pinnedIds = fresh.pinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        myPinnedIds = fresh.myPinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        // Личная сборка (PERSONAL): полный набор прав локально (см. §Часть 2 PERSONAL_BUILD.md).
        myGroupPermissions = if (PersonalFeatures.enabled) AdminPermissions.ALL else perms
        withContext(Dispatchers.Main) { renderPinnedBar() }
    }

    /** Отрисовка плашки закреплённого по [pinnedIds] и [currentPinIndex]. */
    private fun renderPinnedBar() {
        if (!::binding.isInitialized) return
        val ids = pinnedIds
        if (ids.isEmpty() || !chat.isGroup) {
            binding.pinnedBar.visibility = View.GONE
            binding.pinnedBarDivider.visibility = View.GONE
            return
        }
        if (currentPinIndex !in ids.indices) currentPinIndex = 0
        val id = ids[currentPinIndex]
        binding.pinnedBar.visibility = View.VISIBLE
        if (!chatHasWallpaper) binding.pinnedBarDivider.visibility = View.VISIBLE
        binding.tvPinnedLabel.text = if (ids.size > 1)
            getString(R.string.pinned_label_counted, currentPinIndex + 1, ids.size)
        else getString(R.string.pinned_label_single)
        binding.tvPinnedPreview.text = pinPreview(id)
        // Открепить можно только СВОЙ вклад (совместные закрепления, см. MembersSync).
        binding.btnPinnedUnpin.visibility = if (groupCanPin && myPinnedIds.contains(id)) View.VISIBLE else View.GONE
    }

    /** Превью закреплённого сообщения по msgId (из загруженной ленты) или общий фоллбэк. */
    private fun pinPreview(msgId: String): String {
        val m = currentMessages.firstOrNull { it.msgId == msgId } ?: return getString(R.string.pinned_generic)
        val body = when {
            m.isVoice -> getString(R.string.msg_preview_voice)
            m.isImage -> m.text.takeIf { it.isNotBlank() } ?: getString(R.string.msg_preview_photo)
            else -> m.text
        }
        return if (!m.isSelf && m.sender.isNotBlank()) "${m.sender}: $body" else body
    }

    /** Тап по плашке — следующий закреплённый: листаем, прыгаем к нему, подсвечиваем. */
    private fun cyclePinned() {
        if (pinnedIds.isEmpty()) return
        currentPinIndex = (currentPinIndex + 1) % pinnedIds.size
        renderPinnedBar()
        val id = pinnedIds[currentPinIndex]
        val idx = if (::adapter.isInitialized) adapter.indexOfMsgId(id) else -1
        if (idx >= 0) jumpToAdapterIndex(idx, id)
    }

    /** Открепить текущий (только свой вклад). */
    private fun unpinCurrent() {
        val id = pinnedIds.getOrNull(currentPinIndex) ?: return
        if (!groupCanPin || !myPinnedIds.contains(id)) return
        setPinned(id, pin = false)
    }

    /** Закрепить/открепить сообщение из меню (гейтинг — вызывающий). */
    private fun togglePin(msg: Message) {
        if (!groupCanPin || msg.msgId.isBlank()) return
        val pin = !pinnedIds.contains(msg.msgId)
        setPinned(msg.msgId, pin)
    }

    /**
     * Применяет закрепление/открепление: мгновенно правит Room (мои вклады + показываемое,
     * §1.5) и ставит публикацию моего слота (PublishScheduler). Открепление снимает только
     * МОЙ вклад; если сообщение закрепил ещё кто-то, следующий синк вернёт его — это ожидаемо
     * для совместных закреплений (см. MembersSync.mergeSlots).
     */
    private fun setPinned(msgId: String, pin: Boolean) {
        lifecycleScope.launch {
            val db = com.atrum.chat.data.AppDatabase.get(this@ChatActivity)
            withContext(Dispatchers.IO) {
                val fresh = db.chatDao().getById(chat.id) ?: return@withContext
                val mine = (fresh.myPinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
                val shown = (fresh.pinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
                if (pin) {
                    if (!mine.contains(msgId)) mine.add(msgId)
                    if (!shown.contains(msgId)) shown.add(msgId)
                } else {
                    mine.remove(msgId)
                    shown.remove(msgId) // оптимистично; чужой пин вернётся синком
                }
                db.chatDao().updateMyPinnedMsgIds(chat.id, mine.joinToString(","))
                db.chatDao().updatePinnedMsgIds(chat.id, shown.joinToString(","))
            }
            PublishScheduler.markMembersDirty(applicationContext, chat.chatId)
            Toast.makeText(
                this@ChatActivity,
                getString(if (pin) R.string.pinned_done else R.string.unpinned_done),
                Toast.LENGTH_SHORT
            ).show()
            refreshPinState()
        }
    }

    /**
     * Обнаруживает правки закреплённых сообщений между старой и новой лентой и перемапливает
     * закреп. Правка сохраняет senderUserId+timestampMs, но меняет msgId (он из шифртекста):
     * если закреплённый id пропал, а сообщение с тем же автором и временем теперь имеет другой
     * msgId — это и есть правка. Покрывает и локальную правку, и правку от другого участника,
     * которую я вижу как зритель.
     */
    private fun reconcilePinsAfterEdit(oldList: List<Message>, newList: List<Message>) {
        val pins = (pinnedIds + myPinnedIds).distinct()
        if (pins.isEmpty()) return
        for (pid in pins) {
            val oldMsg = oldList.firstOrNull { it.msgId == pid } ?: continue
            // Уже есть в новой ленте под тем же id — правки не было.
            if (newList.any { it.msgId == pid }) continue
            val newMsg = newList.firstOrNull {
                it.senderUserId == oldMsg.senderUserId && it.timestampMs == oldMsg.timestampMs
            } ?: continue
            if (newMsg.msgId != pid && newMsg.msgId.isNotBlank()) {
                lifecycleScope.launch { remapPinnedMsgId(pid, newMsg.msgId) }
            }
        }
    }

    /**
     * Перемап закрепа при правке сообщения (закреп следует за текстом, как в ТГ). msgId
     * привязан к шифртексту, поэтому правка меняет id — заменяем старый id на новый в Room
     * (мой вклад + показываемое) и переопубликовываем свой слот, если старый id был в нём,
     * чтобы у остальных участников закреп тоже обновился. Плашка перерисовывается на месте
     * (§1.5). Ничего не делаем, если старого id в закрепах нет.
     */
    private suspend fun remapPinnedMsgId(oldId: String, newId: String) {
        if (!::chat.isInitialized || !chat.isGroup || oldId == newId || oldId.isBlank() || newId.isBlank()) return
        val db = com.atrum.chat.data.AppDatabase.get(this@ChatActivity)
        var republishMine = false
        withContext(Dispatchers.IO) {
            val fresh = db.chatDao().getById(chat.id) ?: return@withContext
            val mine = (fresh.myPinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
            val shown = (fresh.pinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
            var dirty = false
            val mi = mine.indexOf(oldId)
            if (mi >= 0) {
                if (mine.contains(newId)) mine.removeAt(mi) else mine[mi] = newId
                republishMine = true; dirty = true
            }
            val si = shown.indexOf(oldId)
            if (si >= 0) {
                if (shown.contains(newId)) shown.removeAt(si) else shown[si] = newId
                dirty = true
            }
            if (dirty) {
                db.chatDao().updateMyPinnedMsgIds(chat.id, mine.joinToString(","))
                db.chatDao().updatePinnedMsgIds(chat.id, shown.joinToString(","))
            }
        }
        // Переопубликовываем слот только если менялся МОЙ вклад — иначе чужие закрепы не трогаем.
        if (republishMine) PublishScheduler.markMembersDirty(applicationContext, chat.chatId)
        refreshPinState()
    }

    /** Список всех закреплённых — прыжок к выбранному (bottom-меню в стиле Neon). */
    private fun showPinnedListSheet() {
        if (pinnedIds.isEmpty()) return
        val items = pinnedIds.mapIndexed { i, id ->
            NeonDialog.Item(label = "${i + 1}. ${pinPreview(id)}") {
                currentPinIndex = i
                renderPinnedBar()
                val idx = if (::adapter.isInitialized) adapter.indexOfMsgId(id) else -1
                if (idx >= 0) jumpToAdapterIndex(idx, id)
            }
        }
        NeonDialog.showMenu(this, title = getString(R.string.pinned_show_all), items = items)
    }

    // ====== УПОМИНАНИЯ (@) ======

    /** Настройка ленты упоминаний (вариант 2) и кнопки перехода к упоминаниям. */
    private fun setupMentionStrip() {
        mentionAdapter = MentionAdapter { user -> insertMention(user) }
        binding.rvMentionStrip.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvMentionStrip.adapter = mentionAdapter
        binding.btnMentionJump.setOnClickListener { jumpToNextMention() }
        binding.btnMentionJump.setOnLongClickListener { showMentionMenu(); true }
    }

    /** @упоминания меня в загруженной ленте (от других), к которым я ещё не переходил.
     *  Считаем прямо из ленты — надёжно, без зависимости от таймингов фонового скана. */
    private fun myMentionMessages(): List<Message> {
        if (!::chat.isInitialized || !chat.isGroup) return emptyList()
        return currentMessages.filter {
            !it.isSelf && it.msgId.isNotBlank() && it.msgId !in visitedMentionIds &&
                MentionUtil.mentionsMe(it.text, prefs.myTag, prefs.myName)
        }
    }

    /** Показ/скрытие кнопки @ по упоминаниям меня в ленте. */
    private fun renderMentionButton() {
        if (!::binding.isInitialized) return
        if (!MentionUtil.ENABLED) { binding.btnMentionJump.visibility = View.GONE; return }
        val cnt = myMentionMessages().size
        if (cnt == 0) { binding.btnMentionJump.visibility = View.GONE; return }
        // §0 glass: поверх обоев — полупрозрачная тёмная подложка, иначе surface.
        binding.btnMentionJump.setBackgroundResource(
            if (chatHasWallpaper) R.drawable.bg_mention_fab_glass else R.drawable.bg_mention_fab
        )
        binding.tvMentionJumpCount.text = if (cnt > 9) "9+" else cnt.toString()
        binding.btnMentionJump.visibility = View.VISIBLE
    }

    /** Прыжок к следующему (самому старому непосещённому) упоминанию + подсветка. */
    private fun jumpToNextMention() {
        val next = myMentionMessages().minByOrNull { it.timestampMs } ?: run { renderMentionButton(); return }
        val idx = if (::adapter.isInitialized) adapter.indexOfMsgId(next.msgId) else -1
        if (idx >= 0) jumpToAdapterIndex(idx, next.msgId)
        visitedMentionIds.add(next.msgId)
        renderMentionButton()
    }

    /** Меню по зажатию: список упоминаний меня (авка + ник + текст). */
    private fun showMentionMenu() {
        mentionMenuPopup?.dismiss()
        val items = myMentionMessages().sortedByDescending { it.timestampMs } // новые сверху
        if (items.isEmpty()) return
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(if (chatHasWallpaper) R.drawable.bg_mention_menu_glass else R.drawable.bg_mention_menu)
            setPadding(0, dp(6), 0, dp(6))
        }
        val overW = chatHasWallpaper // поверх обоев/glass — текст белый (иначе тёмный на тёмном в светлой теме)
        val nameCol = if (overW) android.graphics.Color.WHITE else ContextCompat.getColor(this, R.color.text_primary)
        val textCol = if (overW) 0xE0FFFFFF.toInt() else ContextCompat.getColor(this, R.color.text_secondary)
        container.addView(TextView(this).apply {
            text = getString(R.string.mention_menu_title)
            setTextColor(if (overW) 0xB3FFFFFF.toInt() else ContextCompat.getColor(this@ChatActivity, R.color.text_tertiary))
            textSize = 11f; setPadding(dp(12), dp(4), dp(12), dp(6)); letterSpacing = 0.04f
        })
        items.take(6).forEach { m ->
            val row = layoutInflater.inflate(R.layout.item_mention_menu, container, false)
            val av = row.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.iv_mm_avatar)
            val letter = row.findViewById<TextView>(R.id.tv_mm_letter)
            val nameTv = row.findViewById<TextView>(R.id.tv_mm_name)
            val textTv = row.findViewById<TextView>(R.id.tv_mm_text)
            val prof = m.senderUserId?.let { lastKnownProfiles[it] ?: ProfileSync.getGlobalKnown(it) }
            val bmp = AvatarUtils.fromBase64(prof?.avatarBase64)
            if (bmp != null) { av.setImageBitmap(bmp); av.visibility = View.VISIBLE; letter.visibility = View.GONE }
            else { av.visibility = View.GONE; letter.visibility = View.VISIBLE; letter.text = m.sender.trim().firstOrNull()?.uppercase() ?: "?" }
            nameTv.text = m.sender
            nameTv.setTextColor(nameCol)
            textTv.setTextColor(textCol)
            textTv.text = MessageAdapter.highlightMentions(
                m.text.take(60), ContextCompat.getColor(this, R.color.accent_light))
            row.setOnClickListener {
                mentionMenuPopup?.dismiss()
                val idx = if (::adapter.isInitialized) adapter.indexOfMsgId(m.msgId) else -1
                if (idx >= 0) jumpToAdapterIndex(idx, m.msgId)
                visitedMentionIds.add(m.msgId)
                renderMentionButton()
            }
            container.addView(row)
        }
        val popup = android.widget.PopupWindow(container, dp(240), android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = dp(8).toFloat()
        mentionMenuPopup = popup
        // Над кнопкой, выровнено по правому краю.
        popup.showAsDropDown(binding.btnMentionJump, dp(-204), -(dp(56) + binding.btnMentionJump.height))
    }

    /** Пересобрать кандидатов упоминания из профилей (кроме себя). */
    private fun rebuildMentionCandidates(source: Map<String, Profile>) {
        if (!::chat.isInitialized || !chat.isGroup) { mentionCandidates = emptyList(); return }
        mentionCandidates = source.values
            .filter { it.userId != prefs.myUserId && (it.name.isNotBlank() || !it.tag.isNullOrBlank()) }
            .map { MentionUser(it.userId, it.name.ifBlank { it.userId.take(8) }, it.tag, it.avatarBase64) }
    }

    /** (индекс '@', запрос-после-@) у каретки или null, если не пишем упоминание. */
    private fun currentMentionQuery(): Pair<Int, String>? {
        if (!::chat.isInitialized || !chat.isGroup || chat.isFavorites) return null
        val caret = binding.etMessage.selectionStart.coerceAtLeast(0)
        val text = binding.etMessage.text?.toString() ?: return null
        if (caret > text.length) return null
        val before = text.substring(0, caret)
        // '@' в начале строки или после пробела/переноса, затем буквы/цифры/подчёркивание.
        val m = Regex("(?:^|\\s)@([\\p{L}0-9_]*)$").find(before) ?: return null
        val at = before.length - m.groupValues[1].length - 1
        return at to m.groupValues[1]
    }

    /** Обновляет ленту упоминаний по текущему @-запросу. */
    private fun updateMentionStrip() {
        val q = currentMentionQuery()
        if (q == null) { hideMentionStrip(); return }
        val query = q.second.lowercase()
        val list = mentionCandidates.filter { u ->
            query.isEmpty() || (u.tag?.lowercase()?.startsWith(query) == true) || u.name.lowercase().startsWith(query)
        }.take(30)
        if (list.isEmpty()) { hideMentionStrip(); return }
        mentionAdapter?.submit(list)
        // §0 три темы: поверх обоев — полупрозрачная тёмная подложка, иначе surface.
        if (chatHasWallpaper) {
            binding.rvMentionStrip.setBackgroundColor(0x99000000.toInt())
            binding.mentionStripDivider.visibility = View.GONE
        } else {
            binding.rvMentionStrip.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))
            binding.mentionStripDivider.visibility = View.VISIBLE
        }
        binding.rvMentionStrip.visibility = View.VISIBLE
    }

    private fun hideMentionStrip() {
        binding.rvMentionStrip.visibility = View.GONE
        binding.mentionStripDivider.visibility = View.GONE
    }

    /** Вставляет упоминание в поле: заменяет @запрос на «@тег » с подсветкой. */
    private fun insertMention(user: MentionUser) {
        val q = currentMentionQuery() ?: return
        val editable = binding.etMessage.text ?: return
        val caret = binding.etMessage.selectionStart.coerceIn(0, editable.length)
        if (q.first < 0 || q.first > caret) { hideMentionStrip(); return }
        val handle = user.tag?.takeIf { it.isNotBlank() } ?: user.name
        val insert = "@$handle "
        editable.replace(q.first, caret, insert)
        // Подсветка вставленного «@тег» (без хвостового пробела).
        runCatching {
            editable.setSpan(
                android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.accent_light)),
                q.first, q.first + insert.length - 1,
                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.etMessage.setSelection((q.first + insert.length).coerceAtMost(editable.length))
        hideMentionStrip()
    }

    private inner class MentionAdapter(
        private val onClick: (MentionUser) -> Unit
    ) : RecyclerView.Adapter<MentionAdapter.VH>() {
        private var items: List<MentionUser> = emptyList()
        fun submit(list: List<MentionUser>) { items = list; notifyDataSetChanged() }
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_mention_strip, parent, false))
        override fun getItemCount(): Int = items.size
        override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            private val avatar: com.google.android.material.imageview.ShapeableImageView = v.findViewById(R.id.iv_mention_avatar)
            private val letter: TextView = v.findViewById(R.id.tv_mention_letter)
            private val nameTv: TextView = v.findViewById(R.id.tv_mention_name)
            private val tagTv: TextView = v.findViewById(R.id.tv_mention_tag)
            fun bind(u: MentionUser) {
                val bmp = AvatarUtils.fromBase64(u.avatarBase64)
                if (bmp != null) { avatar.setImageBitmap(bmp); avatar.visibility = View.VISIBLE; letter.visibility = View.GONE }
                else { avatar.visibility = View.GONE; letter.visibility = View.VISIBLE; letter.text = u.name.trim().firstOrNull()?.uppercase() ?: "?" }
                nameTv.text = u.name
                tagTv.text = "@" + (u.tag?.takeIf { it.isNotBlank() } ?: u.name)
                itemView.setOnClickListener { onClick(u) }
            }
        }
    }

    // ====== РЕАКЦИИ ======

    /**
     * Разбирает содержимое reactions.txt в карту msgId → emoji → Set<userId>.
     * Формат строки: "msgId|emoji|userId" (plaintext, одна реакция на строку).
     */
    private fun parseReactions(content: String): Map<String, Map<String, Set<String>>> {
        if (content.isBlank()) return emptyMap()
        val result = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        content.split("\n").forEach { line ->
            val parts = line.trim().split("|")
            if (parts.size == 3) {
                val (msgId, emoji, userId) = parts
                if (msgId.isNotBlank() && emoji.isNotBlank() && userId.isNotBlank()) {
                    result.getOrPut(msgId) { mutableMapOf() }
                        .getOrPut(emoji) { mutableSetOf() }
                        .add(userId)
                }
            }
        }
        return result
    }

    /**
     * Обрабатывает нажатие на реакцию (из TelegramMenu или из чипа под сообщением).
     * Оптимистично обновляет UI сразу, затем сохраняет на сервер.
     * После записи — сбрасывает кеш реакций чтобы следующий poll принёс реальное состояние.
     */
    private fun handleReactionToggle(msgId: String, emoji: String) {
        // Заглушённый — только чтение (запрос пользователя), реакции тоже запрещены.
        if (isSelfMuted) return
        val userId = prefs.myUserId
        if (userId.isBlank() || msgId.isBlank()) return

        // ── Вычисляем новое состояние reactions.txt из локального кэша ────────
        // Формат строки: "msgId|emoji|userId". Кэш обновляется из unified poll'а,
        // максимальное устаревание = POLL_INTERVAL_MS (8 сек) — приемлемо для реакций.
        val toggleLine = "$msgId|$emoji|$userId"
        val lines = lastReactionsContent
            .split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val wasPresent = lines.remove(toggleLine)
        if (!wasPresent) lines.add(toggleLine)
        val newReactionsContent = lines.joinToString("\n").ifBlank { "\n" }

        // ── Оптимистичное обновление UI (мгновенно) ───────────────────────────
        currentReactions     = parseReactions(newReactionsContent)
        lastReactionsContent = newReactionsContent
        adapter.setReactions(currentReactions, userId)

        // ── Шифруем перед записью (V4/Argon2id, как profiles.txt) ─────────────
        val encryptedReactions = CryptoHelper.encryptMetadata(
            newReactionsContent, chat.chatPassword, chat.chatId
        )
        lastReactionsRaw = encryptedReactions   // оптимистично обновляем raw-кэш

        // ── Write-only PATCH через PatchQueue: 0 GET + 1 PATCH ───────────────
        // PatchQueue сериализует с отправкой сообщений; debounce 350ms объединяет
        // несколько реакций за один тик в одном PATCH. При ошибке — следующий poll
        // восстановит реальное состояние (lastReactionsContent сбросится в reconcile).
        patchQueue.enqueue(PatchQueue.Action.SaveFile(
            name    = "reactions.txt",
            content = encryptedReactions,
            onDone  = {
                if (chat.isFavorites) {
                    // В локальном чате не используем SyncEngine, поэтому
                    // после сохранения реакции нужно вручную дёрнуть обновление.
                    lifecycleScope.launch {
                        val data = withContext(Dispatchers.IO) { transport.loadAll() }
                        processChannelData(data)
                    }
                } else {
                    // После успешного PATCH — быстро подтверждаем реальное состояние с сервера.
                    // Аналог forceSync после отправки сообщения: обнаруживает конкурентные реакции
                    // партнёра и ошибки записи быстрее обычного 5-секундного тика.
                    syncEngine.forceSync(delayMs = 1_500L)
                }
            }
        ))
    }

    private fun showEditDialog(msg: Message) {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.dialog_edit_title),
            initialText = msg.text,
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel)
        ) { newText ->
            if (newText.isNotEmpty() && newText != msg.text) performEdit(msg, newText)
        }
    }

    private fun performEdit(msg: Message, newText: String) {
        // При редактировании сохраняем оригинальный timestamp и reply-метаданные
        val plaintext = Message.composePlaintext(
            senderName = prefs.myName,
            senderUserId = prefs.myUserId,
            text = newText,
            quotedSender = msg.quotedSender,
            quotedText = msg.quotedText,
            timestampMs = msg.timestampMs
        )
        // Argon2id (V5-фоллбэк до рукопожатия в 1:1) может быть тяжёлым — считаем ВНЕ
        // главного потока (см. encryptChatLine), как и в sendMessage/sendVoice/sendImage.
        lifecycleScope.launch {
            val newEncrypted = withContext(Dispatchers.Default) {
                encryptChatLine(plaintext, chat.chatId)
            }

            // Создаем "оптимистичную" версию сообщения для немедленного обновления в UI
            val pendingEdit = msg.copy(
                text = newText,
                rawEncrypted = newEncrypted,
                isPending = true,
                replacingId = msg.msgId
            )
            chatStore.addOptimistic(pendingEdit)
            // Мгновенно снимаем "часы" (как у обычной отправки, §1.5): НЕ ждём сетевой
            // round-trip через Tor + кворум реле, иначе правка висит "в ожидании" секундами.
            // reconcile позже бесшовно заменит pending серверной строкой (совпадение rawEncrypted).
            chatStore.confirmSent(newEncrypted)

            // Закреп следует за правкой (как в ТГ): msgId выводится из шифртекста
            // (Message.msgId = rawEncrypted.take(40)), поэтому при правке он меняется. Если
            // это сообщение закреплено — перемапливаем закреп со старого id на новый и
            // переопубликовываем свой слот, чтобы плашка показала новый текст, а не «потеряла»
            // сообщение. Работает и для старых бесед (§17): те же поля Room, тот же общий слой.
            val newMsgId = newEncrypted.take(40)
            if (chat.isGroup && (pinnedIds.contains(msg.msgId) || myPinnedIds.contains(msg.msgId))) {
                remapPinnedMsgId(msg.msgId, newMsgId)
            }

            // PatchQueue.ReplaceLine: сериализуется с остальными PATCH-операциями. Сеть — в фоне.
            patchQueue.enqueue(PatchQueue.Action.ReplaceLine(
                oldLine  = msg.rawEncrypted,
                newLine  = newEncrypted,
                onResult = { ok ->
                    if (!ok) {
                        // Ошибка отправки: откатываем правку — вернётся оригинал.
                        chatStore.dropPending(newEncrypted)
                        Toast.makeText(this@ChatActivity,
                            getString(R.string.error_message_not_found), Toast.LENGTH_SHORT).show()
                    }
                    // Сбрасываем кэш для SyncEngine
                    lastContent = ""
                }
            ))
        }
    }

    private fun confirmDelete(msg: Message) {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.dialog_delete_title),
            message = getString(R.string.dialog_delete_message),
            positiveText = getString(R.string.action_delete),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) { performDelete(msg) }
    }

    private fun performDelete(msg: Message) {
        // ⛔ Сообщение верифицированного разработчика нельзя удалить чужим (PERSONAL_BUILD.md
        // §Часть 3). Своё (isSelf) — можно. Дублёр к скрытию пункта меню в showMessageMenu;
        // тихий возврат, чтобы не раскрывать механику. Неподделываемо (VerifiedBadge).
        if (!msg.isSelf && isSenderVerifiedDev(msg)) return
        // ── Оптимистичное удаление через ChatStore tombstone ──────────────────
        // addTombstone() мгновенно убирает сообщение из UI. Tombstone гарантирует
        // что сообщение не "воскреснет" при CDN eventual consistency (GitHub может
        // вернуть старый контент через 1-3 сек после PATCH).
        chatStore.addTombstone(msg.msgId)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { transport.deleteLine(msg.rawEncrypted) }
                // Успех: tombstone останется до reconcile(), который его очистит
                // когда увидит что msgId больше нет в ответе сервера.
            } catch (e: Exception) {
                // Откатываем: сообщение возвращается в UI
                chatStore.removeTombstone(msg.msgId)
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_delete), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Selection bar ─────────────────────────────────────────────────────────

    /**
     * Показывает/скрывает selection bar и обновляет счётчик.
     * Вызывается из adapter.onSelectionChanged.
     */
    private fun updateSelectionBar(selected: Set<String>) {
        if (selected.isEmpty() && !adapter.isSelectionMode) {
            // Exit selection mode — show input, hide selection bar.
            // ⚠️ Не показывать строку ввода, если сейчас действует мут (см.
            // applySelfMuteState) или это read-only чат «Уведомлений» — иначе выход из
            // режима выделения молча возвращал бы ввод там, где его быть не должно.
            if (!isSelfMuted && !chat.isSystemNotifications) binding.inputArea.visibility = View.VISIBLE
            binding.selectionBar.visibility = View.GONE
        } else {
            // Selection mode active
            binding.inputArea.visibility    = View.GONE
            binding.selectionBar.visibility = View.VISIBLE
            val n = selected.size
            binding.tvSelectionCount.text = when {
                n == 0    -> "Выберите сообщения"
                n == 1    -> "1 сообщение"
                n in 2..4 -> "$n сообщения"
                else      -> "$n сообщений"
            }
            // Delete only available for own messages
            val allOwn = currentMessages
                .filter { selected.contains(it.msgId) }
                .all { it.isSelf }
            binding.btnSelectionDelete.alpha = if (allOwn) 1f else 0.3f
            binding.btnSelectionDelete.isEnabled = allOwn
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("message", text))
        Toast.makeText(this, R.string.msg_copied, Toast.LENGTH_SHORT).show()
    }

    // ── Warning banner ────────────────────────────────────────────────────────

    /**
     * Инкрементирует consecutiveFailures с дедупликацией concurrent корутин.
     *
     * Несколько одновременных loadMessages (из polling-цикла и из onMessageSent)
     * могут упасть из-за ОДНОГО сетевого сбоя. Без дедупликации каждая корутина
     * инкрементирует счётчик отдельно → FAILURES_BEFORE_WARNING достигается мгновенно
     * из-за одной кратковременной ошибки → ложный баннер «нет интернета».
     *
     * Решение: два падения в пределах 2 сек считаются одним событием ошибки.
     */
    private fun recordLoadFailure() {
        val now = System.currentTimeMillis()
        if (now - lastFailureEventMs > 2_000L) {
            consecutiveFailures++
            lastFailureEventMs = now
        }
    }

    /**
     * Показывает жёлтый баннер-предупреждение с плавной анимацией.
     * Если баннер уже показан с тем же типом — не перезапускает анимацию.
     * Не критичный — объясняет ситуацию и даёт рекомендацию.
     */
    /**
     * Наблюдение за Tor в Tor-чате: показываем жёлтую плашку, если Tor упал (FAILED)
     * или не забутстрапился за 20с; прячем при READY. collectLatest отменяет ожидание
     * при смене статуса (например, стал READY раньше 20с).
     */
    private fun observeTorStatus() {
        if (chat.isFavorites) return
        if (chat.transportToken == com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN) return
        lifecycleScope.launch {
            TorManager.status.collectLatest { st ->
                when (st) {
                    TorManager.TorStatus.READY ->
                        if (activeWarning == WarningType.TOR) hideChatWarning()
                    TorManager.TorStatus.FAILED ->
                        showChatWarning(WarningType.TOR)
                    else -> {
                        delay(20_000L)
                        if (TorManager.status.value != TorManager.TorStatus.READY) {
                            showChatWarning(WarningType.TOR)
                        }
                    }
                }
            }
        }
    }

    /** Кладёт текст ошибки в буфер обмена (чтобы пользователь мог прислать). */
    private fun copyErrorToClipboard(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("atrum_error", text))
        } catch (_: Exception) {}
    }

    /** Плашка «анимация стикеров выключена при низком заряде» — показ/скрытие по состоянию. */
    private fun updateStickerWarning() {
        if (BatteryUtils.isLow(this) && !BatteryUtils.animateSessionOverride && !BatteryUtils.animatePersistOverride) {
            if (activeWarning == null || activeWarning == WarningType.STICKER_ANIM)
                showChatWarning(WarningType.STICKER_ANIM)
        } else if (activeWarning == WarningType.STICKER_ANIM) {
            hideChatWarning()
        }
    }

    private fun showChatWarning(type: WarningType) {
        if (activeWarning == type) return   // уже показан — не мелькаем
        activeWarning = type

        val (title, message) = when (type) {
            WarningType.TOKEN           -> getString(R.string.warn_token_title)      to getString(R.string.warn_token_message)
            WarningType.RATE_LIMIT      -> getString(R.string.warn_rate_limit_title) to getString(R.string.warn_rate_limit_message)
            WarningType.NETWORK         -> getString(R.string.warn_network_title)    to getString(R.string.warn_network_message)
            WarningType.FORWARD_SECRECY -> getString(R.string.warn_fs_title)        to getString(R.string.warn_fs_message)
            WarningType.TOR             -> getString(R.string.warn_tor_title)       to getString(R.string.warn_tor_message)
            WarningType.STICKER_ANIM    -> getString(R.string.warn_sticker_title)   to getString(R.string.warn_sticker_message)
            WarningType.GROUP_PENDING   -> getString(R.string.warn_group_pending_title) to getString(R.string.warn_group_pending_message)
        }
        binding.tvWarningTitle.text = title
        binding.tvWarningMessage.text = message
        // Любую ошибку (кроме информационного FS) сразу кладём в буфер обмена — чтобы прислать.
        if (type != WarningType.FORWARD_SECRECY && type != WarningType.STICKER_ANIM) copyErrorToClipboard("$title — $message")

        // Для TOKEN — показываем кнопку «Обновить токен»; для остальных — скрываем.
        binding.warningRemember.visibility = View.GONE
        binding.tvWarningAction.setTextColor(ContextCompat.getColor(this, R.color.accent))
        binding.tvWarningAction.setBackgroundResource(R.drawable.bg_button_outline_neon)
        when (type) {
            WarningType.TOKEN -> {
                binding.tvWarningAction.text = getString(R.string.btn_update_token)
                binding.tvWarningAction.visibility = View.VISIBLE
                binding.tvWarningAction.setOnClickListener { showUpdateTokenDialog() }
            }
            WarningType.TOR -> {
                binding.tvWarningAction.text = getString(R.string.btn_tor_retry)
                binding.tvWarningAction.visibility = View.VISIBLE
                binding.tvWarningAction.setOnClickListener {
                    TorManager.start(this)
                    forceHideChatWarning()
                }
            }
            WarningType.STICKER_ANIM -> {
                binding.tvWarningAction.text = getString(R.string.btn_enable_anim)
                binding.tvWarningAction.visibility = View.VISIBLE
                binding.tvWarningAction.setTextColor(ContextCompat.getColor(this, R.color.warning))
                binding.tvWarningAction.setBackgroundResource(R.drawable.bg_button_warning_outline)
                // Чип «Запомнить» — тап переключает контур ↔ жёлтая заливка с галкой.
                rememberChecked = false
                binding.warningRemember.visibility = View.VISIBLE
                binding.warningRemember.setBackgroundResource(R.drawable.bg_remember_chip_off)
                binding.warningRememberCheck.visibility = View.GONE
                binding.warningRemember.setOnClickListener {
                    rememberChecked = !rememberChecked
                    binding.warningRemember.setBackgroundResource(
                        if (rememberChecked) R.drawable.bg_remember_chip_on else R.drawable.bg_remember_chip_off)
                    binding.warningRememberCheck.visibility = if (rememberChecked) View.VISIBLE else View.GONE
                }
                binding.tvWarningAction.setOnClickListener {
                    BatteryUtils.animateSessionOverride = true
                    if (rememberChecked) {
                        prefs.lowBattAnimate = true
                        BatteryUtils.animatePersistOverride = true
                    }
                    adapter.notifyDataSetChanged()   // стикеры снова анимируются
                    forceHideChatWarning()
                }
            }
            else -> binding.tvWarningAction.visibility = View.GONE
        }

        if (binding.warningBanner.visibility == View.VISIBLE) {
            // Баннер уже видим (другой тип) — просто обновляем текст без анимации
            return
        }

        // Анимация: появляется сверху вниз + fade-in
        binding.warningBanner.alpha = 0f
        binding.warningBanner.translationY = -48f   // чуть выше, в пикселях (независимо от dp)
        binding.warningBanner.visibility = View.VISIBLE
        binding.warningBanner.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(380)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Принудительно скрывает баннер — для явных действий пользователя (обновить токен).
     */
    private fun forceHideChatWarning() {
        activeWarning = null
        consecutiveFailures = 0
        lastFailureEventMs = 0L
        binding.warningBanner.animate()
            .alpha(0f)
            .translationY(-48f)
            .setDuration(280)
            .withEndAction {
                binding.warningBanner.visibility = View.GONE
                binding.warningBanner.translationY = 0f
                binding.warningBanner.setOnClickListener(null)
            }
            .start()
    }

    /**
     * Фоновое разрешение транспорта: после быстрого старта на gistDirect() проверяем
     * реальную доступность через TransportFactory.get() и при необходимости переключаемся
     * (Gist -> Nostr), обновляя ImageLoader. При ошибке остаёмся на начальном GistTransport.
     *
     * Был утрачен при рефакторинге v2.6.5 (вызовы остались, определение пропало) —
     * восстановлен из истории; badge подзаголовка намеренно не возвращён, т.к. subtitle
     * теперь управляется отдельной логикой (partnerTag / статус).
     */
    private suspend fun resolveTransport() {
        try {
            val resolved = transportFactory.get()
            val switched = resolved::class != transport::class
            transport = resolved
            if (switched) {
                val newLoader = ImageLoader(transport, chat.chatPassword, chat.chatId)
                imageLoader = newLoader
                if (::adapter.isInitialized) adapter.updateImageLoader(newLoader)
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Открывает диалог ввода нового GitHub-токена.
     *
     * Сохраняет токен в Room DB, пересоздаёт TransportFactory/transport без
     * перезапуска Activity — пользователь продолжает чат с обновлённым токеном.
     */
    private fun showUpdateTokenDialog() {
        NeonDialog.showEdit(
            ctx = this,
            title = getString(R.string.dialog_update_token_title),
            initialText = "",
            positiveText = getString(R.string.btn_save),
            negativeText = getString(R.string.btn_cancel)
        ) { newToken ->
            val trimmed = newToken.trim()
            if (trimmed.isBlank()) return@showEdit
            lifecycleScope.launch {
                // Сохраняем в EncryptedSharedPreferences (не в Room)
                @Suppress("DEPRECATION")
                prefs.saveChatSecrets(chat.chatId, trimmed, chat.chatPassword)
                @Suppress("DEPRECATION")
                chat = chat.copy(transportToken = trimmed)
                // Пересоздаём транспорт с новым токеном
                transportFactory = TransportFactory(
                    chatId     = chat.chatId,
                    transportToken  = trimmed,
                    chatPassword = chat.chatPassword,
                    myUserId   = prefs.myUserId,
                    context    = applicationContext,
                    adminUserId = chat.adminUserId
                )
                transport = transportFactory.instant()
                // В фоне проверяем доступность и, если нужно, переключаемся на Nostr
                lifecycleScope.launch { resolveTransport() }
                // Сбрасываем счётчик ошибок и принудительно прячем любой баннер
                consecutiveFailures = 0
                forceHideChatWarning()
                // Принудительная перезагрузка сообщений чтобы убедиться токен рабочий
                lastContent = ""
                loadMessages()
            }
        }
    }

    /**
     * Скрывает баннер с плавной анимацией при успешной синхронизации.
     * Вызывается когда проблема устранена (сеть появилась, токен обновлён и т.д.).
     */
    private fun hideChatWarning() {
        if (activeWarning == null) return
        activeWarning = null
        consecutiveFailures = 0
        lastFailureEventMs = 0L

        binding.warningBanner.animate()
            .alpha(0f)
            .translationY(-48f)
            .setDuration(280)
            .withEndAction {
                binding.warningBanner.visibility = View.GONE
                binding.warningBanner.translationY = 0f
                binding.warningBanner.setOnClickListener(null)
            }
            .start()
    }

    private fun showLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
    }

    /**
     * Crossfade-переход от loading overlay к списку сообщений.
     */
    private fun maybeReveal() {
        if (firstLoadComplete) return
        
        val storeHasData = chatStore.hasPending() || chatStore.messages.value.isNotEmpty()
        if (contentLoaded || adapter.itemCount > 0 || storeHasData) {
            firstLoadComplete = true
            revealMessages()
        }
    }

    private fun revealMessages() {
        val overlay = binding.loadingOverlay
        val rv      = binding.rvMessages

        // ⚠️ Фикс (репорт: сообщения «пропрыгивают» ПОСЛЕ ухода экрана загрузки — pop-in).
        // Раньше лента держалась alpha=0 под оверлеем и ДОПОЛНИТЕЛЬНО проявлялась на раскрытии
        // (fade 350мс со сдвигом 80мс) уже после ухода оверлея. Теперь лента отрисована и видима
        // ПОД непрозрачным оверлеем (alpha=1), а раскрытие — это только гашение оверлея: готовые
        // сообщения открываются мгновенно, без вторичного проявления (см. §1.5 «всё на месте»).
        rv.visibility = View.VISIBLE
        rv.alpha = 1f

        // Overlay исчезает — открывая уже готовую ленту под ним.
        overlay.animate()
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { overlay.visibility = View.GONE }
            .start()

        // Оверлей ушёл — теперь корректно показываем/прячем заглушку "чат пуст".
        // Системный чат «Уведомления» — своя богатая заглушка (иконка+текст).
        val empty = currentMessages.isEmpty()
        if (chat.isSystemNotifications) {
            binding.notifEmptyState.visibility = if (empty) View.VISIBLE else View.GONE
            binding.tvEmptyPlaceholder.visibility = View.GONE
        } else {
            binding.tvEmptyPlaceholder.visibility = if (empty) View.VISIBLE else View.GONE
        }
    }

    companion object {
        /**
         * Room-id чата, который нужно переоткрыть (recreate) после приёма передачи владения —
         * чтобы транспорт пересоздался со СМЕНЁННЫМ adminUserId (гейт members.txt). Ставит
         * TransferOfferActivity после успешного приёма; ChatActivity снимает в onResume.
         */
        @Volatile var pendingOwnerReloadChatId: Long = -1L

        /** За сколько позиций до верха начинать подгрузку старых сообщений (ленивый рендер). */
        private const val REVEAL_THRESHOLD = 6
        /** Макс. картинок в одном коллаже. */
        const val MAX_COLLAGE_IMAGES = 10
        /** Подряд неудачных загрузок до показа предупреждения. */
        const val FAILURES_BEFORE_WARNING = 5
        /** Максимальная длительность голосового. */
        private const val MAX_VOICE_MS = 15 * 60 * 1000L
        /** Максимум одновременных загрузок изображений. */
        const val MAX_CONCURRENT = 3
        /** Ключ intent-экстра: идентификатор чата (Long, из Room). */
        const val EXTRA_CHAT_ID = "extra_chat_id"

        /** Перейти к сообщению по msgId (из списка медиа) и подсветить. */
        const val EXTRA_SCROLL_TO_MSGID = "extra_scroll_to_msgid"
        /** Удалить сообщение по msgId (из списка медиа). */
        const val EXTRA_DELETE_MSGID = "extra_delete_msgid"

        // ── Polling ───────────────────────────────────────────────────────────
        // Интервал поллинга управляется SyncEngine.ACTIVE_INTERVAL_MS (5 сек).
        /** Базовый интервал адаптивного поллинга (зарезервирован, не используется). */
        const val BASE_MS = 4_000L
        /** Максимальный интервал адаптивного поллинга (зарезервирован, не используется). */
        const val MAX_MS = 30_000L

        // ── Presence ──────────────────────────────────────────────────────────
        /**
         * Период presence-цикла (heartbeat): один write-only PATCH каждые N мс.
         * Поднято с 2с до 5с: частый presence+poll заставлял публичные Nostr-реле
         * резать запросы. 5с — баланс «онлайн» без миганий и нагрузки на реле.
         */
        const val PRESENCE_INTERVAL_MS = 5_000L
        // Фаза 1 синхронизации: union-чтение слотов profiles.txt (убирает lost-update —
        // мерцание аватара/presence). ВЫКЛ по умолчанию: включить на ОБОИХ телефонах для
        // теста; после подтверждения сделать дефолтом. (см. SYNC_AUDIT.md)
        const val SLOT_UNION_PROFILES = true
        /** Через сколько мс без обновления партнёр считается офлайн. */
        const val ONLINE_EXPIRY_MS = 12_000L
        /** Через сколько мс без обновления статус «записывает голосовое» считается устаревшим. */
        const val RECORDING_EXPIRY_MS = 8_000L
        /** Период локального тикера пере-вычисления presence по таймауту. */
        const val PRESENCE_TICK_MS = 1_000L
        /** Через сколько мс без обновления typing-сигнал считается устаревшим. */
        const val TYPING_EXPIRY_MS = 14_000L
        /** Задержка после последнего нажатия клавиши до отправки «перестал печатать». */
        const val TYPING_STOP_DELAY_MS = 3_000L

        /** Троттл самопочинки members.txt у админа (см. maybeAdminRepairMembersFile). */
        const val MEMBERS_REPAIR_THROTTLE_MS = 30_000L

        // ── Profile sync ──────────────────────────────────────────────────────
        /** Количество попыток sync профилей при запуске. */
        const val SYNC_PROFILES_MAX_ATTEMPTS = 3
        /** Базовая задержка между retry sync-профилей (умножается на номер попытки). */
        const val SYNC_PROFILES_RETRY_BASE_MS = 3_000L

    }
}
