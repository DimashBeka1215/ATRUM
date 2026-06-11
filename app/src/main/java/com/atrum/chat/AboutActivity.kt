package com.atrum.chat

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityAboutBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    private val urlGithub        = "https://github.com/DimashBeka1215/ATRUM"
    private val urlTelegram      = "https://t.me/Atrum_Chat"
    private val urlDonationAlerts = "https://www.donationalerts.com/r/dimash_beka1215"
    private val urlBoosty        = "https://boosty.to/sky_pill"
    private val urlBuyMeCoffee   = "https://buymeacoffee.com/atrum"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Версия приложения
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) { "" }
        binding.tvVersion.text = if (versionName.isNotBlank()) "Версия $versionName" else ""

        binding.btnBack.setOnClickListener { finish() }

        setupHiddenRestoreGesture()

        binding.itemDonate.setOnClickListener { showDonateDialog() }

        binding.itemGithub.alpha = 1f
        binding.itemGithub.setOnClickListener { openUrl(urlGithub) }

        binding.itemTelegram.setOnClickListener { openUrl(urlTelegram) }
    }

    // ── Тайный жест возврата скрытого входа по отпечатку ───────────────────────
    private var logoTapCount = 0
    private var lastLogoTapMs = 0L

    /** 7 тапов по иконке приложения возвращают временно скрытый вход по отпечатку. */
    private fun setupHiddenRestoreGesture() {
        binding.aboutAppLogo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastLogoTapMs > 1500L) logoTapCount = 0  // слишком медленно — сброс
            lastLogoTapMs = now
            logoTapCount++
            if (logoTapCount >= 7) {
                logoTapCount = 0
                val prefs = Prefs(this)
                if (prefs.biometricHidden) {
                    prefs.biometricHidden = false
                    Toast.makeText(this, R.string.biometric_restored, Toast.LENGTH_SHORT).show()
                }
                // Если не было скрыто — тихо игнорируем, жест остаётся незаметным.
            }
        }
    }

    private fun showDonateDialog() {
        val dialog = BottomSheetDialog(this, R.style.Theme_GithubChat_BottomSheet)
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_donate, null)

        view.findViewById<View>(R.id.optionDonationAlerts).setOnClickListener {
            dialog.dismiss()
            openUrl(urlDonationAlerts)
        }
        view.findViewById<View>(R.id.optionBoosty).setOnClickListener {
            dialog.dismiss()
            openUrl(urlBoosty)
        }
        view.findViewById<View>(R.id.optionBuyMeCoffee).setOnClickListener {
            dialog.dismiss()
            openUrl(urlBuyMeCoffee)
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }
}
