package com.atrum.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.atrum.chat.databinding.ActivityIntroBinding

/**
 * Onboarding intro — показывается один раз при первом запуске.
 *
 *   Welcome → Шифрование → Транспорт → Безопасность → VPN → Поддержка → Бета
 *
 * После Skip/Get Started ставим Prefs.introShown=true и идём в
 * WelcomeActivity (которая дальше уже разруливает onboarding/lock/chats).
 */
class IntroActivity : AppCompatActivity() {

    private lateinit var binding: ActivityIntroBinding
    private lateinit var prefs: Prefs

    /**
     * Гейт карточки «Официальные источники» (запрос пользователя: код открыт, значит
     * встроенную защиту можно вырезать в модифицированной сборке — человек должен явно
     * подтвердить, что установил официальную версию, прежде чем идти дальше). true —
     * подтверждение уже дано в ЭТОЙ сессии просмотра intro; пока false — и «Далее», и
     * «Пропустить», и свайп заблокированы, пока не подтвердит (см. onPageSelected/
     * onOfficialSourceConfirmed). Не персистентно (Prefs) — это одноразовый экран
     * intro (prefs.introShown), повторно не показывается.
     */
    private var officialSourceConfirmed = false
    private val confirmPageIndex by lazy { pages.indexOfFirst { it.requireConfirm } }

    private val pages by lazy {
        listOf(
            IntroPage(
                iconRes = R.drawable.ic_chat_bubble,
                title = getString(R.string.intro_1_title),
                subtitle = getString(R.string.intro_1_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_lock,
                title = getString(R.string.intro_2_title),
                subtitle = getString(R.string.intro_2_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_p2p_network,
                title = getString(R.string.intro_3_title),
                subtitle = getString(R.string.intro_3_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_download,
                tintColor = ContextCompat.getColor(this, R.color.error),
                title = getString(R.string.intro_official_title),
                subtitle = getString(R.string.intro_official_subtitle),
                linkTelegramUrl = URL_TELEGRAM,
                linkGithubUrl = URL_GITHUB,
                requireConfirm = true
            ),
            IntroPage(
                iconRes = R.drawable.ic_warning,
                title = getString(R.string.intro_5_title),
                subtitle = getString(R.string.intro_5_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_globe,
                title = getString(R.string.intro_vpn_title),
                subtitle = getString(R.string.intro_vpn_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_heart,
                tintColor = 0xFFA855F7.toInt(),
                title = getString(R.string.intro_donate_title),
                subtitle = getString(R.string.intro_donate_subtitle)
            ),
            IntroPage(
                iconRes = R.drawable.ic_flash,
                title = getString(R.string.intro_4_title),
                subtitle = getString(R.string.intro_4_subtitle)
            )
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        if (BuildInfo.isTampered(this)) {
            UpdateRequiredActivity.launch(this)
            finish()
            return
        }

        // EULA — самая первая проверка, до intro и всего остального
        if (!prefs.eulaAccepted) {
            startActivity(Intent(this, EulaActivity::class.java))
            finish()
            return
        }

        // Если intro уже видели — пропускаем сразу дальше
        if (prefs.introShown) {
            goNext()
            return
        }

        binding = ActivityIntroBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Помечаем intro показанным СРАЗУ при показе — гарантирует «один раз и всё»,
        // даже если приложение убьют во время просмотра карточек.
        prefs.introShown = true

        binding.pager.adapter = IntroAdapter(
            pages = pages,
            isConfirmed = { officialSourceConfirmed },
            onConfirmed = { onOfficialSourceConfirmed() }
        )
        binding.pager.offscreenPageLimit = 1

        // Индикаторы страниц
        buildIndicators(pages.size)
        updateIndicator(0)
        applyConfirmGate(0)

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                binding.btnNext.text = getString(
                    if (position == pages.size - 1) R.string.intro_get_started
                    else R.string.intro_next
                )
                applyConfirmGate(position)
            }
        })

        binding.btnNext.setOnClickListener {
            val current = binding.pager.currentItem
            if (current == pages.size - 1) {
                finishIntro()
            } else {
                binding.pager.setCurrentItem(current + 1, true)
            }
        }

        binding.btnSkip.setOnClickListener { finishIntro() }
    }

    private fun buildIndicators(count: Int) {
        binding.indicatorContainer.removeAllViews()
        val density = resources.displayMetrics.density
        val sizePx = (6 * density).toInt()
        val activeWidthPx = (24 * density).toInt()
        val gapPx = (6 * density).toInt()

        for (i in 0 until count) {
            val dot = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    if (i > 0) leftMargin = gapPx
                }
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@IntroActivity, R.drawable.bg_page_indicator_inactive
                )
            }
            binding.indicatorContainer.addView(dot)
        }
        // Запомним размеры для апдейта
        binding.indicatorContainer.tag = Triple(sizePx, activeWidthPx, gapPx)
    }

