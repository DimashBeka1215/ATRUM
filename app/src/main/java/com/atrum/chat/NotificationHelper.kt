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

    const val CH_MESSAGES = "atrum_messages_v2"
    const val CH_SERVICE  = "atrum_service"

    /** Постоянное уведомление foreground-сервиса. */
    const val FGS_ID = 1001
    /** Анонимное уведомление о новом сообщении (общий id → не плодим карточки). */
    private const val MSG_ID = 1002

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Старый DEFAULT-канал (без heads-up) удаляем: важность существующего канала
        // изменить нельзя, поэтому уведомления живут в новом канале с IMPORTANCE_HIGH.
        runCatching { nm.deleteNotificationChannel("atrum_messages") }

        if (nm.getNotificationChannel(CH_MESSAGES) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CH_MESSAGES,
                    ctx.getString(R.string.push_channel_messages),
                    NotificationManager.IMPORTANCE_HIGH
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

    /** Порог отображения: больше — показываем «90+». */
    private const val OVERFLOW = 90

    /**
     * Одно уведомление о непрочитанных с РАСТУЩИМ числом: «У вас 5 сообщений» → «6» → «90+».
     * Имени/текста сообщений нет, contentIntent НЕТ (нажатие не открывает чат).
     * Общий [MSG_ID] → лента не забивается: число обновляется в одной карточке.
     *
     * @param count суммарно непрочитанных от собеседников
     * @param alert true — со звуком (пришло новое); false — тихое обновление числа
     */
    fun notifyNewMessage(ctx: Context, count: Int, alert: Boolean) {
        if (count <= 0) { cancelMessages(ctx); return }
        ensureChannels(ctx)
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
        val text = if (count > OVERFLOW)
            ctx.getString(R.string.push_messages_overflow)
        else
            ctx.resources.getQuantityString(R.plurals.push_new_messages, count, count)
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(ctx.getString(R.string.app_name))
            .setContentText(text)
            .setNumber(if (count > OVERFLOW) OVERFLOW else count)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alert)
            .setSilent(!alert)
            .setPriority(if (alert) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Намеренно НЕ задаём setContentIntent → нажатие не открывает чат.
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(MSG_ID, n)
        } catch (_: SecurityException) {
            // Нет разрешения POST_NOTIFICATIONS — молча выходим.
        }
    }

    /** Убирает уведомление о непрочитанных (всё прочитано / открыли приложение). */
    fun cancelMessages(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(MSG_ID)
    }
}
