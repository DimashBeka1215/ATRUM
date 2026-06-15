package com.atrum.chat

import com.atrum.chat.transport.NostrTransport

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import com.atrum.chat.databinding.ActivityCreateChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * Создание чата. Два экрана с переключением:
 *
 *   1) CHOICE — стартовый: две карточки (Создать P2P / Присоединиться)
 *   2) CREATE — форма создания P2P-чата: profile preview + features + duration chips
 *               → создание чата поверх DHT. Пароль генерируется автоматически.
 *
 * Срок жизни чата: enum [Duration] → expiresAtMs у [Chat].
 */
class CreateChatActivity : SecureActivity() {

    private lateinit var binding: ActivityCreateChatBinding
    private lateinit var db: AppDatabase
    private lateinit var prefs: Prefs

    private enum class Screen { CHOICE, CREATE }
    private var screen: Screen = Screen.CHOICE

    private enum class Duration(val days: Int) {
        DAY_1(1), DAY_7(7), DAY_30(30), UNLIMITED(-1)
    }
    private var selectedDuration: Duration = Duration.UNLIMITED

    /** Путь сообщений: true = через Tor (по умолчанию), false = напрямую. */
    private var selectedTor: Boolean = true

    /** Пароль чата — генерируется автоматически при создании. */
    private val generatedPassword: String = generateSecurePassword()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.get(this)
        prefs = Prefs(this)

        // Choice screen
        binding.cardGithub.setOnClickListener { showScreen(Screen.CREATE) }
        binding.cardJoin.setOnClickListener {
            startActivity(Intent(this, JoinChatActivity::class.java))
        }
        binding.btnCancelChoice.setOnClickListener { finish() }
        binding.btnBackToChoice.setOnClickListener { showScreen(Screen.CHOICE) }

        // Create form (P2P / DHT)
        setupDurationChips()
        setupPathSelector()
        binding.btnCreateGithub.setOnClickListener { createP2pChat() }

        prefs.myAvatarBase64?.let { base64 ->
            AvatarUtils.fromBase64(base64)?.let { bmp -> binding.ivAvatar.setImageBitmap(bmp) }
        }

        startAvatarAnimations()

        showScreen(Screen.CHOICE)
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
        binding.githubForm.visibility = if (target == Screen.CREATE) View.VISIBLE else View.GONE
        binding.btnBackToChoice.visibility = if (target == Screen.CHOICE) View.GONE else View.VISIBLE
        binding.tvSubtitle.visibility = if (target == Screen.CHOICE) View.VISIBLE else View.GONE
    }

    // ═══ Avatar animations ═══

    private fun startAvatarAnimations() {
        // Желе-эффект: scaleX и scaleY анимируются в противофазе —
        // когда рамка растягивается по X, она сжимается по Y, и наоборот.
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

    // ═══ Путь сообщений (Nostr напрямую / через Tor) ═══

    private fun setupPathSelector() {
        applyPathSelection()
        binding.pathNostr.setOnClickListener {
            selectedTor = false; applyPathSelection(); animateBolt(binding.pathNostrIcon)
        }
        binding.pathTor.setOnClickListener {
            selectedTor = true; applyPathSelection(); animateShield(binding.pathTorIcon)
        }
    }

    /** Молния (Nostr): чёткий «рывок» вверх с подскоком масштаба. */
    private fun animateBolt(v: View) {
        v.animate().cancel()
        val up = -6f * resources.displayMetrics.density
        val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.32f, 0.92f, 1f)
        val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.32f, 0.92f, 1f)
        val ty = ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, up, 2f, 0f)
        AnimatorSet().apply {
            playTogether(sx, sy, ty)
            duration = 640
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    /** Щит (Tor): медленный защитный подскок с мягкой пружиной. */
    private fun animateShield(v: View) {
        v.animate().cancel()
        val sx = ObjectAnimator.ofFloat(v, View.SCALE_X, 1f, 1.26f, 0.97f, 1.06f, 1f)
        val sy = ObjectAnimator.ofFloat(v, View.SCALE_Y, 1f, 1.26f, 0.97f, 1.06f, 1f)
        AnimatorSet().apply {
            playTogether(sx, sy)
            duration = 720
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun applyPathSelection() {
        binding.pathNostr.setBackgroundResource(
            if (!selectedTor) R.drawable.bg_chip_selected else R.drawable.bg_chip_default
        )
        binding.pathTor.setBackgroundResource(
            if (selectedTor) R.drawable.bg_chip_selected else R.drawable.bg_chip_default
        )
        binding.pathNostrIcon.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this, if (!selectedTor) R.color.accent_light else R.color.text_secondary
            )
        )
        binding.pathTorIcon.setColorFilter(
            androidx.core.content.ContextCompat.getColor(
                this, if (selectedTor) R.color.accent_light else R.color.text_secondary
            )
        )
        binding.tvPathDesc.setText(
            if (selectedTor) R.string.cc_path_desc_tor else R.string.cc_path_desc_nostr
        )
    }

    // ═══ P2P (Nostr-реле) ═══

    /**
     * Создаёт P2P-чат поверх публичных Nostr-реле — без GitHub, без gist, без токена.
     * Личность чата = локально сгенерированный channelId; в поле токена пишем
     * маркер [NostrTransport.NOSTR_TOKEN], по которому TransportFactory выбирает Nostr.
     */
    private fun createP2pChat() {
        val roomName = prefs.myName.takeIf { it.isNotBlank() }
            ?: getString(R.string.join_default_partner_name)
        val channelId = generateChannelId()
        val password = generatedPassword
        val expiresAt = if (selectedDuration.days < 0) null
            else System.currentTimeMillis() + selectedDuration.days * 24L * 60 * 60 * 1000

        setLoading(true)
        lifecycleScope.launch {
            @Suppress("DEPRECATION")
            val chat = Chat(
                gistId = channelId,
                gistToken = "",
                chatPassword = "",
                partnerName = roomName,
                lastMessage = "",
                lastTimeMs = System.currentTimeMillis(),
                expiresAtMs = expiresAt
            )
            // Токен пути идёт и в секреты, и в приглашение → у собеседника тот же путь.
            val pathToken = if (selectedTor) NostrTransport.NOSTR_TOKEN
                            else NostrTransport.NOSTR_DIRECT_TOKEN
            prefs.saveChatSecrets(channelId, pathToken, password)
            // Ленивый старт Tor только для Tor-чата.
            if (selectedTor) TorManager.start(applicationContext)
            val newId = withContext(Dispatchers.IO) { db.chatDao().insert(chat) }

            val myProfile = Profile(
                userId = prefs.myUserId,
                name = prefs.myName,
                tag = prefs.myTag,
                avatarBase64 = prefs.myAvatarBase64
            )
            // Профиль публикуем В ФОНЕ — НЕ блокируем открытие чата сетью.
            // ChatActivity всё равно опубликует профиль при открытии; здесь дублируем
            // для надёжности через AppScope (переживёт finish() этого экрана).
            AppScope.launch {
                try {
                    val transport = NostrTransport(channelId, password, prefs.myUserId, useTor = selectedTor)
                    ProfileSync.pushMyProfile(transport, password, myProfile)
                } catch (_: Exception) {
                    // не критично — ChatActivity сделает retry при первом открытии
                }
            }

            startActivity(Intent(this@CreateChatActivity, ChatActivity::class.java).apply {
                putExtra(ChatActivity.EXTRA_CHAT_ID, newId)
            })
            finish()
        }
    }

    /** Случайный 128-битный идентификатор канала (32 hex-символа). */
    private fun generateChannelId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCreateGithub.isEnabled = !loading
    }

    companion object {
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
