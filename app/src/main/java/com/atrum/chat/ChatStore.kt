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

    fun confirmSent(encryptedLine: String) {
        pendingByRaw.remove(encryptedLine)
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
        val visible = remote.filter { it.msgId !in tombstones }
        val serverRaws = visible.map { it.rawEncrypted }.toSet()
        pendingByRaw.entries.removeAll { (raw, _) -> raw in serverRaws }
        _messages.value = visible + pendingByRaw.values.toList()
    }

    fun pendingSnapshot(): List<Message> = pendingByRaw.values.toList()

    fun hasPending(): Boolean = pendingByRaw.isNotEmpty()

    fun clear() {
        pendingByRaw.clear()
        tombstones.clear()
        lastRemote = emptyList()
        _messages.value = emptyList()
    }

    private fun emit() {
        val visible = lastRemote.filter { it.msgId !in tombstones }
        _messages.value = visible + pendingByRaw.values.toList()
    }
}
