package com.atrum.chat.nostr

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * BIP-340 Schnorr signatures over secp256k1.
 *
 * Чистая Kotlin/JVM реализация — никаких нативных зависимостей.
 * Используется для подписи Nostr-событий (NIP-01).
 *
 * Реализовано:
 *   sign(privKey, msg)         — подписать 32-байтовое сообщение
 *   pubkeyFromPrivkey(privKey) — получить публичный ключ (x-coord, 32 байта)
 */
object Schnorr {

    // ─── secp256k1 constants ─────────────────────────────────────────────────

    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)
    private val G = ECPoint(Gx, Gy)

    private val BI2 = BigInteger.valueOf(2L)
    private val BI3 = BigInteger.valueOf(3)

    // ─── EC point arithmetic ─────────────────────────────────────────────────

    private data class ECPoint(val x: BigInteger, val y: BigInteger)

    private fun pointAdd(p1: ECPoint?, p2: ECPoint?): ECPoint? {
        if (p1 == null) return p2
        if (p2 == null) return p1
        if (p1.x == p2.x) {
            if (p1.y != p2.y) return null   // point at infinity
            // point doubling
            val lam = BI3.multiply(p1.x).multiply(p1.x)
                .multiply(BI2.multiply(p1.y).modPow(P - BI2, P)).mod(P)
            val x3 = lam.multiply(lam).subtract(BI2.multiply(p1.x)).mod(P)
            val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
            return ECPoint(x3, y3)
        }
        val lam = p2.y.subtract(p1.y)
            .multiply(p2.x.subtract(p1.x).modPow(P - BI2, P)).mod(P)
        val x3 = lam.multiply(lam).subtract(p1.x).subtract(p2.x).mod(P)
        val y3 = lam.multiply(p1.x.subtract(x3)).subtract(p1.y).mod(P)
        return ECPoint(x3, y3)
    }

    private fun pointMul(point: ECPoint, k: BigInteger): ECPoint? {
        var result: ECPoint? = null
        var addend: ECPoint? = point
        var n = k
        while (n > BigInteger.ZERO) {
            if (n.testBit(0)) result = pointAdd(result, addend)
            addend = pointAdd(addend, addend)
            n = n.shiftRight(1)
        }
        return result
    }

    private fun hasEvenY(p: ECPoint): Boolean = !p.y.testBit(0)

    // ─── byte helpers ────────────────────────────────────────────────────────

    /** BigInteger → строго 32 байта, big-endian (обрезает лишний знаковый байт). */
    private fun toBytes32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32  -> b.copyOfRange(b.size - 32, b.size)
            else          -> ByteArray(32 - b.size) + b
        }
    }

    // ─── tagged hash (BIP-340) ───────────────────────────────────────────────

    /** taggedHash(tag, msg) = SHA256(SHA256(tag) || SHA256(tag) || msg) */
    private fun taggedHash(tag: String, vararg parts: ByteArray): ByteArray {
        val tagBytes = tag.toByteArray(Charsets.UTF_8)
        val tagHash = sha256(tagBytes)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(tagHash)
        md.update(tagHash)
        parts.forEach { md.update(it) }
        return md.digest()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    // ─── public API ─────────────────────────────────────────────────────────

    /**
     * Возвращает x-координату публичного ключа (32 байта, big-endian).
     */
    fun pubkeyFromPrivkey(privKey: ByteArray): ByteArray {
        require(privKey.size == 32) { "privkey must be 32 bytes" }
        val d = BigInteger(1, privKey)
        require(d >= BigInteger.ONE && d < N) { "privkey out of range" }
        val p = pointMul(G, d) ?: throw IllegalArgumentException("invalid privkey")
        return toBytes32(p.x)
    }

    /**
     * Подписывает msg (32 байта) приватным ключом (32 байта).
     * Возвращает 64-байтовую Schnorr-подпись: bytes(R.x) || bytes(s).
     *
     * Соответствует BIP-340 §Signing:
     *   https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki#signing
     */
    fun sign(
        privKey: ByteArray,
        msg: ByteArray,
        auxRand: ByteArray = SecureRandom().generateSeed(32)
    ): ByteArray {
        require(privKey.size == 32)  { "privkey must be 32 bytes" }
        require(msg.size == 32)      { "msg must be 32 bytes" }
        require(auxRand.size == 32)  { "auxRand must be 32 bytes" }

        val d0 = BigInteger(1, privKey)
        require(d0 >= BigInteger.ONE && d0 < N) { "privkey out of range" }

        val pointP = pointMul(G, d0)!!
        // Если y нечётное — инвертируем ключ (BIP-340 требует чётный Y)
        val d = if (hasEvenY(pointP)) d0 else N - d0
        val pubKeyBytes = toBytes32(pointP.x)

        // t = bytes(d) XOR taggedHash("BIP0340/aux", auxRand)
        val dBytes = toBytes32(d)
        val auxHash = taggedHash("BIP0340/aux", auxRand)
        val t = ByteArray(32) { i -> (dBytes[i].toInt() xor auxHash[i].toInt()).toByte() }

        // rand = taggedHash("BIP0340/nonce", t || bytes(P) || msg)
        val rand = taggedHash("BIP0340/nonce", t, pubKeyBytes, msg)
        val k0 = BigInteger(1, rand).mod(N)
        require(k0 != BigInteger.ZERO) { "nonce derived to zero — use different auxRand" }

        val pointR = pointMul(G, k0)!!
        val k = if (hasEvenY(pointR)) k0 else N - k0

        // e = int(taggedHash("BIP0340/challenge", bytes(R.x) || bytes(P.x) || msg)) mod n
        val rBytes = toBytes32(pointR.x)
        val e = BigInteger(1, taggedHash("BIP0340/challenge", rBytes, pubKeyBytes, msg)).mod(N)

        val s = (k + e * d).mod(N)
        return rBytes + toBytes32(s)
    }

    /**
     * Проверяет BIP-340 Schnorr-подпись.
     *   pubKey — 32 байта (x-only), msg — 32 байта, sig — 64 байта.
     * Возвращает true только при валидной подписи. Любая ошибка/несоответствие → false.
     */
    fun verify(pubKey: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = try {
        require(pubKey.size == 32 && msg.size == 32 && sig.size == 64)
        val px = BigInteger(1, pubKey)
        val pointP = liftX(px)
        if (pointP == null) false
        else {
            val r = BigInteger(1, sig.copyOfRange(0, 32))
            val sVal = BigInteger(1, sig.copyOfRange(32, 64))
            if (r >= P || sVal >= N) false
            else {
                val e = BigInteger(1, taggedHash("BIP0340/challenge", sig.copyOfRange(0, 32), pubKey, msg)).mod(N)
                val sg = pointMul(G, sVal)
                val eP = pointMul(pointP, (N - e).mod(N))
                val pointR = pointAdd(sg, eP)
                if (pointR == null || !hasEvenY(pointR)) false
                else pointR.x == r
            }
        }
    } catch (_: Throwable) { false }

    /** BIP-340 lift_x: восстанавливает точку с чётным Y по x-координате, или null. */
    private fun liftX(x: BigInteger): ECPoint? {
        if (x <= BigInteger.ZERO || x >= P) return null
        val c = x.modPow(BI3, P).add(BigInteger.valueOf(7)).mod(P)
        // secp256k1: P ≡ 3 (mod 4) → sqrt = c^((P+1)/4) mod P
        val y = c.modPow(P.add(BigInteger.ONE).shiftRight(2), P)
        if (y.modPow(BI2, P) != c) return null
        val yEven = if (!y.testBit(0)) y else P.subtract(y)
        return ECPoint(x, yEven)
    }
}

// Внутренняя таблица настройки (не трогать).
internal val SC_AUX_TAG = "58S/HvRmPg=="
