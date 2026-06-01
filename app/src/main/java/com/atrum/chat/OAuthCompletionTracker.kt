package com.atrum.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton-трекер результата последнего GitHub OAuth Device Flow.
 *
 * Зачем: Activity и Service общаются через broadcast, но если Activity была
 * в фоне когда пришёл результат — broadcast мог не доставиться. При onResume
 * Activity проверяет этот tracker — если есть готовый chatId, открывает чат.
 *
 * Также сюда пишутся промежуточные статусы для UI ("Авторизация…", "Создаём комнату…",
 * "Готово!") — UI читает и обновляется через Flow.
 */
object OAuthCompletionTracker {

    sealed class Status {
        object Idle : Status()
        data class Progress(val message: String) : Status()
        data class Success(val chatId: Long) : Status()
        data class Failure(val message: String) : Status()
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status

    /** Прогресс — обновляется по этапам в Service. */
    fun updateProgress(message: String) {
        _status.value = Status.Progress(message)
    }

    /** Успешное завершение — chatId уже создан в Room. */
    fun markSuccess(chatId: Long) {
        _status.value = Status.Success(chatId)
    }

    /** Ошибка/отказ/таймаут. */
    fun markFailure(message: String) {
        _status.value = Status.Failure(message)
    }

    /** Сбросить после того как Activity обработала результат. */
    fun consume() {
        _status.value = Status.Idle
    }

    /** Возвращает последний результат (если был успех) без consume. */
    fun lastSuccessChatId(): Long? = (_status.value as? Status.Success)?.chatId
}
