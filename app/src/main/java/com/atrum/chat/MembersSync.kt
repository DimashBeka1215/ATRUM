package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatDao
import com.atrum.chat.data.ChatParticipant
import com.atrum.chat.data.ChatParticipantDao
import com.atrum.chat.data.GroupEventDao
import com.atrum.chat.data.GroupEventEntry
import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.MemberSlot
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Синхронизация членства/бана группового чата через members.txt (ADR-001,
 * см. ADR_GROUP_CHATS.md).
 *
 * Формат (plaintext JSON ДО шифрования — шифруется тем же V5/общим паролем чата,
 * что и messages.txt/profiles.txt, см. CryptoHelper.encrypt(content, password, chatId)):
 *
 *   {
 *     "v": 3,
 *     "adminUserId": "...",
 *     "ts": 1730000000000,
 *     "participants": [
 *       {"userId": "...", "banned": false},
 *       {"userId": "...", "banned": true}
 *     ]
 *   }
 *
 * Подлинность НЕ проверяется здесь — это уже сделано транспортом ДО того, как
 * контент попал в AllChannelData.membersContent (см. NostrTransport.latestVerifiedMembersFile):
 * событие принимается только если его pubkey совпадает с детерминированным pubkey
 * администратора и подпись валидна (тот же принцип, что и RelayListStore.tryApply).
 *
 * Публиковать [publish] технически может вызвать любой участник (знает общий пароль
 * группы), но осмысленно это делать только администратору — иначе pubkey события не
 * совпадёт с ожидаемым и все остальные клиенты его проигнорируют на транспортном
 * уровне. Реальная защита от случайного вызова — UI не показывает управление
 * участниками не-админу (см. PartnerProfileActivity, ADR-001 §Action items).
 */
object MembersSync {

    data class Entry(
        val userId: String,
        val banned: Boolean,
        /** null — не заглушён; иначе метка времени (мс), до которой заглушён (см. ChatParticipant.mutedUntilMs). */
        val mutedUntilMs: Long? = null,
        val mutedReason: String? = null,
        /** msgId'ы сообщений-оснований мута (см. ChatParticipant.mutedEvidenceIds). Пусто — не указаны. */
        val mutedEvidenceIds: List<String> = emptyList(),
        /** Битовая маска прав админа (см. AdminPermissions). 0 — обычный участник. */
        val permissions: Int = 0
    )

    /** Список msgId → строка для Room (ChatParticipant.mutedEvidenceIds). Пустой список → null. */
    fun evidenceIdsToStore(ids: List<String>): String? = ids.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** Строка из Room обратно в список msgId. */
    fun evidenceIdsFromStore(stored: String?): List<String> =
        stored?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    data class MembersFile(
        val version: Int,
        val adminUserId: String,
        val participants: List<Entry>,
        val ts: Long,
        /** Название группы. null — не менять локально сохранённое (совместимость со старыми версиями). */
        val groupName: String? = null,
        /** Аватар группы (base64). null — не менять локально сохранённый. */
        val groupAvatarBase64: String? = null,
        /** Описание группы. null — не менять локально сохранённое (см. groupName). */
        val groupDescription: String? = null,
        /**
         * Закреплённые сообщения (Этап 3 «Админы», право PIN) — список msgId в порядке
         * закрепления. В слоте это МОИ закрепления (contributions); итоговый показываемый
         * набор считает [mergeSlots] как объединение слотов уполномоченных пиннеров.
         */
        val pinned: List<String> = emptyList()
    )

