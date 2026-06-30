package com.atrum.chat

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.NostrMessageStore
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Лёгкий foreground-сервис пушей.
 *
 * Минимальная задержка БЕЗ частого опроса реле: для каждого чата открывается
 * ПОТОКОВАЯ подписка ([ChatTransport.watchMessages]) — реле само присылает новое
 * сообщение в момент отправки. Тогда пересчёт непрочитанных идёт ЛОКАЛЬНО из
 * [NostrMessageStore] (без сети) и показывается анонимный пуш с числом.
 *
 * Сеть трогаем редко: раз в [FALLBACK_MS] делаем фоновую сверку (catch-up) на
 * случай пропущенных событий/реконнекта. Всё — через ChatTransport (§1).
 * Пуши показываются только когда приложение свёрнуто ([App.inForeground]).
 */
class MessageWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var recomputeJob: Job? = null

    private val transports = ConcurrentHashMap<Long, ChatTransport>()
    private val watches = ConcurrentHashMap<Long, AutoCloseable>()

    private val prefs by lazy { Prefs(applicationContext) }
    private val db by lazy { AppDatabase.get(applicationContext) }

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
        while (true) {
            try {
                if (prefs.pushEnabled) {
                    ensureWatches()          // открыть стрим-подписки на новые чаты
                    networkSync()            // редкая фоновая сверка (catch-up)
                    recomputeAndNotify()     // пересчёт из локального стора
                }
            } catch (_: Throwable) {
                // Фоновый цикл не должен падать.
            }
            delay(FALLBACK_MS)
        }
    }

    /** Открывает потоковую подписку на каждый чат, у которого её ещё нет. */
    private suspend fun ensureWatches() {
        val myUserId = prefs.myUserId
        val chats = db.chatDao().getAll()
        val activeIds = chats.filter { !it.isFavorites }.map { it.id }.toSet()

        // 1. Очистка: закрываем подписки для чатов, которые были удалены или стали избранными
        val it = watches.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (!activeIds.contains(entry.key)) {
                runCatching { entry.value.close() }
                it.remove()
                transports.remove(entry.key)
            }
        }

        // 2. Добавление: открываем новые стримы
        for (chat in chats) {
            if (chat.isFavorites || watches.containsKey(chat.id)) continue

            try {
                val token = prefs.getChatToken(chat.chatId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.transportToken
                val password = prefs.getChatPassword(chat.chatId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword

                val t = TransportFactory.forChat(applicationContext, chat.chatId, token, password, myUserId)
                transports[chat.id] = t
                watches[chat.id] = t.watchMessages { onStreamEvent() }
            } catch (e: Exception) {
                // Ошибка конкретного чата не должна прерывать цикл
                android.util.Log.e("MessageWatchService", "Failed to watch chat ${chat.chatId}", e)
            }
        }
    }

    /** Реле прислало новое событие → быстрый локальный пересчёт (с debounce). */
    private fun onStreamEvent() {
        if (!prefs.pushEnabled) return
        recomputeJob?.cancel()
        recomputeJob = scope.launch {
            delay(400) // склеиваем всплеск событий в один пересчёт
            runCatching { recomputeAndNotify() }
        }
    }

    /** Редкая сетевая сверка: подтягивает историю в стор (через ChatTransport). */
    private suspend fun networkSync() {
        for ((id, t) in transports) {
            if (db.chatDao().getById(id)?.isFavorites != false) continue
            try { t.loadContent() } catch (_: Exception) {}
        }
    }

    /**
     * Пересчёт непрочитанных ЛОКАЛЬНО из [NostrMessageStore] (без сети) и анонимный
     * пуш с суммарным числом. Звеним только когда сумма выросла.
     */
    private suspend fun recomputeAndNotify() {
        val myName = prefs.myName
        val myUserId = prefs.myUserId
        val aliases = prefs.nameHistory

        var totalUnread = 0
        for (chat in db.chatDao().getAll()) {
            if (chat.isFavorites) continue
            val t = transports[chat.id] ?: continue
            try {
                val password = prefs.getChatPassword(chat.chatId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword
                // FS: устанавливаем сессионный ключ, только если его нет, чтобы не нагружать CPU в фоне.
                if (!CryptoHelper.hasSessionKey(chat.chatId)) {
                    CryptoHelper.ensureSessionKey(
                        chat.chatId, 
                        prefs.getEphemeralPriv(chat.chatId), 
                        chat.partnerEphemeralPubKeyB64
                    )
                }
                val content = NostrMessageStore.render(t.chatId)
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val unread = if (lines.size <= chat.lastSeenLineCount) 0 else {
                    lines.drop(chat.lastSeenLineCount).count { line ->
                        val dec = CryptoHelper.decrypt(line, password, chat.chatId) ?: return@count false
                        val parsed = Message.fromDecrypted(dec, myUserId, myName, aliases)
                        !parsed.isSelf && parsed.sender.isNotEmpty()
                    }
                }
                if (unread != chat.unreadCount) db.chatDao().updateUnread(chat.id, unread)

                // Превью последнего сообщения — чтобы список обновлялся ПОЧТИ МГНОВЕННО на
                // стрим-событие (не дожидаясь 8-секундного опроса ChatsListActivity). Список
                // наблюдает БД через Flow, поэтому updatePreview сразу отражается в UI.
                if (lines.isNotEmpty()) {
                    val lastDec = CryptoHelper.decrypt(lines.last(), password, chat.chatId)
                    if (lastDec != null) {
                        val pm = Message.fromDecrypted(lastDec, myUserId, myName, aliases)
                        val body = when {
                            pm.isImage && pm.text.isBlank() -> getString(R.string.msg_preview_photo)
                            pm.isImage -> getString(R.string.msg_preview_photo_format, pm.text)
                            pm.isVoice -> getString(R.string.msg_preview_voice)
                            pm.isSticker -> getString(R.string.msg_preview_sticker)
                            pm.isReply -> getString(R.string.msg_preview_reply_format, pm.text)
                            else -> pm.text
                        }
                        val preview = (if (pm.isSelf) getString(R.string.msg_preview_self_format, body) else body).take(80)
                        if (preview != chat.lastMessage) db.chatDao().updatePreview(chat.id, preview, chat.lastTimeMs)
                    }
                }
                totalUnread += unread
            } catch (_: Exception) {
                totalUnread += chat.unreadCount
            }
        }

        if (App.inForeground) return // приложение открыто — пуш не нужен

        val last = prefs.pushNotifiedTotal
        when {
            totalUnread == 0 -> if (last != 0) { NotificationHelper.cancelMessages(applicationContext); prefs.pushNotifiedTotal = 0 }
            totalUnread > last -> { NotificationHelper.notifyNewMessage(applicationContext, totalUnread, alert = true); prefs.pushNotifiedTotal = totalUnread }
            totalUnread != last -> { NotificationHelper.notifyNewMessage(applicationContext, totalUnread, alert = false); prefs.pushNotifiedTotal = totalUnread }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watches.values.forEach { runCatching { it.close() } }
        watches.clear()
        transports.clear()
        scope.cancel()
        loopJob = null
    }

    companion object {
        /** Редкая фоновая сверка: стрим даёт реалтайм, сеть трогаем нечасто. */
        private const val FALLBACK_MS = 90_000L

        fun start(ctx: Context) {
            if (!Prefs(ctx).pushEnabled) return
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MessageWatchService::class.java))
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, MessageWatchService::class.java))
        }
    }
}
