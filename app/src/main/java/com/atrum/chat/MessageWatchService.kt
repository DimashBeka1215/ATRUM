package com.atrum.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Лёгкий foreground-сервис: пока приложение свёрнуто, периодически опрашивает реле
 * и шлёт АНОНИМНЫЙ пуш «У вас новое сообщение.» при появлении чужого сообщения.
 *
 * Сетевые запросы — ТОЛЬКО через ChatTransport (TransportFactory), как требует
 * §1 синхронизации. Когда любой экран на переднем плане ([App.inForeground]) —
 * сервис НЕ опрашивает сеть: этим занимаются ChatActivity/ChatsListActivity.
 * Так не возникает второй конкурирующий цикл и не упираемся в rate-limit реле.
 */
class MessageWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = NotificationHelper.buildOngoing(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NotificationHelper.FGS_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NotificationHelper.FGS_ID, notif)
        }
        if (loopJob == null) loopJob = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val prefs = Prefs(applicationContext)
        val db = AppDatabase.get(applicationContext)
        while (true) {
            try {
                if (prefs.pushEnabled && !App.inForeground) checkChats(prefs, db)
            } catch (_: Throwable) {
                // Фоновый цикл не должен падать — следующая итерация попробует снова.
            }
            delay(POLL_MS)
        }
    }

    private suspend fun checkChats(prefs: Prefs, db: AppDatabase) {
        val chats = db.chatDao().getAll()
        val myName = prefs.myName
        val myUserId = prefs.myUserId
        val aliases = prefs.nameHistory

        var totalUnread = 0
        for (chat in chats) {
            if (chat.isFavorites) continue
            try {
                val token = prefs.getChatToken(chat.gistId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.gistToken
                val password = prefs.getChatPassword(chat.gistId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword
                val api = TransportFactory.forChat(applicationContext, chat.gistId, token, password, myUserId)
                val content = api.loadContent()
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

                // Чужие сообщения среди новых строк (свои в счёт не идут).
                val unreadFromOthers = if (lines.size <= chat.lastSeenLineCount) 0 else {
                    lines.drop(chat.lastSeenLineCount).count { line ->
                        val dec = CryptoHelper.decrypt(line, password, chat.gistId) ?: return@count false
                        val parsed = Message.fromDecrypted(dec, myUserId, myName, aliases)
                        !parsed.isSelf && parsed.sender.isNotEmpty()
                    }
                }
                if (unreadFromOthers != chat.unreadCount) db.chatDao().updateUnread(chat.id, unreadFromOthers)
                totalUnread += unreadFromOthers
            } catch (_: Exception) {
                // Ошибка по одному чату не должна мешать остальным — учитываем прошлый unread.
                totalUnread += chat.unreadCount
            }
        }

        // Одно уведомление с растущим числом. Звеним только когда сумма ВЫРОСЛА
        // (пришло новое); при чтении — обновляем число тихо или убираем карточку.
        val last = prefs.pushNotifiedTotal
        when {
            totalUnread == 0 -> if (last != 0) { NotificationHelper.cancelMessages(applicationContext); prefs.pushNotifiedTotal = 0 }
            totalUnread > last -> { NotificationHelper.notifyNewMessage(applicationContext, totalUnread, alert = true); prefs.pushNotifiedTotal = totalUnread }
            totalUnread != last -> { NotificationHelper.notifyNewMessage(applicationContext, totalUnread, alert = false); prefs.pushNotifiedTotal = totalUnread }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        loopJob = null
    }

    companion object {
        /** Реже, чем поллинг открытого чата (~1.5с): фон — экономнее по сети и батарее. */
        private const val POLL_MS = 20_000L

        fun start(ctx: Context) {
            if (!Prefs(ctx).pushEnabled) return
            // Старт FGS из фона на Android 12+ может бросать исключение — не роняем процесс.
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MessageWatchService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MessageWatchService::class.java))
        }
    }
}
