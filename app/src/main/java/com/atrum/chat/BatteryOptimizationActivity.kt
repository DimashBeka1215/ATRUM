package com.atrum.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import com.atrum.chat.databinding.ActivityBatteryOptimizationBinding

/**
 * Полноэкранное предупреждение при ПЕРВОМ включении уведомлений: просим отключить
 * оптимизацию батареи, иначе система «усыпляет» приложение и пуши теряются. Успокаиваем,
 * что на заряд это почти не влияет (Atrum работает экономно).
 *
 * Кнопка «Отключить» открывает системный диалог; «Позже» — просто закрывает экран.
 * Показывается один раз (флаг [Prefs.batteryHintShown]).
 */
class BatteryOptimizationActivity : SecureActivity() {

    private lateinit var binding: ActivityBatteryOptimizationBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryOptimizationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLater.setOnClickListener { finish() }
        binding.btnDisable.setOnClickListener {
            requestIgnoreBatteryOptimizations(this)
            finish()
        }
    }

    companion object {
        /** Если ещё не показывали и приложение не в исключениях — открыть экран и вернуть true. */
        fun showIfNeeded(ctx: Context): Boolean {
            val prefs = Prefs(ctx)
            if (prefs.batteryHintShown) return false
            prefs.batteryHintShown = true
            ctx.startActivity(Intent(ctx, BatteryOptimizationActivity::class.java))
            return true
        }

        /** Прямой системный запрос на исключение из оптимизации батареи. */
        @SuppressLint("BatteryLife")
        fun requestIgnoreBatteryOptimizations(ctx: Context) {
            try {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                    pm.isIgnoringBatteryOptimizations(ctx.packageName)
                ) return
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            } catch (_: Exception) {
                // Некоторые прошивки не поддерживают прямой intent — тихо игнорируем.
            }
        }
    }
}
