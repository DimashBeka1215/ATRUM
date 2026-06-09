package com.atrum.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Статус проверки идентичности партнёра (защита от MITM, пункт 7).
 *
 * Заполняется в ChatActivity при получении профиля партнёра, читается в UI
 * (профиль партнёра). Информативный — НЕ блокирует установку сессии.
 *
 *  VERIFIED   — подпись эфемерного ключа верна, identity-ключ совпадает с запомненным (TOFU).
 *  UNVERIFIED — старый клиент без identity-ключа или подпись не проверилась.
 *  CHANGED    — identity-ключ партнёра ИЗМЕНИЛСЯ относительно запомненного → возможна подмена.
 */
object IdentityState {
    enum class State { UNKNOWN, UNVERIFIED, VERIFIED, CHANGED }

    /**
     * @param state              авто-статус подписи/TOFU.
     * @param partnerIdk         текущий identity-ключ партнёра (base64) или null.
     * @param partnerVerifiedMe  партнёр опубликовал, что лично подтвердил НАШ identity-ключ.
     */
    data class Info(
        val state: State = State.UNKNOWN,
        val partnerIdk: String? = null,
        val partnerVerifiedMe: Boolean = false
    )

    private val map = ConcurrentHashMap<String, Info>()

    fun set(chatId: String, info: Info) { map[chatId] = info }
    fun get(chatId: String): Info = map[chatId] ?: Info()
}
