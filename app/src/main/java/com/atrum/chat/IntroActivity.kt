package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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

        binding.pager.adapter = IntroAdapter(pages)
        binding.pager.offscreenPageLimit = 1

        // Индикаторы страниц
        buildIndicators(pages.size)
        updateIndicator(0)

        binding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateIndicator(position)
                binding.btnNext.text = getString(
                    if (position == pages.size - 1) R.string.intro_get_started
                    else R.string.intro_next
                )
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
}

/** Модель одной intro-страницы. */
data class IntroPage(val iconRes: Int, val title: String, val subtitle: String, val tintColor: Int? = null)

/** Адаптер для ViewPager2 — простые intro-страницы. */
class IntroAdapter(private val pages: List<IntroPage>) :
    RecyclerView.Adapter<IntroAdapter.VH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_intro_page, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(pages[position])
    override fun getItemCount(): Int = pages.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.iv_icon)
        private val title: TextView = view.findViewById(R.id.tv_title)
        private val subtitle: TextView = view.findViewById(R.id.tv_subtitle)

        fun bind(page: IntroPage) {
            icon.setImageResource(page.iconRes)
            if (page.tintColor != null) {
                icon.setColorFilter(page.tin