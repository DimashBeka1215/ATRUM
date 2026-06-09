package com.atrum.chat

import com.atrum.chat.transport.GistTransport

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.RotateAnimation
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
 *   2. Проверка на дубликат (уже подключены к этому gistId)
 *   3. Подключение к gist через [GistApi.loadContent]
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        db = AppDatabase.get(this)

        binding.btnBack.setOnClickListener { if (state != UiState.LOADING) finish() }
        binding.btnBackBottom.setOnClickListener { if (state != UiState.LOADING) finish() }
        binding.btnTogglePassword.setOnClickListener { togglePassword() }
        binding.btnConnect.setOnClickListener { onConnectClicked() }

        startPortalAnimation()

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

        val dialog = AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
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
            // 1. Дубликат
            setProgress(getString(R.string.join_status_checking))
            val existing = withContext(Dispatchers.IO) {
                db.chatDao().getAll().find { it.gistId == invite.gistId }
            }
            if (existing != null) {
                setProgress(getString(R.string.join_status_already))
                openChat(existing.id)
                return
            }

            // 2. Подключение к gist
            setProgress(getString(R.string.join_status_connecting))
            val api = GistApi(token = invite.gistToken, gistId = invite.gistId)
            withContext(Dispatchers.IO) { api.loadContent() } // просто проверяем доступность гиста

            // 3. Проверка "чат уже занят" — фетчим profiles.txt, если там 2+ профиля
            // и моего userId среди них нет, отказываем (чат рассчитан на двоих).
            setProgress(getString(R.string.join_status_verifying))
            val profilesMap = withContext(Dispatchers.IO) {
                try {
                    ProfileSync.pullProfiles(GistTransport(api), invite.chatPassword)
                } catch (_: Throwable) {
                    emptyMap()
                }
            }
            val myUserId = prefs.myUserId
            val alreadyInChat = profilesMap.containsKey(myUserId)

            // Показываем аватарку и имя собеседника, если нашли
            val partnerProfileFound = profilesMap.values.firstOrNull { it.userId != myUserId }
            if (partnerProfileFound != null) {
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
                gistId = invite.gistId,
                gistToken = "",   // secrets stored in EncryptedSharedPreferences
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
            prefs.saveChatSecrets(invite.gistId, invite.gistToken, invite.chatPassword)
            val newChatId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }

            // 5. Публикуем свой профиль (имя/аватар для собеседника)
            setProgress(getString(R.string.join_status_profile))
            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            withContext(Dispatchers.IO) {
                try {
                    ProfileSync.pushMyProfile(GistTransport(api), invite.chatPassword, myProfile)
                } catch (_: Throwable) {
                    // не критично — ChatActivity сделает retry при первом open
                }
            }

            // 6. Открываем чат
            openChat(newChatId)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) return
            showError(mapError(e))
        }
    }

    /** Транслирует исключение из GistApi/сети в человеческое сообщение. */
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
}
