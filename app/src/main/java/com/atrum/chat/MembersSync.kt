package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatDao
import com.atrum.chat.data.ChatParticipant
import com.atrum.chat.data.ChatParticipantDao
import com.atrum.chat.transport.ChatTransport
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

    data class Entry(val userId: String, val banned: Boolean)

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
        val groupDescription: String? = null
    )

    fun parse(decryptedJson: String): MembersFile? = try {
        val j = JSONObject(decryptedJson)
        val arr = j.getJSONArray("participants")
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val userId = o.optString("userId").takeIf { it.isNotBlank() } ?: continue
            list.add(Entry(userId, o.optBoolean("banned", false)))
        }
        MembersFile(
            version = j.getInt("v"),
            adminUserId = j.getString("adminUserId"),
            participants = list,
            ts = j.optLong("ts", System.currentTimeMillis()),
            groupName = j.optString("groupName", "").takeIf { it.isNotBlank() },
            groupAvatarBase64 = j.optString("groupAvatarBase64", "").takeIf { it.isNotBlank() },
            groupDescription = j.optString("groupDescription", "").takeIf { it.isNotBlank() }
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
        groupDescription: String? = null
    ): String =
        JSONObject().apply {
            put("v", version)
            put("adminUserId", adminUserId)
            put("ts", System.currentTimeMillis())
            if (!groupName.isNullOrBlank()) put("groupName", groupName)
            if (!groupAvatarBase64.isNullOrBlank()) put("groupAvatarBase64", groupAvatarBase64)
            if (!groupDescription.isNullOrBlank()) put("groupDescription", groupDescription)
            put(
                "participants",
                JSONArray().also { arr ->
                    participants.forEach { p ->
                        arr.put(
                            JSONObject().apply {
                                put("userId", p.userId)
                                put("banned", p.banned)
                            }
                        )
                    }
                }
            )
        }.toString()

    /**
     * Расшифровывает УЖЕ проверенный по подписи members.txt (см. класс-докстринг) и
     * применяет к локальному кэшу [ChatParticipantDao] с анти-откатом по версии
     * (Chat.membersVersion). Возвращает true, если применилась новая версия.
     *
     * Сохраняет исходный joinedAtMs у уже известных участников — апдейт списка не
     * должен «обнулять» дату присоединения тех, кто и так уже был в кэше.
     */
    suspend fun applyIncoming(
        chat: Chat,
        membersContentEncrypted: String,
        password: String,
        participantDao: ChatParticipantDao,
        chatDao: ChatDao
    ): Boolean {
        if (membersContentEncrypted.isBlank()) return false
        val decrypted = CryptoHelper.decrypt(membersContentEncrypted, password, chat.chatId) ?: return false
        val parsed = parse(decrypted) ?: return false
        if (parsed.version <= chat.membersVersion) return false // анти-откат — версия не новее уже применённой
        // ⚠️ Защита (найдено при аудите): валидная по подписи, но ВЫРОЖДЕННАЯ версия
        // members.txt с пустым participants НЕ должна применяться. У группы всегда есть
        // минимум админ — пустой список означает баг публикующей стороны (например, гонка,
        // где admin-клиент опубликовал новую версию до того, как сам прочитал свою же
        // ChatParticipantDao), а НЕ легитимное "все вышли". Раньше здесь не было проверки:
        // ChatParticipantDao.pruneRemoved(ownerId, keepUserIds=emptyList()) в SQL транслируется
        // в "userId NOT IN ()" — это истинно для ЛЮБОЙ строки, т.е. пустой participants
        // стёр бы ВСЕХ локальных участников у КАЖДОГО клиента, который применил эту версию
        // (и цепочка могла повториться — следующий ban/rename/unban тоже читает уже
        // опустошённую таблицу и republish'ит снова пустой список). Версия анти-отката
        // (membersVersion) уже увеличена не будет — return false здесь НЕ считается
        // "применённой", следующая нормальная версия от админа применится как обычно.
        if (parsed.participants.isEmpty()) return false

        val existing = participantDao.getForChat(chat.id).associateBy { it.userId }
        val now = System.currentTimeMillis()
        participantDao.upsertAll(
            parsed.participants.map { e ->
                ChatParticipant(
                    ownerId = chat.id,
                    userId = e.userId,
                    banned = e.banned,
                    joinedAtMs = existing[e.userId]?.joinedAtMs ?: now
                )
            }
        )
        participantDao.pruneRemoved(chat.id, parsed.participants.map { it.userId })
        chatDao.updateMembersVersionIfNewer(chat.id, parsed.version)
        // Имя/аватар/описание группы — тоже авторитетно приходят отсюда (см. MembersFile).
        // null в parsed означает "не менять" (поле не пришло/пустое), а не "стереть".
        if (parsed.groupName != null || parsed.groupAvatarBase64 != null || parsed.groupDescription != null) {
            chatDao.updateGroupProfile(
                id = chat.id,
                name = parsed.groupName ?: chat.groupName,
                avatar = parsed.groupAvatarBase64 ?: chat.groupAvatarBase64,
                description = parsed.groupDescription ?: chat.groupDescription
            )
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
        groupDescription: String? = null
    ) {
        val content = buildContent(newVersion, adminUserId, participants, groupName, groupAvatarBase64, groupDescription)
        val encrypted = CryptoHelper.encrypt(content, password, chatId)
        transport.saveFile("members.txt", encrypted)
    }
}
