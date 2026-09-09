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
                        sig = j.optString("sig", ""),
                        // Аддитивно (§17): у сертификатов, выпущенных до появления поля, его нет,
                        // и пустая строка означает прежнее поведение — владельца не остаётся.
                        newAdminUserId = j.optString("na", "")
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
                if (r.newAdminUserId.isNotBlank()) put("na", r.newAdminUserId)
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
     * Что именно применилось. Раньше функция возвращала голый Boolean «что-то изменилось», и
     * вызывающему приходилось ДОГАДЫВАТЬСЯ о направлении по побочному признаку — пуст ли теперь
     * adminUserId. Догадка сломалась в тот же день, когда отзыв перестал обнулять владельца
     * (репорт: «отзываю права, а с другой стороны выходит восстановление»). Направление знает
     * только сам сертификат — пусть он его и сообщает.
     */
    data class Applied(
        /** Изменилось ли владение беседой (нужен пересбор транспорта). */
        val ownerChanged: Boolean,
        /** true — последний применённый сертификат ОТЗЫВАЛ права; false — возвращал. */
        val revoked: Boolean
    )

    /**
     * Применяет валидные сертификаты (последний по ts на target) к локальному состоянию.
     * Возвращает [Applied]: изменилось ли владение (тогда вызывающему нужен пересбор транспорта)
     * и в какую сторону сработал последний сертификат.
     */
    suspend fun applyRevokes(
        chat: Chat,
        decrypted: String,
        chatDao: ChatDao,
        participantDao: ChatParticipantDao,
        prefs: Prefs
    ): Applied {
        val nothing = Applied(ownerChanged = false, revoked = false)
        if (!chat.isGroup) return nothing
        val certs = parse(decrypted).filter { MembersSync.isRevokeValid(it, chat.chatId) }
        if (certs.isEmpty()) return nothing
        // На каждого target — сертификат с максимальным ts.
        val latestPerTarget = certs.groupBy { it.targetUserId }
            .mapValues { (_, list) -> list.maxByOrNull { it.ts }!! }
        var changed = false
        // Направление берём у сертификата, который применился последним по времени выпуска, —
        // именно его человек и увидит как «что со мной произошло».
        var lastAppliedRevoked = false
        var lastAppliedTs = Long.MIN_VALUE
        for ((target, r) in latestPerTarget) {
            if (r.ts <= prefs.getRevokeTs(chat.chatId, target)) continue // анти-откат
            prefs.setRevokeEntry(chat.chatId, target, r.revoked, r.ts, r.targetIdk)
            if (r.ts > lastAppliedTs) { lastAppliedTs = r.ts; lastAppliedRevoked = r.revoked }
            if (r.revoked) {
                participantDao.setPermissions(chat.id, target, 0)
                // ⚠️ ВЛАДЕНИЕ ПЕРЕХОДИТ, А НЕ ИСЧЕЗАЕТ (репорт: «бан и мут не работают на прошлых
                // создателей»). Раньше здесь всегда стояла пустая строка — беседа оставалась без
                // владельца, а members.txt (в нём живут баны и муты) участники принимают только
                // от владельца. То есть отозвать создателя было можно, а наказать его после
                // этого — уже никому. Теперь сертификат сам называет преемника, и все клиенты
                // приходят к одному ответу: сертификат подписан корневым ключом и проверяется
                // у каждого (isRevokeValid), подделать преемника нельзя.
                //
                // Пусто (старый сертификат) — прежнее поведение, беседа без владельца.
                if (chat.adminUserId == target) {
                    chatDao.updateAdminUserId(chat.id, r.newAdminUserId)
                    changed = true
                }
                // Преемнику сразу выдаём полный набор прав и закрепляем его корневой ключ —
                // иначе он формально владелец, но его же собственный клиент не считает его
                // модератором (groupCanModerate) и не примет его ростер (пиннинг ключа).
                if (r.newAdminUserId.isNotBlank() && r.newAdminUserId != target) {
                    val heir = participantDao.getOne(chat.id, r.newAdminUserId)
                    if (heir == null) {
                        participantDao.upsert(
                            ChatParticipant(
                                ownerId = chat.id, userId = r.newAdminUserId, banned = false,
                                permissions = AdminPermissions.ALL, pinnedIdentityPubKey = r.rootIdk
                            )
                        )
                    } else {
                        participantDao.setPinnedIdentity(chat.id, r.newAdminUserId, r.rootIdk)
                        participantDao.setPermissions(chat.id, r.newAdminUserId, AdminPermissions.ALL)
                    }
                }
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
        return Applied(ownerChanged = changed, revoked = lastAppliedRevoked)
    }
}