    private fun updateIndicator(position: Int) {
        val (sizePx, activeWidthPx, gapPx) = binding.indicatorContainer.tag as Triple<*, *, *>
        for (i in 0 until binding.indicatorContainer.childCount) {
            val dot = binding.indicatorContainer.getChildAt(i)
            val params = dot.layoutParams as LinearLayout.LayoutParams
            if (i == position) {
                params.width = activeWidthPx as Int
                dot.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_page_indicator_active
                )
            } else {
                params.width = sizePx as Int
                dot.background = androidx.core.content.ContextCompat.getDrawable(
                    this, R.drawable.bg_page_indicator_inactive
                )
            }
            if (i > 0) params.leftMargin = gapPx as Int
            dot.layoutParams = params
        }
    }

    private fun finishIntro() {
        prefs.introShown = true
        goNext()
    }

    private fun goNext() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        // Плавный crossfade переход
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    /**
     * Гейт карточки «Официальные источники»: пока не подтверждено — блокирует ЛЮБОЙ способ
     * уйти со страницы (свайп в обе стороны, «Далее», «Пропустить»), чтобы предупреждение
     * реально было прочитано, а не проскочено случайным свайпом. Вызывается при каждой смене
     * страницы — снимает гейт с остальных страниц и накладывает ровно на страницу гейта.
     */
    private fun applyConfirmGate(position: Int) {
        val gated = position == confirmPageIndex && !officialSourceConfirmed
        binding.pager.isUserInputEnabled = !gated
        binding.btnNext.isEnabled = !gated
        binding.btnNext.alpha = if (gated) 0.4f else 1f
        binding.btnSkip.isEnabled = !gated
        binding.btnSkip.alpha = if (gated) 0.4f else 1f
    }

    /** Вызывается адаптером, когда пользователь нажал кнопку-подтверждение (после отсчёта). */
    private fun onOfficialSourceConfirmed() {
        officialSourceConfirmed = true
        applyConfirmGate(binding.pager.currentItem)
    }

    companion object {
        private const val URL_TELEGRAM = "https://t.me/Atrum_Chat"
        private const val URL_GITHUB = "https://github.com/DimashBeka1215/ATRUM"
    }
}

/** Модель одной intro-страницы. */
data class IntroPage(
    val iconRes: Int,
    val title: String,
    val subtitle: String,
    val tintColor: Int? = null,
    /** Ссылка-кнопка на Telegram-канал (карточка «Официальные источники»); null — скрыть. */
    val linkTelegramUrl: String? = null,
    /** Ссылка-кнопка на GitHub-репозиторий; null — скрыть. */
    val linkGithubUrl: String? = null,
    /** true — страница требует явного подтверждения (таймер + кнопка) перед уходом с неё. */
    val requireConfirm: Boolean = false
)

/**
 * Адаптер для ViewPager2 — intro-страницы.
 *
 * @param isConfirmed текущее состояние гейта «Официальные источники» (запрашивается
 *   заново при каждом bind — переживает пересоздание/переиспользование ViewHolder'а
 *   при свайпах, т.к. Activity хранит состояние, а не адаптер).
 * @param onConfirmed вызывается по тапу на кнопку-гейт (после отсчёта) — Activity снимает
 *   блокировку «Далее»/«Пропустить»/свайпа.
 */
