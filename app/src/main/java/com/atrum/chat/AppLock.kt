package com.atrum.chat

/**
 * Глобальное состояние блокировки приложения.
 *
 * Ставится в true, когда приложение уходит в фон (см. App.ActivityLifecycleCallbacks),
 * если у пользователя задан PIN. Любой защищённый экран (SecureActivity) при
 * возврате на передний план проверяет этот флаг и показывает LockActivity.
 *
 * Сбрасывается в false только после успешного входа (PIN или отпечаток).
 * Так каждый возврат в приложение требует повторной аутентификации, а одноразовый
 * отпечаток запрашивается заново.
 */
object AppLock {
    @Volatile
    var locked: Boolean = false

    /**
     * До этого момента (SystemClock.elapsedRealtime, мс) уход в фон НЕ ставит
     * автоблокировку. Нужен для исходящего шеринга: при отправке приглашения/файла
     * во внешнее приложение (Telegram и др.) приложение кратковременно уходит в фон,
     * а система иногда успевает резюмировать наш таск до того, как целевое приложение
     * выйдет на передний план. Без подавления SecureActivity.onStart показывает
     * LockActivity, и наш таск перебивает Telegram — переход «отскакивает» обратно.
     *
     * 0 = подавление выключено.
     */
    @Volatile
    var suppressLockUntil: Long = 0L

    /** Включить «окно шеринга»: ближайший уход в фон не заблокирует приложение. */
    fun beginShareGrace(windowMs: Long = 60_000L) {
        suppressLockUntil = android.os.SystemClock.elapsedRealtime() + windowMs
    }

    /** Активно ли сейчас «окно шеринга». */
    fun shareGraceActive(): Boolean =
        suppressLockUntil > android.os.SystemClock.elapsedRealtime()

    /** Завершить «окно шеринга» (например, когда приложение вернулось на передний план). */
    fun endShareGrace() {
        suppressLockUntil = 0L
    }

    /**
     * Момент ухода в фон (SystemClock.elapsedRealtime, мс). Нужен для «льготного периода»
     * автоблокировки.
     */
    @Volatile
    var backgroundedAt: Long = 0L

    /**
     * Льготный период автоблокировки. Если пользователь вернулся в приложение в течение
     * этого времени после ухода в фон — пароль НЕ перепрашивается. Это убирает ощущение,
     * что приложение «просит пароль на каждое действие»: краткая отлучка (переключился в
     * другое приложение/шторку на пару секунд, открыл камеру/галерею) больше не блокирует.
     * Пароль спрашивается только после реального отсутствия дольше этого окна и при
     * холодном старте.
     */
    const val AUTO_LOCK_GRACE_MS: Long = 30_000L

    /** Зафиксировать момент ухода в фон (вызывается при автоблокировке). */
    fun markBackgrounded() {
        backgroundedAt = android.os.SystemClock.elapsedRealtime()
    }

    /** true, если возврат произошёл в пределах льготного периода (краткая отлучка). */
    fun withinAutoLockGrace(): Boolean =
        backgroundedAt != 0L &&
            android.os.SystemClock.elapsedRealtime() - backgroundedAt < AUTO_LOCK_GRACE_MS
}
