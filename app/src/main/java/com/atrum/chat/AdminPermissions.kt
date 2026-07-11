package com.atrum.chat

/**
 * Права делегированного администратора группы (битовая маска в ChatParticipant.permissions
 * и MembersSync.Entry.permissions). Главный админ (Chat.adminUserId) имеет ВСЕ права всегда,
 * независимо от маски. Назначает права только главный админ (Этап 1); фактическое
 * применение делегированными админами — мультиподпись транспорта (Этап 2).
 */
object AdminPermissions {
    /** Изменение данных беседы — название, описание (и аватар). */
    const val EDIT = 1 shl 0
    /** Мут и бан участников. */
    const val MODERATE = 1 shl 1
    /** Доступ к статистике чата (экран GroupStatsActivity). */
    const val STATS = 1 shl 2
    /** Закрепление сообщений (сама фича pin — Этап 3). */
    const val PIN = 1 shl 3
    /** Удаление и восстановление сообщений. */
    const val DELETE_RESTORE = 1 shl 4

    /** Все права (для главного админа / «полный набор»). */
    const val ALL = EDIT or MODERATE or STATS or PIN or DELETE_RESTORE

    fun has(mask: Int, perm: Int): Boolean = (mask and perm) != 0

    /** Есть ли ХОТЬ какое-то право — т.е. считается ли участник администратором. */
    fun isAdmin(mask: Int): Boolean = mask != 0

    /** Человекочитаемый список названий прав из маски (для карточки админа). */
    fun names(mask: Int): List<Int> = buildList {
        if (has(mask, EDIT)) add(0)
        if (has(mask, MODERATE)) add(1)
        if (has(mask, STATS)) add(2)
        if (has(mask, PIN)) add(3)
        if (has(mask, DELETE_RESTORE)) add(4)
    }
}
