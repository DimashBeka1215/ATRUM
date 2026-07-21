package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.BluetoothTransport
import com.atrum.chat.transport.NostrTransport
import com.atrum.chat.transport.AllChannelData
import com.atrum.chat.nostr.NostrRelayPool

import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityJoinChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Подключение к существующему чату по invite-паролю.
 *
 * Поток подключения (полностью функциональный, не stub):
 *   1. Валидация ввода → декод [InviteCodec.decode]
 *   2. Проверка на дубликат (уже подключены к этому chatId)
 *   3. Подключение к gist через [транспорт чата]
 *   4. Если есть сообщения — пробуем расшифровать первое для проверки пароля
 *   5. Сохраняем [Chat] локально через Room
 *   6. Публикуем свой профиль через [ProfileSync.pushMyProfile]
 *   7. Открываем [ChatActivity]
 *
 * Все этапы отображаются в inline-статусе [ActivityJoinChatBinding.tvStatus].
 * Ошибки на каждом этапе транслируются в человеческие сообщения через [mapError].
 *
 * Поддерживаемые error cases:
 *   — пустое поле
 *   — не invite формат
 *   — повреждённый invite
 *   — нет интернета (UnknownHostException)
 *   — timeout (SocketTimeoutException)
 *   — 404 (чат удалён / срок истёк)
 *   — 401/403 (токен в invite отозван)
 *   — 5xx (GitHub down)
 *   — неверный пароль для расшифровки
 *   — дубликат (открываем существующий чат вместо нового)
 */
class JoinChatActivity : SecureActivity() {

    private lateinit var binding: ActivityJoinChatBinding
    private lateinit var prefs: Prefs
    private lateinit var db: AppDatabase

    private var passwordVisible: Boolean = false
    private var connectJob: Job? = null

    private enum class UiState { IDLE, LOADING, ERROR, WARNING }
    private var state: UiState = UiState.IDLE

    /**
     * Пульс портала "в такт сердцу" (мокап одобрен пользователем, см. запрос —
     * спокойное дыхание в режиме ожидания, активнее и быстрее когда чат/группа
     * реально подтверждены сетью, с виброоткликом в такт). Смотри startPortalHeartbeat/
     * triggerFoundPulse ниже.
     */
    private var heartbeatAnimator: ValueAnimator? = null
    private var portalFoundPulseActive = false

