package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.databinding.ActivityUpdateBinding
import kotlinx.coroutines.launch

/**
 * Полноэкранный экран обновления.
 *
 * Режимы:
 *  • UPDATE — есть новая версия: показывает ченджлог, кнопки «Обновить» / «Позже»
 *  • UP_TO_DATE — версия актуальна: показывает статус, кнопка «Закрыть»
 */
class UpdateActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUpdateBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val tagName      = intent.getStringExtra(EXTRA_TAG)
        val changelog    = intent.getStringExtra(EXTRA_CHANGELOG)
        val htmlUrl      = intent.getStringExtra(EXTRA_URL)
        val forceRefresh = intent.getBooleanExtra(EXTRA_FORCE_REFRESH, false)

        if (tagName != null && htmlUrl != null) {
            showUpdate(tagName, changelog, htmlUrl)
        } else {
            showLoading()
            lifecycleScope.launch {
                val release = ForceUpdateChecker.checkLatestRelease(this@UpdateActivity, forceRefresh)
                if (release != null) {
                    showUpdate(release.tagName, release.changelog, release.htmlUrl)
                } else {
                    showUpToDate()
                }
            }
        }
    }

    // ── Состояния ──────────────────────────────────────────────────────────

    private fun showLoading() {
        binding.ivHeroIcon.setImageResource(R.drawable.ic_refresh)
        binding.ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_tertiary))
        binding.tvHeroTitle.text = getString(R.string.update_screen_checking)
        binding.tvHeroSubtitle.text = ""
        binding.cardChangelog.visibility = View.GONE
        binding.btnUpdate.visibility = View.GONE
        binding.btnLater.text = getString(R.string.update_screen_btn_close)
        binding.btnLater.setOnClickListener { finish() }
        setupSourceLinks(null)
    }

    private fun showUpdate(tag: String, changelog: String?, url: String) {
        val currentVersion = currentVersionName()
        binding.ivHeroIcon.setImageResource(R.drawable.ic_bell)
        binding.ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent))
        binding.tvHeroTitle.text = getString(R.string.update_screen_title_new, tag)
        binding.tvHeroSubtitle.text = getString(R.string.update_screen_subtitle_new, currentVersion)

        binding.cardChangelog.visibility = View.VISIBLE
        binding.tvChangelog.text = changelog?.takeIf { it.isNotBlank() }
            ?: getString(R.string.update_dialog_body_fallback)

        binding.btnUpdate.visibility = View.VISIBLE
        binding.btnUpdate.setOnClickListener { openUrl(url) }
        binding.btnLater.text = getString(R.string.update_dialog_negative)
        binding.btnLater.setOnClickListener { finish() }

        setupSourceLinks(url)
    }

    private fun showUpToDate() {
        val currentVersion = currentVersionName()
        binding.ivHeroIcon.setImageResource(R.drawable.ic_check)
        binding.ivHeroIcon.setColorFilter(ContextCompat.getColor(this, R.color.online))
        binding.tvHeroTitle.text = getString(R.string.update_screen_title_ok)
        binding.tvHeroSubtitle.text = getString(R.string.update_screen_subtitle_ok, currentVersion)

        binding.cardChangelog.visibility = View.GONE
        binding.btnUpdate.visibility = View.GONE
        binding.btnLater.text = getString(R.string.update_screen_btn_close)
        binding.btnLater.setOnClickListener { finish() }

        setupSourceLinks(null)
    }

    private fun setupSourceLinks(releaseUrl: String?) {
        val releasesUrl = releaseUrl ?: "https://github.com/DimashBeka1215/ATRUM/releases"
        val codeUrl     = "https://github.com/DimashBeka1215/ATRUM"
        binding.cardSourceReleases.setOnClickListener { openUrl(releasesUrl) }
        binding.cardSourceCode.setOnClickListener { openUrl(codeUrl) }
    }

    private fun openUrl(url: String) {
        try {
            AppLock.beginShareGrace()
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }

    private fun currentVersionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    // ── Companion ──────────────────────────────────────────────────────────

    companion object {
        private const val EXTRA_TAG           = "tag"
        private const val EXTRA_CHANGELOG     = "changelog"
        private const val EXTRA_URL           = "url"
        private const val EXTRA_FORCE_REFRESH = "force_refresh"

        fun startForCheck(context: Context, forceRefresh: Boolean = false) {
            context.startActivity(
                Intent(context, UpdateActivity::class.java)
                    .putExtra(EXTRA_FORCE_REFRESH, forceRefresh)
            )
        }

        fun startWithRelease(context: Context, info: ForceUpdateChecker.ReleaseInfo) {
            context.startActivity(
                Intent(context, UpdateActivity::class.java)
                    .putExtra(EXTRA_TAG, info.tagName)
                    .putExtra(EXTRA_CHANGELOG, info.changelog)
                    .putExtra(EXTRA_URL, info.htmlUrl)
            )
        }
    }
}
