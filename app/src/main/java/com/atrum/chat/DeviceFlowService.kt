package com.atrum.chat

import com.atrum.chat.transport.GistTransport

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.data.Chat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground Service который выполняет GitHub Device Flow до конца независимо
 * от того, открыта DeviceFlowActivity или нет.
 *
 * Зачем именно Service:
 *   - Когда пользователь уходит в Custom Tabs, наша Activity уходит в фон.
 *     На Android 10+ background-Activity не может запускать другие Activity
 *     (Background Activity Launch restrictions). Это причина почему пользователь
 *     "застревал" в браузере.
 *   - Foreground Service с persistent notification легально живёт пока работает,
 *     и при необходимости запускает Activity через PendingIntent — это всегда
 *     срабатывает (даже когда другое приложение поверх).
 *
 * Жизненный цикл:
 *   1. Activity стартует Service с device_code, roomName, roomPassword
 *   2. Service показывает foreground notification "Ожидаем авторизации…"
 *   3. Service polling-ит токен (с интервалом из device_code response)
 *   4. На success: создаёт gist → сохраняет Chat в Room → пушит профиль
 *   5. Обновляет notification: "Готово! Открыть чат" с PendingIntent на ChatActivity
 *   6. Шлёт broadcast (если Activity жива — она автоматически откроет ChatActivity)
 *   7. Service сам себя останавливает (stopSelf)
 *
 * Гарантии:
 *   - Chat сохраняется в Room ДО любых попыток открыть Activity. Даже если ОС
 *     убьёт всё перед открытием — после перезапуска чат уже в списке.
 *   - Один и тот же device_code не polling-ится дважды (Service единственный).
 *   - При повторном START Service игнорирует если уже работает.
 *   - При cancel или success — корутины отменяются, scope cancel'ится.
 */
