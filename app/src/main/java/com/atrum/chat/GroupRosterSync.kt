package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatParticipant
import com.atrum.chat.data.ChatParticipantDao
import com.atrum.chat.transport.ProfileSlotSigned

/**
 * Децентрализованный ростер групповой беседы (ADR-001).
 *
 * ⭐ Идея (запрос пользователя): «зачисление в участники и счётчик НЕ должны зависеть
 * от присутствия админа в сети — беседа работает сама». Раньше единственным источником
 * членства был подписанный админом members.txt, который пишет ТОЛЬКО клиент админа
 * ([ChatActivity.maybeAdminEnrollNewMembers]). Пока админ офлайн, новые участники ни у
 * кого не зачислялись, счётчик был заморожен.
 *
 * Теперь членство/счётчик считаются из САМООПУБЛИКОВАННЫХ слотов profiles.txt: каждый
 * участник и так публикует свой профиль (имя/ава/presence). Наличие подписанного слота —
 * это и есть доказательство «я участник» в рамках модели Варианта A (граница доверия —
 * знание пароля из инвайта; см. ADR_GROUP_CHATS.md). members.txt остаётся ОВЕРЛЕЕМ
 * модерации (бан/мут/роли/лимит) и применяется отдельно [MembersSync.applyIncoming]
 * ДО этого вызова.
 *
 * Порядок на каждом тике синка: сначала MembersSync (оверлей + анти-откат по версии),
 * потом [applyProfileRoster] (наполнение из профилей). При офлайн-админе версия
 * members.txt не растёт → MembersSync выходит рано и НИЧЕГО не прунит, а этот метод
 * добавляет новых участников. Так связь с админом разорвана.
 */
object GroupRosterSync {

    /**
     * Наполняет [ChatParticipantDao] из подписанных слотов profiles.txt.
     *
     * Делает ровно три вещи, не затрагивая banned/muted/permissions (это зона members.txt):
     *  1. добавляет активных участников, которых ещё нет в локальном ростере;
     *  2. убирает тех, кто вышел сам (profiles.txt с left=true / deleted=true), если они
     *     НЕ забанены (забаненные остаются видимы админу — бан наблюдаем);
     *  3. никого больше не прунит (в отличие от members.txt-пути) — членство теперь
     *     самосуверенно.
     *
     * Привязка userId↔pubkey: слот profiles.txt подписан ключом самого участника
     * (privkey = f(пароль, userId), см. NostrTransport). Из слота берём ТОЛЬКО тот userId,
     * чей выведенный pubkey совпадает с pubkey подписавшего событие. Иначе один участник
     * мог бы «накрутить» счётчик чужими userId (в пределах и без того общей парольной
     * границы доверия — полная защита требует Варианта B, отложен).
     *
     * @return true если состав ростера реально изменился (Room Flow и так перерисует UI).
     */
    suspend fun applyProfileRoster(
        chat: Chat,
        signedSlots: List<ProfileSlotSigned>,
        password: String,
        participantDao: ChatParticipantDao,
        myUserId: String,
        adminUserId: String?,
        pubkeyForUserId: (String) -> String
    ): Boolean {
        if (!chat.isGroup) return false
        if (signedSlots.isEmpty()) return false

        val activeIds = HashSet<String>()   // опубликовали профиль и НЕ вышли
        val leftIds = HashSet<String>()     // опубликовали профиль с left/deleted

        for (slot in signedSlots) {
            val profiles = ProfileSync.parseProfiles(slot.content, password, chat.chatId)
            if (profiles.isEmpty()) continue
            for ((uid, p) in profiles) {
                if (uid.isBlank()) continue
                // Слот подписан ключом участника → принимаем только «свой» userId.
                if (!pubkeyForUserId(uid).equals(slot.signerPubkey, ignoreCase = true)) continue
                if (p.left || p.deleted) leftIds.add(uid) else activeIds.add(uid)
            }
        }

        // Я и админ — участники по определению (мой собственный слот мог ещё не вернуться
        // с реле; админ — якорь беседы). Себя не форсим, если я сам объявил выход.
        if (myUserId.isNotBlank() && !leftIds.contains(myUserId)) activeIds.add(myUserId)
        if (!adminUserId.isNullOrBlank()) activeIds.add(adminUserId)

        if (activeIds.isEmpty() && leftIds.isEmpty()) return false

        val current = participantDao.getForChat(chat.id).associateBy { it.userId }
        var changed = false

        // 1) Новые активные участники — добавляем БЕЗ затирания banned/muted/permissions.
        for (uid in activeIds) {
            if (current.containsKey(uid)) continue
            participantDao.upsert(ChatParticipant(ownerId = chat.id, userId = uid, banned = false))
            changed = true
        }

        // 2) Вышедшие сами — убираем, если не забанены и это не админ/не я.
        for (uid in leftIds) {
            if (uid == adminUserId || uid == myUserId) continue
            val existing = current[uid] ?: continue
            if (existing.banned) continue
            participantDao.removeIfNotBanned(chat.id, uid)
            changed = true
        }

        return changed
    }
}
