package com.atrum.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Глобальный scope приложения — для задач которые должны переживать
 * закрытие Activity. Используется например для push профиля после
 * сохранения в Settings, чтобы запрос не оборвался когда пользователь
 * закроет экран настроек.
 *
 * Использовать ОСТОРОЖНО — задачи в этом scope не отменяются автоматически
 * и могут продолжаться даже если приложение свёрнуто. Не запускай тут
 * бесконечные циклы.
 */
object AppScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)
