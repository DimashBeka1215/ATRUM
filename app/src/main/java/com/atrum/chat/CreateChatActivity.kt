package com.atrum.chat

import com.atrum.chat.transport.GistTransport

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityCreateChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Создание чата. Три экрана с переключением:
 *
 *   1) CHOICE — стартовый: три карточки (GitHub / Join / Manual)
 *   2) GITHUB — premium дизайн: profile preview + features card + info warning +
 *               duration chips → DeviceFlow OAuth. Пароль генерируется автоматически.
 *   3) MANUAL — продвинутый режим: 4 поля (имя/token/gist/пароль)
 *
 * Срок жизни чата: enum [Duration] → передаётся в DeviceFlowActivity как extra.
 */
class CreateChatActivity : SecureActivity() {

    private lateinit var binding: ActivityCreateChatBinding
    private lateinit var db: AppDatabase
    private lateinit var prefs: Prefs

    private enum class Screen { CHOICE, GITHUB, MANUAL }
    private var screen: Screen = Screen.CHOICE

    private enum class Duration(val days: Int) {
        DAY_1(1), DAY_7(7), DAY_30(30), DAY_90(90), UNLIMITED(-1)
    }
    private var selectedDuration: Duration = Duration.UNLIMITED

    /** Пароль, который будет использован при создании чата через GitHub OAuth. */
    private val generatedPassword: String = generateSecurePassword()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.get(this)
        prefs = Prefs(this)

        // Фильтр без пробелов на secret-поля
        val noWhitespace = InputFilter { source, start, end, _, _, _ ->
            val original = source.subSequence(start, end).toString()
            val cleaned = original.filter { !it.isWhitespace() }
            if (cleaned == original) null else cleaned
        }
        listOf(
            binding.etToken, binding.etGist, binding.etManualPassword
        ).forEach { it.filters = arrayOf<InputFilter>(noWhitespace) + it.filters }

