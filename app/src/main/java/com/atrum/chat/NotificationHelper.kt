package com.atrum.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Локальные уведомления приложения.
 *
 * Приватность — намеренно: уведомление о новом сообщении НЕ содержит имени
 * отправителя или текста («У вас новое сообщение.») и НЕ кликабельно (нет
 * contentIntent → нажатие ничего не открывает и в чат не ведёт).
 *
 * Два канала:
 *   CH_MESSAGES — само уведомление о новом сообщении (со звуком, DEFAULT).
 *   CH_SERVICE  — тихое постоянное уведомление работающего фонового сервиса (MIN).
 */
object NotificationHelper {

    const val CH_MESSAGES = "atrum_messages"
    const val CH_SERVICE  = "atrum_service"

    /** Постоянное уведомление foreground-сервиса. */
    const val FGS_ID = 1001
    /** Анонимное уведомление о новом сообщении (общий id → не плодим карточки). */
    private const val MSG_ID = 1002

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (nm.getNotificationChannel(CH_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_MESSAGES,
                    ctx.getString(R.string.push_channel_messages),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = ctx.getString(R.string.push_channel_messages_desc)
                    setShowBadge(true)
                }
            )
        }
        if (nm.getNotificationChannel(CH_SERVICE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_SERVICE,
                    ctx.getString(R.string.push_channel_service),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = ctx.getString(R.string.push_channel_service_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    /** Тихое постоянное уведомление, пока сервис работает в фоне. */
    fun buildOngoing(ctx: Context): Notification {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.push_service_running))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    /** Анонимное «У вас новое сообщение.» Без имени, без текста, БЕЗ contentIntent. */
    fun notifyNewMessage(ctx: Context) {
        ensureChannels(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(ctx.getString(R.string.push_new_message))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Намеренно НЕ задаём setContentIntent → нажатие не открывает чат.
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(MSG_ID, n)
        } catch (_: SecurityException) {
            // Нет разрешения POST_NOTIFICATIONS — молча выходим.
        }
    }
}
