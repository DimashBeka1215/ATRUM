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
 * Кастомный кадратор аватара (1:1, круглая рамка) в стиле ATRUM — замена системного uCrop
 * (белое окно на тёмной теме). Тот же движок, что у шапки ([BannerCropView]), но в
 * [BannerCropView.circleMode]. Вход: EXTRA_SOURCE_URI. Выход (RESULT_OK): EXTRA_OUTPUT_URI —
 * Uri квадратного JPEG (аватар маскируется по кругу при рендере, AvatarUtils.toCircle).
 */
class AvatarCropActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_URI = "output_uri"
        private const val MAX_DECODE = 4096
        private const val OUT_SIZE = 512
    }

    private lateinit var cropView: BannerCropView
    private lateinit var seek: SeekBar
    private lateinit var tvZoom: TextView
    private var syncingSeek = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_avatar_crop)

        cropView = findViewById(R.id.cropView)
        seek = findViewById(R.id.seekZoom)
        tvZoom = findViewById(R.id.tvZoom)

        // Круглая рамка 1:1 (аватар).
        cropView.configure(circle = true, aspectW = 1f, aspectH = 1f)

        findViewById<View>(R.id.btnCancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btnDone).setOnClickListener { onDone() }
        findViewById<View>(R.id.btnReset).setOnClickListener { cropView.resetZoom() }

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

    private fun loadSource(uri: Uri) {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeScaled(uri) }
            if (bmp == null) {
                Toast.makeText(this@AvatarCropActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
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
                val cropped = cropView.getCroppedBitmap(OUT_SIZE, OUT_SIZE) ?: return@withContext null
                val f = File(cacheDir, "avatar_crop_${System.currentTimeMillis()}.jpg")
                runCatching {
                    FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    f
                }.getOrNull()
            }
            if (out == null) {
                Toast.makeText(this@AvatarCropActivity, R.string.error_avatar_load, Toast.LENGTH_SHORT).show()
                return@launch
            }
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_OUTPUT_URI, Uri.fromFile(out).toString()))
            finish()
        }
    }
}
