package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatDao
import com.atrum.chat.data.ChatParticipant
import com.atrum.chat.data.ChatParticipantDao
import org.json.JSONObject

/**
 * Передача владения беседой (ADR_MESSAGE_AUTHENTICITY.md §10). Обкатано в песочнице (own.js, 7/7).
 *
 * Цепочка сертификатов [MembersSync.OwnerTransfer] лежит в отдельном файле `owner.txt`
 * (ChatTransport.load/saveOwnerCerts), зашифрованном доменом чата. Каждый сертификат подписан
 * приватным identity-ключом ТЕКУЩЕГО владельца и указывает нового. Приёмник идёт по цепочке от
 * закреплённого владельца и применяет переходы: меняет `adminUserId`, перепинивает identity-ключ
 * владельца, понижает роль прежнего (создатель → админ либо → участник по флагу keepOldAsAdmin).
 *
 * Неподделываемо: чужой сертификат без приватного ключа владельца не пройдёт проверку. Строго
 * best-effort у вызывающего — при любой ошибке владелец не меняется.
 */
object OwnerSync {

    /** Разбор `owner.txt` (по одному JSON-сертификату на строку). Битые строки пропускаются. */
    fun parseCerts(decrypted: String): List<MembersSync.OwnerTransfer> {
        val out = ArrayList<MembersSync.OwnerTransfer>()
        for (line in decrypted.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            runCatching {
                val j = JSONObject(t)
                out.add(
                    MembersSync.OwnerTransfer(
                        chatId = j.getString("c"),
                        fromUserId = j.getString("fu"),
                        fromIdk = j.getString("fi"),
                        toUserId = j.getString("tu"),
                        toIdk = j.getString("ti"),
                        keepOldAsAdmin = j.optInt("k", 0) == 1,
                        ts = j.optLong("ts", 0L),
                        sig = j.optString("sig", ""),
                        acceptSig = j.optString("as", "")
                    )
                )
            }
        }
        return out
    }

    /** Сериализация цепочки обратно в `owner.txt`. */
    fun serializeCerts(certs: List<MembersSync.OwnerTransfer>): String =
        certs.joinToString("\n") { c ->
            JSONObject().apply {
                put("c", c.chatId); put("fu", c.fromUserId); put("fi", c.fromIdk)
                put("tu", c.toUserId); put("ti", c.toIdk)
                put("k", if (c.keepOldAsAdmin) 1 else 0); put("ts", c.ts)
                put("sig", c.sig); put("as", c.acceptSig)
            }.toString()
        }

    /**
     * Upsert сертификата по подписи ОФФЕРА: если оффер с той же [sig] уже есть — ЗАМЕНЯЕТ его
     * (так согласие получателя дополняет оффер полем acceptSig, а не плодит дубль); иначе добавляет.
     */
    fun appendCert(existingDecrypted: String, cert: MembersSync.OwnerTransfer): String {
        val chain = parseCerts(existingDecrypted).toMutableList()
        val idx = chain.indexOfFirst { it.sig == cert.sig && cert.sig.isNotBlank() }
        if (idx >= 0) chain[idx] = cert else chain.add(cert)
        return serializeCerts(chain)
    }

    /**
     * Ищет НЕПРИНЯТЫЙ оффер лично мне (для показа полноэкранного окна). Возвращает оффер или null.
     * pinnedAdminIdk — закреплённый ключ текущего владельца.
     */
    fun findPendingOfferForMe(
        chat: Chat, decryptedOwnerContent: String, myUserId: String, myIdentityPubKey: String, pinnedAdminIdk: String?
    ): MembersSync.OwnerTransfer? {
        if (!chat.isGroup) return null
        return parseCerts(decryptedOwnerContent).firstOrNull { c ->
            MembersSync.isPendingOfferForMe(c, chat.chatId, pinnedAdminIdk, chat.adminUserId, myUserId, myIdentityPubKey)
        }
    }

    /**
     * Идёт по цепочке от ТЕКУЩЕГО владельца и применяет валидные переходы. Возвращает true, если
     * владелец реально сменился. Идемпотентно: уже применённые переходы (fromUserId != текущий)
     * пропускаются. Best-effort — вызывающий оборачивает в runCatching.
     */
    suspend fun applyOwnerChain(
        chat: Chat,
        decryptedOwnerContent: String,
        chatDao: ChatDao,
        participantDao: ChatParticipantDao
    ): Boolean {
        if (!chat.isGroup) return false
        val certs = parseCerts(decryptedOwnerContent)
        if (certs.isEmpty()) return false

        var curOwner = chat.adminUserId ?: return false
        var curIdk = participantDao.getOne(chat.id, curOwner)?.pinnedIdentityPubKey
        var changed = false
        // Ограничиваем число шагов длиной цепочки — защита от зацикливания на битых данных.
        var guard = certs.size + 1
        while (guard-- > 0) {
            val next = certs.firstOrNull { c ->
                c.fromUserId == curOwner &&
                    MembersSync.isOwnerTransferValid(c, chat.chatId, curIdk, curOwner)
            } ?: break

            // Новый владелец: строка участника должна существовать и нести его ключ.
            val existing = participantDao.getOne(chat.id, next.toUserId)
            if (existing == null) {
                participantDao.upsert(
                    ChatParticipant(
                        ownerId = chat.id, userId = next.toUserId, banned = false,
                        permissions = AdminPermissions.ALL, pinnedIdentityPubKey = next.toIdk
                    )
                )
            } else {
                participantDao.setPinnedIdentity(chat.id, next.toUserId, next.toIdk)
                participantDao.setPermissions(chat.id, next.toUserId, AdminPermissions.ALL)
            }
            // Прежний владелец: остаётся админом (ALL) либо падает до участника (0).
            participantDao.setPermissions(chat.id, next.fromUserId, if (next.keepOldAsAdmin) AdminPermissions.ALL else 0)
            // Корень доверия и владелец беседы — на нового.
            chatDao.updateAdminUserId(chat.id, next.toUserId)

            curOwner = next.toUserId
            curIdk = next.toIdk
            changed = true
        }
        return changed
    }
}
