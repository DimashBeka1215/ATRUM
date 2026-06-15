package com.atrum.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** После перезагрузки телефона заново поднимает фоновый сервис пушей, если он включён. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (Prefs(context).pushEnabled) MessageWatchService.start(context)
        }
    }
}
