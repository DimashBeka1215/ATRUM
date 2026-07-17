package com.atrum.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewAnimationUtils
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.atrum.chat.databinding.ActivityPersonalizationBinding

class PersonalizationActivity : SecureActivity() {

    private lateinit var binding: ActivityPersonalizationBinding
    private lateinit var prefs: Prefs

    // НАША галерея вместо системного ACTION_GET_CONTENT (см. pickWallpaper()/MediaPick).
    private val wallpaperPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.any { it }) MediaPick.pickOne(this, lifecycleScope) { onWallpaperPicked(it) }
        else Toast.makeText(this, R.string.gallery_perm_needed, Toast.LENGTH_SHORT).show()
    }

    private fun pickWallpaper() {
        if (MediaPick.hasAccess(this)) MediaPick.pickOne(this, lifecycleScope) { onWallpaperPicked(it) }
        else wallpaperPermLauncher.launch(MediaPick.perms())
    }

    private fun onWallpaperPicked(uri: android.net.Uri) {
        startActivity(
            Intent(this, WallpaperPreviewActivity::class.java)
                .putExtra(WallpaperPreviewActivity.EXTRA_IMAGE_URI, uri.toString())
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPersonalizationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.itemTheme.setOnClickListener { showThemeDialog() }
        binding.itemLanguage.setOnClickListener { showLanguageDialog() }
        binding.itemWallpapers.setOnClickListener { showWallpaperOptions() }
        binding.itemChatUiStyle.setOnClickListener { showChatUiStyleDialog() }
        binding.itemBanner.setOnClickListener {
            startActivity(Intent(this, HeaderSettingsActivity::class.java))
        }

        updateThemeLabel()
        updateLanguageLabel()
        updateChatUiStyleLabel()

        if (App.screenshot != null) {
            animateReveal()
        }
    }

    /** true только у того экземпляра Activity, который реально читает App.screenshot для reveal. */
    private var screenshotConsumed = false

    override fun onDestroy() {
        super.onDestroy()
        // Страховка: если reveal-анимацию прервали до завершения, не оставляем
        // полноэкранный Bitmap висеть в статике App.screenshot. Чистим только у читающего
        // экземпляра — у установившего (до recreate) флаг false, иначе сломали бы анимацию.
        if (screenshotConsumed) App.screenshot = null
    }

    private fun animateReveal() {
        val screenshot = App.screenshot ?: return
        screenshotConsumed = true
        val cx = App.centerX
        val cy = App.centerY

        // Мы используем android.R.id.content, чтобы скриншот был именно в области контента,
        // и анимация reveal корректно работала поверх него.
        val container = findViewById<ViewGroup>(android.R.id.content)
        
        val imageView = ImageView(this).apply {
            setImageBitmap(screenshot)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        
        // Добавляем старый скриншот в контейнер
        container.addView(imageView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        
        // Скрываем корень разметки новой темы перед стартом
        binding.root.visibility = View.INVISIBLE
        
        binding.root.post {
            // Поднимаем новую разметку НАД скриншотом
            binding.root.bringToFront()
            binding.root.visibility = View.VISIBLE
            
            // Координаты cx/cy — абсолютные в окне. 
            // createCircularReveal ожидает координаты относительно анимируемого View.
            val location = IntArray(2)
            binding.root.getLocationInWindow(location)
            val relativeX = cx - location[0]
            val relativeY = cy - location[1]

            val finalRadius = Math.hypot(container.width.toDouble(), container.height.toDouble()).toFloat()
            val anim = ViewAnimationUtils.createCircularReveal(binding.root, relativeX, relativeY, 0f, finalRadius)
            anim.duration = 750 // Apple-style fluid duration
            anim.interpolator = AccelerateDecelerateInterpolator()
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.removeView(imageView)
                    App.screenshot = null
                }
            })
            anim.start()
        }
    }

    override fun onResume() {
        super.onResume()
        // Wallpaper may have changed in WallpaperPreviewActivity — refresh labels
        updateChatUiStyleLabel()
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private fun updateThemeLabel() {
        binding.tvThemeValue.text = when (prefs.appTheme) {
            App.THEME_LIGHT  -> getString(R.string.theme_light)
            App.THEME_SYSTEM -> getString(R.string.theme_system)
            else             -> getString(R.string.theme_dark)
        }
    }

    private fun showThemeDialog() {
        val themes = arrayOf(
            getString(R.string.theme_dark),
            getString(R.string.theme_light),
            getString(R.string.theme_system)
        )
        val values = arrayOf(App.THEME_DARK, App.THEME_LIGHT, App.THEME_SYSTEM)
        val current = values.indexOf(prefs.appTheme).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(themes, current) { dialog, which ->
                val chosen = values[which]
                dialog.dismiss()
                // Используем post, чтобы диалог успел исчезнуть из кадра до снятия скриншота
                binding.root.post {
                    if (chosen == App.THEME_LIGHT) showLightThemeWarning()
                    else applyTheme(chosen, binding.itemTheme)
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun showLightThemeWarning() {
        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.theme_light_warning_title)
            .setMessage(R.string.theme_light_warning_message)
            .setPositiveButton(R.string.theme_light_warning_confirm) { dialog, _ -> 
                dialog.dismiss()
                binding.root.post {
                    applyTheme(App.THEME_LIGHT, binding.itemTheme) 
                }
            }
            .setNegativeButton(R.string.theme_light_warning_cancel, null)
            .show()
    }

    private fun applyTheme(theme: String, fromView: View) {
        val mode = App.modeFromTheme(theme)
        if (AppCompatDelegate.getDefaultNightMode() == mode) return

        // 1. Снимаем скриншот текущего состояния (старой темы)
        val root = window.decorView
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        root.draw(canvas)
        App.screenshot = bitmap

        // 2. Координаты центра точки нажатия
        val location = IntArray(2)
        fromView.getLocationInWindow(location)
        App.centerX = location[0] + fromView.width / 2
        App.centerY = location[1] + fromView.height / 2

        prefs.appTheme = theme
        
        // Переключаем режим и перезапускаем вручную для анимации
        AppCompatDelegate.setDefaultNightMode(mode)
        updateThemeLabel()

        val intent = Intent(this, PersonalizationActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    // ── Language ──────────────────────────────────────────────────────────────

    private fun updateLanguageLabel() {
        binding.tvLanguageValue.text = when (prefs.appLanguage) {
            App.LANG_EN -> getString(R.string.lang_english)
            App.LANG_RU -> getString(R.string.lang_russian)
            else        -> getString(R.string.lang_system)
        }
    }

    private fun showLanguageDialog() {
        val langs = arrayOf(
            getString(R.string.lang_russian),
            getString(R.string.lang_english),
            getString(R.string.lang_system)
        )
        val values = arrayOf(App.LANG_RU, App.LANG_EN, App.LANG_SYSTEM)
        val current = values.indexOf(prefs.appLanguage).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.lang_dialog_title)
            .setSingleChoiceItems(langs, current) { dialog, which ->
                dialog.dismiss()
                applyLanguage(values[which])
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun applyLanguage(lang: String) {
        prefs.appLanguage = lang
        val locales = if (lang.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                      else LocaleListCompat.forLanguageTags(lang)
        AppCompatDelegate.setApplicationLocales(locales)
    }

    // ── Wallpaper ─────────────────────────────────────────────────────────────

    private fun showWallpaperOptions() {
        val options = arrayOf(
            getString(R.string.wallpaper_preview_adjust),
            getString(R.string.wallpaper_change),
            getString(R.string.wallpaper_clear)
        )
        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.settings_wallpaper_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, WallpaperPreviewActivity::class.java))
                    1 -> {
                        AppLock.beginShareGrace()
                        pickWallpaper()
                    }
                    2 -> {
                        prefs.wallpaperPortrait = null
                        prefs.wallpaperLandscape = null
                        // Atmospheric Glass доступен без обоев — не сбрасываем стиль
                        Toast.makeText(this, R.string.wallpaper_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ── Chat UI Style ─────────────────────────────────────────────────────────

    private fun updateChatUiStyleLabel() {
        binding.tvChatUiStyleValue.text = when (prefs.chatUiStyle) {
            Prefs.CHAT_UI_GLASS -> getString(R.string.chat_ui_glass)
            else                -> getString(R.string.chat_ui_classic)
        }
    }

    private fun showChatUiStyleDialog() {
        val styles = arrayOf(
            getString(R.string.chat_ui_classic),
            getString(R.string.chat_ui_glass)
        )
        val values = arrayOf(Prefs.CHAT_UI_CLASSIC, Prefs.CHAT_UI_GLASS)
        val current = values.indexOf(prefs.chatUiStyle).coerceAtLeast(0)

        AlertDialog.Builder(this, R.style.Theme_AtrumChat_Dialog)
            .setTitle(R.string.settings_item_chat_ui_style)
            .setSingleChoiceItems(styles, current) { dialog, which ->
                dialog.dismiss()
                prefs.chatUiStyle = values[which]
                updateChatUiStyleLabel()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

}