    private val scanLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { res ->
        if (res.resultCode == RESULT_OK) {
            val raw = res.data?.getStringExtra(QrScanActivity.EXTRA_RAW)
            InviteCodec.extractInvite(raw)?.let { prefillAndConnect(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = AppDatabase.get(this)

        // Прогрев соединений с реле сразу при открытии экрана ввода приглашения —
        // к моменту нажатия "Подключиться" TLS уже установлен, соединение мгновенное.
        if (TorManager.status.value != TorManager.TorStatus.IDLE) NostrRelayPool.prewarm(NostrTransport.RELAYS)

        binding.btnBack.setOnClickListener { if (state != UiState.LOADING) finish() }
        binding.btnBackBottom.setOnClickListener { if (state != UiState.LOADING) finish() }
        binding.btnTogglePassword.setOnClickListener { togglePassword() }
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnScanQr.setOnClickListener {
            if (state == UiState.LOADING) return@setOnClickListener
            scanLauncher.launch(Intent(this, QrScanActivity::class.java).apply {
                putExtra(QrScanActivity.EXTRA_MODE, QrScanActivity.MODE_INVITE)
            })
        }

        startPortalAnimation()
        startPortalHeartbeat(fast = false)

        // Приглашение могло прийти из штатной камеры (deep-link atrum://join#…)
        // или быть передано явным extra (например, со сканера на экране приглашения).
        val incoming = intent.getStringExtra(EXTRA_PREFILL)
            ?: InviteCodec.extractInvite(intent.data?.toString())
        if (!incoming.isNullOrBlank()) {
            binding.root.post { prefillAndConnect(incoming) }
        }

        // Проактивная валидация ввода
        binding.etPassword.addTextChangedListener(SimpleTextWatcher {
            val input = binding.etPassword.text?.toString().orEmpty().trim()

            // Если была фатальная ошибка после нажатия "Подключиться" — сбрасываем её
            if (state == UiState.ERROR) clearStatus()

            if (input.isNotEmpty()) {
                if (!input.startsWith(InviteCodec.PREFIX)) {
                    showWarning(getString(R.string.join_err_not_invite))
                } else if (input.length < InviteCodec.PREFIX.length + 10) {
                    showWarning(getString(R.string.join_err_corrupt))
                } else {
                    // Похоже на валидный код — убираем предупреждения
                    if (state == UiState.WARNING) clearStatus()
                }
            } else {
                clearStatus()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        connectJob?.cancel()
        heartbeatAnimator?.cancel()
    }

    // ═══ Основной flow ═══

    /** Подставляет invite в поле и запускает обычный путь подключения (с запросом PIN при необходимости). */
    private fun prefillAndConnect(invite: String) {
        if (state == UiState.LOADING) return
        binding.etPassword.setText(invite)
        binding.etPassword.setSelection(invite.length)
        clearStatus()
        onConnectClicked()
    }

    private fun onConnectClicked() {
        if (state == UiState.LOADING) return

        val input = binding.etPassword.text?.toString().orEmpty().trim()
        if (input.isBlank()) {
            showError(getString(R.string.join_err_empty))
            return
        }

        if (InviteCodec.looksLikeEncryptedInvite(input)) {
            showPinDialog(input)
            return
        }

        val invite = InviteCodec.decodeLegacy(input)
        if (invite == null) {
            val message = if (!InviteCodec.looksLikeInvite(input)) {
                getString(R.string.join_err_not_invite)
            } else {
                getString(R.string.join_err_corrupt)
            }
            showError(message)
            return
        }

        connectJob?.cancel()
        connectJob = lifecycleScope.launch { runConnect(invite) }
    }

    private fun showPinDialog(input: String) {
        val view = layoutInflater.inflate(R.layout.dialog_join_pin, null)
        val etPin = view.findViewById<EditText>(R.id.et_join_pin)
        etPin.requestFocus()

        val dialog = AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<android.widget.Button>(R.id.btn_cancel).setOnClickListener {
            dialog.dismiss()
        }
        view.findViewById<android.widget.Button>(R.id.btn_connect).setOnClickListener {
            val pin = etPin.text.toString()
            try {
                val invite = InviteCodec.decode(input, pin)
                if (invite != null) {
                    dialog.dismiss()
                    connectJob?.cancel()
                    connectJob = lifecycleScope.launch { runConnect(invite) }
                } else {
                    showError(getString(R.string.join_err_corrupt))
                }
            } catch (e: InviteCodec.ExpiredException) {
                dialog.dismiss()
                showError(getString(R.string.join_err_expired))
            } catch (e: Exception) {
                dialog.dismiss()
                showError(getString(R.string.join_err_wrong_pin))
            }
        }
        dialog.show()
    }

    private suspend fun runConnect(invite: InviteCodec.Decoded) {
        try {
            // 0. BT-чат по приглашению не присоединяется — он локальный (только рядом по BLE).
            if (invite.transportToken == BluetoothTransport.BT_TOKEN) {
                showError(getString(R.string.join_err_bt))
                return
            }
            // 1. Дубликат
            setProgress(getString(R.string.join_status_checking))
            val existing = withContext(Dispatchers.IO) {
                db.chatDao().getAll().find { it.chatId == invite.channelId }
            }
            if (existing != null) {
                setProgress(getString(R.string.join_status_already))
                openChat(existing.id)
                return
            }

            // 2. Подключение к транспорту: DHT (token=="dht") или legacy Gist.
            setProgress(getString(R.string.join_status_connecting))
            val isTor = invite.transportToken != NostrTransport.NOSTR_DIRECT_TOKEN
            // Ленивый старт Tor, если путь чата — через Tor.
            if (isTor) {
                TorManager.start(applicationContext)
                // Взводим сторож синхронизации ПРЯМО в момент нажатия «Подключиться» — см.
                // TorSyncWatchdog.kt. Отчёт (CrashActivity) придёт, если синхронизация этого
                // чата не подтвердится ни разу за окно наблюдения, либо раньше — на любое
                // отклонение от сценария (см. вызовы reportDeviation ниже).
                TorSyncWatchdog.arm(applicationContext, invite.channelId)
            }
            // Все чаты сейчас живут в Nostr. Путь (Tor/прямой) берём из токена приглашения.
            // Групповой чат (ADR-001): передаём adminUserId — транспорту нужен ДО первого
            // чтения, чтобы проверять подпись members.txt (см. NostrTransport.adminPubkeyHex).
            val transport: ChatTransport =
                if (invite.isGroup) {
                    NostrTransport(
                        invite.channelId, invite.chatPassword, prefs.myUserId,
                        preferTor = isTor, adminUserId = invite.adminUserId
                    )
                } else {
                    NostrTransport(
                        invite.channelId, invite.chatPassword, prefs.myUserId,
                        preferTor = isTor
                    )
                }

            // ── Групповой чат (ADR-001) — отдельная ветка, полностью решающая свою часть
            // подключения (проверка бана/лимита, локальная запись, открытие чата) и
            // возвращающая управление ДО существующего 1:1-пути ниже. Существующий 1:1-код
            // (шаги 3-6) не тронут ни строкой — он рассчитан ровно на двух участников и
            // не должен исполняться для групп.
            if (invite.isGroup) {
                val myUserId = prefs.myUserId

                // ⚠️ Фикс (найдено по репорту пользователя: в портале не показывались
                // название и аватар группы). Причина — showPartnerInfo() вызывался ТОЛЬКО
                // в 1:1-пути ниже по коду; групповая ветка всегда завершается через
                // return (см. конец этой ветки) и до него никогда не доходила. Название
                // группы известно СРАЗУ из инвайта (groupNameSeed, зашито без сети) —
                // показываем не дожидаясь сети (§1.5 CLAUDE.md). Аватар группы в инвайт
                // не зашит (раздул бы код в разы) — подтянем его чуть ниже, как только
                // получим members.txt/сеть.
                withContext(Dispatchers.Main) {
                    showFoundInfo(
                        name = invite.groupNameSeed?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.cc_group_default_name),
                        tag = null,
                        avatarBase64 = null
                    )
                }

                setProgress(getString(R.string.join_status_verifying))
                var allData: AllChannelData? = null
                var membersParsed: MembersSync.MembersFile? = null
                var groupProfileParsed: GroupProfileSync.GroupProfile? = null
                // ⚠️ Bounded retry (см. companion.JOIN_PROFILE_MAX_ATTEMPTS) — не показываем
                // "нашли"/не сохраняем чат, пока название+аватар группы не подтверждены сетью,
                // но и не ждём бесконечно: после исчерпания попыток идём дальше с тем, что
                // есть (сид-имя из инвайта, аватар подтянется позже через ChatActivity).
                for (attempt in 0 until JOIN_PROFILE_MAX_ATTEMPTS) {
                    allData = withContext(Dispatchers.IO) {
                        try {
                            transport.loadAll()
                        } catch (e: Throwable) {
                            if (isTor) {
                                TorSyncWatchdog.reportDeviation(
                                    applicationContext, invite.channelId, "JoinChatActivity.loadAll(group)", e
                                )
                            }
                            null
                        }
                    }
                    membersParsed = allData?.membersContent
                        ?.takeIf { it.isNotBlank() }
                        ?.let { CryptoHelper.decrypt(it, invite.chatPassword, invite.channelId) }
                        ?.let { MembersSync.parse(it) }
                    // «Профиль беседы» (groupprofile.txt, GroupProfileSync) — приходит в том
                    // же loadAll(): маленькое стабильное событие с именем/авой/описанием,
                    // не затирается энроллами (в отличие от members.txt) — самый надёжный
                    // и быстрый источник данных группы для новичка.
                    groupProfileParsed = allData?.groupProfileContent
                        ?.takeIf { it.isNotBlank() }
                        ?.let { CryptoHelper.decrypt(it, invite.chatPassword, invite.channelId) }
                        ?.let { GroupProfileSync.parse(it) }
                        ?: groupProfileParsed

                    if (membersParsed != null) {
                        // members.txt уже есть (админ его публиковал) — источник истины.
                        val myEntry = membersParsed!!.participants.firstOrNull { it.userId == myUserId }
                        if (myEntry?.banned == true) {
                            showError(getString(R.string.join_err_banned))
                            return
                        }
                        val activeCount = membersParsed!!.participants.count { !it.banned }
                        val limit = invite.participantLimit
                        if (myEntry == null && limit != null && activeCount >= limit) {
                            showError(getString(R.string.join_err_group_full))
                            return
                        }
                    } else {
                        // members.txt ещё не долетело (задержка реле) — приближённая проверка
                        // по profiles.txt. Best-effort, не криптографическое принуждение —
                        // см. ADR_GROUP_CHATS.md, тот же уровень доверия, что у самого инвайта.
                        val profilesMap = withContext(Dispatchers.IO) {
                            try {
                                ProfileSync.pullProfiles(transport, invite.chatPassword)
                            } catch (_: Throwable) {
                                emptyMap()
                            }
                        }
                        val limit = invite.participantLimit
                        if (!profilesMap.containsKey(myUserId) && limit != null && profilesMap.size >= limit) {
                            showError(getString(R.string.join_err_group_full))
                            return
                        }
                    }

                    val groupInfoReady = groupProfileParsed?.groupName?.isNotBlank() == true ||
                        groupProfileParsed?.groupAvatarBase64?.isNotBlank() == true ||
                        membersParsed?.groupName?.isNotBlank() == true ||
                        membersParsed?.groupAvatarBase64?.isNotBlank() == true
                    if (groupInfoReady || attempt == JOIN_PROFILE_MAX_ATTEMPTS - 1) break
                    setProgress(getString(R.string.join_status_loading_profile))
                    delay(JOIN_PROFILE_RETRY_DELAY_MS)
                }

                // Название/аватар группы, подтверждённые СЕТЬЮ (members.txt реально
                // получен) — обновляем превью в портале И переводим пульс в "активный"
                // режим (мокап одобрен пользователем). Специально ПОСЛЕ бан/лимит-
                // проверок выше: если группа забанила/переполнена, мы уже вышли по
                // return и не показываем "нашли" + не запускаем радостный пульс для
                // чата, в который на самом деле не попали.
                if (groupProfileParsed?.groupName != null || groupProfileParsed?.groupAvatarBase64 != null ||
                    membersParsed?.groupName != null || membersParsed?.groupAvatarBase64 != null) {
                    withContext(Dispatchers.Main) {
                        showFoundInfo(
                            name = groupProfileParsed?.groupName?.takeIf { it.isNotBlank() }
                                ?: membersParsed?.groupName?.takeIf { it.isNotBlank() }
                                ?: invite.groupNameSeed?.takeIf { it.isNotBlank() }
                                ?: getString(R.string.cc_group_default_name),
                            tag = null,
                            avatarBase64 = groupProfileParsed?.groupAvatarBase64
                                ?: membersParsed?.groupAvatarBase64
                        )
                        triggerFoundPulse()
                    }
                }

                setProgress(getString(R.string.join_status_saving))
                @Suppress("DEPRECATION")
                val groupChat = Chat(
                    chatId = invite.channelId,
                    transportToken = "",
                    chatPassword = "",
                    // partnerName — легаси-поле для экранов, ещё не переведённых на groupName.
                    partnerName = invite.groupNameSeed?.takeIf { it.isNotBlank() }
                        ?: getString(R.string.cc_group_default_name),
                    lastMessage = "",
                    lastTimeMs = System.currentTimeMillis(),
                    isGroup = true,
                    participantLimit = invite.participantLimit,
                    adminUserId = invite.adminUserId,
                    // ⚠️ Фикс (репорт: «при заходе по приглашению ава чата подхватывается
                    // не всегда»): имя/аву группы, УЖЕ подтверждённые сетью выше
                    // (members.txt получен в retry-цикле), кладём в строку чата сразу при
                    // создании — не полагаясь только на applyIncoming ниже (он может не
                    // примениться, если конкретная копия members.txt не расшифровалась/
                    // оказалась битой с одного из реле) и тем более не дожидаясь
                    // следующего опроса уже открытого чата.
                    groupName = groupProfileParsed?.groupName?.takeIf { it.isNotBlank() }
                        ?: membersParsed?.groupName?.takeIf { it.isNotBlank() }
                        ?: invite.groupNameSeed,
                    groupAvatarBase64 = groupProfileParsed?.groupAvatarBase64?.takeIf { it.isNotBlank() }
                        ?: membersParsed?.groupAvatarBase64?.takeIf { it.isNotBlank() }
                )
                // Анти-откат профиля беседы стартует с реально применённого ts (см.
                // GroupProfileSync.applyIncoming) — отставшее реле не откатит имя/аву.
                groupProfileParsed?.let { gp -> prefs.setGroupProfileTs(invite.channelId, gp.ts) }
                prefs.saveChatSecrets(invite.channelId, invite.transportToken, invite.chatPassword)
                val newGroupChatId = withContext(Dispatchers.IO) { db.chatDao().insert(groupChat) }

                // Уже полученный members.txt применяем сразу — мгновенный список участников
                // без ожидания следующего опроса (§1.5 CLAUDE.md — всё грузится на месте).
                // val-снимок перед использованием внутри лямбды — allData теперь var
                // (обновляется в retry-цикле выше), а Kotlin не даёт smart-cast для var,
                // захваченного в closure.
                val allDataSnapshot = allData
                if (allDataSnapshot != null && allDataSnapshot.membersContent.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val freshChat = db.chatDao().getById(newGroupChatId)
                            if (freshChat != null) {
                                MembersSync.applyIncoming(
                                    freshChat, allDataSnapshot.membersContent, invite.chatPassword,
                                    db.chatParticipantDao(), db.chatDao()
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Децентрализованный ростер (ADR-001): сразу наполняем участников из уже
                // полученных самоопубликованных профилей — счётчик виден мгновенно и НЕ
                // ждёт, пока админ (возможно офлайн) зачислит нас в members.txt.
                if (allDataSnapshot != null && allDataSnapshot.profileSlotsSigned.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val freshChat = db.chatDao().getById(newGroupChatId)
                            if (freshChat != null) {
                                GroupRosterSync.applyProfileRoster(
                                    chat = freshChat,
                                    signedSlots = allDataSnapshot.profileSlotsSigned,
                                    password = invite.chatPassword,
                                    participantDao = db.chatParticipantDao(),
                                    myUserId = prefs.myUserId,
                                    adminUserId = freshChat.adminUserId,
                                    pubkeyForUserId = transport::pubkeyForUserId
                                )
                            }
                        }
                    }
                }

                // ⭐ Фаза 3 (ADR_MESSAGE_AUTHENTICITY.md §10): закрепляем identity-ключ админа
                // ИЗ ИНВАЙТА — авторитетно и ДО сети, поэтому его нельзя подменить фейковым
                // профилем (TOFU-poisoning). Ставим ПОСЛЕ применения членства/ростера, не затирая
                // banned/perm; дальше ключ переживает синк (MembersSync.upsertAll его сохраняет).
                invite.adminIdentityPubKey?.takeIf { it.isNotBlank() }?.let { adminIdk ->
                    val adminUid = invite.adminUserId
                    if (!adminUid.isNullOrBlank()) withContext(Dispatchers.IO) {
                        val pDao = db.chatParticipantDao()
                        if (pDao.getOne(newGroupChatId, adminUid) == null) {
                            pDao.upsert(
                                com.atrum.chat.data.ChatParticipant(
                                    ownerId = newGroupChatId, userId = adminUid,
                                    banned = false, pinnedIdentityPubKey = adminIdk
                                )
                            )
                        } else {
                            pDao.pinIdentityIfEmpty(newGroupChatId, adminUid, adminIdk)
                        }
                    }
                }

                // Публикуем свой профиль в фоне — сигнал "я здесь" всем участникам. Членство
                // считается из этого профиля напрямую (GroupRosterSync), без зависимости от
                // того, в сети ли админ; клиент админа при случае ещё и внесёт нас в
                // подписанный members.txt (оверлей модерации), но счётчику это уже не нужно.
                // ⚠️ Фикс (репорт: «админ видит меня обычным, галочку/иммунитет игнорит»;
                // диагност на устройстве админа показал idk=нет, isig=нет). Раньше профиль при
                // ВХОДЕ публиковался БЕЗ identityPubKey/identitySig — моя личность попадала в
                // беседу только когда я сам ОТКРОю чат (ChatActivity.pushMyProfile). Пока не
                // открыл — на реле лежал профиль без подписи, и собеседники физически не могли
                // меня верифицировать: ни галочки, ни иммунитета. Теперь identity кладётся сразу
                // при входе. Подпись — над доменом atrum_idsig_v1_<chatId> моим identity-ключом,
                // где chatId = invite.channelId (= Chat.chatId, по которому проверяет
                // VerifiedBadge.isVerifiedProfile — тот же домен, что в ChatActivity.computeIdentitySig).
                val myGroupIdentitySig = run {
                    val idPriv = prefs.getOrCreateIdentity().first
                    try {
                        CryptoHelper.signWithIdentity(idPriv, VerifiedBadge.identitySigData(invite.channelId))
                    } catch (_: Exception) {
                        null
                    } finally {
                        idPriv.fill(0) // затираем локальную копию приватника (§1)
                    }
                }
                val myGroupProfile = Profile(
                    userId = prefs.myUserId,
                    name = prefs.myName,
                    tag = prefs.myTag,
                    avatarBase64 = prefs.myAvatarBase64,
                    identityPubKey = prefs.myIdentityPubKey,
                    identitySig = myGroupIdentitySig
                )
                AppScope.launch {
                    try {
                        ProfileSync.pushMyProfile(transport, invite.chatPassword, myGroupProfile, prefs.getOrCreateIdentity().first)
                    } catch (_: Exception) {}
                }

                openChat(newGroupChatId)
                return
            }

            // 3. Проверка "чат уже занят" — фетчим profiles.txt, если там 2+ профиля
            // и моего userId среди них нет, отказываем (чат рассчитан на двоих).
            setProgress(getString(R.string.join_status_verifying))
            var profilesMap: Map<String, Profile> = emptyMap()
            var partnerProfileFound: Profile? = null
            val myUserId = prefs.myUserId
            // ⚠️ Bounded retry (см. companion.JOIN_PROFILE_MAX_ATTEMPTS, тот же принцип, что
            // и в групповой ветке выше) — не сохраняем чат/не открываем ChatActivity, пока
            // ава+ник собеседника не подтверждены сетью, но и не ждём бесконечно: партнёр
            // мог ещё не открыть чат/не опубликовать профиль вовсе — тогда идём дальше с
            // дефолтным именем, аватар подтянется позже сам (см. ChatActivity.doSyncProfilesOnce).
            for (attempt in 0 until JOIN_PROFILE_MAX_ATTEMPTS) {
                profilesMap = withContext(Dispatchers.IO) {
                    try {
                        ProfileSync.pullProfiles(transport, invite.chatPassword)
                    } catch (e: Throwable) {
                        if (isTor) {
                            TorSyncWatchdog.reportDeviation(
                                applicationContext, invite.channelId, "JoinChatActivity.pullProfiles", e
                            )
                        }
                        emptyMap()
                    }
                }
                partnerProfileFound = profilesMap.values.firstOrNull { it.userId != myUserId }
                val ready = partnerProfileFound != null &&
                    partnerProfileFound!!.name.isNotBlank() &&
                    !partnerProfileFound!!.avatarBase64.isNullOrBlank()
                if (ready || attempt == JOIN_PROFILE_MAX_ATTEMPTS - 1) break
                setProgress(getString(R.string.join_status_loading_profile))
                delay(JOIN_PROFILE_RETRY_DELAY_MS)
            }
            val alreadyInChat = profilesMap.containsKey(myUserId)

            // Показываем аватарку и имя собеседника, если нашли
            if (partnerProfileFound != null) {
                if (isTor) {
                    TorSyncWatchdog.disarm(invite.channelId, "партнёр найден при первом pullProfiles (JoinChatActivity)")
                }
                withContext(Dispatchers.Main) {
                    showFoundInfo(
                        name = partnerProfileFound!!.name,
                        tag = partnerProfileFound!!.tag,
                        avatarBase64 = partnerProfileFound!!.avatarBase64,
                        verified = VerifiedBadge.isVerifiedProfile(partnerProfileFound, invite.channelId)
                    )
                    triggerFoundPulse()
                }
            }

            if (profilesMap.size >= 2 && !alreadyInChat) {
                showError(getString(R.string.join_err_chat_full))
                return
            }

            // 4. Локальная запись чата.
            //    partnerJoined=true сразу — раз мы джойнимся, в чате уже двое (я + создатель).
            //    Если найден partner profile в profilesMap — берём его имя/аватар.
            setProgress(getString(R.string.join_status_saving))
            val partnerProfile = profilesMap.values.firstOrNull { it.userId != myUserId }
            @Suppress("DEPRECATION")
            val chat = Chat(
                chatId = invite.channelId,
                transportToken = "",   // secrets stored in EncryptedSharedPreferences
                chatPassword = "",
                partnerName = partnerProfile?.name
                    ?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.join_default_partner_name),
                partnerTag = partnerProfile?.tag,
                partnerAvatarBase64 = partnerProfile?.avatarBase64,
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                partnerJoined = true
            )
            // Save secrets in EncryptedSharedPreferences before DB insert
            prefs.saveChatSecrets(invite.channelId, invite.transportToken, invite.chatPassword)
            val newChatId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }

            // 5. Профиль публикуем В ФОНЕ — не блокируем открытие чата (ChatActivity
            //    всё равно опубликует профиль при открытии). AppScope переживёт finish().
            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            AppScope.launch {
                try {
                    val ok = ProfileSync.pushMyProfile(transport, invite.chatPassword, myProfile)
                    if (!ok && isTor) {
                        // ProfileSync.lastError — реальная причина, проглоченная внутри
                        // pushMyProfile (см. ProfileSync.kt). Читаем сразу после false —
                        // безопасно, см. doc-comment lastError.
                        val cause = ProfileSync.lastError
                            ?: IllegalStateException("pushMyProfile вернул false, но lastError пуст")
                        TorSyncWatchdog.reportDeviation(applicationContext, invite.channelId, "JoinChatActivity.pushMyProfile", cause)
                    }
                } catch (e: Throwable) {
                    if (isTor) {
                        TorSyncWatchdog.reportDeviation(applicationContext, invite.channelId, "JoinChatActivity.pushMyProfile", e)
                    }
                }
            }

            // 6. Открываем чат сразу
            openChat(newChatId)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) return
            if (invite.transportToken != NostrTransport.NOSTR_DIRECT_TOKEN) {
                TorSyncWatchdog.reportDeviation(applicationContext, invite.channelId, "JoinChatActivity.runConnect", e)
            }
            showError(mapError(e))
        }
    }

    /** Транслирует сетевое исключение в человеческое сообщение. */
    private fun mapError(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            e is UnknownHostException -> getString(R.string.join_err_no_internet)
            e is SocketTimeoutException -> getString(R.string.join_err_timeout)
            msg.contains("HTTP 404", ignoreCase = true) ||
                    msg.contains("не найден") ->
                getString(R.string.join_err_not_found)
            msg.contains("HTTP 401", ignoreCase = true) ||
                    msg.contains("HTTP 403", ignoreCase = true) ->
                getString(R.string.join_err_access)
            msg.contains("HTTP 5", ignoreCase = true) ->
                getString(R.string.join_err_server)
            msg.contains("Empty response", ignoreCase = true) ||
                    msg.contains("Пустой ответ") ->
                getString(R.string.join_err_empty_response)
            else -> getString(R.string.join_err_generic_fmt, msg.take(120))
        }
    }

    private fun openChat(chatId: Long) {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
    }

    // ═══ UI states ═══

    private fun setProgress(text: String) {
        state = UiState.LOADING
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(getColor(R.color.text_secondary))
        binding.tvStatus.visibility = View.VISIBLE
        setInteractive(false)
        binding.btnConnect.text = getString(R.string.join_btn_connecting)
    }

    private fun showError(text: String) {
        state = UiState.ERROR
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(getColor(R.color.error))
        binding.tvStatus.visibility = View.VISIBLE
        setInteractive(true)
        binding.btnConnect.text = getString(R.string.join_chat_connect)
        // Ошибка ПОСЛЕ того, как уже показали "нашли" (см. triggerFoundPulse) — например,
        // группа оказалась переполнена уже после показа превью. Возвращаем портал в
        // спокойный пульс, а не оставляем его "радостно" биться рядом с ошибкой.
        if (portalFoundPulseActive) {
            portalFoundPulseActive = false
            startPortalHeartbeat(fast = false)
        }
    }

    private fun showWarning(text: String) {
        // Не перекрываем загрузку проактивным предупреждением
        if (state == UiState.LOADING) return
        state = UiState.WARNING
        binding.tvStatus.text = text
        binding.tvStatus.setTextColor(getColor(R.color.warning))
        binding.tvStatus.visibility = View.VISIBLE
    }

    private fun clearStatus() {
        state = UiState.IDLE
        binding.tvStatus.visibility = View.GONE
    }

    private fun setInteractive(enabled: Boolean) {
        binding.btnConnect.isEnabled = enabled
        binding.btnConnect.alpha = if (enabled) 1f else 0.6f
        binding.etPassword.isEnabled = enabled
        binding.btnTogglePassword.isEnabled = enabled
        binding.btnBack.isEnabled = enabled
        binding.btnBackBottom.isEnabled = enabled
    }

    private fun togglePassword() {
        passwordVisible = !passwordVisible
        val cursor = binding.etPassword.selectionEnd
        binding.etPassword.inputType = if (passwordVisible) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        binding.etPassword.setSelection(cursor.coerceAtLeast(0))
    }

    private fun startPortalAnimation() {
        // Медленное вращение портала
        val rotate = RotateAnimation(
            0f, 360f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 20000
            repeatCount = Animation.INFINITE
            interpolator = LinearInterpolator()
        }
        binding.ivDoor.startAnimation(rotate)
    }

    /**
     * Пульс портала "в такт сердцу" (мокап одобрен пользователем). Спокойный режим
     * (fast=false) — мягкое двойное биение раз ~1.8с, слабый виброотклик на каждый
     * удар (как в покое). Активный (fast=true) — чаще и сильнее (~0.9с), запускается
     * ТОЛЬКО из triggerFoundPulse() при реально подтверждённом сетью "нашли" (см.
     * вызовы в runConnect — НЕ на мгновенном локальном groupNameSeed, это ещё не сеть).
     *
     * Крутится параллельно с startPortalAnimation() (медленное вращение) — ObjectAnimator
     * (scale/alpha, свойства View) и старый Animation (rotate, canvas-матрица при
     * отрисовке) используют разные механизмы и корректно комбинируются на одной вью.
     */
    private fun startPortalHeartbeat(fast: Boolean) {
        heartbeatAnimator?.cancel()
        val view = binding.ivDoor
        val duration = if (fast) 900L else 1800L
        val peakA = if (fast) 1.12f else 1.05f
        val peakB = if (fast) 1.08f else 1.03f
        val alphaBase = if (fast) 0.88f else 0.78f
        val alphaPeak = 1f

        fun kf(fraction: Float, value: Float) = Keyframe.ofFloat(fraction, value)
        val scaleXHolder = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            kf(0f, 1f), kf(0.15f, peakA), kf(0.30f, 1f), kf(0.45f, peakB), kf(0.60f, 1f), kf(1f, 1f)
        )
        val scaleYHolder = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            kf(0f, 1f), kf(0.15f, peakA), kf(0.30f, 1f), kf(0.45f, peakB), kf(0.60f, 1f), kf(1f, 1f)
        )
        val alphaHolder = PropertyValuesHolder.ofKeyframe(
            View.ALPHA,
            kf(0f, alphaBase), kf(0.15f, alphaPeak), kf(0.30f, alphaBase),
            kf(0.45f, alphaPeak), kf(0.60f, alphaBase), kf(1f, alphaBase)
        )

        // Виброудар РОВНО в момент двух "ударов" анимации (15% и 45% цикла) — читаем
        // ту же animatedFraction, что двигает scale/alpha, поэтому вибро и визуальный
        // пульс не могут разъехаться по времени (единый источник времени — один аниматор).
        var firedFirstBeat = false
        var firedSecondBeat = false
        val animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleXHolder, scaleYHolder, alphaHolder).apply {
            this.duration = duration
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedFraction
                if (!firedFirstBeat && f >= 0.15f) {
                    firedFirstBeat = true
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (!firedSecondBeat && f >= 0.45f) {
                    firedSecondBeat = true
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (f < 0.05f) {
                    firedFirstBeat = false
                    firedSecondBeat = false
                }
            }
        }
        animator.start()
        heartbeatAnimator = animator
    }

