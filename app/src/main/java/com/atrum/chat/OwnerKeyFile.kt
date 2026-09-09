package com.atrum.chat

import android.util.Base64

/**
 * Файл резервной копии ключа владельца (`.atr`).
 *
 * Зачем файл, а не строка: копию нужно уметь просто взять и положить обратно, не занимаясь
 * копированием текста через буфер. И заодно — чтобы посторонний, открывший файл в блокноте,
 * не увидел ничего осмысленного.
 *
 * ФОРМАТ (всё, кроме первого байта, выглядит случайным мусором):
 * ```
 *   версия(1) ‖ соль(16) ‖ nonce(12) ‖ AES-GCM(шифртекст+тег)
 * ```
 * Внутри шифра:
 * ```
 *   MAGIC(5) ‖ приватный ключ(32) ‖ публичный ключ(32) ‖ подписанный отзыв(64)
 * ```
 * Снаружи НЕТ ни магической подписи, ни имени формата — всё узнаваемое спрятано внутрь.
 * Приложение понимает, что файл его, не по заголовку, а по тому, что сошёлся GCM-тег и
 * расшифрованное начинается с [MAGIC]. Побочный, но нужный эффект: файл статистически
 * неотличим от случайных байт, а неверный пароль, порча байта и чужой файл дают ровно один
 * и тот же ответ — null.
 *
 * Отзыв кладётся В ФАЙЛ намеренно и заранее: подписать его может только владелец ключа, а
 * если ключ потерян, подписывать уже нечем. Заготовленный отзыв позволяет погасить ключ даже
 * тогда, когда самого ключа не осталось (см. [VerifiedBadge.revokeSigData]).
 *
 * Размер файла постоянный — 178 байт, длина ничего не выдаёт.
 */
object OwnerKeyFile {

    /** Расширение. Косметика: опознание идёт по расшифровке, а не по имени. */
    const val EXTENSION = "atr"

    /** Имя по умолчанию при сохранении. */
    const val DEFAULT_NAME = "atrum-owner.atr"

    private const val VERSION: Byte = 1
    private val MAGIC = byteArrayOf(0x41, 0x54, 0x52, 0x4B, 0x31) // "ATRK1", только ВНУТРИ шифра

    private const val KEY_LEN = 32
    private const val SIG_LEN = 64
    private const val PAYLOAD_LEN = 5 + KEY_LEN + KEY_LEN + SIG_LEN

    sealed class Parsed {
        data class Ok(val privB64: String, val pubB64: String, val revocationSig: String) : Parsed()
        /** Не расшифровалось: неверный пароль, чужой файл или испорченные байты. */
        object Unreadable : Parsed()
    }

    /**
     * Собирает содержимое `.atr`. [priv] — сырой приватный ключ (32 байта), [pubB64] — его
     * публичная половина, [revocationSigB64] — заранее подписанный отзыв.
     */
    fun build(priv: ByteArray, pubB64: String, revocationSigB64: String, password: String): ByteArray? {
        val pub = runCatching { Base64.decode(pubB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        val sig = runCatching { Base64.decode(revocationSigB64, Base64.NO_WRAP) }.getOrNull() ?: return null
        if (priv.size != KEY_LEN || pub.size != KEY_LEN || sig.size != SIG_LEN) return null

        val payload = ByteArray(PAYLOAD_LEN)
        System.arraycopy(MAGIC, 0, payload, 0, MAGIC.size)
        System.arraycopy(priv, 0, payload, 5, KEY_LEN)
        System.arraycopy(pub, 0, payload, 5 + KEY_LEN, KEY_LEN)
        System.arraycopy(sig, 0, payload, 5 + 2 * KEY_LEN, SIG_LEN)

        return try {
            val sealed = CryptoHelper.sealWithPassword(payload, password)
            ByteArray(1 + sealed.size).also {
                it[0] = VERSION
                System.arraycopy(sealed, 0, it, 1, sealed.size)
            }
        } finally {
            payload.fill(0)
        }
    }

    /** Разбирает содержимое `.atr`. Любая неудача — [Parsed.Unreadable], без подробностей. */
    fun parse(blob: ByteArray, password: String): Parsed {
        if (blob.isEmpty() || blob[0] != VERSION) return Parsed.Unreadable
        val payload = CryptoHelper.openWithPassword(blob.copyOfRange(1, blob.size), password)
            ?: return Parsed.Unreadable
        try {
            if (payload.size != PAYLOAD_LEN) return Parsed.Unreadable
            for (i in MAGIC.indices) if (payload[i] != MAGIC[i]) return Parsed.Unreadable

            val priv = payload.copyOfRange(5, 5 + KEY_LEN)
            val pub = payload.copyOfRange(5 + KEY_LEN, 5 + 2 * KEY_LEN)
            val sig = payload.copyOfRange(5 + 2 * KEY_LEN, PAYLOAD_LEN)

            // Публичный ключ НЕ берём на веру: выводим из приватного и сверяем. Иначе подложенный
            // файл мог бы заявить чужой публичный ключ при своём приватном.
            val derived = CryptoHelper.identityPublicFromPrivate(priv) ?: return Parsed.Unreadable
            val pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)
            if (derived != pubB64) return Parsed.Unreadable

            return Parsed.Ok(
                privB64 = Base64.encodeToString(priv, Base64.NO_WRAP),
                pubB64 = pubB64,
                revocationSig = Base64.encodeToString(sig, Base64.NO_WRAP)
            ).also { priv.fill(0) }
        } finally {
            payload.fill(0)
        }
    }
}
