package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityOauthWarningBinding

/**
 * Экран-предупреждение перед запуском GitHub Device Flow в браузере.
 *
 * Показывает что GitHub увидит во время авторизации (IP, время, устройство)
 * и какие разрешения получает приложение (только Gist scope).
 *
 * На "Продолжить" → передаёт room_name/password/duration в DeviceFlowActivity,
 * который и запускает реальный OAuth.
 *
 * ТОЛЬКО UI и проброс extras. Никакой бизнес-логики.
 */
class OAuthWarningActivity : SecureActivity() {

    private lateinit var binding: ActivityOauthWarningBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOauthWarningBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnContinue.setOnClickListener { continueToDeviceFlow() }
    }

    private fun continueToDeviceFlow() {
        val roomName = intent.getStringExtra(DeviceFlowActivity.EXTRA_ROOM_NAME).orEmpty()
        val roomPassword = intent.getStringExtra(DeviceFlowActivity.EXTRA_ROOM_PASSWORD).orEmpty()
        val durationDays = intent.getIntExtra(CreateChatActivity.EXTRA_DURATION_DAYS, -1)

        val intent = Intent(this, DeviceFlowActivity::class.java).apply {
            putExtra(DeviceFlowActivity.EXTRA_ROOM_NAME, roomName)
            putExtra(DeviceFlowActivity.EXTRA_ROOM_PASSWORD, roomPassword)
            putExtra(CreateChatActivity.EXTRA_DURATION_DAYS, durationDays)
        }
        startActivity(intent)
        finish()
    }
}
