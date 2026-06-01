package com.atrum.chat

/**
 * Конфигурация OAuth для GitHub Device Flow.
 *
 * Используем CLIENT_ID публичного OAuth App "GitHub CLI" — он работает без
 * client_secret через Device Flow, и пользователь не должен ничего настраивать.
 *
 * Device Flow устроен так:
 *   1. App запрашивает device_code + user_code у github.com/login/device/code
 *   2. App показывает user_code и просит открыть github.com/login/device
 *   3. Пользователь подтверждает в браузере
 *   4. App опрашивает github.com/login/oauth/access_token и получает токен
 *
 * Этот OAuth App принадлежит GitHub Inc, мы только пользуемся его публичным
 * client_id для авторизации. Это стандартная практика для CLI/native tools.
 *
 * АРХИТЕКТУРНО: токен полученный через OAuth не участвует в шифровании
 * сообщений. Шифрование зависит только от пароля комнаты.
 */
object OAuthConfig {

    /** Public client ID GitHub CLI (общедоступный OSS приложения). */
    const val CLIENT_ID: String = "178c6fc778ccc68e1d6a"

    /** Scopes которые нужны для создания/записи gist'ов. */
    const val SCOPE: String = "gist"

    /** Эндпоинт для запроса device_code и user_code. */
    const val DEVICE_CODE_URL: String = "https://github.com/login/device/code"

    /** Эндпоинт для polling токена. */
    const val TOKEN_URL: String = "https://github.com/login/oauth/access_token"

    /** Куда пользователь идёт в браузере чтобы ввести user_code. */
    const val VERIFICATION_URL: String = "https://github.com/login/device"
}
