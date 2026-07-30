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
    @Volatile private var lastFullSyncMs = 0L

    private val transports = ConcurrentHashMap<Long, ChatTransport>()
    private val watches = ConcurrentHashMap<Long, AutoCloseable>()

    // «Пропускать лишнее» (репорт: «греет CPU в фоне»): на каждом тике самое дорогое —
    // расшифровка (Argon2/AES) членства и непрочитанных. Если зашифрованный контент чата
    // с прошлого тика НЕ изменился, повторно расшифровывать и применять нечего — тяжёлую
    // работу пропускаем, а число непрочитанных берём уже посчитанное. Ключ — chat.id,
    // значение — хеш зашифрованного контента (без расшифровки).
    private val lastGroupSyncHash = ConcurrentHashMap<Long, Int>()
    private val lastRecomputeHash = ConcurrentHashMap<Long, Int>()

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
        // START_NOT_STICKY (было START_STICKY): если систему убила службу или пользователь
        // закрыл приложение — НЕ воскрешаем её сами (репорт: «сам себя перезапускает, не
        // могу закрыть»). Служба работает, пока приложение открыто/в фоне; пользователь сам
        // решает — оставить в фоне или закрыть.
        return START_NOT_STICKY
    }

    private suspend fun loop() {
        while (true) {
            try {
                if (prefs.pushEnabled) {
                    ensureWatches()          // открыть стрим-подписки на новые чаты
                    // ⚠️ Членство групп (баны/муты → системные уведомления + пропагация
                    // бана) синкается РЕГУЛЯРНО и НЕЗАВИСИМО от foreground (репорт: «бан не
                    // пришёл»/«уведомления не приходят, когда ты в чате уведомлений»).
                    // Раньше это жило внутри networkSync, который работал ТОЛЬКО в фоне —
                    // пока пользователь на любом другом экране (напр. чат «Уведомления»),
                    // чужие группы никто не опрашивал, и уведомления не появлялись. Файлы
                    // членства крошечные (~1-2КБ), так что частый опрос дёшев.
                    syncAllGroupMembership()
                    // Истечение срока моего мута — нет события members.txt, ловим по времени.
                    SystemNotifications.checkMuteExpiry(applicationContext)
                    // Догрузка истории 1:1 для счётчика непрочитанных — только в фоне
                    // (батарея): на переднем плане этим занимается открытый ChatActivity.
                    if (!App.inForeground) {
                        val now = System.currentTimeMillis()
                        if (!watchesHealthy() || now - lastFullSyncMs >= SAFETY_SYNC_MS) {
                            networkSync()
                            lastFullSyncMs = now
                        }
                    }
                    recomputeAndNotify()     // пересчёт из локального стора
                }
            } catch (_: Throwable) {
                // Фоновый цикл не должен падать.
            }
            // Интервал: на переднем плане чуть реже (беседа грузится без конкуренции за
            // Tor), в фоне чаще. НО если скоро истекает мой мут — просыпаемся ровно к
            // сроку (+буфер), чтобы «мут истёк» пришло точно вовремя, а не с задержкой.
            val base = if (App.inForeground) MEMBERSHIP_SYNC_FG_MS else MEMBERSHIP_SYNC_MS
            val nextExpiry = runCatching { SystemNotifications.nearestFutureMuteExpiry(applicationContext) }.getOrDefault(Long.MAX_VALUE)
            val untilExpiry = if (nextExpiry == Long.MAX_VALUE) base
                else (nextExpiry - System.currentTimeMillis() + 300L).coerceIn(500L, base)
            delay(untilExpiry)
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
                lastGroupSyncHash.remove(entry.key)
                lastRecomputeHash.remove(entry.key)
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

                // adminUserId — иначе NostrTransport.adminPubkeyHex = null и members.txt/
                // groupprofile.txt никогда не проходят проверку подписи в ФОНОВОМ сервисе
                // (см. networkSync ниже: фон применяет мут/бан, пока приложение закрыто).
                val t = TransportFactory.forChat(applicationContext, chat.chatId, token, password, myUserId, chat.adminUserId)
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

    /** Все стрим-подписки живы? Если да — сетевую сверку можно пропустить (экономия). */
    private fun watchesHealthy(): Boolean {
        if (transports.isEmpty()) return false
        return transports.values.all { runCatching { it.isWatchHealthy() }.getOrDefault(false) }
    }

    /** Догрузка истории 1:1-чатов в стор для счётчика непрочитанных (только в фоне). */
    private suspend fun networkSync() {
        for ((id, t) in transports) {
            val chat = db.chatDao().getById(id) ?: continue
            if (chat.isFavorites || chat.isGroup) continue // группы — в syncAllGroupMembership
            try { t.loadContent() } catch (_: Exception) {}
        }
    }

    /**
     * Синк членства ВСЕХ групп: применяет members.txt (баны/муты → системные уведомления,
     * см. MembersSync.applyIncoming) и профиль беседы, пропагирует бан. Вызывается на
     * КАЖДОМ тике сервиса независимо от foreground — иначе, пока пользователь на другом
     * экране, уведомления о чужих группах не генерируются (репорт). Файлы крошечные.
     */
    private suspend fun syncAllGroupMembership() {
        for ((id, t) in transports) {
            val chat = db.chatDao().getById(id) ?: continue
            if (chat.isFavorites || !chat.isGroup) continue
            // Открытый прямо сейчас чат опрашивает свой ChatActivity (1с) — не дублируем
            // тяжёлый loadAll через Tor, чтобы не тормозить загрузку самой беседы (репорт).
            if (chat.chatId == App.currentOpenChatId) continue
            runCatching {
                val all = t.loadAll()
                // Пропуск неизменного: хеш зашифрованного членства/профиля/слотов. Если он
                // совпал с прошлым тиком — расшифровывать и применять нечего (дорогой Argon2
                // не запускаем). checkMuteExpiry (по времени) идёт отдельно и не зависит от этого.
                val syncHash = (all.membersContent.hashCode() * 31 + all.groupProfileContent.hashCode()) * 31 +
                    all.memberSlots.joinToString("").hashCode() * 31 +
                    all.profileSlotsSigned.joinToString("").hashCode()
                if (lastGroupSyncHash[chat.id] == syncHash) return@runCatching
                lastGroupSyncHash[chat.id] = syncHash
                val password = prefs.getChatPassword(chat.chatId).takeIf { it.isNotEmpty() }
                    ?: @Suppress("DEPRECATION") chat.chatPassword
                runCatching {
                    GroupProfileSync.applyIncoming(chat, all.groupProfileContent, password, db.chatDao(), prefs)
                }
                runCatching {
                    MembersSync.applyIncoming(
                        chat = chat,
                        membersContentEncrypted = all.membersContent,
                        password = password,
                        participantDao = db.chatParticipantDao(),
                        chatDao = db.chatDao(),
                        myUserId = prefs.myUserId,
                        appContext = applicationContext,
                        groupEventDao = db.groupEventDao(),
                        memberSlots = all.memberSlots,
                        pubkeyForUserId = t::pubkeyForUserId
                    )
                }
                // Децентрализованный ростер (ADR-001): наполняем участников из
                // самоопубликованных профилей, чтобы счётчик рос БЕЗ админа. После MembersSync.
                runCatching {
                    GroupRosterSync.applyProfileRoster(
                        chat = chat,
                        signedSlots = all.profileSlotsSigned,
                        password = password,
                        participantDao = db.chatParticipantDao(),
                        myUserId = prefs.myUserId,
                        adminUserId = chat.adminUserId,
                        pubkeyForUserId = t::pubkeyForUserId
                    )
                }
                // Бан больше НЕ удаляет чат — он сохраняется и продолжает опрашиваться,
                // чтобы разбан оставался наблюдаемым (репорт: «забаненные невидимы»).
                // Из списка забаненный чат прячется по флагу (см. ChatsListActivity),
                // уведомление о бане/разбане пишет applyIncoming выше.

                // ⚠️ Фоновый ЭНРОЛЛ (репорт: «число участников у людей меняется только
                // после захода админа»). Раньше добавление новичков в members.txt жило ТОЛЬКО
                // в ChatActivity (открытый чат), поэтому у остальных счётчик обновлялся лишь
                // когда админ сам заходил. Теперь админ дозаписывает новичков и в фоне, для
                // чатов, которые прямо сейчас НЕ открыты (открытый обслуживает ChatActivity,
                // см. continue выше — конфликта нет). Публикация — тем же планировщиком.
                runCatching { maybeAdminEnrollBackground(chat, all, password, t) }
            }
        }
    }

    /**
     * Админский энролл в фоне: добавляет в members.txt участников, которые уже опубликовали
     * свой профиль (profiles.txt), но ещё не в ростере. Только для МОИХ (админских) групп и
     * только когда ростер уже непустой (самолечение «с нуля» — задача ChatActivity, чтобы
     * фон не опубликовал вырожденную версию). Публикация — через PublishScheduler (версия+1,
     * снимок из Room), как и в ChatActivity.maybeAdminEnrollNewMembers.
     */
    private val bgEnrollThrottleMs = HashMap<String, Long>()

    private suspend fun maybeAdminEnrollBackground(
        chat: com.atrum.chat.data.Chat,
        all: com.atrum.chat.transport.AllChannelData,
        password: String,
        transport: com.atrum.chat.transport.ChatTransport
    ) {
        if (chat.adminUserId != prefs.myUserId) return
        // ⚠️ ПРОИЗВОДИТЕЛЬНОСТЬ (репорт: «чаты грузятся долго, сообщения через 10с»):
        // энролл расшифровывает ВСЕ слоты профилей (unionProfileSlots) и делит диспетчер
        // расшифровки с открытым чатом → на переднем плане это тормозило загрузку. Теперь
        // энролл идёт ТОЛЬКО когда приложение в фоне (нет открытого чата/списка, конкуренции
        // нет) и не чаще раза в 90с на чат — новичок появится у всех в течение полутора
        // минут, но без ущерба скорости UI.
        if (App.inForeground) return
        val now = System.currentTimeMillis()
        if (now - (bgEnrollThrottleMs[chat.chatId] ?: 0L) < 90_000L) return
        bgEnrollThrottleMs[chat.chatId] = now
        val profiles = if (ChatActivity.SLOT_UNION_PROFILES && all.profileSlots.isNotEmpty())
            ProfileSync.unionProfileSlots(all.profileSlots, password, transport.chatId)
        else ProfileSync.parseProfiles(all.profilesContent, password, transport.chatId)
        if (profiles.isEmpty()) return
        val current = db.chatParticipantDao().getForChat(chat.id)
        if (current.isEmpty()) return // «с нуля» не заводим в фоне — это делает ChatActivity
        val knownIds = current.map { it.userId }.toSet()
        val bannedIds = current.filter { it.banned }.map { it.userId }.toSet()
        val activeCount = current.count { !it.banned }
        // Не воскрешаем вышедших/удаливших профиль (ADR-001, децентрализованный ростер).
        val candidates = profiles.keys.filter { uid ->
            uid != prefs.myUserId && uid !in knownIds && uid !in bannedIds &&
                profiles[uid]?.let { !it.left && !it.deleted } != false
        }
        if (candidates.isEmpty()) return
        val limit = chat.participantLimit
        val freeSlots = limit?.let { (it - activeCount).coerceAtLeast(0) } ?: candidates.size
        if (freeSlots <= 0) return
        val toAdd = candidates.take(freeSlots)
        db.chatParticipantDao().upsertAll(
            toAdd.map { com.atrum.chat.data.ChatParticipant(ownerId = chat.id, userId = it, banned = false) }
        )
        PublishScheduler.markMembersDirty(applicationContext, chat.chatId)
    }

    /**
     * Упоминание меня (@) в групповом сообщении → уведомление в чат «Уведомления», один раз
     * на msgId (дедуп через Prefs.claimMentionNotified — фон пересканирует непрочитанные
     * каждый тик). Вызывается из recomputeAndNotify при декодировании новых строк.
     */
    private suspend fun maybeNotifyMention(chat: com.atrum.chat.data.Chat, msg: Message) {
        // Вызывающий уже подтвердил: группа, не своё, упомянут я — здесь только дедуп+запись.
        val msgId = msg.msgId
        if (msgId.isBlank()) return
        if (!prefs.claimMentionNotified(chat.chatId, msgId)) return
        val groupName = chat.groupName?.takeIf { it.isNotBlank() } ?: chat.partnerName
        SystemNotifications.notifyMentioned(applicationContext, groupName, msg.sender)
    }

    /**
     * Пересчёт непрочитанных ЛОКАЛЬНО из [NostrMessageStore] (без сети) и анонимный
     * пуш с суммарным числом. Звеним только когда сумма выросла.
     */
    private suspend fun recomputeAndNotify() {
        val myName = prefs.myName
        val myUserId = prefs.myUserId
        val aliases = prefs.nameHistory
        val myTag = prefs.myTag // ⚠️ читаем ОДИН раз: prefs — EncryptedSharedPreferences,
        // доступ к полю = расшифровка; читать myTag/myName на каждую строку в цикле дорого.

        var totalUnread = 0
        for (chat in db.chatDao().getAll()) {
            if (chat.isFavorites) continue
            // Забаненный чат сохраняется (для наблюдения разбана), но пуши/непрочитанные
            // по нему НЕ считаем — иначе прилетали бы уведомления из группы, откуда
            // пользователя исключили.
            if (chat.isGroup && db.chatParticipantDao().getOne(chat.id, myUserId)?.banned == true) continue
            val t = transports[chat.id] ?: continue
            try {
                val content = NostrMessageStore.render(t.chatId)
                // Пропуск неизменного: если контент чата и позиция «прочитано» не менялись
                // с прошлого тика — расшифровывать строки заново незачем (это самый дорогой
                // шаг). Берём уже посчитанное число непрочитанных и идём дальше. render() —
                // это только чтение локального стора, без расшифровки, поэтому дёшево.
                val recomputeHash = content.hashCode() * 31 + chat.lastSeenLineCount
                if (lastRecomputeHash[chat.id] == recomputeHash) {
                    totalUnread += chat.unreadCount
                    continue
                }
                lastRecomputeHash[chat.id] = recomputeHash
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
                val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
                val mentionIds = ArrayList<String>()
                val unread = if (lines.size <= chat.lastSeenLineCount) 0 else {
                    lines.drop(chat.lastSeenLineCount).count { line ->
                        val dec = CryptoHelper.decrypt(line, password, chat.chatId) ?: return@count false
                        val parsed = Message.fromDecrypted(dec, myUserId, myName, aliases)
                        // @упоминание меня в группе: собираем msgId (бейдж/кнопка) и уведомляем,
                        // если чат не открыт (иначе я и так вижу).
                        if (MentionUtil.ENABLED && chat.isGroup && !parsed.isSelf && MentionUtil.mentionsMe(parsed.text, myTag, myName)) {
                            if (parsed.msgId.isNotBlank()) mentionIds.add(parsed.msgId)
                            if (chat.chatId != App.currentOpenChatId) maybeNotifyMention(chat, parsed)
                        }
                        !parsed.isSelf && parsed.sender.isNotEmpty()
                    }
                }
                if (unread != chat.unreadCount) db.chatDao().updateUnread(chat.id, unread)
                // Непрочитанные @упоминания → бейдж «@N»/снимок кнопки. Пишем только при
                // реальном изменении, чтобы не дёргать Flow списка чатов лишний раз.
                if (MentionUtil.ENABLED && chat.isGroup) {
                    val csv = mentionIds.joinToString(",").ifEmpty { null }
                    if (csv != chat.mentionMsgIds) db.chatDao().updateMentionMsgIds(chat.id, csv)
                }

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
                    // lastDec == null (forward secrecy при закрытом чате) — превью не трогаем.
                } else if (chat.lastMessage.isNotEmpty()) {
                    // Сообщений не осталось (очистка/удаление) — чистим застрявший «отпечаток».
                    db.chatDao().updatePreview(chat.id, "", chat.lastTimeMs)
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Приложение СМАХНУЛИ из «недавних» — это осознанное закрытие пользователем.
        // Помечаем флагом и НЕ воскрешаем (репорт: «сам себя перезапускает, не могу закрыть»):
        // резервный воркер увидит флаг и не поднимет службу обратно. Фоновые уведомления при
        // обычном сворачивании (Home) не трогаются — служба продолжает работать; воскрешение
        // после Doze-kill (когда пользователь НЕ закрывал) остаётся, флаг там не взведён.
        runCatching { prefs.serviceUserDismissed = true }
        runCatching { stopSelf() }
        super.onTaskRemoved(rootIntent)
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
        /** Страховочная полная сверка, даже если стрим считается живым (silent-fail реле). */
        private const val SAFETY_SYNC_MS = 5 * 60_000L
        /**
         * Тик синка членства групп (баны/муты → уведомления). Частый и независимый от
         * foreground — уведомления должны приходить на любом экране (репорт). Файлы
         * членства крошечные, поэтому опрос дёшев.
         */
        // Фон: реже, чтобы не греть процессор и дать устройству спать (репорт: «высокий расход
        // батареи в фоне ~28%/14ч»). Новые сообщения и так приходят мгновенно через стрим-
        // подписку; этот опрос — подстраховка для членства/непрочитанных, ему частота не нужна.
        // 30с → 60с (с явного разрешения пользователя, см. CLAUDE.md §1): вдвое меньше фоновых
        // пробуждений и Tor-loadAll по группам. Цена: фоновые уведомления (бан/мут/непрочитанное)
        // могут отстать до ~60с. Стрим доставки сообщений это НЕ трогает.
        private const val MEMBERSHIP_SYNC_MS = 60_000L
        /** Тот же синк на переднем плане — чуть реже фона, но открытый чат пропускается
         *  (skip в syncAllGroupMembership), так что конкуренции за Tor нет. 10с — баланс
         *  между скоростью уведомлений и нагрузкой. */
        private const val MEMBERSHIP_SYNC_FG_MS = 10_000L

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
