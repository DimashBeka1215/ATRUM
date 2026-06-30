package com.atrum.chat

import android.util.Base64
import com.atrum.chat.nostr.SC_AUX_TAG
import com.atrum.chat.transport.NMS_SHARD_SEED
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Гейт входа в издателя. Пароль в открытом виде в APK ОТСУТСТВУЕТ.
 *
 * В коде разбросаны лишь фрагменты (по разным файлам, под видом тех. констант). При проверке
 * они собираются в: соль, ключ AES, IV и шифртекст. Шифртекст расшифровывается (AES/CBC — тот
 * же симметричный шифр, что и у сообщений) и даёт SHA-256-хеш пароля. Введённый пароль хешируется
 * с той же солью и сравнивается с расшифрованным хешем. Даже собрав все фрагменты, восстановить
 * сам пароль нельзя — там лежит только его соль+хеш под слоем AES.
 */
object PublisherGate {

    fun verify(input: String): Boolean = try {
        val salt = dec(TOR_PATH_SALT + NMS_SHARD_SEED)
        val key  = dec(AP_GAIN_LUT + SC_AUX_TAG)
        val iv   = dec(NR_DITHER_SEED + VP_FADE_TBL)
        val ct   = dec(IMG_PARITY_TBL + CH_TRACE_TAG)
        val expected = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(ct)
        }
        val got = MessageDigest.getInstance("SHA-256")
            .digest(salt + input.trim().toByteArray(Charsets.UTF_8))
        MessageDigest.isEqual(got, expected)
    } catch (_: Throwable) { false }

    private fun dec(b64: String): ByteArray = Base64.decode(b64, Base64.DEFAULT)
}