    /**
     * Переход в "активный" пульс — вызывать РОВНО ОДИН РАЗ на реально подтверждённое
     * сетью "нашли" (см. класс-докстринг startPortalHeartbeat). Повторные вызовы
     * (например, второй showFoundInfo в групповой ветке — уточнение аватара уже
     * после первого показа) не должны переигрывать акцентный удар заново — эффект
     * "заело" вместо однократного приятного акцента.
     */
    private fun triggerFoundPulse() {
        if (portalFoundPulseActive) return
        portalFoundPulseActive = true
        binding.ivDoor.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startPortalHeartbeat(fast = true)
    }

    /**
     * Показывает найденного собеседника/группу в портале (аватар + имя + тег).
     * Общая точка для 1:1 (profiles.txt) и группового (groupNameSeed/members.txt)
     * путей — раньше это работало ТОЛЬКО для 1:1 (showPartnerInfo(profile: Profile)),
     * групповая ветка в runConnect() никогда её не вызывала (см. фикс в runConnect).
     *
     * avatarBase64 == null допустим (например, для группы имя уже известно из инвайта,
     * а аватар ещё не долетел из сети) — тогда просто не трогаем аватар-вью, показываем
     * только имя; аватар появится отдельным вызовом этой же функции, когда придёт сеть.
     */
    private fun showFoundInfo(name: String, tag: String?, avatarBase64: String?, verified: Boolean = false) {
        val bitmap = AvatarUtils.fromBase64(avatarBase64)
        if (bitmap != null) {
            val alreadyShown = binding.ivPartnerAvatar.visibility == View.VISIBLE
            binding.ivPartnerAvatar.setImageBitmap(bitmap)
            if (!alreadyShown) {
                binding.ivPartnerAvatar.visibility = View.VISIBLE
                binding.ivPartnerAvatar.alpha = 0f
                binding.ivPartnerAvatar.scaleX = 0.5f
                binding.ivPartnerAvatar.scaleY = 0.5f
                binding.ivPartnerAvatar.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(1000)
                    .setInterpolator(OvershootInterpolator())
                    .start()
            }
        }

        if (name.isNotBlank()) {
            // Галочка «Разработчик ATRUM» у ника в превью по инвайту (неподделываемо —
            // verified посчитан по подписи identity в вызывающем коде, см. VerifiedBadge).
            binding.tvPartnerName.text =
                if (verified) VerifiedBadge.nameWithBadge(this, name) else name
            if (!tag.isNullOrBlank()) {
                binding.tvPartnerTag.text = tag
                binding.tvPartnerTag.visibility = View.VISIBLE
            } else {
                binding.tvPartnerTag.visibility = View.GONE
            }

            if (binding.llPartnerInfoGroup.visibility != View.VISIBLE) {
                binding.llPartnerInfoGroup.visibility = View.VISIBLE
                binding.llPartnerInfoGroup.alpha = 0f
                binding.llPartnerInfoGroup.animate()
                    .alpha(1f)
                    .setDuration(1000)
                    .setStartDelay(300)
                    .start()
            }
        }
    }

