package com.atrum.chat

import org.json.JSONObject

/**
 * Профиль пользователя в чате — то что собеседник видит.
 * Хранится в profiles.txt в gist в зашифрованном виде, формат JSON:
 *
 *   {
 *     "<userId>": {
 *       "name": "Alex",
 *       "avatar": "<base64 jpeg>" or null,
 *       "updatedAt": 1700000000000,
 *       "lastReadIndex": 42
 *     },
 *     ...
 *   }
 *
 * lastReadIndex — индекс последнего прочитанного сообщения в chat.txt.
 * Используется для отображения галочек прочитанности у отправителя.
 */
data class Profile(
    val userId: String,
    val name: String,
    val tag: String? = null,
    val avatarBase64: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val lastReadIndex: Int = 0,
    /**
     * Unix-мс последнего нажатия клавиши. Не равно нулю пока пользователь печатает.
     * Считается «живым» если now - typingTs < TYPING_EXPIRY_MS (8 сек).
     * Никогда не хранится дольше сессии — при выходе из чата обнуляется.
     */
    val typingTs: Long = 0L,
    /**
     * Unix-мс последнего heartbeat «я онлайн».
     * Обновляется каждые 5 сек пока приложение на переднем плане.
     * При уходе в фон немедленно обнуляется → собеседник видит «не в сети»
     * уже через один цикл опроса (~1.5 сек).
     * Считается «живым» если now - onlineTs < ONLINE_EXPIRY_MS (10 сек).
     */
    val onlineTs: Long = 0L,
    /**
     * true если пользователь удалил свой профиль через «Удалить профиль».
     * У собеседников показывается заглушка «Профиль удалён» вместо аватарки.
     * Не пишем false в JSON чтобы экономить байты.
     */
    val deleted: Boolean = false,
    /**
     * Base64-кодированный X25519 публичный ключ текущей сессии.
     *
     * Генерируется при каждом открытии ChatActivity, публикуется через profiles.txt.
     * Когда оба участника видят ключи друг друга — вычисляется ECDH-общий секрет,
     * из него через HKDF выводится сессионный ключ шифрования (V3-формат).
     *
     * При закрытии чата приватный ключ уничтожается → сообщения сессии
     * нельзя расшифровать даже зная пароль (forward secrecy).
     *
     * null = участник открыл чат старой версией или ещё не инициировал сессию.
     */
    val ephemeralPubKey: String? = null,
    /** Статус пользователя — произвольный текст, задаётся в настройках. */
    val status: String? = null
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("name", name)
        if (tag != null) put("tag", tag)
        if (avatarBase64 != null) put("avatar", avatarBase64) else put("avatar", JSONObject.NULL)
        put("updatedAt", updatedAt)
        put("lastReadIndex", lastReadIndex)
        if (typingTs > 0L) put("typingTs", typingTs)
        if (onlineTs > 0L) put("onlineTs", onlineTs)
        if (deleted) put("deleted", true)
        if (ephemeralPubKey != null) put("eph", ephemeralPubKey)
        if (status != null) put("status", status)
    }

    companion object {
        fun fromJsonObject(userId: String, json: JSONObject): Profile {
            val avatar = if (json.isNull("avatar")) null else json.optString("avatar", null)
            return Profile(
                userId = userId,
                name = json.optString("name", ""),
                tag = json.optString("tag", null)?.takeIf { it.isNotBlank() },
                avatarBase64 = avatar?.takeIf { it.isNotBlank() },
                updatedAt = json.optLong("updatedAt", 0L),
                lastReadIndex = json.optInt("lastReadIndex", 0),
                typingTs = json.optLong("typingTs", 0L),
                onlineTs = json.optLong("onlineTs", 0L),
                deleted = json.optBoolean("deleted", false),
                ephemeralPubKey = json.optString("eph", "").takeIf { it.isNotBlank() },
                status = json.optString("status", null)?.takeIf { it.isNotBlank() }
            )
        }
    }
}
