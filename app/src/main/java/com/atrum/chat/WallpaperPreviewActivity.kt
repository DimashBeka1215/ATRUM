package com.atrum.chat

import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.SeekBar
import androidx.core.content.ContextCompat
import com.atrum.chat.databinding.ActivityWallpaperPreviewBinding

/**
 * Экран превью обоев и настройки прозрачности.
 *
 * Открывается двумя способами:
 *   1. С ключом IMAGE_URI — пользователь выбрал новый файл из галереи.
 *      Apply: сохраняет новые обои + значения ползунков.
 *
 *   2. Без IMAGE_URI (PREVIEW_MODE) — открывается из меню «Обои чатов»
 *      для просмотра/редактирования текущего вида без смены обоев.
 *      Apply: сохраняет только значения ползунков.
 *
 * Мок-чат (шапка + 2 пузырька + панель ввода) реагирует на ползунки в реальном времени,
 * отражая итоговый вид с учётом режима интерфейса (Классический / Атмосферное стекло).
 */
class WallpaperPreviewActivity : SecureActivity() {

    private lateinit var binding: ActivityWallpaperPreviewBinding
    private lateinit var prefs: Prefs

    /** URI нового изображения, null = режим превью текущих настроек. */
    private var newImageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWallpaperPreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        newImageUri = intent.getStringExtra(EXTRA_IMAGE_URI)?.let { Uri.parse(it) }

        loadPreviewImage()
        applyMockChatStyle()
        setupSliders()
        updateMockAlpha()

        binding.btnCancel.setOnClickListener { finish() }

        binding.btnApply.setOnClickListener {
            // Сохраняем обои, если был выбран новый файл
            newImageUri?.let { uri ->
                val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                val base64 = uriToBase64(uri)
                if (portrait) prefs.wallpaperPortrait  = base64
                else          prefs.wallpaperLandscape = base64
            }
            // Всегда сохраняем настройки прозрачности
            prefs.bubbleAlphaSelf  = binding.seekBubbleSelf.progress  + MIN_ALPHA
            prefs.bubbleAlphaOther = binding.seekBubbleOther.progress + MIN_ALPHA
            prefs.uiAlpha          = binding.seekUi.progress          + MIN_ALPHA
            setResult(RESULT_OK)
            finish()
        }
    }

    // ── Загрузка изображения ──────────────────────────────────────────────────

    private fun loadPreviewImage() {
        val uri = newImageUri
        if (uri != null) {
            // Новое изображение из галереи
            runCatching {
                contentResolver.openInputStream(uri)?.use { stream ->
                    binding.ivPreview.setImageBitmap(BitmapFactory.decodeStream(stream))
                }
            }
        } else {
            // Текущие обои из prefs
            val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            val base64 = if (portrait) prefs.wallpaperPortrait else prefs.wallpaperLandscape
            if (!base64.isNullOrBlank()) {
                runCatching {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    binding.ivPreview.setImageBitmap(
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    )
                }
            }
            // Если обоев нет — отображается тёмный bg из layout (android:background="@color/bg")
        }
    }

    // ── Стиль мок-чата (classic / glass) ─────────────────────────────────────

    private fun applyMockChatStyle() {
        val isGlass = prefs.chatUiStyle == Prefs.CHAT_UI_GLASS
        if (isGlass) {
            binding.mockHeader.background =
                ContextCompat.getDrawable(this, R.drawable.bg_glass_toolbar)
            binding.mockBubbleSelf.background =
                ContextCompat.getDrawable(this, R.drawable.bg_glass_msg_self)
            binding.mockBubbleOther.background =
                ContextCompat.getDrawable(this, R.drawable.bg_glass_msg_other)
            binding.mockInputPill.background =
                ContextCompat.getDrawable(this, R.drawable.bg_glass_input_pill)
            binding.mockInputArea.setBackgroundColor(android.graphics.Color.TRANSPARENT)

            // В glass-режиме текст "другого" — белый
            val tvOther = binding.mockBubbleOther.getChildAt(0) as? android.widget.TextView
            tvOther?.setTextColor(android.graphics.Color.WHITE)
        } else {
            val overlayColor = ContextCompat.getColor(this, R.color.chat_overlay)
            binding.mockHeader.setBackgroundColor(overlayColor)
            binding.mockBubbleSelf.background =
                ContextCompat.getDrawable(this, R.drawable.bg_message_self)
            binding.mockBubbleOther.background =
                ContextCompat.getDrawable(this, R.drawable.bg_message_other)
            binding.mockInputPill.background =
                ContextCompat.getDrawable(this, R.drawable.bg_chat_input_pill)
            binding.mockInputArea.setBackgroundColor(overlayColor)
        }
    }

    // ── Ползунки ──────────────────────────────────────────────────────────────

    private fun setupSliders() {
        // SeekBar: progress 0..90 → значение MIN_ALPHA..100
        binding.seekBubbleSelf.max  = 100 - MIN_ALPHA
        binding.seekBubbleOther.max = 100 - MIN_ALPHA
        binding.seekUi.max          = 100 - MIN_ALPHA

        binding.seekBubbleSelf.progress  = prefs.bubbleAlphaSelf  - MIN_ALPHA
        binding.seekBubbleOther.progress = prefs.bubbleAlphaOther - MIN_ALPHA
        binding.seekUi.progress          = prefs.uiAlpha          - MIN_ALPHA

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                updateLabels()
                updateMockAlpha()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        }

        binding.seekBubbleSelf.setOnSeekBarChangeListener(listener)
        binding.seekBubbleOther.setOnSeekBarChangeListener(listener)
        binding.seekUi.setOnSeekBarChangeListener(listener)

        updateLabels()
    }

    private fun updateLabels() {
        binding.tvBubbleSelfValue.text  = "${binding.seekBubbleSelf.progress  + MIN_ALPHA}%"
        binding.tvBubbleOtherValue.text = "${binding.seekBubbleOther.progress + MIN_ALPHA}%"
        binding.tvUiValue.text          = "${binding.seekUi.progress          + MIN_ALPHA}%"
    }

    private fun updateMockAlpha() {
        binding.mockBubbleSelf.alpha  = (binding.seekBubbleSelf.progress  + MIN_ALPHA) / 100f
        binding.mockBubbleOther.alpha = (binding.seekBubbleOther.progress + MIN_ALPHA) / 100f
        val uiA                       = (binding.seekUi.progress          + MIN_ALPHA) / 100f
        binding.mockHeader.alpha    = uiA
        binding.mockInputArea.alpha = uiA
    }

    // ── Утилиты ───────────────────────────────────────────────────────────────

    private fun uriToBase64(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.use {
            Base64.encodeToString(it.readBytes(), Base64.DEFAULT)
        }
    }.getOrNull()

    companion object {
        const val EXTRA_IMAGE_URI = "IMAGE_URI"
        private const val MIN_ALPHA = 10   // минимум 10% — элементы всегда видны
    }
}
