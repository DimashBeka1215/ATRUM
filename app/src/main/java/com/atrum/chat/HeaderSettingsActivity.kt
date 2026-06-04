package com.atrum.chat

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.atrum.chat.databinding.ActivityHeaderSettingsBinding
import com.yalantis.ucrop.UCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

class HeaderSettingsActivity : SecureActivity() {

    private lateinit var binding: ActivityHeaderSettingsBinding
    private lateinit var prefs: Prefs

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        const val RESULT_BANNER_CHANGED = 100

        /** Допустимые MIME-типы. GIF / APNG / SVG / анимации — запрещены. */
        private val ALLOWED_MIME = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")

        /** Максимальный размер исходного файла (8 МБ). */
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** Минимальное разрешение исходника перед кропом. */
        private const val MIN_SRC_WIDTH  = 800
        private const val MIN_SRC_HEIGHT = 400

        /** Rate-limit: не чаще одного изменения каждые 30 секунд. */
        private const val RATE_LIMIT_MS = 30_000L
    }

    // ── Image picking ──────────────────────────────────────────────────────────

    private val pickImage = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> if (uri != null) validateThenCrop(uri) }

    private val cropImage = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        when {
            result.resultCode == Activity.RESULT_OK && result.data != null -> {
                val uri = UCrop.getOutput(result.data!!)
                if (uri != null) saveCroppedBanner(uri)
            }
            result.resultCode == UCrop.RESULT_ERROR && result.data != null -> {
                val err = UCrop.getError(result.data!!)
                Toast.makeText(this,
                    getString(R.string.error_avatar_load) + (err?.message?.let { ": $it" } ?: ""),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHeaderSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnChangeBanner.setOnClickListener { pickImage.launch("image/*") }
        binding.btnRemoveBanner.setOnClickListener { confirmRemove() }

        loadCurrentBanner()
    }

    // ── Display ────────────────────────────────────────────────────────────────

    private fun loadCurrentBanner() {
        val bmp = prefs.myBannerBase64?.let { bannerFromBase64(it) }
        if (bmp != null) showBanner(bmp) else showNoBanner()
    }

    private fun showBanner(bmp: Bitmap) {
        binding.ivBannerPreview.setImageBitmap(bmp)
        binding.ivBannerPreview.visibility = View.VISIBLE
        binding.llNoBannerPlaceholder.visibility = View.GONE
        binding.btnRemoveBanner.visibility = View.VISIBLE
    }

    private fun showNoBanner() {
        binding.ivBannerPreview.visibility = View.GONE
        binding.llNoBannerPlaceholder.visibility = View.VISIBLE
        binding.btnRemoveBanner.visibility = View.GONE
    }

    // ── Validation → Safe zone hint → Crop ────────────────────────────────────

    /**
     * Полный pipeline перед кропом:
     *   1. Rate-limit
     *   2. Формат (MIME)
     *   3. Размер файла (≤ 8 МБ)
     *   4. Разрешение оригинала (≥ 1280 × 720)
     *   5. Safe-zone диалог
     *   6. UCrop (2:1, max 2560×1280)
     */
    private fun validateThenCrop(uri: Uri) {
        lifecycleScope.launch {
            // Rate-limit — проверяем на главном потоке (только чтение Prefs)
            if (!checkRateLimit()) return@launch

            // Тяжёлые IO-проверки — фоновый поток
            val error = withContext(Dispatchers.IO) { validateSource(uri) }
            if (error != null) {
                Toast.makeText(this@HeaderSettingsActivity, error, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Показываем подсказку о safe zone, затем запускаем UCrop
            showSafeZoneHint { startCropBanner(uri) }
        }
    }

    /**
     * Проверяет формат, размер файла и минимальное разрешение.
     * Выполняется на Dispatchers.IO.
     * @return строка с ошибкой или null если всё ок.
     */
    private fun validateSource(uri: Uri): String? {
        // 1. MIME-тип
        val mime = contentResolver.getType(uri)?.lowercase()
        if (mime == null || mime !in ALLOWED_MIME) {
            return getString(R.string.header_error_format)
        }

        // 2. Размер файла
        val fileSize = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
        }.getOrDefault(0L)
        if (fileSize > MAX_FILE_BYTES) {
            return getString(R.string.header_error_size)
        }

        // 3. Минимальное разрешение оригинала (только bounds, без полной загрузки)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        val w = opts.outWidth
        val h = opts.outHeight
        if (w < MIN_SRC_WIDTH || h < MIN_SRC_HEIGHT) {
            return getString(R.string.header_error_resolution)
        }

        return null // всё ок
    }

    /** Проверяет rate-limit. Возвращает true если можно продолжать. */
    private fun checkRateLimit(): Boolean {
        val last = prefs.lastBannerChangeTime
        if (last <= 0L) return true
        val elapsed = System.currentTimeMillis() - last
        if (elapsed < RATE_LIMIT_MS) {
            val remainSec = ((RATE_LIMIT_MS - elapsed) / 1000).toInt() + 1
            Toast.makeText(this,
                getString(R.string.header_error_rate_limit, remainSec),
                Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    /**
     * Диалог с подсказкой о safe zone перед запуском UCrop.
     * Сообщает пользователю, что края могут быть обрезаны на разных экранах.
     */
    private fun showSafeZoneHint(onContinue: () -> Unit) {
        AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
            .setTitle(R.string.header_safe_zone_title)
            .setMessage(R.string.header_safe_zone_tip)
            .setPositiveButton(R.string.header_safe_zone_continue) { _, _ -> onContinue() }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ── Crop ───────────────────────────────────────────────────────────────────

    private fun startCropBanner(sourceUri: Uri) {
        val destUri = Uri.fromFile(File(cacheDir, "banner_crop_${System.currentTimeMillis()}.jpg"))
        val accentColor = androidx.core.content.ContextCompat.getColor(this, R.color.accent)
        val bgColor = androidx.core.content.ContextCompat.getColor(this, R.color.bg)
        val toolbarColor = androidx.core.content.ContextCompat.getColor(this, R.color.surface)
        val textColor = androidx.core.content.ContextCompat.getColor(this, R.color.text_primary)

        val options = UCrop.Options().apply {
            setCircleDimmedLayer(false)
            setShowCropFrame(true)
            setShowCropGrid(true)
            setCompressionFormat(Bitmap.CompressFormat.JPEG)
            setCompressionQuality(85)
            setToolbarTitle(getString(R.string.header_settings_crop_title))
            setHideBottomControls(false)
            setFreeStyleCropEnabled(true) // Разрешаем свободное изменение рамки

            // Styling uCrop to match app theme
            setToolbarColor(toolbarColor)
            setStatusBarColor(toolbarColor)
            setToolbarWidgetColor(textColor)
            setActiveControlsWidgetColor(accentColor)
            setRootViewBackgroundColor(bgColor)
            setLogoColor(accentColor)
        }
        cropImage.launch(
            UCrop.of(sourceUri, destUri)
                .withAspectRatio(3f, 1f)
                .withMaxResultSize(2400, 800)
                .withOptions(options)
                .getIntent(this)
        )
    }

    // ── Save ───────────────────────────────────────────────────────────────────

    private fun saveCroppedBanner(uri: Uri) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap == null) {
                Toast.makeText(this@HeaderSettingsActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                return@launch
            }

            showBanner(bitmap)

            val base64 = withContext(Dispatchers.IO) { bannerToBase64(bitmap) }
            prefs.myBannerBase64 = base64
            prefs.lastBannerChangeTime = System.currentTimeMillis()

            setResult(RESULT_BANNER_CHANGED)
            Toast.makeText(this@HeaderSettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // ── Remove ─────────────────────────────────────────────────────────────────

    private fun confirmRemove() {
        AlertDialog.Builder(this, R.style.Theme_GithubChat_Dialog)
            .setTitle(R.string.header_settings_remove_title)
            .setMessage(R.string.header_settings_remove_confirm)
            .setPositiveButton(R.string.header_settings_remove_action) { _, _ ->
                prefs.myBannerBase64 = null
                // Rate-limit не сбрасываем при удалении — только при установке
                setResult(RESULT_BANNER_CHANGED)
                showNoBanner()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // ── Codec helpers ──────────────────────────────────────────────────────────

    /** JPEG 85 % → base64. Вызывать на Dispatchers.IO. */
    private fun bannerToBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun bannerFromBase64(base64: String): Bitmap? = runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