        // Watcher для пароля и всех полей в Manual-форме
        binding.etManualPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                showPwdError(binding.tvManualPwdError, s?.toString().orEmpty())
                updateManualButtonState()
            }
        })
        val manualFieldWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateManualButtonState() }
        }
        binding.etPartnerName.addTextChangedListener(manualFieldWatcher)
        binding.etToken.addTextChangedListener(manualFieldWatcher)
        binding.etGist.addTextChangedListener(manualFieldWatcher)

        // Choice screen
        binding.cardGithub.setOnClickListener { showScreen(Screen.GITHUB) }
        binding.cardManual.setOnClickListener { showScreen(Screen.MANUAL) }
        binding.cardJoin.setOnClickListener {
            startActivity(Intent(this, JoinChatActivity::class.java))
        }
        binding.btnCancelChoice.setOnClickListener { finish() }
        binding.btnBackToChoice.setOnClickListener { showScreen(Screen.CHOICE) }

        // GitHub form
        setupDurationChips()
        binding.btnCreateGithub.setOnClickListener { startGithubFlow() }

        // Аватарка в GitHub-форме
        prefs.myAvatarBase64?.let { base64 ->
            AvatarUtils.fromBase64(base64)?.let { bmp -> binding.ivAvatar.setImageBitmap(bmp) }
        }

        startAvatarAnimations()

        // Manual form
        binding.btnCreateManual.setOnClickListener { createManual() }
        binding.btnHelpToken.setOnClickListener {
            openUrl("https://github.com/settings/tokens/new?scopes=gist&description=Atrum%20Chat")
        }
        binding.btnHelpGist.setOnClickListener {
            openUrl("https://gist.github.com")
        }

        showScreen(Screen.CHOICE)
        updateManualButtonState()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBackPressed() {
        if (screen != Screen.CHOICE) {
            showScreen(Screen.CHOICE)
        } else {
            super.onBackPressed()
        }
    }

    private fun showScreen(target: Screen) {
        screen = target
        binding.choiceScreen.visibility = if (target == Screen.CHOICE) View.VISIBLE else View.GONE
        binding.githubForm.visibility = if (target == Screen.GITHUB) View.VISIBLE else View.GONE
        binding.manualForm.visibility = if (target == Screen.MANUAL) View.VISIBLE else View.GONE
        binding.btnBackToChoice.visibility = if (target == Screen.CHOICE) View.GONE else View.VISIBLE
        binding.tvSubtitle.visibility = if (target == Screen.CHOICE) View.VISIBLE else View.GONE
    }

    // ═══ Avatar animations ═══

    private fun startAvatarAnimations() {
        // Желе-эффект: scaleX и scaleY анимируются в противофазе —
        // когда рамка растягивается по X, она сжимается по Y, и наоборот.
        // Это создаёт органичное упругое "дыхание" без какого-либо вращения.
        val jellyX = ObjectAnimator.ofFloat(
            binding.flAvatarGlow, View.SCALE_X,
            1f, 1.07f, 0.95f, 1.04f, 0.98f, 1f
        ).apply {
            duration = 2_200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val jellyY = ObjectAnimator.ofFloat(
            binding.flAvatarGlow, View.SCALE_Y,
            1f, 0.95f, 1.07f, 0.98f, 1.04f, 1f
        ).apply {
            duration = 2_200
            repeatCount = ObjectAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        AnimatorSet().apply { playTogether(jellyX, jellyY); start() }

        // Fade-in при входе на экран
        binding.flAvatarContainer.alpha = 0f
        binding.flAvatarContainer.animate()
            .alpha(1f)
            .setDuration(600)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    // ═══ Duration chips ═══

    private fun setupDurationChips() {
        val chips = listOf(
            binding.chip1d   to Duration.DAY_1,
            binding.chip7d   to Duration.DAY_7,
            binding.chip30d  to Duration.DAY_30,
            binding.chip90d  to Duration.DAY_90,
            binding.chipUnlim to Duration.UNLIMITED
        )
        applyDurationSelection(chips)
        chips.forEach { (view, duration) ->
            view.setOnClickListener {
                selectedDuration = duration
                applyDurationSelection(chips)
            }
        }
    }

    private fun applyDurationSelection(chips: List<Pair<android.widget.TextView, Duration>>) {
        chips.forEach { (view, duration) ->
            val selected = duration == selectedDuration
            view.setBackgroundResource(
                if (selected) R.drawable.bg_chip_selected else R.drawable.bg_chip_default
            )
            view.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    this,
                    if (selected) R.color.accent_light else R.color.text_secondary
                )
            )
        }
    }

    // ═══ GitHub OAuth ═══

    private fun startGithubFlow() {
        val roomName = prefs.myName.takeIf { it.isNotBlank() } ?: "Чат"

        val intent = Intent(this, OAuthWarningActivity::class.java).apply {
            putExtra(DeviceFlowActivity.EXTRA_ROOM_NAME, roomName)
            putExtra(DeviceFlowActivity.EXTRA_ROOM_PASSWORD, generatedPassword)
            putExtra(EXTRA_DURATION_DAYS, selectedDuration.days)
        }
        startActivity(intent)
        finish()
    }

    // ═══ Manual ═══

    private fun createManual() {
        val partnerName = binding.etPartnerName.text.toString().trim()
        val token = binding.etToken.text.toString().trim()
        val gist = binding.etGist.text.toString().trim()
        val pwd = binding.etManualPassword.text.toString().trim()

        if (partnerName.isEmpty() || token.isEmpty() || gist.isEmpty() || pwd.isEmpty()) {
            Toast.makeText(this, R.string.error_create_chat, Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                gistId = gist,
                gistToken = "",   // secrets stored in EncryptedSharedPreferences
                chatPassword = "",
                partnerName = partnerName,
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis()
            )
            // Save secrets in EncryptedSharedPreferences before DB insert
            prefs.saveChatSecrets(gist, token, pwd)
            val newId = db.chatDao().insert(chat)

            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            withContext(Dispatchers.IO) {
                try {
                    val api = GistApi(token = token, gistId = gist)
                    ProfileSync.pushMyProfile(GistTransport(api), pwd, myProfile)
                } catch (_: Exception) {
                }
            }

            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCreateGithub.isEnabled = !loading
        binding.btnCreateManual.isEnabled = !loading
    }

    // ═══ Валидация пароля (Manual) ═══

    private fun passwordError(pwd: String): String? {
        if (pwd.isEmpty()) return null
        if (pwd.any { it.isWhitespace() }) return "Введите пароль без пробелов"
        if (pwd.any { it == '%' || it.code < 0x20 || it.code > 0x7E })
            return "Удалите недопустимый символ"
        return null
    }

    private fun showPwdError(errorView: android.widget.TextView, pwd: String) {
        val err = passwordError(pwd)
        if (err != null) {
            errorView.text = err
            errorView.visibility = View.VISIBLE
        } else {
            errorView.visibility = View.GONE
        }
    }

    private fun updateManualButtonState() {
        val partnerName = binding.etPartnerName.text?.toString().orEmpty().trim()
        val token       = binding.etToken.text?.toString().orEmpty().trim()
        val gist        = binding.etGist.text?.toString().orEmpty().trim()
        val pwd         = binding.etManualPassword.text?.toString().orEmpty()
        val allFilled   = partnerName.isNotEmpty() && token.isNotEmpty()
                && gist.isNotEmpty() && pwd.isNotEmpty()
        val pwdOk       = passwordError(pwd) == null
        val enabled     = allFilled && pwdOk
        binding.btnCreateManual.isEnabled = enabled
        binding.btnCreateManual.alpha = if (enabled) 1f else 0.45f
    }

    companion object {
        /** -1 = бессрочно, иначе количество дней до истечения. */
        const val EXTRA_DURATION_DAYS = "duration_days"

        private const val GENERATED_LEN = 14
        private const val GENERATED_ALPHABET =
            "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        fun generateSecurePassword(): String {
            val rng = SecureRandom()
            val sb = StringBuilder(GENERATED_LEN)
            repeat(GENERATED_LEN) {
                sb.append(GENERATED_ALPHABET[rng.nextInt(GENERATED_ALPHABET.length)])
            }
            return sb.toString()
        }
    }
}
