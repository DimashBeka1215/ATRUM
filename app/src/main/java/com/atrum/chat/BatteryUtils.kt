package com.atrum.chat

import android.content.Context
import android.os.BatteryManager

/** Утилиты заряда. Используется для отключения тяжёлых анимаций при низком заряде. */
object BatteryUtils {

    /** Порог «низкого заряда» в процентах. */
    const val LOW_PCT = 15

    /**
     * true — заряд батареи ≤ [LOW_PCT]% (и известен). При недоступности датчика — false
     * (не мешаем работе). Используется, чтобы не проигрывать анимированные стикеры и
     * экономить заряд.
     */
    fun isLow(ctx: Context): Boolean {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
        val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return lvl in 1..LOW_PCT
    }

    /** Сессионный обход: пользователь нажал «Включить» на плашке — анимируем несмотря на заряд. */
    @Volatile
    var animateSessionOverride = false

    /** Постоянный обход (галочка «Запомнить»): загружается из Prefs при старте. */
    @Volatile
    var animatePersistOverride = false

    /** true — стикеры нужно заморозить (низкий заряд И пользователь не включил вручную). */
    fun freezeStickers(ctx: Context): Boolean = !animateSessionOverride && !animatePersistOverride && isLow(ctx)
}
