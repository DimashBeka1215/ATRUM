package com.atrum.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Автозапуск после перезагрузки телефона ОТКЛЮЧЁН (репорт: «ATRUM сам себя перезапускает
 * и прописывается в фон, не могу закрыть»). Приёмник больше не зарегистрирован в манифесте
 * и намеренно ничего не делает: фоновый сервис пушей поднимается только когда пользователь
 * сам открыл приложение при включённом тумблере пушей (App.onCreate / NotificationsActivity).
 * Класс оставлен, чтобы не ломать возможные ссылки; тело — пустое.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // no-op: автозапуск после загрузки убран намеренно.
    }
}