class DeviceFlowService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val deviceCode = intent?.getStringExtra(EXTRA_DEVICE_CODE)
        val intervalSec = intent?.getIntExtra(EXTRA_INTERVAL_SEC, 5) ?: 5
        val expiresInSec = intent?.getIntExtra(EXTRA_EXPIRES_SEC, 900) ?: 900
        val roomName = intent?.getStringExtra(EXTRA_ROOM_NAME)
        val roomPassword = intent?.getStringExtra(EXTRA_ROOM_PASSWORD)
        val durationDays = intent?.getIntExtra(EXTRA_DURATION_DAYS, -1) ?: -1

        if (deviceCode.isNullOrBlank() || roomName.isNullOrBlank() || roomPassword.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Если уже работает с другим запросом — игнорим повторный старт
        if (workJob?.isActive == true) {
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildProgressNotification(getString(R.string.df_status_authorizing)))

        workJob = scope.launch {
            runFlow(deviceCode, intervalSec, expiresInSec, roomName, roomPassword, durationDays)
            // После завершения — service больше не нужен
            stopSelf()
        }

        // Не пере-запускать сервис если ОС его убила: device_code всё равно истечёт
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        workJob?.cancel()
        scope.cancel()
    }

    private suspend fun runFlow(
        deviceCode: String,
        intervalSec: Int,
        expiresInSec: Int,
        roomName: String,
        roomPassword: String,
        durationDays: Int
    ) {
        // 1. Polling до получения токена / истечения / отказа
        val tokenResult = OAuthManager.pollUntilDone(deviceCode, intervalSec, expiresInSec)

        val token: String = when (tokenResult) {
            is OAuthManager.TokenResult.Success -> tokenResult.accessToken
            OAuthManager.TokenResult.Expired -> {
                val msg = getString(R.string.df_status_expired)
                updateNotification(msg, terminal = true)
                OAuthCompletionTracker.markFailure(msg)
                broadcastResult(false, msg)
                return
            }
            OAuthManager.TokenResult.AccessDenied -> {
                val msg = getString(R.string.df_status_denied)
                updateNotification(msg, terminal = true)
                OAuthCompletionTracker.markFailure(msg)
                broadcastResult(false, msg)
                return
            }
            is OAuthManager.TokenResult.Error -> {
                updateNotification(getString(R.string.df_status_error, tokenResult.message), terminal = true)
                OAuthCompletionTracker.markFailure(tokenResult.message)
                broadcastResult(false, tokenResult.message)
                CrashHandler.report(
                    context = applicationContext,
                    title = "Polling токена завершился неожиданным результатом",
                    throwable = RuntimeException("TokenResult.Error: ${tokenResult.message}")
                )
                return
            }
            else -> {
                // Pending/SlowDown не должны доходить сюда, но если дошли — репортим
                val msg = "Unexpected token result: $tokenResult"
                updateNotification(getString(R.string.df_status_error, msg), terminal = true)
                OAuthCompletionTracker.markFailure(msg)
                broadcastResult(false, msg)
                CrashHandler.report(
                    context = applicationContext,
                    title = "Неожиданный результат polling'а токена",
                    throwable = RuntimeException(msg)
                )
                return
            }
        }

        // 2. Создаём gist (до 3 попыток с нарастающей паузой)
        val creatingMsg = getString(R.string.df_status_creating_gist)
        updateNotification(creatingMsg)
        OAuthCompletionTracker.updateProgress(creatingMsg)
        var gistId: String? = null
        var lastGistError: String = "create gist failed"
        for (attempt in 1..3) {
            try {
                gistId = GistApi.createGist(token, "Secure chat room: $roomName")
                break
            } catch (e: Exception) {
                lastGistError = e.message ?: "create gist failed"
                android.util.Log.e("DeviceFlowService", "createGist attempt $attempt failed: $lastGistError", e)
                if (attempt < 3) delay(2_000L * attempt)
            }
        }
        if (gistId == null) {
            updateNotification(getString(R.string.df_status_error, lastGistError), terminal = true)
            OAuthCompletionTracker.markFailure(lastGistError)
            broadcastResult(false, lastGistError)
            CrashHandler.report(
                context = applicationContext,
                title = "Не удалось создать чат через GitHub (createGist провалился после 3 попыток). roomName=$roomName",
                throwable = RuntimeException("createGist failed: $lastGistError")
            )
            return
        }

        // 3. Сохраняем Chat в Room — это критично, ДО попытки открыть UI.
        //    Даже если ОС всё убьёт после этой строки, чат уже в БД.
        val savingMsg = getString(R.string.df_status_saving)
        updateNotification(savingMsg)
        OAuthCompletionTracker.updateProgress(savingMsg)
        val prefs = Prefs(applicationContext)
        if (prefs.defaultGistToken == null) prefs.defaultGistToken = token

        val db = AppDatabase.get(applicationContext)
        // Срок жизни: durationDays >= 1 → expiresAtMs = now + days*86400s.
        // -1 или 0 → null (бессрочно).
        val expiresAt: Long? = if (durationDays > 0) {
            System.currentTimeMillis() + durationDays.toLong() * 24L * 60L * 60L * 1000L
        } else null

        @Suppress("DEPRECATION")
        val chat = Chat(
            gistId = gistId,
            gistToken = "",   // secrets stored in EncryptedSharedPreferences
            chatPassword = "",
            partnerName = roomName,
            lastMessage = "",
            lastTimeMs = System.currentTimeMillis(),
            expiresAtMs = expiresAt
        )
        // Save secrets in EncryptedSharedPreferences before DB insert.
        // Если что-то упадёт здесь — gist уже создан на GitHub, поэтому пробуем
        // его удалить чтобы не оставлять мусор, потом репортим краш.
        val chatId: Long
        try {
            prefs.saveChatSecrets(gistId, token, roomPassword)
            chatId = db.chatDao().insert(chat)
        } catch (e: Exception) {
            val msg = e.message ?: "save failed"
            android.util.Log.e("DeviceFlowService", "Failed to save chat: $msg", e)
            // Пытаемся откатить gist — не критично если не получится
            try { GistApi.deleteGist(token, gistId) } catch (_: Exception) {}
            updateNotification(getString(R.string.df_status_error, msg), terminal = true)
            OAuthCompletionTracker.markFailure(msg)
            broadcastResult(false, msg)
            CrashHandler.report(
                context = applicationContext,
                title = "Не удалось сохранить чат в БД/Keystore после создания gist. gistId=$gistId",
                throwable = e
            )
            return
        }

        // 4. Push своего профиля (не критично если упадёт — будет в следующий sync)
        val profileMsg = getString(R.string.df_status_profile)
        updateNotification(profileMsg)
        OAuthCompletionTracker.updateProgress(profileMsg)
        try {
            val api = GistTransport(GistApi(token, gistId))
            ProfileSync.pushMyProfile(
                api, roomPassword,
                Profile(
                    userId = prefs.myUserId,
                    name = prefs.myName,
                    tag = prefs.myTag,
                    avatarBase64 = prefs.myAvatarBase64
                )
            )
        } catch (_: Exception) {
        }

        // 5. Помечаем успех в tracker — это ГЛАВНЫЙ канал коммуникации с Activity.
        //    Когда пользователь вернётся в DeviceFlowActivity, onResume увидит
        //    это через Flow и сразу откроет ChatActivity.
        OAuthCompletionTracker.markSuccess(chatId)

        // 6. Финальный notification с PendingIntent → ChatActivity.
        //    Если пользователь не вернётся сам — тапнет уведомление.
        showCompletionNotification(chatId, roomName)

        // 7. Broadcast — для DeviceFlowActivity если она ещё жива (onStart-ed).
        broadcastResult(success = true, chatId = chatId)

        // 8. Поднимаем нашу task ПОВЕРХ Custom Tabs. NEW_TASK + CLEAR_TASK
        //    форсит создание свежей task с ChatActivity как root, что
        //    автоматически "опускает" task с Custom Tabs ниже неё в стеке Recents.
        //    Также moveTaskToFront — на случай если task уже существует.
        try {
            val openChat = chatActivityIntent(applicationContext, chatId).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            applicationContext.startActivity(openChat)
        } catch (_: Exception) {
        }

        try {
            val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.appTasks?.firstOrNull()?.moveToFront()
        } catch (_: Exception) {
        }
    }

    // ====== NOTIFICATIONS ======

    private fun buildProgressNotification(status: String): android.app.Notification {
        ensureChannel()
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.df_notif_title))
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(status: String, terminal: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel()
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.df_notif_title))
            .setContentText(status)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (terminal) {
            builder.setOngoing(false)
        } else {
            builder.setOngoing(true)
        }
        nm.notify(NOTIF_ID, builder.build())
    }

    private fun showCompletionNotification(chatId: Long, roomName: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel()

        val pi = PendingIntent.getActivity(
            this,
            chatId.toInt(),
            chatActivityIntent(this, chatId).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // PRIORITY_MAX + CATEGORY_CALL + setFullScreenIntent дают самое агрессивное
        // поведение: на чистом Android — heads-up notification поверх Custom Tabs,
        // на Samsung/Xiaomi/Huawei — открытие Activity напрямую поверх браузера.
        val notification = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_lock)
            .setContentTitle(getString(R.string.df_done_title))
            .setContentText(getString(R.string.df_done_text, roomName))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pi, true)
            .build()

        nm.notify(NOTIF_ID_DONE, notification)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "GitHub auth", NotificationManager.IMPORTANCE_LOW)
            )
        }
        if (nm.getNotificationChannel(CHANNEL_DONE) == null) {
            // IMPORTANCE_HIGH + bypass DND = heads-up даже когда телефон в режиме
            // "не беспокоить". В сочетании с setFullScreenIntent это даёт
            // максимальный шанс вытащить пользователя из браузера.
            val channel = NotificationChannel(
                CHANNEL_DONE,
                "GitHub auth — готово",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомление об успешном создании комнаты"
                enableLights(true)
                enableVibration(true)
                setBypassDnd(true)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }
    }

    // ====== BROADCAST ======

    private suspend fun broadcastResult(success: Boolean, errorMessage: String? = null, chatId: Long? = null) {
        withContext(Dispatchers.Main) {
            val intent = Intent(ACTION_DONE).apply {
                setPackage(packageName)
                putExtra(EXTRA_SUCCESS, success)
                if (chatId != null) putExtra(EXTRA_CHAT_ID, chatId)
                if (errorMessage != null) putExtra(EXTRA_ERROR_MSG, errorMessage)
            }
            sendBroadcast(intent)
        }
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val NOTIF_ID_DONE = 1002
        private const val CHANNEL_PROGRESS = "device_flow_progress"
        private const val CHANNEL_DONE = "device_flow_done"

        const val EXTRA_DEVICE_CODE = "device_code"
        const val EXTRA_INTERVAL_SEC = "interval_sec"
        const val EXTRA_EXPIRES_SEC = "expires_sec"
        const val EXTRA_ROOM_NAME = "room_name"
        const val EXTRA_ROOM_PASSWORD = "room_password"
        /** Срок жизни чата в днях. -1 или 0 = бессрочно. */
        const val EXTRA_DURATION_DAYS = "duration_days"

        const val ACTION_DONE = "com.atrum.chat.DEVICE_FLOW_DONE"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_ERROR_MSG = "error"

        fun start(
            ctx: Context,
            deviceCode: String,
            intervalSec: Int,
            expiresInSec: Int,
            roomName: String,
            roomPassword: String,
            durationDays: Int = -1
        ) {
            val i = Intent(ctx, DeviceFlowService::class.java).apply {
                putExtra(EXTRA_DEVICE_CODE, deviceCode)
                putExtra(EXTRA_INTERVAL_SEC, intervalSec)
                putExtra(EXTRA_EXPIRES_SEC, expiresInSec)
                putExtra(EXTRA_ROOM_NAME, roomName)
                putExtra(EXTRA_ROOM_PASSWORD, roomPassword)
                putExtra(EXTRA_DURATION_DAYS, durationDays)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        private fun chatActivityIntent(ctx: Context, chatId: Long): Intent =
            Intent(ctx, ChatActivity::class.java).putExtra(ChatActivity.EXTRA_CHAT_ID, chatId)
    }
}
