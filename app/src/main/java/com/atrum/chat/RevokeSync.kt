package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatDao
import com.atrum.chat.data.ChatParticipant
import com.atrum.chat.data.ChatParticipantDao
import org.json.JSONObject

/**
 * Отзыв/возврат создателя verified-root'ом (ОБРАТИМО). Обкатано в песочнице (revoke2.js, 10/10).
 *
 * Цепочка сертификатов [MembersSync.OwnerRevoke] лежит в отдельном файле `revoke.txt`
 * (ChatTransport.load/saveRevokes), зашифрованном доменом чата. Каждый сертификат подписан
 * приватным ключом root (обязан быть в захардкоженном [VerifiedBadge.VERIFIED]) и несёт флаг
 * revoked=true (отзыв) / false (возврат). Применяется последний по ts на каждого target
 * (анти-откат через [Prefs.getRevokeTs]).
 *
 * Отзыв: беседа перестаёт признавать ключ/права создателя — `adminUserId` очищается, права 0,
 * а его members.txt/offer'ы отвергаются (см. [MembersSync.applyIncoming], [Prefs.isUserRevoked]).
 * Возврат: создатель восстанавливается (adminUserId + перепин ключа + права ALL).
 *
 * Неподделываемо: без root-приватника чужой сертификат не пройдёт [MembersSync.isRevokeValid];
 * отозванный создатель сам себя вернуть не может. Best-effort — вызывающий оборачивает в runCatching.
 */
object RevokeSync {

    /** Разбор `revoke.txt` (по одному JSON-сертификату на строку). Битые строки пропускаются. */
    fun parse(decrypted: String): List<MembersSync.OwnerRevoke> {
        val out = ArrayList<MembersSync.OwnerRevoke>()
        for (line in decrypted.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            runCatching {
                val j = JSONObject(t)
                out.add(
                    MembersSync.OwnerRevoke(
                        chatId = j.getString("c"),
                        targetUserId = j.getString("tu"),
                        targetIdk = j.getString("ti"),
                        rootIdk = j.getString("ri"),
                        revoked = j.optInt("rv", 0) == 1,
                        ts = j.optLong("ts", 0L),
                        sig = j.optString("sig", "")
                    )
                )
            }
        }
        return out
    }

    /** Сериализация цепочки обратно в `revoke.txt`. */
    fun serialize(certs: List<MembersSync.OwnerRevoke>): String =
        certs.joinToString("\n") { r ->
            JSONObject().apply {
                put("c", r.chatId); put("tu", r.targetUserId); put("ti", r.targetIdk)
                put("ri", r.rootIdk); put("rv", if (r.revoked) 1 else 0); put("ts", r.ts); put("sig", r.sig)
            }.toString()
        }

    /**
     * Upsert по target: на каждого создателя держим только ПОСЛЕДНИЙ по ts сертификат (файл
     * маленький, история не нужна — состояние определяется последним). Более старый не затирает.
     */
    fun appendCert(existingDecrypted: String, cert: MembersSync.OwnerRevoke): String {
        val chain = parse(existingDecrypted).toMutableList()
        val idx = chain.indexOfFirst { it.targetUserId == cert.targetUserId }
        if (idx >= 0) {
            if (cert.ts >= chain[idx].ts) chain[idx] = cert
        } else chain.add(cert)
        return serialize(chain)
    }

    /**
     * Применяет валидные сертификаты (последний по ts на target) к локальному состоянию.
     * Возвращает true, если что-то реально изменилось (нужен recreate для пересоздания транспорта).
     */
    suspend fun applyRevokes(
        chat: Chat,
        decrypted: String,
        chatDao: ChatDao,
        participantDao: ChatParticipantDao,
        prefs: Prefs
    ): Boolean {
        if (!chat.isGroup) return false
        val certs = parse(decrypted).filter { MembersSync.isRevokeValid(it, chat.chatId) }
        if (certs.isEmpty()) return false
        // На каждого target — сертификат с максимальным ts.
        val latestPerTarget = certs.groupBy { it.targetUserId }
            .mapValues { (_, list) -> list.maxByOrNull { it.ts }!! }
        var changed = false
        for ((target, r) in latestPerTarget) {
            if (r.ts <= prefs.getRevokeTs(chat.chatId, target)) continue // анти-откат
            prefs.setRevokeEntry(chat.chatId, target, r.revoked, r.ts, r.targetIdk)
            if (r.revoked) {
                participantDao.setPermissions(chat.id, target, 0)
                // adminUserId меняем (и просим recreate) ТОЛЬКО если он реально был этим создателем.
                // Иначе холостой recreate на первом открытии мог гасить оптимистичное сообщение (§1.5).
                if (chat.adminUserId == target) { chatDao.updateAdminUserId(chat.id, ""); changed = true }
            } else {
                // Возврат: восстановить создателя (строка участника + ключ + права + владелец).
                val existing = participantDao.getOne(chat.id, target)
                if (existing == null) {
                    participantDao.upsert(
                        ChatParticipant(
                            ownerId = chat.id, userId = target, banned = false,
                            permissions = AdminPermissions.ALL, pinnedIdentityPubKey = r.targetIdk
                        )
                    )
                } else {
                    participantDao.setPinnedIdentity(chat.id, target, r.targetIdk)
                    participantDao.setPermissions(chat.id, target, AdminPermissions.ALL)
                }
                if (chat.adminUserId != target) { chatDao.updateAdminUserId(chat.id, target); changed = true }
            }
        }
        return changed
    }
}
