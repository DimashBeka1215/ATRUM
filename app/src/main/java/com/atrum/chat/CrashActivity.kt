package com.atrum.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityCrashBinding

/**
 * Экран краша — показывается вместо стандартного "Приложение остановлено".
 *
 * Дизайн: тёплое жёлтое предупреждение, без агрессии.
 * Автоматически копирует лог в буфер обмена при открытии.
 * Кнопка "Скопировать" — повторное копирование + Toast.
 * Кнопка "Закрыть" — завершает процесс чисто.
 */
class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val log = intent.getStringExtra(EXTRA_LOG)
            ?: CrashHandler.getLastLog(this)
            ?: getString(R.string.crash_log_unavailable)

        binding.tvCrashLog.text = log

        // Автоматически копируем при открытии
        copyToClipboard(log, silent = true)

        binding.btnCopy.setOnClickListener {
            copyToClipboard(log, silent = false)
        }

        binding.btnClose.setOnClickListener {
            finishAndRemoveTask()
        }
    }

    private fun copyToClipboard(text: String, silent: Boolean) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Atrum crash log", text))
        if (!silent) {
            Toast.makeText(this, R.string.crash_copied_toast, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_LOG = "crash_log"
    }
}