class IntroAdapter(
    private val pages: List<IntroPage>,
    private val isConfirmed: () -> Boolean,
    private val onConfirmed: () -> Unit
) : RecyclerView.Adapter<IntroAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intro_page, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) =
        holder.bind(pages[position], isConfirmed, onConfirmed)

    override fun onViewRecycled(holder: VH) {
        super.onViewRecycled(holder)
        holder.cancelCountdown() // не оставляем тикающий таймер на переиспользуемой вьюхе
    }

    override fun getItemCount(): Int = pages.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_icon)
        private val title: TextView = view.findViewById(R.id.tv_title)
        private val subtitle: TextView = view.findViewById(R.id.tv_subtitle)
        private val linkRow: LinearLayout = view.findViewById(R.id.ll_link_row)
        private val btnTelegram: Button = view.findViewById(R.id.btn_link_telegram)
        private val btnGithub: Button = view.findViewById(R.id.btn_link_github)
        private val btnConfirm: Button = view.findViewById(R.id.btn_confirm_official)

        private var countdownTimer: CountDownTimer? = null

        fun cancelCountdown() {
            countdownTimer?.cancel()
            countdownTimer = null
        }

        private fun openUrl(url: String) {
            try {
                // Та же грация, что и в AboutActivity: тап по внешней ссылке не должен
                // «зажечь» экран блокировки при возврате из браузера.
                AppLock.beginShareGrace()
                itemView.context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (_: Exception) {}
        }

        fun bind(page: IntroPage, isConfirmed: () -> Boolean, onConfirmed: () -> Unit) {
            icon.setImageResource(page.iconRes)
            if (page.tintColor != null) {
                icon.setColorFilter(page.tintColor, android.graphics.PorterDuff.Mode.SRC_IN)
            } else {
                icon.clearColorFilter()
            }
            title.text = page.title
            subtitle.text = page.subtitle

            val hasLinks = page.linkTelegramUrl != null || page.linkGithubUrl != null
            linkRow.visibility = if (hasLinks) View.VISIBLE else View.GONE
            if (page.linkTelegramUrl != null) {
                btnTelegram.visibility = View.VISIBLE
                btnTelegram.setOnClickListener { openUrl(page.linkTelegramUrl) }
            } else {
                btnTelegram.visibility = View.GONE
            }
            if (page.linkGithubUrl != null) {
                btnGithub.visibility = View.VISIBLE
                btnGithub.setOnClickListener { openUrl(page.linkGithubUrl) }
            } else {
                btnGithub.visibility = View.GONE
            }

            cancelCountdown()
            if (!page.requireConfirm) {
                btnConfirm.visibility = View.GONE
                return
            }

            val ctx = itemView.context
            btnConfirm.visibility = View.VISIBLE
            if (isConfirmed()) {
                // Уже подтверждено в этой сессии просмотра — статичный вид, без таймера.
                btnConfirm.isEnabled = false
                btnConfirm.alpha = 1f
                btnConfirm.text = ctx.getString(R.string.intro_official_confirmed)
                btnConfirm.setOnClickListener(null)
                return
            }
            btnConfirm.isEnabled = false
            btnConfirm.alpha = 0.4f
            btnConfirm.setOnClickListener(null)
            btnConfirm.text = ctx.getString(R.string.intro_official_confirm_countdown_fmt, CONFIRM_GATE_SECONDS)
            countdownTimer = object : CountDownTimer(CONFIRM_GATE_SECONDS * 1000L, 1_000L) {
                override fun onTick(msLeft: Long) {
                    val secondsLeft = ((msLeft + 999) / 1000).toInt().coerceAtLeast(1)
                    btnConfirm.text = ctx.getString(R.string.intro_official_confirm_countdown_fmt, secondsLeft)
                }
                override fun onFinish() {
                    btnConfirm.isEnabled = true
                    btnConfirm.alpha = 1f
                    btnConfirm.text = ctx.getString(R.string.intro_official_confirm_btn)
                    btnConfirm.setOnClickListener {
                        btnConfirm.isEnabled = false
                        btnConfirm.text = ctx.getString(R.string.intro_official_confirmed)
                        onConfirmed()
                    }
                }
            }.start()
        }

        companion object {
            private const val CONFIRM_GATE_SECONDS = 10
        }
    }
}
