package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityUpdateRequiredBinding

class UpdateRequiredActivity : AppCompatActivity() {

    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityUpdateRequiredBinding.inflate(layoutInflater)
        setContentView(binding.root)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        binding.linkSource.setOnClickListener { openUrl(URL_SOURCE) }
        binding.linkTgChannel.setOnClickListener { openUrl(URL_TG_CHANNEL) }
        binding.linkTgChat.setOnClickListener { openUrl(URL_TG_CHAT) }

        binding.btnUninstall.setOnClickListener { requestUninstall() }

        startRelentlessVibration()
    }

    override fun onResume() {
        super.onResume()
        startRelentlessVibration()
    }

    private fun startRelentlessVibration() {
        try {
            val vib = vibrator ?: obtainVibrator().also { vibrator = it }
            if (vib == null || !vib.hasVibrator()) return
            val timings = longArrayOf(0, 700, 150)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = intArrayOf(0, 255, 0)
                vib.vibrate(VibrationEffect.createWaveform(timings, amplitudes, 0))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(timings, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun requestUninstall() {
        try {
            startActivity(
                Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Throwable) {
        }
    }

    private fun obtainVibrator(): Vibrator? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val URL_SOURCE = "https://github.com/DimashBeka1215/ATRUM"
        private const val URL_TG_CHANNEL = "https://t.me/Atrum_Chat"
        private const val URL_TG_CHAT = "https://t.me/+4hhc8PwwNf03ZmMy"

        fun launch(context: Context) {
            val intent = Intent(context, UpdateRequiredActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)
        }
    }
}
