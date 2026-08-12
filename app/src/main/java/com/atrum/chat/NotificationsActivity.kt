package com.atrum.chat

import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.atrum.chat.databinding.ActivityNotificationsBinding

/**
 * Отдельный экран «Уведомления» (открывается из Настроек как пункт со стрелкой,
 * по образцу «О приложении»). Логика включения и запрос runtime-разрешения
 * POST_NOTIFICATIONS перенесены сюда из SettingsActivity.
 *
 * Три тумблера:
 *  1. Push-уведомления — фоновая служба [MessageWatchService];
 *  2. Автозапуск вместе с системой ([Prefs.autoStartOnBoot], см. [BootReceiver]);
 *  3. Восстанавливать после закрытия ([Prefs.reviveService], см. [PushCatchupWorker]).
 *
 * Второй и третий зависят от первого: без включённых пушей службы нет и поднимать нечего.
 * Поэтому при выключённом push они притушены и по тапу показывают подсказку.
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

        binding.switchPush.isChecked      = prefs.pushEnabled
        binding.switchAutoStart.isChecked = prefs.autoStartOnBoot
        binding.switchRevive.isChecked    = prefs.reviveService

        binding.itemNotifications.setOnClickListener { togglePush() }
        binding.itemAutoStart.setOnClickListener { toggleAutoStart() }
        binding.itemRevive.setOnClickListener { toggleRevive() }

        applyDependentState()
    }

    /**
     * Притушивает зависимые строки, когда push выключен. Вызывается сразу после любого
     * изменения [Prefs.pushEnabled], чтобы результат был виден на месте, без выхода
     * и повторного входа на экран (§1.5).
     */
    private fun applyDependentState() {
        val alpha = if (prefs.pushEnabled) 1f else 0.45f
        binding.itemAutoStart.alpha = alpha
        binding.itemRevive.alpha    = alpha
    }

    /** Автозапуск фоновой службы после перезагрузки телефона. */
    private fun toggleAutoStart() {
        if (!prefs.pushEnabled) {
            Toast.makeText(this, R.string.settings_push_required, Toast.LENGTH_SHORT).show()
            return
        }
        val enabled = !binding.switchAutoStart.isChecked
        prefs.autoStartOnBoot = enabled
        binding.switchAutoStart.isChecked = enabled
    }

    /** Возвращать службу, даже если пользователь смахнул приложение из «недавних». */
    private fun toggleRevive() {
        if (!prefs.pushEnabled) {
            Toast.makeText(this, R.string.settings_push_required, Toast.LENGTH_SHORT).show()
            return
        }
        val enabled = !binding.switchRevive.isChecked
        prefs.reviveService = enabled
        binding.switchRevive.isChecked = enabled
        if (enabled) {
            // Применяем сразу: снимаем возможный старый флаг «я сам закрыл» и убеждаемся,
            // что резервный воркер запланирован — иначе тумблер сработал бы только после
            // следующего запуска приложения (§1.5).
            prefs.serviceUserDismissed = false
            PushCatchupWorker.schedule(this)
        }
    }

    /** Включает/выключает push-уведомления и фоновый сервис. */
    private fun togglePush() {
        if (binding.switchPush.isChecked) {
            prefs.pushEnabled = false
            binding.switchPush.isChecked = false
            MessageWatchService.stop(this)
            PushCatchupWorker.cancel(this)
            applyDependentState()
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
        applyDependentState()
        // Первый раз — показываем полноэкранное предупреждение про батарею; дальше
        // (если экран уже видели) — сразу системный запрос.
        if (!BatteryOptimizationActivity.showIfNeeded(this)) {
            BatteryOptimizationActivity.requestIgnoreBatteryOptimizations(this)
        }
    }
}
