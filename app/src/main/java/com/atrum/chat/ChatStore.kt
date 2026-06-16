package com.atrum.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ChatStore {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val pendingByRaw = LinkedHashMap<String, Message>()
    private val tombstones = mutableSetOf<String>()
    private var lastRemote: List<Message> = emptyList()

    fun addOptimistic(msg: Message) {
        require(msg.isPending) { "addOptimistic requires isPending=true" }
        require(msg.rawEncrypted.isNotBlank()) { "addOptimistic requires non-blank rawEncrypted" }
        pendingByRaw[msg.rawEncrypted] = msg
        emit()
    }

    /**
     * Подтверждает отправку: часы -> галочка МГНОВЕННО, без ожидания опроса.
     * Не удаляем оптимистичное сообщение (иначе оно исчезнет до прихода серверной
     * строки), а помечаем isPending=false и сразу emit(). reconcile() позже заменит
     * его серверной копией (совпадение rawEncrypted) — бесшовно, без дублей.
     * Так стикер/сообщение никогда не "зависает в отправке до перезахода".
     */
    fun confirmSent(encryptedLine: String) {
        val msg = pendingByRaw[encryptedLine]
        if (msg != null && msg.isPending) {
            pendingByRaw[encryptedLine] = msg.copy(isPending = false)
        }
        emit()
    }

    /**
     * Replaces a pending entry keeping its queue position.
     * Used during image upload: placeholder key → real gist:ID, transparent to user.
     */
    fun replacePending(oldRaw: String, newMsg: Message) {
        require(newMsg.isPending) { "replacePending requires isPending=true" }
        require(newMsg.rawEncrypted.isNotBlank()) { "replacePending requires non-blank rawEncrypted" }
        val entries = pendingByRaw.entries.toList()
        pendingByRaw.clear()
        for ((k, v) in entries) {
            if (k == oldRaw) pendingByRaw[newMsg.rawEncrypted] = newMsg
            else pendingByRaw[k] = v
        }
        emit()
    }

    /**
     * Обновляет прогресс отправки голосового у pending-сообщения (на месте, §1.5).
     * progress: VP_PROCESSING или 0..100. copy() меняет equals → StateFlow эмитит.
     */
    fun updateVoiceProgress(encryptedLine: String, progress: Int) {
        val m = pendingByRaw[encryptedLine] ?: return
        if (m.voiceProgress != progress) {
            pendingByRaw[encryptedLine] = m.copy(voiceProgress = progress)
            emit()
        }
    }

    /** Убирает оптимистичное сообщение (например, запись оказалась слишком короткой). */
    fun dropPending(encryptedLine: String) {
        if (pendingByRaw.remove(encryptedLine) != null) emit()
    }

    fun failSend(encryptedLine: String) {
        if (pendingByRaw.containsKey(encryptedLine)) {
            emit()
        }
    }

    fun addTombstone(msgId: String) {
        tombstones.add(msgId)
        emit()
    }

    fun removeTombstone(msgId: String) {
        tombstones.remove(msgId)
        emit()
    }

    fun reconcile(remote: List<Message>) {
        lastRemote = remote
        val serverIds = remote.map { it.msgId }.toSet()
        tombstones.removeAll { id -> id !in serverIds }
        // Снимаем pending, у которых появилась серверная копия — по rawEncrypted ИЛИ по
        // имени файла стикера/картинки. Шифртекст недетерминирован (random salt/nonce),
        // поэтому для стикеров сверка по imageFileName надёжнее — иначе оставались дубли.
        val visible = remote.filter { it.msgId !in tombstones }
        val serverRaws = visible.map { it.rawEncrypted }.toSet()
        val serverFiles = visible.mapNotNull { it.imageFileName }.toSet()
        pendingByRaw.entries.removeAll { (raw, m) ->
            raw in serverRaws || (m.imageFileName != null && m.imageFileName in serverFiles)
        }
        _messages.value = compose()
    }

    fun pendingSnapshot(): List<Message> = pendingByRaw.values.toList()

    fun hasPending(): Boolean = pendingByRaw.isNotEmpty()

    fun clear() {
        pendingByRaw.clear()
        tombstones.clear()
        lastRemote = emptyList()
        _messages.value = emptyList()
    }

    /**
     * Собирает итоговый список: серверные сообщения + pending, но БЕЗ дублей.
     * pending скрывается, если для него уже есть серверная копия — по rawEncrypted
     * ИЛИ по imageFileName (стикеры/картинки, у которых ciphertext недетерминирован).
     */
    private fun compose(): List<Message> {
        val visible = lastRemote.filter { it.msgId !in tombstones }
        val serverRaws = visible.map { it.rawEncrypted }.toSet()
        val serverFiles = visible.mapNotNull { it.imageFileName }.toSet()
        val pend = pendingByRaw.values.filter { m ->
            m.rawEncrypted !in serverRaws &&
            (m.imageFileName == null || m.imageFileName !in serverFiles)
        }
        return visible + pend
    }

    private fun emit() {
        _messages.value = compose()
    }
}
