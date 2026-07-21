package com.atrum.chat

/**
 * Подпись и проверка АВТОРСТВА сообщений (ADR_MESSAGE_AUTHENTICITY.md, Фаза 2).
 *
 * Идея: каждое сообщение автор подписывает своим ЛИЧНЫМ identity-ключом (Ed25519, не
 * выводимым из общего пароля), поэтому инсайдер с паролем не может подделать авторство.
 * Подписи живут в отдельном файле `sigs.txt` (см. ChatTransport.load/saveSignatures) как
 * строки `msgId|identityPubKeyB64|sigB64`. Строка самого сообщения (chat.txt) не меняется —
 * старые клиенты файл подписей не читают, доставка не затрагивается (§1).
 *
 * Канон подписи — сам зашифрованный текст строки (`rawEncrypted`): он байт-идентичен у
 * автора и у получателя (одна и та же строка на реле), а поддельная строка от инсайдера
 * будет иметь ДРУГОЙ `rawEncrypted` и не пройдёт проверку по закреплённому ключу автора.
 *
 * Всё здесь — чистые функции без сети/побочных эффектов; сетевые чтение/запись и
 * шифрование blob'а делает вызывающий (ChatActivity), строго best-effort.
 */
object MessageAuthSync {

    /** Разбор `sigs.txt`: msgId → (identityPubKeyB64, sigB64). Битые строки пропускаются. */
    fun parse(content: String): MutableMap<String, Pair<String, String>> {
        val map = HashMap<String, Pair<String, String>>()
        for (line in content.split("\n")) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split("|")
            if (parts.size >= 3 && parts[0].isNotBlank() && parts[1].isNotBlank() && parts[2].isNotBlank()) {
                map[parts[0]] = parts[1] to parts[2]
            }
        }
        return map
    }

    /** Сериализация карты подписей обратно в текст `sigs.txt`. */
    fun serialize(map: Map<String, Pair<String, String>>): String =
        map.entries.joinToString("\n") { "${it.key}|${it.value.first}|${it.value.second}" }

    /**
     * Считает состояние подлинности для каждого сообщения:
     *  - UNSIGNED — подписи нет (старое/переходное);
     *  - VERIFIED — подпись валидна И ключ подписанта совпал с закреплённым за userId (TOFU);
     *  - FORGED   — подпись невалидна, ЛИБО ключ не совпал с закреплённым (подмена/имперсонация).
     *
     * @param pinnedByUserId закреплённые identity-ключи участников (ChatParticipant.pinnedIdentityPubKey).
     */
    fun computeAuthStates(
        messages: List<Message>,
        sigs: Map<String, Pair<String, String>>,
        pinnedByUserId: Map<String, String>,
        chatId: String
    ): Map<String, MsgAuth> {
        val out = HashMap<String, MsgAuth>()
        for (m in messages) {
            if (m.rawEncrypted.isBlank()) continue
            val uid = m.senderUserId?.takeIf { it.isNotBlank() } ?: continue
            val entry = sigs[m.msgId]
            if (entry == null) {
                out[m.msgId] = MsgAuth.UNSIGNED
                continue
            }
            val (idPub, sig) = entry
            val sigOk = CryptoHelper.verifyMessage(idPub, chatId, uid, m.timestampMs, m.rawEncrypted, sig)
            val pinned = pinnedByUserId[uid]?.takeIf { it.isNotBlank() }
            out[m.msgId] = when {
                !sigOk -> MsgAuth.FORGED
                pinned != null && pinned != idPub -> MsgAuth.FORGED // ключ не совпал с закреплённым
                else -> MsgAuth.VERIFIED
            }
        }
        return out
    }

    /**
     * Дописывает подписи к МОИМ сообщениям, у которых их ещё нет. Возвращает обновлённую
     * карту, если добавили хотя бы одну подпись; иначе null (писать нечего — сети не трогаем).
     * Чужие подписи в [existing] сохраняются как есть (merge).
     */
    fun buildOwnSignatures(
        messages: List<Message>,
        myUserId: String,
        myIdPub: String,
        myIdPriv: ByteArray,
        chatId: String,
        existing: MutableMap<String, Pair<String, String>>
    ): Map<String, Pair<String, String>>? {
        if (myUserId.isBlank() || myIdPub.isBlank()) return null
        var changed = false
        for (m in messages) {
            if (m.rawEncrypted.isBlank()) continue
            if (m.senderUserId != myUserId) continue
            if (existing.containsKey(m.msgId)) continue
            val sig = CryptoHelper.signMessage(myIdPriv, chatId, myUserId, m.timestampMs, m.rawEncrypted) ?: continue
            existing[m.msgId] = myIdPub to sig
            changed = true
        }
        return if (changed) existing else null
    }
}