    /** Минималистичный TextWatcher для одного callback. */
    private class SimpleTextWatcher(val onChanged: () -> Unit) : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged()
        override fun afterTextChanged(s: android.text.Editable?) {}
    }

    companion object {
        /** Готовая invite-строка для автоподстановки (со сканера/экрана приглашения). */
        const val EXTRA_PREFILL = "prefill_invite"

        // ⚠️ Фикс (репорт: "ава в окне ввода инвайта грузится с задержкой, но со временем
        // подхватывается"). Раньше и 1:1-, и групповой путь делали РОВНО ОДНУ попытку
        // прочитать profiles.txt/members.txt перед тем, как показать превью и сохранить
        // чат — на нестабильном Tor-соединении она нередко возвращалась пустой (партнёр
        // ещё не успел опубликовать профиль/сеть не ответила), и превью оставалось без
        // авы/имени до тех пор, пока это не подхватит уже ChatActivity после открытия.
        // Простое решение по запросу пользователя: не считать данные готовыми, пока
        // ава+имя не пришли, и ПОКАЗЫВАТЬ ЗАГРУЗКУ, повторяя попытку в фоне — но
        // ОГРАНИЧЕННО (не бесконечно), т.к. данные и так рано или поздно подхватятся
        // самим ChatActivity после открытия чата (см. doSyncProfilesOnce/processChannelData).
        // 6 попыток по 2.5с ≈ 15с — тот же порядок величины, что и остальные bounded-retry
        // окна в проекте (см. GroupStatsActivity/UserStatsActivity.FIRST_LOAD_MAX_ATTEMPTS).
        const val JOIN_PROFILE_MAX_ATTEMPTS = 6
        const val JOIN_PROFILE_RETRY_DELAY_MS = 2_500L
    }
}
