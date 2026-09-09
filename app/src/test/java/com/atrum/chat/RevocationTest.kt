package com.atrum.chat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Домен подписи отзыва.
 *
 * Проверяется только чистая часть: сама подпись и разбор файла тянут android.util.Base64 и
 * AES, которых в JVM-юните нет. Но домен — ровно то место, где ошибка была бы незаметной и
 * фатальной: разойдись строка хоть на символ между отправителем и получателем, отзыв просто
 * никогда бы не сработал, и понять это можно было бы только по тому, что утёкший ключ
 * продолжает жить.
 */
class RevocationTest {

    @Test
    fun domainIsBoundToTheKey() {
        val a = VerifiedBadge.revokeSigData("AAAA")
        val b = VerifiedBadge.revokeSigData("BBBB")
        assertNotEquals(String(a), String(b))
    }

    @Test
    fun domainIsStableAndPrefixed() {
        val key = "0p/tHO9Id3V9A56bPqga9oOwf34pEkIAxCWUKdCKSmE="
        assertEquals("atrum_idrevoke_v1_$key", String(VerifiedBadge.revokeSigData(key)))
        // Дважды посчитанный домен обязан совпадать байт в байт — иначе подпись не сойдётся.
        assertArrayEquals(VerifiedBadge.revokeSigData(key), VerifiedBadge.revokeSigData(key))
    }

    @Test
    fun domainDiffersFromIdentityDomain() {
        // Домены обязаны быть разными: иначе подпись, сделанную для доказательства личности,
        // можно было бы предъявить как отзыв — и любой участник беседы гасил бы чужой ключ.
        val key = "AAAA"
        assertNotEquals(
            String(VerifiedBadge.revokeSigData(key)),
            String(VerifiedBadge.identitySigData(key))
        )
    }
}
