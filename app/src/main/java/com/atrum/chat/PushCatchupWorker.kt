package com.atrum.chat

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Резервное («belt-and-suspenders») пробуждение доставки уведомлений.
 *
 * Foreground-сервис [MessageWatchService] может быть убит системой/прошивкой или
 * приостановлен в Doze — тогда пуши перестают приходить до следующего открытия
 * приложения. Этот периодический воркер WorkManager переживает Doze и перезапуск
 * процесса: раз в ~15 минут он поднимает сервис заново (если пуши включены), а тот
 * сам досинхронизирует историю и покажет уведомление о непрочитанных.
 *
 * Это НЕ замена FCM (его нет по дизайну — Atrum без серверов), а максимально
 * надёжный доступный резерв поверх стрим-подписок.
 */
class PushCatchupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (!Prefs(ctx).pushEnabled) return Result.success()
        runCatching { MessageWatchService.start(ctx) }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "atrum_push_catchup"

        /** Планирует периодическую сверку (идемпотентно: повторный вызов не плодит задачи). */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<PushCatchupWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Отменяет резервную сверку (пуши выключены). */
        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