    fun parse(decryptedJson: String): MembersFile? = try {
        val j = JSONObject(decryptedJson)
        val arr = j.getJSONArray("participants")
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val userId = o.optString("userId").takeIf { it.isNotBlank() } ?: continue
            val evidenceArr = o.optJSONArray("mutedEvidence")
            val evidenceIds = if (evidenceArr != null) {
                (0 until evidenceArr.length()).mapNotNull { evidenceArr.optString(it).takeIf { s -> s.isNotBlank() } }
            } else emptyList()
            list.add(
                Entry(
                    userId = userId,
                    banned = o.optBoolean("banned", false),
                    mutedUntilMs = o.optLong("mutedUntil", 0L).takeIf { it > 0L },
                    mutedReason = o.optString("mutedReason", "").takeIf { it.isNotBlank() },
                    mutedEvidenceIds = evidenceIds,
                    permissions = o.optInt("perm", 0)
                )
            )
        }
        val pinnedArr = j.optJSONArray("pinned")
        val pinnedList = if (pinnedArr != null) {
            (0 until pinnedArr.length()).mapNotNull { pinnedArr.optString(it).takeIf { s -> s.isNotBlank() } }
        } else emptyList()
        MembersFile(
            version = j.getInt("v"),
            adminUserId = j.getString("adminUserId"),
            participants = list,
            ts = j.optLong("ts", System.currentTimeMillis()),
            groupName = j.optString("groupName", "").takeIf { it.isNotBlank() },
            groupAvatarBase64 = j.optString("groupAvatarBase64", "").takeIf { it.isNotBlank() },
            groupDescription = j.optString("groupDescription", "").takeIf { it.isNotBlank() },
            pinned = pinnedList
        )
    } catch (_: Exception) {
        null
    }

    fun buildContent(
        version: Int,
        adminUserId: String,
        participants: List<Entry>,
        groupName: String? = null,
        groupAvatarBase64: String? = null,
        groupDescription: String? = null,
        pinned: List<String> = emptyList()
    ): String =
        JSONObject().apply {
            put("v", version)
            put("adminUserId", adminUserId)
            put("ts", System.currentTimeMillis())
            if (!groupName.isNullOrBlank()) put("groupName", groupName)
            if (!groupAvatarBase64.isNullOrBlank()) put("groupAvatarBase64", groupAvatarBase64)
            if (!groupDescription.isNullOrBlank()) put("groupDescription", groupDescription)
            if (pinned.isNotEmpty()) put("pinned", JSONArray(pinned))
            put(
                "participants",
                JSONArray().also { arr ->
                    participants.forEach { p ->
                        arr.put(
                            JSONObject().apply {
                                put("userId", p.userId)
                                put("banned", p.banned)
                                if (p.mutedUntilMs != null) put("mutedUntil", p.mutedUntilMs)
                                if (!p.mutedReason.isNullOrBlank()) put("mutedReason", p.mutedReason)
                                if (p.mutedEvidenceIds.isNotEmpty()) put("mutedEvidence", JSONArray(p.mutedEvidenceIds))
                                if (p.permissions != 0) put("perm", p.permissions)
                            }
                        )
                    }
                }
            )
        }.toString()

    /** Вклад одного делегата в слияние: его права (perm) + распарсенный слот. */
    data class Contribution(val perm: Int, val file: MembersFile)

    /**
     * Слияние слотов мультиподписи (Этапы 2–3) с ВЕРХОВЕНСТВОМ ГЛАВНОГО.
     *
     *  • [primary] — members.txt слот ГЛАВНОГО: единственный источник истины по ростеру,
     *    ролям (perm) и имени/описанию. Делегаты это НЕ меняют.
     *  • [contributions] — слоты уполномоченных делегатов с их правами. Из каждого берётся
     *    ТОЛЬКО то, на что есть право: мут/бан — при MODERATE, закрепления — при PIN.
     *
     * Результат: у не-главного участника banned = ИЛИ по MODERATE-слотам, мут = максимальный
     * по сроку; закреплённые = объединение пинов главного и PIN-слотов (порядок: сперва
     * главного, затем добавленные делегатами). Версия/adminUserId/perm/имя/описание — от
     * главного. Делегат не может добавить/убрать участника или изменить роли.
     */
    fun mergeSlots(primary: MembersFile, contributions: List<Contribution>): MembersFile {
        if (contributions.isEmpty()) return primary
        val primaryAdmin = primary.adminUserId
        val moderators = contributions.filter { AdminPermissions.has(it.perm, AdminPermissions.MODERATE) }.map { it.file }
        val pinners = contributions.filter { AdminPermissions.has(it.perm, AdminPermissions.PIN) }.map { it.file }

        val mergedParticipants = primary.participants.map { p ->
            if (p.userId == primaryAdmin) return@map p // верховенство — главного не трогаем
            var banned = p.banned
            var mutedUntil = p.mutedUntilMs
            var mutedReason = p.mutedReason
            var mutedEvidence = p.mutedEvidenceIds
            for (d in moderators) {
                val dp = d.participants.firstOrNull { it.userId == p.userId } ?: continue
                if (dp.banned) banned = true
                val dUntil = dp.mutedUntilMs
                if (dUntil != null && dUntil > (mutedUntil ?: 0L)) {
                    mutedUntil = dUntil
                    mutedReason = dp.mutedReason
                    mutedEvidence = dp.mutedEvidenceIds
                }
            }
            p.copy(banned = banned, mutedUntilMs = mutedUntil, mutedReason = mutedReason, mutedEvidenceIds = mutedEvidence)
        }

        // Закрепления: объединение (сперва пины главного, затем добавленные PIN-делегатами),
        // без дублей, с сохранением порядка. Открепить чужой пин нельзя — только свой (каждый
        // публикует свои вклады в своём слоте), это ожидаемо для совместных закреплений.
        val mergedPinned = LinkedHashSet<String>(primary.pinned)
        pinners.forEach { mergedPinned.addAll(it.pinned) }

        return primary.copy(participants = mergedParticipants, pinned = mergedPinned.toList())
    }

    /**
     * Стабильная подпись СЛИТОГО состояния членства — по ней applyIncoming понимает,
     * изменилось ли что-то (мут/бан делегата не двигает числовую версию главного), и
     * дедуплицирует уведомления. Детерминированно сортирует по userId.
     */
    fun mergeStateSignature(f: MembersFile): String =
        "v${f.version}|" + f.participants.sortedBy { it.userId }.joinToString(";") {
            "${it.userId}:${if (it.banned) 1 else 0}:${it.mutedUntilMs ?: 0L}:${it.permissions}"
        } + "|pin:" + f.pinned.joinToString(",")

    /**
     * Расшифровывает УЖЕ проверенный по подписи members.txt (см. класс-докстринг) и
     * применяет к локальному кэшу [ChatParticipantDao] с анти-откатом по версии
     * (Chat.membersVersion). Возвращает true, если применилась новая версия.
     *
     * Сохраняет исходный joinedAtMs у уже известных участников — апдейт списка не
     * должен «обнулять» дату присоединения тех, кто и так уже был в кэше.
     *
     * Мультиподпись (Этап 2): если переданы [memberSlots] + [pubkeyForUserId] + [appContext],
     * поверх ростера ГЛАВНОГО (membersContentEncrypted) сливаются мут/бан уполномоченных
     * делегатов (см. [mergeSlots]). Без этих параметров поведение БАЙТ-В-БАЙТ прежнее —
     * одиночная подпись главного, анти-откат по числовой версии.
     */
    suspend fun applyIncoming(
        chat: Chat,
        membersContentEncrypted: String,
        password: String,
        participantDao: ChatParticipantDao,
        chatDao: ChatDao,
        /**
         * Для системного чата «Уведомления» (SystemNotifications): при передаче ОБОИХ
         * параметров изменение МОЕГО статуса (мут/снятие/бан), реально применённое этим
         * вызовом, пишется уведомлением. Здесь — потому что это единственная точка входа
         * всех путей применения (тик чата/список/фон), а анти-откат по версии даёт
         * «ровно один раз на устройство» бесплатно. null (напр., JoinChatActivity) —
         * прежнее поведение без уведомлений.
         */
        myUserId: String? = null,
        appContext: android.content.Context? = null,
        /**
         * Журнал приходов/уходов группы (раздел «Беседа» статистики). При передаче —
         * дифф старого/нового списка участников пишется как join/leave (см. GroupEventEntry).
         * null (напр., JoinChatActivity) — не журналируем.
         */
        groupEventDao: GroupEventDao? = null,
        /**
         * Мультиподпись (Этап 2): все проверенные по подписи слоты members.txt
         * (см. AllChannelData.memberSlots). Пусто — одиночная подпись главного (как раньше).
         */
        memberSlots: List<MemberSlot> = emptyList(),
        /** Деривация pubkey участника по userId (transport.pubkeyForUserId) — для сопоставления
         *  подписанта слота с участником ростера. null — слияние выключено. */
        pubkeyForUserId: ((String) -> String)? = null
    ): Boolean {
        if (membersContentEncrypted.isBlank()) return false
        val decrypted = CryptoHelper.decrypt(membersContentEncrypted, password, chat.chatId) ?: return false
        val primaryParsed = parse(decrypted) ?: return false
        // ⚠️ Защита (найдено при аудите): валидная по подписи, но ВЫРОЖДЕННАЯ версия
        // members.txt с пустым participants НЕ должна применяться. У группы всегда есть
        // минимум админ — пустой список означает баг публикующей стороны (например, гонка,
        // где admin-клиент опубликовал новую версию до того, как сам прочитал свою же
        // ChatParticipantDao), а НЕ легитимное "все вышли". Раньше здесь не было проверки:
        // ChatParticipantDao.pruneRemoved(ownerId, keepUserIds=emptyList()) в SQL транслируется
        // в "userId NOT IN ()" — это истинно для ЛЮБОЙ строки, т.е. пустой participants
        // стёр бы ВСЕХ локальных участников у КАЖДОГО клиента, который применил эту версию.
        if (primaryParsed.participants.isEmpty()) return false

        // ── Gate применения + возможное слияние мультиподписи ──────────────────────
        // Без слотов/деривации/контекста — прежний путь: одиночная подпись главного,
        // анти-откат строго по числовой версии (поведение байт-в-байт как раньше).
        val mergingActive = memberSlots.isNotEmpty() && pubkeyForUserId != null && appContext != null
        val parsed: MembersFile
        val mergeToken: String?
        if (!mergingActive) {
            if (primaryParsed.version <= chat.membersVersion) return false // анти-откат
            parsed = primaryParsed
            mergeToken = null
        } else {
            val deriveKey = pubkeyForUserId!! // mergingActive гарантирует non-null
            val ctx = appContext!!
            // Ростер главного — источник истины. Реальный откат версии главного (атака/сбой) отвергаем.
            if (primaryParsed.version < chat.membersVersion) return false
            val primaryAdmin = primaryParsed.adminUserId
            val primaryPubkey = deriveKey(primaryAdmin).lowercase()
            // Права делегатов по pubkey: уполномоченные — с MODERATE (мут/бан) ИЛИ PIN (пины).
            val permByPubkey = primaryParsed.participants
                .filter { it.userId != primaryAdmin && (AdminPermissions.has(it.permissions, AdminPermissions.MODERATE) || AdminPermissions.has(it.permissions, AdminPermissions.PIN)) }
                .associate { deriveKey(it.userId).lowercase() to it.permissions }
            val contributions = memberSlots
                .filter { it.signerPubkey.lowercase() != primaryPubkey }
                .mapNotNull { slot ->
                    val perm = permByPubkey[slot.signerPubkey.lowercase()] ?: return@mapNotNull null
                    CryptoHelper.decrypt(slot.content, password, chat.chatId)?.let { parse(it) }
                        ?.let { Contribution(perm, it) }
                }
            val merged = mergeSlots(primaryParsed, contributions)
            val sig = mergeStateSignature(merged)
            val primaryNewer = primaryParsed.version > chat.membersVersion
            // Применяем, если сменился ростер главного ИЛИ изменилось слитое состояние
            // (мут/бан делегата не двигает версию главного). Идемпотентно: одинаковая
            // подпись — ничего не делаем (нет лишних ребайндов, §14).
            if (!primaryNewer && sig == Prefs(ctx).getMembersMergeSig(chat.chatId)) return false
            parsed = merged
            mergeToken = sig
        }

        val existing = participantDao.getForChat(chat.id).associateBy { it.userId }
        val now = System.currentTimeMillis()
        participantDao.upsertAll(
            parsed.participants.map { e ->
                ChatParticipant(
                    ownerId = chat.id,
                    userId = e.userId,
                    banned = e.banned,
                    joinedAtMs = existing[e.userId]?.joinedAtMs ?: now,
                    mutedUntilMs = e.mutedUntilMs,
                    mutedReason = e.mutedReason,
                    mutedEvidenceIds = evidenceIdsToStore(e.mutedEvidenceIds),
                    permissions = e.permissions
                )
            }
        )
        participantDao.pruneRemoved(chat.id, parsed.participants.map { it.userId })
        chatDao.updateMembersVersionIfNewer(chat.id, parsed.version)
        // Запоминаем подпись применённого слитого состояния — gate следующего тика
        // (мультиподпись: делегатский мут/бан не двигает версию главного, см. выше).
        if (mergeToken != null && appContext != null) Prefs(appContext).setMembersMergeSig(chat.chatId, mergeToken)

        // ── Журнал приходов/уходов (раздел «Беседа», см. GroupEventEntry) ──
        // Дифф старого (existing) и нового списков. Первый прогон засеивает приходы уже
        // известных участников по joinedAtMs, чтобы отчёт стартовал «с создания беседы»,
        // а не с нуля. Прошлые уходы не восстановимы — копятся с этого момента.
        if (groupEventDao != null) runCatching {
            val newIds = parsed.participants.map { it.userId }.toSet()
            val oldIds = existing.keys
            if (groupEventDao.countForChat(chat.id) == 0 && existing.isNotEmpty()) {
                groupEventDao.insertAll(
                    existing.values.map {
                        GroupEventEntry(ownerId = chat.id, userId = it.userId,
                            type = GroupEventEntry.TYPE_JOIN, atMs = it.joinedAtMs)
                    }
                )
            }
            val joins = (newIds - oldIds).map {
                GroupEventEntry(ownerId = chat.id, userId = it, type = GroupEventEntry.TYPE_JOIN, atMs = now)
            }
            val leaves = (oldIds - newIds).map {
                GroupEventEntry(ownerId = chat.id, userId = it, type = GroupEventEntry.TYPE_LEAVE, atMs = now)
            }
            if (joins.isNotEmpty() || leaves.isNotEmpty()) groupEventDao.insertAll(joins + leaves)
        }
        // Имя/описание группы приходят и отсюда; null в parsed = "не менять".
        // ⚠️ Фикс (репорт: «админ поменял аву — у собеседника видно, у меня нет»).
        // members.txt С beta321 БОЛЬШЕ НЕ НЕСЁТ АВУ (parsed.groupAvatarBase64 обычно null,
        // ава едет только в groupprofile.txt). Раньше здесь стояло
        // `parsed.groupAvatarBase64 ?: chat.groupAvatarBase64`, где chat — УСТАРЕВШИЙ
        // снимок от вызывающего: у админа он держал СТАРУЮ аву и затирал ей только что
        // сохранённую новую (а свой groupprofile.txt с новой авой отбрасывался
        // анти-откатом по ts). Коалесцируем против СВЕЖЕГО значения из БД, а не из
        // снимка, — Room-ава больше не откатывается. Обратная совместимость: если старый
        // админ всё же прислал аву в members.txt (parsed != null) — применяем её.
        if (parsed.groupName != null || parsed.groupAvatarBase64 != null || parsed.groupDescription != null) {
            val freshChat = chatDao.getById(chat.id)
            chatDao.updateGroupProfile(
                id = chat.id,
                name = parsed.groupName ?: freshChat?.groupName ?: chat.groupName,
                avatar = parsed.groupAvatarBase64 ?: freshChat?.groupAvatarBase64 ?: chat.groupAvatarBase64,
                description = parsed.groupDescription ?: freshChat?.groupDescription ?: chat.groupDescription
            )
        }
        // Закреплённые (Этап 3): показываемый набор = слитые пины (главный + PIN-делегаты),
        // объединённые с МОИМИ вкладами. Юнион с myPinnedMsgIds не даёт только что
        // закреплённому мной сообщению «мигнуть» и пропасть, пока мой слот не дошёл до реле
        // (тот же класс гонки, что и с делегатским мутом в Этапе 2). Открепил сам — из myPinned
        // ушло, юнион не вернёт; чужой пин остаётся, т.к. он в слитом parsed.pinned.
        val freshForPins = chatDao.getById(chat.id)
        val myPins = freshForPins?.myPinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val displayPins = LinkedHashSet(parsed.pinned).apply { addAll(myPins) }
        val newPinnedCsv = displayPins.joinToString(",")
        // Пишем ТОЛЬКО при реальном изменении — иначе безусловная запись на каждый apply
        // дёргает Flow таблицы чатов (лишние ререндеры списка = тормоза).
        if (newPinnedCsv != (freshForPins?.pinnedMsgIds ?: "")) {
            chatDao.updatePinnedMsgIds(chat.id, newPinnedCsv)
        }
        // ── Уведомление об изменении МОЕГО статуса (см. параметры myUserId/appContext) ──
        // Три гарантии (аудит «точность/скорость/НЕ заедать»):
        //  • ТОЧНОСТЬ: тип события определяется по old→new; при существовавшей старой
        //    записи (свежий вход/переустановка не спамит про давно активный мут).
        //  • БЕЗ ДУБЛЕЙ: applyIncoming зовётся из чата/списка/фона одновременно —
        //    claimMembersVersion атомарно отдаёт версию ровно ОДНОМУ гонщику.
        //  • НЕ ЗАЕДАЕТ: сама запись (mutex + диск) уходит в AppScope — применение
        //    members.txt (критический путь мута/бана) её НЕ ждёт и возвращается сразу.
        if (myUserId != null && appContext != null) {
            val old = existing[myUserId]
            val new = parsed.participants.firstOrNull { it.userId == myUserId }
            if (old != null && new != null) {
                val groupName = parsed.groupName
                    ?: chat.groupName?.takeIf { it.isNotBlank() }
                    ?: chat.partnerName
                val wasMuted = old.mutedUntilMs != null && old.mutedUntilMs > now
                val isMuted = new.mutedUntilMs != null && new.mutedUntilMs > now
                val action: (suspend () -> Unit)? = when {
                    !old.banned && new.banned ->
                        { -> SystemNotifications.notifyBanned(appContext, groupName) }
                    // Разбан наблюдаем, т.к. бан больше не удаляет чат (участник остаётся
                    // в кэше с banned=true, и переход true→false ловится этим же путём).
                    old.banned && !new.banned ->
                        { -> SystemNotifications.notifyUnbanned(appContext, groupName) }
                    isMuted && (!wasMuted || old.mutedUntilMs != new.mutedUntilMs) ->
                        { -> SystemNotifications.notifyMuted(appContext, groupName, new.mutedUntilMs!!, new.mutedReason) }
                    wasMuted && !isMuted && !new.banned ->
                        { -> SystemNotifications.notifyUnmuted(appContext, groupName) }
                    // Права админа изменились (назначение/снятие) — отдельное действие
                    // админа = отдельная версия members.txt, поэтому с мут/бан не пересекается.
                    old.permissions != new.permissions && new.permissions != 0 -> {
                        val perms = new.permissions
                        { -> SystemNotifications.notifyRoleGranted(appContext, groupName, perms) }
                    }
                    old.permissions != 0 && new.permissions == 0 && !new.banned ->
                        { -> SystemNotifications.notifyRoleRevoked(appContext, groupName) }
                    else -> null
                }
                // Трекинг МОЕГО мута для уведомления об ИСТЕЧЕНИИ срока (нет события
                // members.txt при истечении — см. SystemNotifications.checkMuteExpiry):
                //   • стал заглушён / срок изменился → запоминаем until;
                //   • сняли (досрочно) или забанили → сбрасываем, чтобы не было двойного.
                if (isMuted && new.mutedUntilMs != null) {
                    SystemNotifications.rememberMyMute(appContext, chat.chatId, new.mutedUntilMs!!)
                } else if ((wasMuted && !isMuted) || new.banned) {
                    SystemNotifications.clearMyMute(appContext, chat.chatId)
                }
                // Claim только когда есть РЕАЛЬНОЕ событие — «мой статус не менялся» токен/версию
                // не расходует. Выигравший гонку пишет в фоне, не блокируя этот вызов.
                // Мультиподпись: дедуп по СТРОКОВОМУ токену слитого состояния (мут/бан делегата
                // не двигает числовую версию главного); иначе — по числовой версии как раньше.
                val claimed = if (mergeToken != null)
                    SystemNotifications.claimMembersToken(appContext, chat.chatId, mergeToken)
                else
                    SystemNotifications.claimMembersVersion(appContext, chat.chatId, parsed.version)
                if (action != null && claimed) {
                    AppScope.launch { runCatching { action() } }
                }
            }
        }
        return true
    }

    /**
     * Публикует новую версию members.txt. Вызывать ТОЛЬКО от лица администратора
     * (myUserId транспорта должен быть равен adminUserId) — иначе событие подпишется
     * не тем ключом и все остальные клиенты его проигнорируют (см. класс-докстринг).
     *
     * @param groupName/[groupAvatarBase64]/[groupDescription] передавать ТЕКУЩЕЕ значение
     *   (даже если не менялось) — иначе следующий applyIncoming ничего не тронет
     *   (null = "не менять"), так что случайной потери имени/аватара/описания при
     *   простом добавлении участника нет, но явно передавать их надо самому вызывающему
     *   коду (см. ChatActivity/PartnerProfileActivity).
     *
     * ⚠️ Фикс (репорт: жёлтая плашка мута появляется у заглушённого через 10–25с).
     * Раньше здесь стоял generic CryptoHelper.encrypt() — для группового чата (у групп
     * НИКОГДА нет forward-secrecy сессионного ключа) он всегда падает на V5: Argon2id
     * (64 МиБ) со случайной солью НА КАЖДЫЙ вызов, некэшируемо. Это ровно та проблема,
     * что уже описана и решена в CryptoHelper.encryptMetadata() для profiles.txt —
     * V4 с детерминированной солью от chatId, ключ Argon2 считается один раз и
     * переиспользуется (getOrDeriveArgon2KeyV4), дальше "почти бесплатно". members.txt
     * ошибочно не был переведён на этот путь при добавлении мута/бана — теперь тоже
     * V4, тот же тёплый кэш, что и у profiles.txt/текста сообщений группы (encryptGroupMessage),
     * скорее всего уже прогретый к моменту действия админа. decrypt() на приёме менять
     * не нужно — формат определяется по префиксу автоматически (см. MembersSync.applyIncoming).
     */
    suspend fun publish(
        transport: ChatTransport,
        password: String,
        chatId: String,
        adminUserId: String,
        newVersion: Int,
        participants: List<Entry>,
        groupName: String? = null,
        groupAvatarBase64: String? = null,
        groupDescription: String? = null,
        pinned: List<String> = emptyList()
    ) {
        // ⚠️ АВА В members.txt БОЛЬШЕ НЕ ПУБЛИКУЕТСЯ (требование пользователя: «мгновенно
        // банить и мутить, максимальная задержка 2 секунды»). История вопроса: тяжёлая
        // ава (~25-40К base64) сначала вовсе убивала синк членства (раздувала событие за
        // порог чанкования NOSTR_CHUNK_CHARS — приёмники получали нечитаемый манифест
        // "CHUNKED:N"), а после бюджета-пережатия всё равно делала КАЖДЫЙ мут/бан
        // публикацией на десятки килобайт через Tor (секунды). Теперь members.txt несёт
        // только членство + имя/описание (~1-2КБ) — публикация и доставка укладываются
        // в тик опроса. Ава группы едет ИСКЛЮЧИТЕЛЬНО отдельным «профилем беседы»
        // (groupprofile.txt, GroupProfileSync) — маленьким стабильным событием, которое
        // публикуется только при реальной смене авы. Параметр [groupAvatarBase64]
        // сохранён в сигнатуре, но игнорируется: чтение (applyIncoming) не тронуто —
        // старые копии members.txt с авой внутри по-прежнему применяются (null = «не
        // менять» защищает от затирания локальной авы новыми slim-копиями).
        val content = buildContent(newVersion, adminUserId, participants, groupName, null, groupDescription, pinned)
        transport.saveFile("members.txt", CryptoHelper.encryptMetadata(content, password, chatId))
    }

    /**
     * Максимальный размер ЗАШИФРОВАННОГО метаданного события. Ниже NOSTR_CHUNK_CHARS
     * (48 000, порог чанкования в NostrTransport.saveFile) с запасом — чанкованный
     * members.txt/groupprofile.txt нечитаем для приёмников (см. publish).
     */
    const val METADATA_EVENT_MAX_CHARS = 45_000
}
