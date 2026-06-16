package com.atrum.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.Manifest
import android.view.MotionEvent
import android.view.HapticFeedbackConstants
import android.annotation.SuppressLint
import java.io.File
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityChatBinding
import com.atrum.chat.transport.AllGistData
import com.atrum.chat.transport.ChatAndReactions
import com.atrum.chat.transport.ChatTransport
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
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class ChatActivity : SecureActivity() {

    private lateinit var binding: ActivityChatBinding
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase
    private val voiceRecorder by lazy { VoiceRecorder(this) }
    private var voiceUiJob: Job? = null
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

    /**
     * Счётчик активных загрузок изображений. При count > 0 поле ввода и кнопки
     * блокируются (UI state). Текстовая очередь (MessageSendManager) не затрагивается.
     */
    private val activeImageUploads = AtomicInteger(0)

    // ── Новая архитектура: SyncEngine + PatchQueue + ChatStore ───────────────

    /** Единый ETag-polling engine. Заменяет pollJob. */
    private lateinit var syncEngine: SyncEngine

    /** Сериализованная очередь всех PATCH-запросов. Заменяет прямые saveFile/appendLine вызовы. */
    private lateinit var patchQueue: PatchQueue

    /** Local-first хранилище сообщений. Source of truth для UI. */
    private val chatStore = ChatStore()

    /** Coroutine собирающая события syncEngine в процессор данных. */
    private var syncCollectorJob: Job? = null

    // ── Warning banner ────────────────────────────────────────────────────────

    /** Типы мягких предупреждений. Каждый имеет свой текст, но одинаковый визуал. */
    private enum class WarningType { TOKEN, RATE_LIMIT, NETWORK, FORWARD_SECRECY, TOR }

    /** Текущий активный тип предупреждения, null если баннер скрыт. */
    private var activeWarning: WarningType? = null

    // ── Loading state ─────────────────────────────────────────────────────────

    /** false до момента первой успешной загрузки сообщений. */
    private var firstLoadComplete = false
    // Гейт показа чата: держим loading overlay пока не установится сессия (или таймаут).
    private var contentLoaded = false
    private var handshakeSettled = false
    private val handshakeRevealTimeoutMs = 10_000L

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
    /** Счётчик последовательных poll'ов с V3-сообщениями без сессионного ключа.
     * Баннер forward secrecy появляется только после 2+ таких poll'ов, чтобы
     * не мигать во время ECDH-рукопожатия (обычно завершается за 1–3 сек). */
    private var lockedV3ConsecutiveCount = 0

    // pollJob заменён на syncEngine (см. выше)
    private var lastContent: String = ""

    /** Задержка перед пометкой "прочитано" — минимальная, чтобы галочки приходили быстро. */
    private val markAsReadDelayMs = 500L
    private var markAsReadJob: Job? = null

    /** Кэш последнего отправленного в gist значения lastReadIndex — чтобы не пушить впустую. */
    private var lastPushedReadIndex: Int = -1

    /** Сообщение, на которое мы сейчас отвечаем (null = обычное сообщение). */
    private var replyingTo: Message? = null

    /** Последний загруженный список сообщений — для selection mode (copy/delete). */
    private var currentMessages: List<Message> = emptyList()

    // deletedTombstones перенесены в ChatStore (chatStore.addTombstone / removeTombstone)

    // ── Reactions ─────────────────────────────────────────────────────────────

    /** Текущая карта реакций: msgId → emoji → Set<userId>. */
    private var currentReactions: Map<String, Map<String, Set<String>>> = emptyMap()

    /** Сырой (зашифрованный) контент reactions.txt с последнего poll'а — для ETag-сравнения. */
    private var lastReactionsRaw: String = ""

    /** Расшифрованный контент reactions.txt — для парсинга и манипуляций в handleReactionToggle. */
    private var lastReactionsContent: String = ""

    /** Менеджер отправки: token bucket + очередь + прогрессивные блокировки. */
    private lateinit var sendManager: MessageSendManager

    /** Корутина обратного отсчёта при блокировке (обновляет hint поля ввода). */
    private var countdownJob: Job? = null

    /** Корутина обратного отсчёта жёлтой плашки лимита GitHub. */
    private var rateLimitJob: Job? = null

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

    /**
     * Последний снимок профилей из gist (обновляется в doRefreshPartnerReadIndex).
     * Используется для write-only typing/online пушей — 1 запрос вместо 2.
     * Максимальное «протухание» = ~4 сек (один цикл опроса).
     */
    private val lastKnownProfiles = mutableMapOf<String, Profile>()

    /** Хеш последнего полученного содержимого profiles.txt.
     * Позволяет пропустить парсинг если файл не изменился с прошлого polling-тика.
     * Снижает CPU-нагрузку при частом опросе (профили меняются только при presence-пуше).
     */
    private var lastProfilesHash: Int = 0

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
        if (limited.size == 1) sendImage(limited[0]) else sendImages(limited)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = AppDatabase.get(this)

        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, -1L)
        if (chatId < 0) {
            finish()
            return
        }

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
            val restoredToken = prefs.getChatToken(loaded.gistId).takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") loaded.gistToken
            val restoredPassword = prefs.getChatPassword(loaded.gistId).takeIf { it.isNotEmpty() }
                ?: @Suppress("DEPRECATION") loaded.chatPassword
            @Suppress("DEPRECATION")
            chat = loaded.copy(gistToken = restoredToken, chatPassword = restoredPassword)
            setupUi()
            // Прогреваем Argon2-ключ в фоне (V2 фолбэк): первое шифрование/дешифрование
            // занимает 400–700 мс, кеш после этого делает всё мгновенным.
            // Запускаем до loadMessages — к моменту расшифровки первых строк ключ уже готов.
            launch(Dispatchers.Default) {
                CryptoHelper.warmUp(chat.chatPassword, chat.gistId)
            }
            // Восстанавливаем или генерируем X25519 пару ключей для forward secrecy.
            // Приватный ключ живёт в памяти до onDestroy, но теперь также дублируется в БД
            // для непрерывности сессии V3 при перезаходах в чат.
            if (chat.myEphemeralPrivKeyB64 != null && chat.myEphemeralPubKeyB64 != null) {
                myEphemeralPrivKey = Base64.decode(chat.myEphemeralPrivKeyB64, Base64.NO_WRAP)
                myCurrentEphemeralPubKey = chat.myEphemeralPubKeyB64
            } else {
                val (privKey, pubKeyB64) = CryptoHelper.generateEphemeralKeyPair()
                myEphemeralPrivKey = privKey
                myCurrentEphemeralPubKey = pubKeyB64
                // Сохраняем в БД — теперь сессия не сбросится при Activity.finish()
                db.chatDao().updateMyEphemeralKeys(chat.id,
                    Base64.encodeToString(privKey, Base64.NO_WRAP), pubKeyB64)
            }
            // Подписываем свой эфемерный ключ долговременным identity-ключом (защита от MITM).
            myEphemeralSig = computeEphemeralSig(myCurrentEphemeralPubKey, chat.gistId)

            // Если в БД уже был ключ партнёра — сразу устанавливаем сессионный ключ,
            // чтобы первое же loadMessages() расшифровало V3-сообщения.
            if (chat.partnerEphemeralPubKeyB64 != null) {
                tryEstablishSessionKey(chat.partnerEphemeralPubKeyB64)
            }
            // Держим overlay до завершения рукопожатия (с таймаутом) — чтобы при входе
            // в чат сессия и проверка идентичности уже были готовы.
            startHandshakeGate()
            // Мгновенный показ из кэша прошлого захода (в этой сессии) — чат не грузится
            // с нуля; сетевой loadMessages ниже обновит его в фоне.
            ChatSnapshotCache.get(chat.gistId)?.let { cached ->
                runCatching { processGistData(cached) }
            }
            // Первая загрузка — silent: gist может быть только что создан,
            // первая попытка может выдать "файл не найден" — это норм, polling попробует ещё.
            loadMessages(silent = true)
            // Страховка: если за 15с контент так и не пришёл (нет сети / Tor не поднялся) —
            // всё равно показываем чат, чтобы не залипнуть на спиннере.
            lifecycleScope.launch {
                delay(15_000L)
                if (!firstLoadComplete) { firstLoadComplete = true; revealMessages() }
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
     * Подтягивает профиль собеседника из gist и пушит свой профиль.
     * Если имя или аватарка собеседника изменились — обновляет UI и Room.
     */
    private fun syncProfiles() = lifecycleScope.launch {
        if (chat.isFavorites) return@launch
        // Подстраховка: до 3 попыток с нарастающей паузой (3с → 6с).
        // Раньше была одна попытка, после которой всё молча игнорировалось —
        // любая кратковременная сетевая ошибка приводила к тому, что ephemeral
        // ключ не доходил до партнёра и ECDH-рукопожатие не завершалось.
        repeat(SYNC_PROFILES_MAX_ATTEMPTS) { attempt ->
            try {
                doSyncProfilesOnce()
                return@launch   // успех — выходим
            } catch (_: Exception) {
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
     */
    private suspend fun doSyncProfilesOnce() {
        val allProfiles = ProfileSync.pullProfiles(transport, chat.chatPassword)
        val partner = ProfileSync.findPartner(allProfiles, prefs.myUserId, prefs.myName)

        // Если кроме меня появился кто-то ещё (или partner найден) — чат "занят".
        // Помечаем partnerJoined=true чтобы кнопка "Поделиться" стала disabled.
        val secondParticipantExists = partner != null ||
                allProfiles.values.any { it.userId != prefs.myUserId }
        if (secondParticipantExists && !chat.partnerJoined) {
            db.chatDao().markPartnerJoined(chat.id)
            chat = chat.copy(partnerJoined = true)
        }

        if (partner != null) {
            // isNotBlank() защищает от затирания имени когда pushPresenceCached партнёра
            // временно записал пустое имя (например, при первом тике до синка профилей).
            // Защита: если имя пустое — это presence-пинг с неполным профилем
            // (например, pushPresenceCached до первого syncProfiles). Не затираем
            // сохранённое имя. Аватарка обновляется независимо.
            val nameToSave = if (partner.name.isNotBlank()) partner.name else chat.partnerName
            val profileChanged = nameToSave != chat.partnerName ||
                    partner.tag != chat.partnerTag ||
                    partner.avatarBase64 != chat.partnerAvatarBase64
            if (profileChanged) {
                db.chatDao().updatePartnerProfile(chat.id, nameToSave, partner.tag, partner.avatarBase64)
                chat = chat.copy(
                    partnerName         = nameToSave,
                    partnerTag          = partner.tag,
                    partnerAvatarBase64 = partner.avatarBase64
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
            verifiedPartnerIdk = prefs.getConfirmedPartnerIdentity(chat.gistId),
            status = prefs.myStatus.takeIf { it.isNotBlank() }
        )
        ProfileSync.pushMyProfile(transport, chat.chatPassword, myProfile)
    }

    /** Обновляет шапку чата с актуальными данными собеседника (имя + аватарка). */
    private fun applyPartnerToHeader() {
        if (chat.isFavorites) {
            binding.tvDisplayName.text = getString(R.string.favorites_name)
            binding.ivPartnerAvatar.visibility = View.GONE
            binding.tvPartnerAvatar.visibility = View.VISIBLE
            binding.tvPartnerAvatar.text = "★"
            binding.tvPartnerAvatar.setBackgroundResource(R.drawable.bg_avatar_favorites)
            binding.tvChatSubtitle.text = getString(R.string.favorites_description)
            binding.vOnlineIndicator.visibility = View.GONE
            return
        }

        binding.tvDisplayName.text = chat.partnerName
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
            binding.tvPartnerAvatar.text = "✕"
            binding.tvPartnerAvatar.background =
                androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_avatar_deleted)
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

    private fun openPartnerProfile() {
        if (chat.isFavorites) return
        // Берём данные из lastKnownProfiles если уже загрузились,
        // иначе fallback на кэшированные данные из Room (chat объект).
        val partner = lastKnownProfiles.values.firstOrNull { it.userId != prefs.myUserId }
        val name         = partner?.name         ?: chat.partnerName
        val tag          = partner?.tag          ?: chat.partnerTag
        val status       = partner?.status
        val avatarBase64 = partner?.avatarBase64 ?: chat.partnerAvatarBase64
        val refs = currentMessages
            .mapNotNull { msg ->
                when {
                    msg.imageFileNames != null -> msg.imageFileNames
                    msg.imageFileName != null -> listOf(msg.imageFileName)
                    msg.imageBase64 != null -> listOf("base64:${msg.imageBase64}")
                    else -> null
                }
            }
            .flatten()
        val intent = android.content.Intent(this, PartnerProfileActivity::class.java).apply {
            putExtra(PartnerProfileActivity.EXTRA_NAME, name)
            putExtra(PartnerProfileActivity.EXTRA_TAG, tag)
            putExtra(PartnerProfileActivity.EXTRA_STATUS, status)
            putExtra(PartnerProfileActivity.EXTRA_AVATAR_BASE64, avatarBase64)
            putExtra(PartnerProfileActivity.EXTRA_GIST_ID, chat.gistId)
            putExtra(PartnerProfileActivity.EXTRA_GIST_TOKEN, chat.gistToken)
            putExtra(PartnerProfileActivity.EXTRA_CHAT_PASSWORD, chat.chatPassword)
            putExtra(PartnerProfileActivity.EXTRA_IDENTITY_PUB, partner?.identityPubKey)
            putExtra(PartnerProfileActivity.EXTRA_EPH_PUB, partner?.ephemeralPubKey)
            putExtra(PartnerProfileActivity.EXTRA_EPH_SIG, partner?.ephemeralSig)
            putExtra(PartnerProfileActivity.EXTRA_VERIFIED_PARTNER_IDK, partner?.verifiedPartnerIdk)
            putStringArrayListExtra(PartnerProfileActivity.EXTRA_IMAGE_REFS, ArrayList(refs))
        }
        startActivity(intent)
    }

    private fun setupUi() {
        transportFactory = TransportFactory(
            gistId = chat.gistId,
            gistToken = chat.gistToken,
            chatPassword = chat.chatPassword,
            myUserId = prefs.myUserId,
            isFavorites = chat.isFavorites,
            chatIdLong = chat.id,
            chatDao = db.chatDao(),
            context = applicationContext
        )
        // Стартуем с Gist напрямую (без проверки) — UI не ждёт
        transport = transportFactory.instant()
        // В фоне проверяем реальную доступность — переключимся на Nostr если нужно
        lifecycleScope.launch { resolveTransport() }

        applyPartnerToHeader()

        // ImageLoader — общий для адаптера и openImageFullscreen
        val imageLoader = ImageLoader(transport, chat.chatPassword)

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
        // Применяем уже известный индекс прочитанности (из Room) к адаптеру
        adapter.setPartnerLastReadIndex(chat.partnerLastReadIndex)
        // Начальные значения непрозрачности пузырьков (могут быть обновлены в applyWallpaper)
        adapter.bubbleAlphaSelf  = prefs.bubbleAlphaSelf  / 100f
        adapter.bubbleAlphaOther = prefs.bubbleAlphaOther / 100f

        binding.rvMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        // Отключаем анимации изменений: без этого DiffUtil при каждом новом сообщении
        // запускает fade-out → fade-in на ВСЕХ видимых айтемах → они временно пропадают.
        (binding.rvMessages.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        binding.rvMessages.adapter = adapter

        // ── Swipe-to-reply ────────────────────────────────────────────────────
        val swipeCallback = SwipeToReplyCallback(this) { position ->
            val msg = adapter.getItem(position)
            if (msg != null) startReply(msg)
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
                startActivity(android.content.Intent.createChooser(sendIntent, "Переслать"))
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

        // Подписка на ChatStore: UI обновляется мгновенно при любом изменении state.
        // collect (не collectLatest): каждый emit обрабатывается до конца — нет отмены
        // посередине adapter.submit(), что предотвращает мигание при быстром потоке событий.
        lifecycleScope.launch {
            chatStore.messages.collect { messages ->
                // messages = compose() из ChatStore: серверные + pending БЕЗ дублей и с
                // корректными флагами isPending. Подаём ОДНИМ списком — НЕ передаём pending
                // отдельным аргументом, иначе подтверждённый-но-ещё-в-очереди стикер
                // (isPending=false, но всё ещё в pendingByRaw) рендерился бы дважды
                // (compose + pendingSnapshot) → копия, исчезающая только после перезахода.
                currentMessages = messages.filter { !it.isPending }
                adapter.submit(messages)
                // Снимаем загрузочный оверлей ТОЛЬКО когда сообщения уже уложены в список
                // (post выполнится после layout-прохода) — чат проявляется готовым, без пустой вспышки.
                // Раскрываем чат, когда пришли реальные сообщения. Если чат и впрямь пустой
                // (нет превью истории) — раскрываем сразу; иначе держим спиннер до данных:
                // через медленный Tor первый ответ может быть пустым/транзиентным.
                if (messages.isNotEmpty() || chat.lastMessage.isBlank()) {
                    binding.rvMessages.post { maybeReveal() }
                }
                // Заглушку "чат пуст" показываем только после снятия загрузочного оверлея —
                // иначе спиннер виден поверх надписи "пусто".
                binding.tvEmptyPlaceholder.visibility =
                    if (messages.isEmpty() && firstLoadComplete) View.VISIBLE else View.GONE
                // Авто-скролл только если уже у дна: не прерываем чтение истории.
                // canScrollVertically(1) == false → нельзя скроллить дальше вниз = мы у дна.
                val isAtBottom = !binding.rvMessages.canScrollVertically(1)
                if (messages.isNotEmpty() && isAtBottom) {
                    binding.rvMessages.scrollToPosition(messages.size - 1)
                }
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
                if (chat.isFavorites) {
                    // Для локального чата имитируем мгновенную загрузку из "сети"
                    // сразу после appendLine, чтобы сообщение вышло из pending-статуса.
                    val data = withContext(Dispatchers.IO) { transport.loadAll() }
                    processGistData(data)
                }
            },
            onMessageSent = {
                // Сообщение ушло — лимит снят, прячем жёлтую плашку сразу.
                runOnUiThread { hideRateLimitBanner() }
                if (!chat.isFavorites) {
                    // Сбрасываем ETag: следующий GET обойдёт CDN-кеш и вернёт свежий контент.
                    // Это аналог ?t=Date.now() из веб-версии — cache-bust после PATCH.
                    // Без этого CDN может отдавать 304 (старый контент) 1-3 сек после PATCH,
                    // и часики висят до следующего обычного тика (10 сек).
                    lifecycleScope.launch {
                        // Немедленный форс-синк: ETag сброшен → 200 гарантирован → часики гаснут.
                        syncEngine.forceSync(delayMs = 0L)
                        // Страховка: если sync пропустил single-flight guard (предыдущий GET в полёте)
                        delay(1_500L)
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
            onSendFailed = { _, reason ->
                runOnUiThread {
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
                runOnUiThread { showRateLimitBanner(retryAfterMs) }
            }
        )

        binding.btnBack.setOnClickListener { finish() }
        binding.btnMore.setOnClickListener { confirmClearHistory() }
        binding.vAvatarContainer.setOnClickListener { openPartnerProfile() }
        if (chat.isFavorites) {
            binding.btnMore.visibility = View.GONE
        }
        binding.btnSend.setOnClickListener { sendMessage() }
        setupVoiceInput()
        binding.btnAttach.setOnClickListener { pickImages.launch("image/*") }
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
        val hasWallpaper = !base64.isNullOrBlank()
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
            binding.ivChatWallpaper.visibility = android.view.View.GONE
        }

        if (isGlass) {
            applyGlassStyle()
        } else if (hasWallpaper) {
            applyClassicWallpaperStyle()
        } else {
            applyClassicSolidStyle()
        }

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

        binding.viewScrimTop.visibility    = android.view.View.GONE
        binding.viewScrimBottom.visibility = android.view.View.GONE

        clearBackdropBlur()
        restoreDefaultTextColors()
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

        binding.viewScrimTop.visibility    = android.view.View.GONE
        binding.viewScrimBottom.visibility = android.view.View.GONE

        clearBackdropBlur()
        restoreDefaultTextColors()
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
        isInForeground = true
        resumeVisibleStickers()
        applyWallpaper()
        if (::chat.isInitialized) {
            // Re-ensure: при возврате в Tor-чат поднимаем Tor, если он «уснул» в фоне.
            if (!chat.isFavorites &&
                chat.gistToken != com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN) {
                TorManager.start(this)
            }
            // Сбрасываем кэши — при возврате в чат гарантируем:
            //  1. lastContent="" → loadMessages всегда парсит заново
            //  2. lastPushedReadIndex=-1 → read receipt отправится даже если контент не изменился
            lastContent = ""
            lastPushedReadIndex = -1
            // Показываем overlay только если нет уже загруженных сообщений.
            // Раньше overlay показывался всегда при onResume, и если сеть была медленной
            // или транспорт возвращал ошибку, сообщения прятались за оверлеем навсегда.
            if (!firstLoadComplete || adapter.itemCount == 0) {
                firstLoadComplete = false
                binding.loadingOverlay.alpha = 1f
                binding.loadingOverlay.visibility = View.VISIBLE
                binding.rvMessages.alpha = 0f
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
            // И перетягиваем свою аватарку — могла поменяться в Settings, и
            // если она у нас в Room — partnerName тоже мог обновиться.
            // На всякий случай перерисовываем шапку из свежей версии чата.
            lifecycleScope.launch {
                val fresh = db.chatDao().getById(chat.id) ?: return@launch
                if (fresh.partnerName != chat.partnerName ||
                    fresh.partnerAvatarBase64 != chat.partnerAvatarBase64) {
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

    override fun onDestroy() {
        super.onDestroy()
        // Освобождаем видео-плееры webm-стикеров (ExoPlayer/GL). Без этого они продолжают
        // крутиться в фоне и утекают между чатами -> рост памяти и OOM (в т.ч. при Argon2).
        try { binding.rvMessages.adapter = null } catch (_: Exception) {}
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
            try { CryptoHelper.clearCachedKey(transport.chatId, chat.chatPassword) } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        isInForeground = false
        pauseVisibleStickers()
        // Останавливаем SyncEngine и коллектор событий
        if (::syncEngine.isInitialized) syncEngine.stop()
        syncCollectorJob?.cancel()
        syncCollectorJob = null
        markAsReadJob?.cancel()
        markAsReadJob = null
        // Останавливаем presence-цикл и немедленно сбрасываем оба статуса в 0:
        // собеседник увидит «не в сети / не печатает» через один цикл опроса (~3 сек)
        isCurrentlyTyping = false
        stopTypingJob?.cancel(); stopTypingJob = null
        presenceJob?.cancel(); presenceJob = null
        if (::transport.isInitialized) {
            val capturedTransport  = transport
            val capturedPassword   = chat.chatPassword
            val capturedUserId     = prefs.myUserId
            val capturedName       = prefs.myName
            val capturedAvatar     = prefs.myAvatarBase64
            val capturedEphKey     = myCurrentEphemeralPubKey
            val capturedIdentity   = prefs.myIdentityPubKey
            val capturedSig        = myEphemeralSig
            val capturedConfirmed  = prefs.getConfirmedPartnerIdentity(chat.gistId)
            AppScope.launch {
                try {
                    ProfileSync.pushPresence(
                        api               = capturedTransport,
                        password          = capturedPassword,
                        myUserId          = capturedUserId,
                        typingTs          = 0L,
                        onlineTs          = 0L,
                        myEphemeralPubKey = capturedEphKey,
                        myName            = capturedName,
                        myAvatarBase64    = capturedAvatar,
                        myIdentityPubKey     = capturedIdentity,
                        myEphemeralSig       = capturedSig,
                        myVerifiedPartnerIdk = capturedConfirmed
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun startPolling() {
        if (chat.isFavorites) return

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
                processGistData(data)
            }
        }
    }

    /**
     * Suspend-версия опроса профилей — вызывается из polling-цикла (sequential)
     * и напрямую из syncProfiles/refreshPartnerReadIndex для разовых вызовов.
     * Обновляет: галочки прочтения, индикатор «печатает».
     */
    private suspend fun doRefreshPartnerReadIndex() {
        // Загружаем сырой зашифрованный файл и проверяем хеш ДО дешифровки.
        // Если profiles.txt не изменился — пропускаем парсинг (экономим CPU).
        // Сам GET всё равно происходит — это нужно для обнаружения изменений.
        val rawEncrypted = transport.loadFileOrNull("profiles.txt")?.trim()
        val newHash = rawEncrypted.hashCode()
        if (newHash == lastProfilesHash && lastKnownProfiles.isNotEmpty()) return
        lastProfilesHash = newHash

        val allProfiles = if (rawEncrypted.isNullOrEmpty()) emptyMap()
            else ProfileSync.parseProfiles(rawEncrypted, chat.chatPassword, transport.chatId)
        // Обновляем кэш — используется для write-only typing/online пушей
        lastKnownProfiles.clear()
        lastKnownProfiles.putAll(allProfiles)

        val partner = ProfileSync.findPartner(allProfiles, prefs.myUserId, prefs.myName)
            ?: run { updateTypingIndicator(false); updateOnlineIndicator(false); return }

        // Проверяем появился ли у партнёра новый ephemeral pub key — если да, устанавливаем V3
        tryEstablishSessionKey(partner.ephemeralPubKey)
        verifyPartnerIdentity(partner)

        // Синхронизируем имя и аватарку партнёра при каждом опросе профилей.
        // Ранее это делалось только в syncProfiles() (один раз при открытии чата).
        // Если syncProfiles() падал из-за сети — аватарка не показывалась никогда.
        // Теперь обновляем здесь: polling loop запущен и всё равно скачивает профили.
        if (partner.name.isNotBlank() &&
            (partner.name != chat.partnerName || partner.avatarBase64 != chat.partnerAvatarBase64)
        ) {
            db.chatDao().updatePartnerProfile(chat.id, partner.name, partner.tag, partner.avatarBase64)
            chat = chat.copy(partnerName = partner.name, partnerTag = partner.tag, partnerAvatarBase64 = partner.avatarBase64)
            applyPartnerToHeader()
        }

        // Флаг удалённого профиля — обновляем в DB и UI если изменился
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

        val now = System.currentTimeMillis()

        // Удалённый профиль не показывает typing/online
        if (partner.deleted) {
            updateTypingIndicator(false)
            updateOnlineIndicator(false)
            return
        }

        val isTyping = partner.typingTs > 0L && now - partner.typingTs < TYPING_EXPIRY_MS
        updateTypingIndicator(isTyping)

        val isOnline = partner.onlineTs > 0L && now - partner.onlineTs < ONLINE_EXPIRY_MS
        updateOnlineIndicator(isOnline)

        // Пока партнёр онлайн — держим быстрый интервал поллинга сообщений (BASE_MS).
    }

    /** Разовый вызов для ручного обновления (кнопка обновить, onResume). */
    private fun refreshPartnerReadIndex() = lifecycleScope.launch {
        try { doRefreshPartnerReadIndex() } catch (_: Exception) {}
    }

    /**
     * Обрабатывает уже загруженный зашифрованный контент profiles.txt — без сетевого вызова.
     *
     * Вызывается из loadMessages() когда данные пришли вместе с chat.txt в одном GET
     * (preloadedData.profilesContent или результат transport.loadAll()). Содержит ту же
     * логику что и doRefreshPartnerReadIndex(), но не делает собственный network-запрос.
     *
     * Результат: обновлены lastKnownProfiles, typing/online индикаторы, галочки прочтения,
     * имя/аватар партнёра, ephemeral ключ (V3 forward secrecy).
     */
    private suspend fun processProfilesFromContent(rawEncrypted: String) {
        if (rawEncrypted.isBlank()) return
        val newHash = rawEncrypted.hashCode()
        if (newHash == lastProfilesHash && lastKnownProfiles.isNotEmpty()) return
        lastProfilesHash = newHash

        val parsed = ProfileSync.parseProfiles(rawEncrypted, chat.chatPassword, transport.chatId)
        // Сырой снимок — для presence-записей (чтобы не реинжектить устаревшего партнёра).
        lastKnownProfiles.clear()
        lastKnownProfiles.putAll(parsed)
        // Для отображения и сессионного ключа — «липкий» партнёр (флаки-чтение не теряет его).
        val allProfiles = ProfileSync.unionAndRemember(transport.chatId, parsed)

        val partner = ProfileSync.findPartner(allProfiles, prefs.myUserId, prefs.myName)
        if (partner == null) {
            updateTypingIndicator(false)
            updateOnlineIndicator(false)
            return
        }

        // Обновляем V3-сессионный ключ если партнёр опубликовал новый ephemeral ключ
        tryEstablishSessionKey(partner.ephemeralPubKey)
        verifyPartnerIdentity(partner)

        // Обновляем имя/аватар партнёра
        if (partner.name.isNotBlank() &&
            (partner.name != chat.partnerName || partner.tag != chat.partnerTag || partner.avatarBase64 != chat.partnerAvatarBase64)) {
            db.chatDao().updatePartnerProfile(chat.id, partner.name, partner.tag, partner.avatarBase64)
            chat = chat.copy(partnerName = partner.name, partnerTag = partner.tag, partnerAvatarBase64 = partner.avatarBase64)
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

        if (partner.deleted) {
            updateTypingIndicator(false)
            updateOnlineIndicator(false)
            return
        }

        val now = System.currentTimeMillis()
        val isTyping = partner.typingTs > 0L && now - partner.typingTs < TYPING_EXPIRY_MS
        updateTypingIndicator(isTyping)

        val isOnline = partner.onlineTs > 0L && now - partner.onlineTs < ONLINE_EXPIRY_MS
        updateOnlineIndicator(isOnline)
    }

    /**
     * Загружает и отображает сообщения.
     *
     * [silent]        = true  → без spinner; ошибки не показываем тостом.
     * [useEtag]       = true  → ETag-оптимизация (legacy, используется только при форсированном вызове).
     * [preloadedData] = not null → данные уже есть (legacy compatibility, для doClearHistory).
     *
     * В новой архитектуре основной поток данных идёт через SyncEngine → processGistData().
     * loadMessages() используется только для первой загрузки (silent=true из onCreate).
     */
    private fun loadMessages(
        silent: Boolean = false,
        useEtag: Boolean = false,
        preloadedData: AllGistData? = null
    ) {
        if (!silent) showLoading(true)
        lifecycleScope.launch {
            try {
                val data: AllGistData = when {
                    preloadedData != null -> preloadedData
                    else -> withContext(Dispatchers.IO) { transport.loadAll() }
                }
                processGistData(data)
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
     * Центральная точка обработки данных от GitHub.
     * Вызывается из:
     *  • SyncEngine.events collector (основной поток, каждые 10с)
     *  • loadMessages() (первая загрузка, форсированные вызовы)
     *
     * Всё в одном месте: парсинг → reconcile → UI → read receipt → profiles.
     * НЕ делает никаких сетевых вызовов — только обрабатывает уже полученные данные.
     */
    private suspend fun processGistData(data: AllGistData) {
        ChatSnapshotCache.put(chat.gistId, data)
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
            val parsedReactions = withContext(Dispatchers.Default) {
                val decrypted = if (reactionsRaw.isBlank()) reactionsRaw
                else CryptoHelper.decrypt(reactionsRaw, chat.chatPassword, chat.gistId) ?: reactionsRaw
                parseReactions(decrypted)
            }
            currentReactions = parsedReactions
            lastReactionsContent = ""
            // Обновляем реакции в адаптере ТОЛЬКО при реальном изменении — иначе
            // notifyDataSetChanged на каждый опрос перерисовывает список и стикеры мигают.
            withContext(Dispatchers.Main) {
                adapter.setReactions(currentReactions, prefs.myUserId)
            }
        }

        // ETag content dedup: пропускаем парсинг сообщений если chat.txt не изменился
        // и read receipt уже был отправлен. lastPushedReadIndex = -1 форсирует
        // обработку даже при совпадении контента (первый запуск, onResume).
        if (chatContent == lastContent && lastPushedReadIndex != -1) {
            // Даже если контент не изменился (в т.ч. пустой чат при первой загрузке) —
            // снимаем загрузочный оверлей, иначе спиннер крутится бесконечно.
            contentLoaded = true
            maybeReveal()
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

        val messages: List<Message> = withContext(Dispatchers.Default) {
            allLines.mapNotNull { rawLine ->
                val line = rawLine.trim()
                CryptoHelper.decrypt(line, pass, chat.gistId)?.let { decrypted ->
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

        // Forward secrecy баннер (V3 сообщения без сессионного ключа).
        // Порог 6 тиков (~9 сек) — даём время на обмен ephemeral ключами при старте.
        if (CryptoHelper.hasLockedV3Messages(chatContent, chat.gistId)) {
            if (firstLoadComplete) lockedV3ConsecutiveCount++
            if (lockedV3ConsecutiveCount >= 6 && activeWarning != WarningType.FORWARD_SECRECY) {
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
            val previewBody = when {
                last.isMultiImage && last.text.isBlank() -> "📷 Фото (${last.imageFileNames?.size ?: 2})"
                last.isMultiImage  -> "📷 ${last.text}"
                last.isImage && last.text.isBlank() -> "📷 Фото"
                last.isImage       -> "📷 ${last.text}"
                last.isSticker     -> getString(R.string.msg_preview_sticker)
                last.isReply       -> "↪ ${last.text}"
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

        // ── Profiles (typing / online / partner data) ─────────────────────────
        if (profilesContent.isNotBlank()) {
            processProfilesFromContent(profilesContent)
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
            chat = chat.copy(lastSeenLineCount = totalLines, unreadCount = 0)

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
                    status = prefs.myStatus.takeIf { it.isNotBlank() }
                )
                val currentTransport = transport
                val chatPassword = chat.chatPassword
                // 3 попытки с паузой 2 сек — гарантируем что read receipt дойдёт
                // даже при временных сетевых сбоях.
                AppScope.launch {
                    repeat(3) { attempt ->
                        val ok = try {
                            ProfileSync.pushMyProfile(currentTransport, chatPassword, myProfile)
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
        val adapter = StickerAdapter(emptyList()) { sticker ->
            sendSticker(sticker)
            binding.etMessage.setText("")
            hideStickerSuggestions()
        }
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

        lifecycleScope.launch {
            try {
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
                        CryptoHelper.encrypt(b64, chat.chatPassword, transport.chatId)
                    }
                    val uploaded = withContext(Dispatchers.IO) {
                        transport.uploadImage(encryptedSticker, chat.chatPassword)
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
                    CryptoHelper.encrypt(plaintext, chat.chatPassword, transport.chatId)
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
                stickerPanel?.hidePanel()

                // 5. Отправка ТОЛЬКО короткой строки-заголовка (без extraFiles — контент уже в
                //    отдельном gist). Маленький PATCH вместо тяжёлого инлайна стикера.
                withContext(Dispatchers.IO) {
                    transport.appendLine(encryptedLine = encryptedMessage)
                }

                // Cache-bust и синхронизация
                chatStore.confirmSent(encryptedMessage) // Сразу подтверждаем отправку в ChatStore
                syncEngine.forceSync(delayMs = 0L)
                stopTypingSignal()

            } catch (e: Exception) {
                val reason = e.message?.take(120) ?: "unknown"
                android.widget.Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendMessage() {
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
                CryptoHelper.encrypt(plaintext, chat.chatPassword, chat.gistId)
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
            }
        }
        // Если отклонено (очередь полна) — текст остаётся в поле, пользователь видит отказ
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
        val hasText = text.trim().isNotEmpty()
        binding.btnSend.visibility = if (hasText) View.VISIBLE else View.GONE
        binding.btnVoice.visibility = if (hasText) View.GONE else View.VISIBLE
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
            while (voiceRecorder.isRecording) {
                val ms = voiceRecorder.elapsedMs()
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
        restoreInputAfterRecording()
        if (cancel) {
            lifecycleScope.launch(Dispatchers.IO) { runCatching { voiceRecorder.cancel() } }
            return
        }
        // Чистка нейросетью + кодирование — тяжёлые, делаем в фоне, чтобы UI не залипал
        // после «отправить». Поле ввода уже вернулось, пользователь не ждёт.
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { voiceRecorder.stop(minMs = 700L) }
            if (result == null) {
                Toast.makeText(this@ChatActivity, R.string.voice_too_short, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val (file, durMs) = result
            val wf = Message.encodeWaveform(downsampleLevels(levelsSnapshot, 40))
            sendVoice(file, ((durMs + 500L) / 1000L).toInt().coerceAtLeast(1), wf)
        }
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

    private fun sendVoice(file: File, durationSec: Int, waveform: String) {
        if (sendManager.isPunished()) return
        val now = System.currentTimeMillis()
        lifecycleScope.launch {
            try {
                val b64 = withContext(Dispatchers.Default) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                val encryptedContent = withContext(Dispatchers.Default) {
                    CryptoHelper.encrypt(b64, chat.chatPassword, chat.gistId)
                }
                val contentRef = withContext(Dispatchers.IO) {
                    transport.uploadImage(encryptedContent, chat.chatPassword)
                }
                // Кэшируем контент и копируем файл под ссылку → своё голосовое сразу «готово».
                ImageCache.put(contentRef, b64, null)
                runCatching {
                    val playDir = File(cacheDir, "voice_play").apply { mkdirs() }
                    file.copyTo(File(playDir, "v_" + Integer.toHexString(contentRef.hashCode()) + ".m4a"), overwrite = true)
                }
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
                    CryptoHelper.encrypt(plaintext, chat.chatPassword, chat.gistId)
                }
                val pendingMsg = Message(
                    sender = prefs.myName,
                    text = "",
                    isSelf = true,
                    rawEncrypted = encryptedMessage,
                    timestampMs = now,
                    voiceFileName = contentRef,
                    voiceDurationSec = durationSec,
                    voiceWaveform = waveform,
                    senderUserId = prefs.myUserId,
                    isPending = true
                )
                chatStore.addOptimistic(pendingMsg)
                withContext(Dispatchers.IO) { transport.appendLine(encryptedLine = encryptedMessage) }
                chatStore.confirmSent(encryptedMessage)
                syncEngine.forceSync(delayMs = 0L)
                stopTypingSignal()
                runCatching { file.delete() }
            } catch (e: Exception) {
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + (e.message?.take(120) ?: "unknown"),
                    Toast.LENGTH_LONG).show()
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
    private fun showRateLimitBanner(durationMs: Long) {
        if (durationMs <= 0L) { hideRateLimitBanner(); return }

        val isGlass = prefs.chatUiStyle == Prefs.CHAT_UI_GLASS
        val bannerBg = if (isGlass) R.drawable.bg_rate_limit_banner_glass
                       else R.drawable.bg_rate_limit_banner
        val contentColor = ContextCompat.getColor(
            this, if (isGlass) R.color.warning else R.color.warning_on)

        binding.rateLimitBanner.setBackgroundResource(bannerBg)
        binding.ivRateLimitIcon.setColorFilter(contentColor)
        binding.tvRateLimitTitle.setTextColor(contentColor)
        binding.tvRateLimitMessage.setTextColor(contentColor)

        if (binding.rateLimitBanner.visibility != View.VISIBLE) {
            binding.rateLimitBanner.alpha = 0f
            binding.rateLimitBanner.visibility = View.VISIBLE
            binding.rateLimitBanner.animate().alpha(1f).setDuration(220L).start()
        }
        // Пока действует лимит — отправка приглушена и заблокирована.
        binding.btnSend.isEnabled = false
        binding.btnSend.alpha = 0.4f

        rateLimitJob?.cancel()
        val endMs = System.currentTimeMillis() + durationMs
        rateLimitJob = lifecycleScope.launch {
            while (true) {
                val remaining = endMs - System.currentTimeMillis()
                if (remaining <= 0) break
                val sec = (remaining + 999) / 1000   // округляем вверх
                val mmss = String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60)
                binding.tvRateLimitMessage.text = getString(R.string.rate_limit_retry_in, mmss)
                delay(500L)
            }
            hideRateLimitBanner()
        }
    }

    /** Прячет плашку лимита и возвращает кнопку отправки (если нет активной спам-блокировки). */
    private fun hideRateLimitBanner() {
        rateLimitJob?.cancel()
        rateLimitJob = null
        if (binding.rateLimitBanner.visibility == View.VISIBLE) {
            binding.rateLimitBanner.animate().alpha(0f).setDuration(180L).withEndAction {
                binding.rateLimitBanner.visibility = View.GONE
            }.start()
        }
        binding.btnSend.alpha = 1f
        // Не включаем отправку, если параллельно активна спам-блокировка (countdownJob).
        if (countdownJob == null) binding.btnSend.isEnabled = true
    }

    // ── Forward secrecy ──────────────────────────────────────────────────────

    /**
     * Устанавливает сессионный ключ (V3) если партнёр опубликовал новый ephemeral pub key.
     *
     * Вызывается из syncProfiles() и doRefreshPartnerReadIndex() при каждом получении
     * профиля партнёра. Пересчёт происходит только при смене ключа.
     *
     * После setSessionKey() все новые encrypt() автоматически используют V3 (forward secrecy).
     */
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
            IdentityState.set(chat.gistId, IdentityState.Info(IdentityState.State.UNVERIFIED, idk, partnerVerifiedMe))
            return
        }
        val data = Base64.decode(eph, Base64.NO_WRAP) + chat.gistId.toByteArray(Charsets.UTF_8)
        if (!CryptoHelper.verifyIdentitySignature(idk, data, sig)) {
            IdentityState.set(chat.gistId, IdentityState.Info(IdentityState.State.UNVERIFIED, idk, partnerVerifiedMe))
            return
        }
        val known = prefs.getKnownPartnerIdentity(chat.gistId)
        val state = when (known) {
            null -> {
                prefs.setKnownPartnerIdentity(chat.gistId, idk)   // trust on first use
                IdentityState.State.VERIFIED
            }
            idk -> IdentityState.State.VERIFIED
            else -> IdentityState.State.CHANGED
        }
        IdentityState.set(chat.gistId, IdentityState.Info(state, idk, partnerVerifiedMe))
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

    private fun tryEstablishSessionKey(partnerPubKey: String?) {
        if (partnerPubKey == null) return
        if (partnerPubKey == lastPartnerEphemeralPubKey) return   // ключ не изменился
        val privKey = myEphemeralPrivKey ?: return                // наш ключ ещё не готов

        val sessionKey = CryptoHelper.computeSessionKey(privKey, partnerPubKey, chat.gistId)
        if (sessionKey != null) {
            CryptoHelper.setSessionKey(chat.gistId, sessionKey)
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
        androidx.appcompat.app.AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
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
                ChatSnapshotCache.clear(chat.gistId)
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
                doPushPresence(typingTs, System.currentTimeMillis())
                // ±20% джиттер скрывает ритм presence-пульса от анализа трафика
                val jitter = (PRESENCE_INTERVAL_MS * 0.2 * (Math.random() * 2 - 1)).toLong()
                delay(PRESENCE_INTERVAL_MS + jitter)
            }
        }
    }

    /**
     * Шлёт один presence-PATCH (typingTs + onlineTs).
     *
     * Оптимизированная версия: использует кэш lastKnownProfiles для write-only PATCH
     * (без GET). Кэш обновляется из единого polling loop каждые POLL_INTERVAL_MS, поэтому
     * максимальное "протухание" данных партнёра — один тик (8 сек). ephemeralPubKey
     * партнёра сохраняется нетронутым — берётся прямо из кэша, not overwritten.
     *
     * Fallback на полный GET+PATCH только если наш профиль ещё не попал в кэш
     * (первые секунды после открытия чата, до первого успешного poll).
     */
    private suspend fun doPushPresence(typingTs: Long, onlineTs: Long) {
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
                myEphemeralPubKey = myCurrentEphemeralPubKey,
                myName            = prefs.myName,
                myTag             = prefs.myTag,
                myAvatarBase64    = prefs.myAvatarBase64,
                myIdentityPubKey     = prefs.myIdentityPubKey,
                myEphemeralSig       = myEphemeralSig,
                myVerifiedPartnerIdk = prefs.getConfirmedPartnerIdentity(chat.gistId)
            )
        }

        // Обновляем локальный кэш сразу — следующий write-only push будет корректным
        if (ok) {
            lastKnownProfiles[myUserId]?.let { current ->
                lastKnownProfiles[myUserId] = current.copy(typingTs = typingTs, onlineTs = onlineTs)
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
        binding.etMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (s.isNullOrEmpty()) stopTypingSignal() else onTypingDetected()
                updateStickerSuggestions(s?.toString() ?: "")
                updateSendVoiceButtons(s?.toString() ?: "")
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
                    CryptoHelper.encrypt(plaintext, chat.chatPassword, chat.gistId)
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
                    isPending = true
                )
                chatStore.addOptimistic(pendingMsg)

                // 3. Шифруем изображение для пакетной отправки
                val encryptedImage = withContext(Dispatchers.Default) {
                    CryptoHelper.encrypt(base64, chat.chatPassword, chat.gistId)
                }

                // 4. Отправляем сообщение и изображение ОДНИМ запросом (Batch PATCH)
                withContext(Dispatchers.IO) {
                    transport.appendLine(
                        encryptedLine = encryptedMessage,
                        extraFiles = mapOf(imageFileName to encryptedImage)
                    )
                }

                // Cache-bust и форс-синк для быстрого скрытия часиков
                lastContent = ""
                syncEngine.forceSync(delayMs = 0L)

            } catch (e: Exception) {
                tempEncrypted?.let { chatStore.failSend(it) }
                val reason = e.message?.take(120) ?: "unknown"
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    Toast.LENGTH_LONG).show()
            } finally {
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
                    CryptoHelper.encrypt(plaintext, chat.chatPassword, chat.gistId)
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
                                CryptoHelper.encrypt(b64, chat.chatPassword, chat.gistId)
                            }
                            synchronized(extraFiles) {
                                extraFiles[fileName] = encrypted
                            }
                        }
                    }
                }

                if (extraFiles.size != total) throw RuntimeException(getString(R.string.error_image_load))

                // 4. Отправляем сообщение и все изображения ОДНИМ запросом (Batch PATCH)
                withContext(Dispatchers.IO) {
                    transport.appendLine(
                        encryptedLine = encryptedMessage,
                        extraFiles = extraFiles
                    )
                }

                lastContent = ""
                syncEngine.forceSync(delayMs = 0L)

            } catch (e: Exception) {
                tempEncrypted?.let { chatStore.failSend(it) }
                val reason = e.message?.take(120) ?: "unknown"
                Toast.makeText(this@ChatActivity,
                    getString(R.string.error_send) + "\n" + reason,
                    Toast.LENGTH_LONG).show()
            } finally {
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

    private fun showMessageMenu(msg: Message, anchor: View) {
        TelegramMenu.show(
            ctx      = this,
            anchor   = anchor,
            items    = buildList {
                add(TelegramMenu.Item(getString(R.string.action_reply),  R.drawable.ic_reply_menu)   { startReply(msg) })
                add(TelegramMenu.Item(getString(R.string.action_copy),   R.drawable.ic_copy_menu)    { copyToClipboard(msg.text) })
                add(TelegramMenu.Item("Выбрать",                          R.drawable.ic_select_mode)  { adapter.enterSelectionMode(msg) })
                if (msg.isSelf) {
                    add(TelegramMenu.Item(getString(R.string.action_edit),   R.drawable.ic_edit_menu)  { showEditDialog(msg) })
                    add(TelegramMenu.Item(getString(R.string.action_delete), R.drawable.ic_trash_menu, isDestructive = true) { confirmDelete(msg) })
                }
            },
            onReaction = { emoji -> handleReactionToggle(msg.msgId, emoji) }
        )
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
            newReactionsContent, chat.chatPassword, chat.gistId
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
                        processGistData(data)
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
        val newEncrypted = CryptoHelper.encrypt(plaintext, chat.chatPassword, chat.gistId)

        // PatchQueue.ReplaceLine: сериализуется с остальными PATCH-операциями
        patchQueue.enqueue(PatchQueue.Action.ReplaceLine(
            oldLine  = msg.rawEncrypted,
            newLine  = newEncrypted,
            onResult = { ok ->
                if (!ok) {
                    Toast.makeText(this@ChatActivity,
                        getString(R.string.error_message_not_found), Toast.LENGTH_SHORT).show()
                }
                // Сбрасываем кэш — следующий тик SyncEngine покажет обновлённое сообщение
                lastContent = ""
            }
        ))
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
            // Exit selection mode — show input, hide selection bar
            binding.inputArea.visibility    = View.VISIBLE
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
        if (chat.gistToken == com.atrum.chat.transport.NostrTransport.NOSTR_DIRECT_TOKEN) return
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

    private fun showChatWarning(type: WarningType) {
        if (activeWarning == type) return   // уже показан — не мелькаем
        activeWarning = type

        val (title, message) = when (type) {
            WarningType.TOKEN           -> getString(R.string.warn_token_title)      to getString(R.string.warn_token_message)
            WarningType.RATE_LIMIT      -> getString(R.string.warn_rate_limit_title) to getString(R.string.warn_rate_limit_message)
            WarningType.NETWORK         -> getString(R.string.warn_network_title)    to getString(R.string.warn_network_message)
            WarningType.FORWARD_SECRECY -> getString(R.string.warn_fs_title)        to getString(R.string.warn_fs_message)
            WarningType.TOR             -> getString(R.string.warn_tor_title)       to getString(R.string.warn_tor_message)
        }
        binding.tvWarningTitle.text = title
        binding.tvWarningMessage.text = message
        // Любую ошибку (кроме информационного FS) сразу кладём в буфер обмена — чтобы прислать.
        if (type != WarningType.FORWARD_SECRECY) copyErrorToClipboard("$title — $message")

        // Для TOKEN — показываем кнопку «Обновить токен»; для остальных — скрываем.
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
                val newLoader = ImageLoader(transport, chat.chatPassword)
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
                prefs.saveChatSecrets(chat.gistId, trimmed, chat.chatPassword)
                @Suppress("DEPRECATION")
                chat = chat.copy(gistToken = trimmed)
                // Пересоздаём транспорт с новым токеном
                transportFactory = TransportFactory(
                    gistId     = chat.gistId,
                    gistToken  = trimmed,
                    chatPassword = chat.chatPassword,
                    myUserId   = prefs.myUserId,
                    context    = applicationContext
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
     * Вызывается один раз при первой успешной загрузке.
     *
     * Overlay уходит за 250 мс, RecyclerView появляется за 350 мс —
     * небольшой сдвиг даёт ощущение «контент проявляется» а не «мигает».
     */
    /** Запускает ожидание установки сессии с таймаутом, затем разрешает показ чата. */
    private fun startHandshakeGate() {
        if (chat.isFavorites) {
            handshakeSettled = true
            maybeReveal()
            return
        }

        lifecycleScope.launch {
            val deadline = System.currentTimeMillis() + handshakeRevealTimeoutMs
            while (System.currentTimeMillis() < deadline && !CryptoHelper.hasSessionKey(chat.gistId)) {
                delay(200)
            }
            handshakeSettled = true
            maybeReveal()
        }
    }

    /**
     * Показывает чат, как только загружен контент сообщений.
     * НЕ ждём рукопожатие (V3-сессию): иначе спиннер висел до установки сессии
     * с собеседником или до 10-с таймаута — отсюда "крутится просто так и
     * кончается когда хочет". Сессия доустанавливается в фоне; непрочитанные
     * V3-сообщения прикрыты отдельным FS-баннером (lockedV3ConsecutiveCount).
     */
    private fun maybeReveal() {
        if (firstLoadComplete) return
        if (contentLoaded) {
            firstLoadComplete = true
            revealMessages()
        }
    }

    private fun revealMessages() {
        val overlay = binding.loadingOverlay
        val rv      = binding.rvMessages

        // Overlay исчезает
        overlay.animate()
            .alpha(0f)
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction { overlay.visibility = View.GONE }
            .start()

        // RecyclerView проявляется чуть позже — ощущение плавного появления контента
        rv.animate()
            .alpha(1f)
            .setDuration(350)
            .setStartDelay(80)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withStartAction { rv.visibility = View.VISIBLE }
            .start()

        // Оверлей ушёл — теперь корректно показываем/прячем заглушку "чат пуст".
        binding.tvEmptyPlaceholder.visibility =
            if (currentMessages.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        /** Ключ intent-экстра: идентификатор чата (Long, из Room). */
        const val EXTRA_CHAT_ID = "extra_chat_id"

        // ── Polling ───────────────────────────────────────────────────────────
        // Интервал поллинга управляется SyncEngine.ACTIVE_INTERVAL_MS (5 сек).
        /** Базовый интервал адаптивного поллинга (зарезервирован, не используется). */
        const val BASE_MS = 4_000L
        /** Максимальный интервал адаптивного поллинга (зарезервирован, не используется). */
        const val MAX_MS = 30_000L

        // ── Presence ──────────────────────────────────────────────────────────
        /** Период presence-цикла: один write-only PATCH каждые N мс. */
        const val PRESENCE_INTERVAL_MS = 2_000L
        /**
         * Через сколько мс без обновления партнёр считается офлайн.
         * 32 сек = PRESENCE_INTERVAL_MS (12) + poll interval (8) + сеть (4) + запас (8).
         * Гарантирует что партнёр не "мигает" при задержках сети.
         */
        const val ONLINE_EXPIRY_MS = 20_000L
        /** Через сколько мс без обновления typing-сигнал считается устаревшим. */
        const val TYPING_EXPIRY_MS = 14_000L
        /** Задержка после последнего нажатия клавиши до отправки «перестал печатать». */
        const val TYPING_STOP_DELAY_MS = 3_000L

        // ── Profile sync ──────────────────────────────────────────────────────
        /** Количество попыток sync профилей при запуске. */
        const val SYNC_PROFILES_MAX_ATTEMPTS = 3
        /** Базовая задержка между retry sync-профилей (умножается на номер попытки). */
        const val SYNC_PROFILES_RETRY_BASE_MS = 3_000L

        // ── Error handling ────────────────────────────────────────────────────
        /** Количество последовательных ошибок сети до показа баннера предупреждения. */
        const val FAILURES_BEFORE_WARNING = 5

        // ── Media ─────────────────────────────────────────────────────────────
        /** Максимальное количество фото в коллаже за одну отправку. */
        const val MAX_COLLAGE_IMAGES = 10
        /** Максимальная длительность голосового (5 минут). */
        private const val MAX_VOICE_MS = 5 * 60 * 1000L
        /** Максимальное количество одновременных загрузок изображений. */
        const val MAX_CONCURRENT = 3
    }
}
