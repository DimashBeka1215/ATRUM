package com.atrum.chat

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Кастомный кадратор шапки (3:1) в стиле ATRUM — замена uCrop на этом пути.
 *
 * Вход: EXTRA_SOURCE_URI (Uri исходного фото). Выход (RESULT_OK): EXTRA_OUTPUT_URI —
 * Uri JPEG-файла вырезанной шапки. HeaderSettingsActivity дальше сохраняет его как раньше.
 */
class BannerCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_URI = "output_uri"
        private const val MAX_DECODE = 4096
    }

    private lateinit var cropView: BannerCropView
    private lateinit var seek: SeekBar
    private lateinit var tvZoom: TextView
    private var safeOn = false
    private var syncingSeek = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_banner_crop)

        cropView = findViewById(R.id.cropView)
        seek = findViewById(R.id.seekZoom)
        tvZoom = findViewById(R.id.tvZoom)

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDone).setOnClickListener { onDone() }
        findViewById<View>(R.id.btnReset).setOnClickListener { cropView.resetZoom() }
        findViewById<View>(R.id.btnSafe).setOnClickListener { toggleSafe() }

        cropView.onZoom = { f ->
            syncingSeek = true
            seek.progress = (f * 1000).toInt()
            tvZoom.text = "${100 + (f * 300).toInt()}%"
            syncingSeek = false
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser || syncingSeek) return
                cropView.setZoomFraction(p / 1000f)
                tvZoom.text = "${100 + (p / 1000f * 300).toInt()}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val src = intent.getStringExtra(EXTRA_SOURCE_URI)?.let { Uri.parse(it) }
        if (src == null) { finish(); return }
        loadSource(src)
    }

    private fun toggleSafe() {
        safeOn = !safeOn
        cropView.showSafeZone = safeOn
        findViewById<TextView>(R.id.tvSafe).setText(if (safeOn) R.string.bcrop_safe_hide else R.string.bcrop_safe_show)
    }

    private fun loadSource(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeScaled(uri) }
            if (bmp == null) {
                Toast.makeText(this@BannerCropActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                finish(); return@launch
            }
            cropView.setBitmap(bmp)
        }
    }

    /** Декодирует с даунскейлом (макс сторона MAX_DECODE) — чтобы не словить OOM на огромных фото. */
    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_DECODE || bounds.outHeight / sample > MAX_DECODE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Throwable) { null }

    private fun onDone() {
        lifecycleScope.launch {
            val out = withContext(Dispatchers.IO) {
                val cropped = cropView.getCroppedBitmap() ?: return@withContext null
                val f = File(cacheDir, "banner_crop_${System.currentTimeMillis()}.jpg")
                runCatching {
                    FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    f
                }.getOrNull()
            }
            if (out == null) {
                Toast.makeText(this@BannerCropActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                return@launch
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, Uri.fromFile(out).toString()))
            finish()
        }
    }
}
