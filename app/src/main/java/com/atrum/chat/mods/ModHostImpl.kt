package com.atrum.chat.mods

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * Конкретная реализация [ModHost] — узкая поверхность, которую видит загруженный мод.
 * Возможности добавляются ОСОЗНАННО (Фаза 3). Сейчас: лог, тост, регистрация пункта
 * настроек. Никакого доступа к крипто/ключам/транспорту — этого здесь просто нет.
 */
class ModHostImpl(
    private val context: Context,
    override val appVersionCode: Int
) : ModHost {

    /** Пункт, который мод добавил в свой подраздел настроек. */
    data class SettingsItem(val title: String, val summary: String, val onClick: () -> Unit)

    val settingsItems = mutableListOf<SettingsItem>()

    override fun log(message: String) {
        Log.i("AtrumMod", message)
    }

    override fun toast(message: String) {
        Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    override fun registerSettingsItem(title: String, summary: String, onClick: () -> Unit) {
        settingsItems.add(SettingsItem(title, summary, onClick))
    }
}
