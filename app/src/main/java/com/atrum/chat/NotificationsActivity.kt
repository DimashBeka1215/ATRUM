package com.atrum.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.atrum.chat.databinding.ActivityNotificationsBinding

/**
 * Отдельный экран «Уведомления» (открывается из Настроек как пункт со стрелкой,
 * по образцу «О приложении»). Содержит тумблер push-уведомлений. Логика включения
 * и запрос runtime-разрешения POST_NOTIFICATIONS перенесены сюда из SettingsActivity.
 */
class NotificationsActivity : SecureActivity() {

    private lateinit var binding: ActivityNotificationsBinding
    private lateinit var prefs: Prefs

    private val requestNotifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) enablePush()
        else {
            binding.switchPush.isChecked = false
            Toast.makeText(this, R.string.push_need_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotificationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnBack.setOnClickListener { finish() }

        binding.switchPush.isChecked = prefs.pushEnabled
        binding.itemNotifications.setOnClickListener { togglePush() }
    }

    /** Включает/выключает push-уведомления и фоновый сервис. */
    private fun togglePush() {
        if (binding.switchPush.isChecked) {
            prefs.pushEnabled = false
            binding.switchPush.isChecked = false
            MessageWatchService.stop(this)
            PushCatchupWorker.cancel(this)
            return
        }
        // Android 13+ требует runtime-разрешение на показ уведомлений.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enablePush()
        }
    }

    private fun enablePush() {
        prefs.pushEnabled = true
        prefs.serviceUserDismissed = false // пользователь сознательно включил пуши
        binding.switchPush.isChecked = true
        MessageWatchService.start(this)
        PushCatchupWorker.schedule(this)
        // Первый раз — показываем полноэкранное предупреждение про батарею; дальше
        // (если экран уже видели) — сразу системный запрос.
        if (!BatteryOptimizationActivity.showIfNeeded(this)) {
            BatteryOptimizationActivity.requestIgnoreBatteryOptimizations(this)
        }
    }
}
