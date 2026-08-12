package com.atrum.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск фоновой службы пушей после перезагрузки телефона.
 *
 * История: раньше автозапуск был вшит БЕЗУСЛОВНО и его пришлось полностью убрать по репорту
 * «ATRUM сам себя перезапускает и прописывается в фон, не могу закрыть». Теперь это не
 * поведение по умолчанию, а тумблер «Автозапуск вместе с системой» на экране «Уведомления»
 * ([Prefs.autoStartOnBoot]), по умолчанию ВЫКЛЮЧЕННЫЙ. Без явного согласия пользователя
 * приёмник по-прежнему ничего не делает — старое поведение сохраняется (§17).
 *
 * Требуются ОБА условия: пуши включены И автозапуск разрешён. Первое проверяется ещё раз
 * внутри [MessageWatchService.start] — двойная защита от случайного подъёма службы.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        // Часть прошивок (Xiaomi/Huawei и др.) шлёт свой broadcast вместо стандартного.
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        runCatching {
            val prefs = Prefs(context)
            if (!prefs.pushEnabled || !prefs.autoStartOnBoot) return

            // Перезагрузка — новая сессия системы: флаг «я сам закрыл приложение» относился
            // к прошлой сессии и больше не актуален. Включённый тумблер автозапуска — это
            // явное согласие пользователя, иначе тумблер выглядел бы сломанным.
            prefs.serviceUserDismissed = false

            MessageWatchService.start(context)
            PushCatchupWorker.schedule(context)
        }
    }
}
