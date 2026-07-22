package com.atrum.chat.stickers

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.atrum.chat.Prefs
import java.security.MessageDigest

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

    private val ENDPOINT_API = intArrayOf(
        100, 87, 181, 69, 165, 123, 23, 132, 108, 129, 118, 24, 235, 169,
        126, 53, 168, 10, 199, 15, 91, 93, 138, 226, 218, 218, 227, 223
    )
    private val ENDPOINT_FILE = intArrayOf(
        100, 87, 181, 69, 165, 123, 23, 132, 108, 129, 118, 24, 235, 169, 126, 53,
        168, 10, 199, 15, 91, 93, 138, 226, 218, 222, 229, 199, 215, 75, 157, 220, 120
    )

    @Volatile private var cdnKey: ByteArray? = null

    private fun endpointKey(context: Context): ByteArray {
        cdnKey?.let { return it }
        val k = try {
            val pm = context.packageManager
            val pkg = context.packageName
            val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures
            }
            val der = sigs?.firstOrNull()?.toByteArray()
            if (der == null) ByteArray(32) else MessageDigest.getInstance("SHA-256").digest(der)
        } catch (_: Throwable) {
            ByteArray(32)
        }
        cdnKey = k
        return k
    }

    private fun unpack(enc: IntArray, key: ByteArray): String {
        val out = ByteArray(enc.size) { (enc[it] xor (key[it % key.size].toInt() and 0xFF)).toByte() }
        return String(out, Charsets.US_ASCII)
    }

    fun apiBase(context: Context) = unpack(ENDPOINT_API, endpointKey(context)) + botToken(context)
    fun fileBase(context: Context) = unpack(ENDPOINT_FILE, endpointKey(context)) + botToken(context)

    /** Папка внутри filesDir где хранятся все стикер-паки. */
    const val STICKER_DIR = "stickers"

    /** Имя файла с метаданными пака. */
    const val META_FILE   = "meta.json"

    /** Максимальный размер одного файла стикера на диске (5 МБ). */
    const val MAX_STICKER_BYTES = 5 * 1024 * 1024L
}
