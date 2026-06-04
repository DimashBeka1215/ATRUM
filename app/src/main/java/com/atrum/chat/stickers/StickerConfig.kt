package com.atrum.chat.stickers

import android.content.Context
import com.atrum.chat.Prefs

/**
 * Конфигурация модуля стикеров.
 *
 * Токен бота хранится в EncryptedSharedPreferences через Prefs.stickerBotToken.
 * Задаётся пользователем в настройках (StickerSettingsActivity).
 */
internal object StickerConfig {

    /** Возвращает текущий токен из Prefs. */
    fun botToken(context: Context): String =
        Prefs(context).stickerBotToken

    fun apiBase(context: Context)  = "https://api.telegram.org/bot${botToken(context)}"
    fun fileBase(context: Context) = "https://api.telegram.org/file/bot${botToken(context)}"

    /** Папка внутри filesDir где хранятся все стикер-паки. */
    const val STICKER_DIR = "stickers"

    /** Имя файла с метаданными пака. */
    const val META_FILE   = "meta.json"

    /** Максимальный размер одного файла стикера на диске (5 МБ). */
    const val MAX_STICKER_BYTES = 5 * 1024 * 1024L
}
