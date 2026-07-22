package com.atrum.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.databinding.ActivityCreditsBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Supporter(
    val nameRu: String,
    val nameEn: String,
    val quoteRu: String,
    val quoteEn: String,
    val avatarColor: String? = null,
    val avatarRes: Int? = null,
    /**
     * URL аватарки — грузится из СЕТИ во время открытия экрана, а НЕ хранится в APK (по просьбе).
     * Пока грузится/если офлайн — показывается инициал (avatarColor). Приоритет: avatarRes → avatarUrl → инициал.
     */
    val avatarUrl: String? = null,
    val clickUrl: String? = null,
    /** Тап по карточке открывает аватар крупно по центру (лёгкое затемнение фона). */
    val zoomAvatarOnTap: Boolean = false
)

class CreditsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreditsBinding

    private val urlDonationAlerts = "https://www.donationalerts.com/r/dimash_beka1215"
    private val urlBoosty        = "https://boosty.to/sky_pill"
    private val urlBuyMeCoffee   = "https://buymeacoffee.com/atrum"

    private val supporters = listOf(
        Supporter(
            nameRu = "Некромант",
            nameEn = "Necromant",
            quoteRu = "«Дьявольски хороший мессенджер, ЛОВИТ ДАЖЕ НА ПАРКОВКЕ, анонимнее некуда. Разработчик — легенда.»",
            quoteEn = "«Devilishly good messenger, WORKS EVEN IN A PARKING LOT, couldn't be more anonymous. The dev is a legend.»",
            avatarColor = "#1A2E1A",
            avatarRes = R.drawable.avatar_nekromant,
            clickUrl = "https://youtube.com/shorts/wUkO5MI2Mx8?si=U9W1CQ9huEq8AMvZ"
        ),
        Supporter(
            nameRu = "Мизуки Хамато",
            nameEn = "Mizuki Hamato",
            quoteRu = "«Github не стоит блокировать хотя бы, ради этого»",
            quoteEn = "«GitHub alone is worth not blocking, just for this»",
            avatarColor = "#8B1A1A",
            avatarRes = R.drawable.avatar_mizuki,
            clickUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1"
        ),
        Supporter(
            nameRu = "Себастьян Михаэлис",
            nameEn = "Sebastian Michaelis",
            quoteRu = "«Интересно, что будет дальше»",
            quoteEn = "«Curious to see what comes next»",
            avatarColor = "#1A1A2E",
            avatarRes = R.drawable.avatar_sebastian,
            clickUrl = "https://youtube.com/shorts/lZt99dXc10o?feature=share"
        ),
        Supporter(
            nameRu = "Никита Попов",
            nameEn = "Nikita Popov",
            quoteRu = "«Интернет — это живой организм. Если его отравлять и медленно душить, то, возможно, он поймёт, что заражён паразитами, и отправит антитела на устранение проблем. Мы — те самые антитела, которые чинят способ связи между людьми.»",
            quoteEn = "«The internet is a living organism. If you poison it and slowly strangle it, maybe it will realize it's infected with parasites and send antibodies to fix the problems. We are those antibodies — the ones repairing how people connect.»",
            avatarColor = "#101010",
            // Фото НЕ в APK — грузится из сети (ImgBB) при открытии экрана, фолбэк — инициал «Н».
            avatarUrl = "https://i.ibb.co.com/G3P1G9q1/IMG-20260721-045915-449.jpg"
        ),
        Supporter(
            nameRu = "Star Bling",
            nameEn = "Star Bling",
            quoteRu = "«За то реакции есть!!!»",
            quoteEn = "«But hey, at least there are reactions!!!»",
            avatarColor = "#1A1030",
            // Фото НЕ в APK — грузится из сети (postimg) при открытии экрана, фолбэк — инициал «S».
            avatarUrl = "https://i.postimg.cc/qq3kbVhz/IMG-20260722-160528-718.jpg",
            zoomAvatarOnTap = true
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreditsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        val isRu = resources.configuration.locales[0].language == "ru"
        binding.tvCount.text = if (isRu) "${supporters.size} ${ruPeople(supporters.size)}" else "${supporters.size} supporters"

        buildSupportersList(isRu)
        animateEntrance()

        binding.btnWantIn.setOnClickListener { showDonateDialog() }
    }

    // ═══ Build list ═══

    private fun buildSupportersList(isRu: Boolean) {
        val inflater = LayoutInflater.from(this)

        // Разные ритмы для каждого сторонника
        val glowPulseDurations  = longArrayOf(2_600L, 3_100L, 2_900L)
        val glowRotateDurations = longArrayOf(11_000L, 14_000L, 9_000L)
        val heartbeatDelays     = longArrayOf(0L, 400L, 800L)

        supporters.forEachIndexed { index, supporter ->
            val itemView  = inflater.inflate(R.layout.item_supporter, binding.llSupporters, false)
            val ivAvatar  = itemView.findViewById<com.google.android.material.imageview.ShapeableImageView>(R.id.ivAvatar)
            val flInitial = itemView.findViewById<View>(R.id.flInitial)
            val tvInitial = itemView.findViewById<TextView>(R.id.tvInitial)
            val vGlow     = itemView.findViewById<View>(R.id.vAvatarGlow)

            // Avatar content
            if (supporter.avatarRes != null) {
                ivAvatar.setImageResource(supporter.avatarRes)
                ivAvatar.visibility = View.VISIBLE
                flInitial.visibility = View.GONE
            } else {
                supporter.avatarColor?.let { color ->
                    try {
                        flInitial.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(color.toColorInt())
                    } catch (_: Exception) {}
                }
                val name = if (isRu) supporter.nameRu else supporter.nameEn
                tvInitial.text = name.trim().firstOrNull()?.uppercase() ?: "?"
                // Фото из СЕТИ (по просьбе — НЕ в APK): грузим поверх инициала, заменяем когда придёт.
                if (!supporter.avatarUrl.isNullOrBlank()) loadRemoteAvatar(supporter.avatarUrl, ivAvatar, flInitial)
            }

            itemView.findViewById<TextView>(R.id.tvName).text =
                if (isRu) supporter.nameRu else supporter.nameEn
            itemView.findViewById<TextView>(R.id.tvQuote).text =
                if (isRu) supporter.quoteRu else supporter.quoteEn

            if (supporter.clickUrl != null) {
                (itemView as android.view.ViewGroup).getChildAt(0)
                    .setOnClickListener { openUrl(supporter.clickUrl) }
            } else if (supporter.zoomAvatarOnTap) {
                (itemView as android.view.ViewGroup).getChildAt(0)
                    .setOnClickListener { showAvatarOverlay(ivAvatar.drawable) }
            }

            if (index == supporters.size - 1) {
                itemView.findViewById<View>(R.id.divider).visibility = View.GONE
            }

            binding.llSupporters.addView(itemView)

            // Запускаем анимации после того как карточка въехала
            val animDelay = 180L + index * 130L + 380L
            vGlow.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    // 1. Glow пульсирует (scale ±18% + alpha)
                    startGlowPulse(vGlow, glowPulseDurations[index % glowPulseDurations.size])
                    // 2. Glow медленно вращается (у второго — против часовой)
                    startGlowRotation(vGlow, glowRotateDurations[index % glowRotateDurations.size], reverse = index % 2 == 1)
                    // 3. Аватарка бьётся как сердце
                    val avatarTarget = if (ivAvatar.visibility == View.VISIBLE) ivAvatar else flInitial
                    startHeartbeat(avatarTarget, heartbeatDelays[index % heartbeatDelays.size])
                }
            }, animDelay)
        }
    }

    /** HTTP-клиент только для аватарок сторонников (прямое соединение, не Tor — экран благодарностей). */
    private val avatarHttp by lazy { okhttp3.OkHttpClient() }

    /**
     * Грузит аватарку сторонника ИЗ СЕТИ (по просьбе — не хранить фото в APK). Пока грузится/если
     * офлайн — остаётся инициал. Заменяем на месте по готовности (§1.5). Ошибки глушим — просто инициал.
     */
    private fun loadRemoteAvatar(
        url: String,
        iv: com.google.android.material.imageview.ShapeableImageView,
        initial: View
    ) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                runCatching {
                    avatarHttp.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@runCatching null
                        val bytes = resp.body?.bytes() ?: return@runCatching null
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                }.getOrNull()
            }
            if (bmp != null && !isFinishing && !isDestroyed) {
                iv.setImageBitmap(bmp)
                iv.visibility = View.VISIBLE
                initial.visibility = View.GONE
            }
        }
    }

    // ═══ Entrance animations ═══

    private fun animateEntrance() {
        binding.root.post {
            binding.tvCount.alpha = 0f
            binding.tvCount.animate().alpha(1f).setDuration(400).setStartDelay(60).start()

            for (i in 0 until binding.llSupporters.childCount) {
                val card = binding.llSupporters.getChildAt(i)
                card.alpha = 0f
                card.translationY = 60f
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420)
                    .setStartDelay(120L + i * 130L)
                    .setInterpolator(OvershootInterpolator(0.9f))
                    .start()
            }

            binding.btnWantIn.alpha = 0f
            binding.btnWantIn.translationY = 40f
            binding.btnWantIn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(380)
                .setStartDelay(120L + supporters.size * 130L + 60L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        }
    }

    // ═══ Avatar layer animations ═══

    /** Glow-кольцо: пульсация масштаба ±18% + alpha */
    private fun startGlowPulse(view: View, durationMs: Long) {
        val interp = AccelerateDecelerateInterpolator()
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.18f, 1f).apply {
            duration = durationMs; repeatCount = ObjectAnimator.INFINITE; interpolator = interp
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.18f, 1f).apply {
            duration = durationMs; repeatCount = ObjectAnimator.INFINITE; interpolator = interp
        }
        val al = ObjectAnimator.ofFloat(view, View.ALPHA, 0.6f, 1f, 0.6f).apply {
            duration = durationMs; repeatCount = ObjectAnimator.INFINITE; interpolator = interp
        }
        AnimatorSet().apply { playTogether(sx, sy, al); start() }
    }

    /** Glow-кольцо: медленное вращение */
    private fun startGlowRotation(view: View, durationMs: Long, reverse: Boolean) {
        ObjectAnimator.ofFloat(view, View.ROTATION, 0f, if (reverse) -360f else 360f).apply {
            duration = durationMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** Аватарка: heartbeat — scale 1→1.07→0.97→1 */
    private fun startHeartbeat(view: View, delayMs: Long) {
        val interp = AccelerateDecelerateInterpolator()
        val sx = ObjectAnimator.ofFloat(view, View.SCALE_X, 1f, 1.07f, 0.97f, 1f).apply {
            duration = 900; startDelay = delayMs; repeatCount = ObjectAnimator.INFINITE; interpolator = interp
        }
        val sy = ObjectAnimator.ofFloat(view, View.SCALE_Y, 1f, 1.07f, 0.97f, 1f).apply {
            duration = 900; startDelay = delayMs; repeatCount = ObjectAnimator.INFINITE; interpolator = interp
        }
        AnimatorSet().apply { playTogether(sx, sy); start() }
    }

    // ═══ Donate dialog ═══

    private fun showDonateDialog() {
        val dialog = BottomSheetDialog(this, R.style.Theme_AtrumChat_BottomSheet)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_donate, null, false)
        view.findViewById<View>(R.id.optionDonationAlerts).setOnClickListener {
            dialog.dismiss(); openUrl(urlDonationAlerts)
        }
        view.findViewById<View>(R.id.optionBoosty).setOnClickListener {
            dialog.dismiss(); openUrl(urlBoosty)
        }
        view.findViewById<View>(R.id.optionBuyMeCoffee).setOnClickListener {
            dialog.dismiss(); openUrl(urlBuyMeCoffee)
        }
        dialog.setContentView(view)
        dialog.show()
    }

    /**
     * Показывает аватар сторонника КРУПНО по центру экрана с лёгким затемнением фона.
     * Кастомный Dialog (не системное окно, §10 DESIGN): прозрачное окно + window-dim ~0.55,
     * круглый аватар со скруглением как в карточке, тап в любом месте — закрыть, плавное появление.
     * Битмап берётся уже загруженный (из ivAvatar) — второй раз из сети не тянем.
     */
    private fun showAvatarOverlay(drawable: android.graphics.drawable.Drawable?) {
        if (drawable == null || isFinishing || isDestroyed) return

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val container = android.widget.FrameLayout(this).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            isClickable = true
            isFocusable = true
        }

        val iv = com.google.android.material.imageview.ShapeableImageView(this).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(220), dp(220)).apply {
                gravity = android.view.Gravity.CENTER
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel
                .builder(context, R.style.ShapeAppearance_AtrumChat_Circle, 0).build()
            strokeColor = android.content.res.ColorStateList.valueOf(
                androidx.core.content.ContextCompat.getColor(context, R.color.accent_light)
            )
            strokeWidth = dp(2).toFloat()
            setImageDrawable(drawable)
        }
        container.addView(iv)

        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(container)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.55f) // лёгкое затемнение фона за аватаром
        }
        container.setOnClickListener { dialog.dismiss() }
        dialog.show()

        // Плавное появление: fade + лёгкий pop (§8 DESIGN: alpha, ≤300ms)
        iv.alpha = 0f
        iv.scaleX = 0.86f
        iv.scaleY = 0.86f
        iv.animate().alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.9f))
            .start()
    }

    /** Русское склонение слова «человек» для счётчика: 1 → человек, 2–4 → человека, 5+ → человек. */
    private fun ruPeople(n: Int): String {
        val m100 = n % 100
        val m10 = n % 10
        return when {
            m100 in 11..14 -> "человек"
            m10 == 1 -> "человек"
            m10 in 2..4 -> "человека"
            else -> "человек"
        }
    }

    private fun openUrl(url: String) {
        try {
            AppLock.beginShareGrace()
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: Exception) {}
    }
}
