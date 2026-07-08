package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.BluetoothTransport
import com.atrum.chat.transport.NostrTransport
import com.atrum.chat.nostr.NostrRelayPool

import android.content.Intent
import android.os.Bundle
import android.text.InputType
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

                setProgress(getString(R.string.join_status_verifying))
                val allData = withContext(Dispatchers.IO) {
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
                val membersParsed = allData?.membersContent
                    ?.takeIf { it.isNotBlank() }
                    ?.let { CryptoHelper.decrypt(it, invite.chatPassword, invite.channelId) }
                    ?.let { MembersSync.parse(it) }

                if (membersParsed != null) {
                    // members.txt уже есть (админ его публиковал) — источник истины.
                    val myEntry = membersParsed.participants.firstOrNull { it.userId == myUserId }
                    if (myEntry?.banned == true) {
                        showError(getString(R.string.join_err_banned))
                        return
                    }
                    val activeCount = membersParsed.participants.count { !it.banned }
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
                    groupName = invite.groupNameSeed
                )
                prefs.saveChatSecrets(invite.channelId, invite.transportToken, invite.chatPassword)
                val newGroupChatId = withContext(Dispatchers.IO) { db.chatDao().insert(groupChat) }

                // Уже полученный members.txt применяем сразу — мгновенный список участников
                // без ожидания следующего опроса (§1.5 CLAUDE.md — всё грузится на месте).
                if (allData != null && allData.membersContent.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val freshChat = db.chatDao().getById(newGroupChatId)
                            if (freshChat != null) {
                                MembersSync.applyIncoming(
                                    freshChat, allData.membersContent, invite.chatPassword,
                                    db.chatParticipantDao(), db.chatDao()
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                // Публикуем свой профиль в фоне — сигнал админу "я здесь". Само добавление
                // в members.txt делает клиент админа при следующем опросе (см. ChatActivity).
                val myGroupProfile = Profile(
                    userId = prefs.myUserId,
                    name = prefs.myName,
                    tag = prefs.myTag,
                    avatarBase64 = prefs.myAvatarBase64
                )
                AppScope.launch {
                    try {
                        ProfileSync.pushMyProfile(transport, invite.chatPassword, myGroupProfile)
                    } catch (_: Exception) {}
                }

                openChat(newGroupChatId)
                return
            }

            // 3. Проверка "чат уже занят" — фетчим profiles.txt, если там 2+ профиля
            // и моего userId среди них нет, отказываем (чат рассчитан на двоих).
            setProgress(getString(R.string.join_status_verifying))
            val profilesMap = withContext(Dispatchers.IO) {
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
            val myUserId = prefs.myUserId
            val alreadyInChat = profilesMap.containsKey(myUserId)

            // Показываем аватарку и имя собеседника, если нашли
            val partnerProfileFound = profilesMap.values.firstOrNull { it.userId != myUserId }
            if (partnerProfileFound != null) {
                if (isTor) {
                    TorSyncWatchdog.disarm(invite.channelId, "партнёр найден при первом pullProfiles (JoinChatActivity)")
                }
                withContext(Dispatchers.Main) {
                    showPartnerInfo(partnerProfileFound)
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

    private fun showPartnerInfo(profile: Profile) {
        val bitmap = AvatarUtils.fromBase64(profile.avatarBase64)
        if (bitmap != null) {
            binding.ivPartnerAvatar.setImageBitmap(bitmap)
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

        if (profile.name.isNotBlank()) {
            binding.tvPartnerName.text = profile.name
            if (!profile.tag.isNullOrBlank()) {
                binding.tvPartnerTag.text = profile.tag
                binding.tvPartnerTag.visibility = View.VISIBLE
            } else {
                binding.tvPartnerTag.visibility = View.GONE
            }

            binding.llPartnerInfoGroup.visibility = View.VISIBLE
            binding.llPartnerInfoGroup.alpha = 0f
            binding.llPartnerInfoGroup.animate()
                .alpha(1f)
                .setDuration(1000)
                .setStartDelay(300)
                .start()
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
    }
}
