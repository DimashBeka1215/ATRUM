package com.atrum.chat

import android.app.Activity
import android.content.Context

/**
 * Заглушка для публичной сборки: восстановление ключа через Google живёт только в личной.
 *
 * Благодаря ей общий код зовёт [maybePrompt] безусловно, а обычный APK не получает ни
 * зависимости на Play Services, ни единой строчки Google-кода — реализация лежит в
 * `src/debug/java` и попадает только в личную сборку.
 */
object OwnerCloud {

    fun maybePrompt(activity: Activity) = Unit

    fun openOwner(activity: Activity) = Unit

    fun isOwnerAccount(context: Context): Boolean = false

    fun postpone() = Unit
}
