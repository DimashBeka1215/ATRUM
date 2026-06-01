package com.atrum.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Local-first хранилище сообщений чата. Source of truth для UI.
 *
 * GitHub — только persistence layer. UI никогда не ждёт сетевого ответа.
 *
 * ┌──────────────────────────────────────────────────────────────────────┐
 * │  addOptimistic() → мгновенный UI                                     │
 * │  reconcile()     → merge remote + pending, tombstones                │
 * │  confirmSent()   → pending → confirmed (исчезнет на след. reconcile) │
 * │  failSend()      → pending остаётся в UI (пользователь видит ошибку) │
 * └──────────────────────────────────────────────────────────────────────┘
 *
 * Ключевое правило reconcile():
 *   Pending-сообщения НИКОГДА не исчезают из UI пока не придут confirmSent()
 *   или failSend(). Даже если GitHub вернул старый контент без нового сообщения
 *   (CDN eventual consistency) — UI не мерцает и не теряет сообщения.
 */
class ChatStore {

    // ── State ─────────────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    /** Текущий список сообщений для UI (remote + pending, без tombstones). */
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** Pending-сообщения: key = rawEncrypted (зашифрованный текст как уникальный ID). */
    private val pendingByRaw = LinkedHashMap<String, Message>()   // сохраняет порядок вставки

    /** Tombstones: msgId сообщений, удалённых оптимистично. */
    private val tombstones = mutableSetOf<String>()

    /** Последний remote snapshot (для восстановления при ошибках). */
    private var lastRemote: List<Message> = emptyList()

    // ── Optimistic updates ────────────────────────────────────────────────────

    /**
     * Добавить pending-сообщение (мгновенный UI, до подтверждения сервера).
     * [msg] должен иметь isPending = true и непустой rawEncrypted.
     */
    fun addOptimistic(msg: Message) {
        require(msg.isPending) { "addOptimistic требует isPending=true" }
        require(msg.rawEncrypted.isNotBlank()) { "addOptimistic требует непустой rawEncrypted" }
        pendingByRaw[msg.rawEncrypted] = msg
        emit()
    }

    /**
     * Подтвердить отправку: убрать из pending.
     * Сообщение придёт в UI при следующем reconcile() без isPending=true.
     * [encryptedLine] — тот же rawEncrypted что передавался в addOptimistic.
     */
    fun confirmSent(encryptedLine: String) {
        pendingByRaw.remove(encryptedLine)
        // Не вызываем emit() здесь: следующий reconcile() покажет сообщение из remote.
        // Это предотвращает brief "мерцание" (исчезновение pending + появление confirmed).
    }

    /**
     * Пометить отправку как failed: оставить сообщение в UI с isPending=true.
     * Пользователь видит что сообщение зависло и может предпринять действие.
     */
    fun failSend(encryptedLine: String) {
        // Оставляем в pending — пользователь видит сообщение (с часиками).
        // В будущем: добавить failed-состояние с кнопкой Retry.
        if (pendingByRaw.containsKey(encryptedLine)) {
            emit()   // перерисовываем чтобы обновить UI-статус (если он изменился)
        }
    }

    // ── Tombstones ────────────────────────────────────────────────────────────

    /**
     * Оптимистично скрыть сообщение (до подтверждения удаления сервером).
     * GitHub Gist eventual consistency: сервер может вернуть старый контент после PATCH.
     * Tombstone гарантирует что сообщение не "воскреснет" при следующем reconcile().
     */
    fun addTombstone(msgId: String) {
        tombstones.add(msgId)
        emit()
    }

    /**
     * Откатить tombstone (если PATCH удаления завершился ошибкой).
     * Сообщение снова появится в UI при следующем emit() или reconcile().
     */
    fun removeTombstone(msgId: String) {
        tombstones.remove(msgId)
        emit()
    }

    // ── Reconciliation ────────────────────────────────────────────────────────

    /**
     * Синхронизировать с remote данными.
     *
     * Алгоритм:
     *  1. Фильтруем remote: убираем tombstones (optimistic deletes ещё не подтверждены CDN).
     *  2. Очищаем tombstones для msgId которых уже нет в remote (сервер подтвердил удаление).
     *  3. Убираем из pendingByRaw сообщения которые уже есть в remote (по rawEncrypted).
     *     Это "подтверждение" прихода: pending → confirmed без явного вызова confirmSent().
     *  4. Итоговый список = remote (без tombstones) + оставшиеся pending в конце.
     *
     * Pending НИКОГДА не удаляются пока не найдены в remote (шаг 3) или не вызван failSend().
     * Это решает ключевой баг: исчезновение сообщений при CDN eventual consistency.
     */
    fun reconcile(remote: List<Message>) {
        lastRemote = remote

        // Шаг 2: очищаем tombstones которых уже нет на сервере (подтверждено удалёнными)
        val serverIds = remote.map { it.msgId }.toSet()
        tombstones.removeAll { id -> id !in serverIds }

        // Шаг 1: фильтруем remote — не показываем tombstoned сообщения
        val visible = remote.filter { it.msgId !in tombstones }

        // Шаг 3: убираем pending которые уже есть в remote
        val serverRaws = visible.map { it.rawEncrypted }.toSet()
        pendingByRaw.entries.removeAll { (raw, _) -> raw in serverRaws }

        // Шаг 4: remote + pending (в порядке вставки)
        _messages.value = visible + pendingByRaw.values.toList()
    }

    // ── Direct state access ───────────────────────────────────────────────────

    /** Текущий список pending-сообщений (снимок). */
    fun pendingSnapshot(): List<Message> = pendingByRaw.values.toList()

    /** true если есть pending-сообщения (не все ещё подтверждены). */
    fun hasPending(): Boolean = pendingByRaw.isNotEmpty()

    /** Сбросить всё состояние (clearHistory). */
    fun clear() {
        pendingByRaw.clear()
        tombstones.clear()
        lastRemote = emptyList()
        _messages.value = emptyList()
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Перестроить и опубликовать текущий список без полного reconcile(). */
    private fun emit() {
        val visible = lastRemote.filter { it.msgId !in tombstones }
        _messages.value = visible + pendingByRaw.values.toList()
    }
}
