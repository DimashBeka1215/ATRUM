package com.atrum.chat

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/**
 * Кодирует и декодирует invite-строки для подключения к существующему чату.
 *
 * Формат v3 (текущий — Argon2id + срок действия):
 *   "ATRM" + base64url( salt[16] + nonce[12] + AES-256-GCM( VER ‖ channelId ‖ transportToken ‖ chatPassword ‖ expiryMs ) )
 *   Ключ деривируется через Argon2id(PIN, salt) — GPU-стойкий KDF (как и шифрование сообщений).
 *   PIN передаётся собеседнику отдельным каналом — перехват invite без PIN бесполезен.
 *   expiryMs — unix-мс, после которого invite считается недействительным.
 *
 * Формат v2 (PBKDF2 — только чтение, обратная совместимость):
 *   "ATRM" + base64url( salt[16] + nonce[12] + AES-256-GCM( "2" ‖ channelId ‖ transportToken ‖ chatPassword ) )
 *
 * Формат v1 (legacy, plain base64 — только чтение):
 *   "ATRM" + base64url( "1" ‖ channelId ‖ transportToken ‖ chatPassword )
 *
 * Безопасность:
 *   — invite содержит транспортный токен, поэтому требует PIN.
 *   — AEAD-тег AES-GCM защищает целостность и подтверждает правильность PIN.
 *   — Argon2id делает офлайн-перебор короткого кода дорогим (в отличие от PBKDF2).
 */
object InviteCodec {

    /** Префикс позволяет UI отличить invite от случайной строки. */
    const val PREFIX = "ATRM"

    /** Текущая версия (Argon2id + expiry). */
    private const val VERSION = "3"

    /** Версия v2 — PBKDF2 (только чтение). */
    private const val VERSION_PBKDF2 = "2"

    /** Версия v1 — legacy plain base64 (только чтение). */
    private const val VERSION_LEGACY = "1"

    /** Разделитель полей. Record Separator (0x1E) — в реальном тексте не встречается. */
    private const val SEP = ""

    /** Срок жизни invite по умолчанию — 48 часов. */
    const val DEFAULT_TTL_MS = 48L * 60 * 60 * 1000

    // Argon2id параметры (фиксированы — обе стороны должны получать одинаковый ключ).
    private const val ARGON2_MEM_KB   = 65536
    private const val ARGON2_ITER     = 3
    private const val ARGON2_PARALLEL = 1
    private const val KEY_LEN         = 32
    private const val SALT_LEN        = 16
    private const val NONCE_LEN       = 12
    private const val GCM_TAG_BITS    = 128

    /** Invite просрочен. */
    class ExpiredException : Exception("invite expired")

    /** Результат декодирования invite-строки. */
    data class Decoded(
        val channelId: String,
        val transportToken: String,
        val chatPassword: String
    )

    /**
     * Кодирует данные чата в зашифрованную PIN-ом invite-строку (формат v3).
     * @param ttlMillis срок жизни invite в мс (по умолчанию 48 часов).
     */
    fun encode(
        channelId: String,
        transportToken: String,
        chatPassword: String,
        pin: String,
        ttlMillis: Long = DEFAULT_TTL_MS
    ): String {
        require(channelId.isNotBlank()) { "channelId is blank" }
        require(transportToken.isNotBlank()) { "transportToken is blank" }
        require(chatPassword.isNotBlank()) { "chatPassword is blank" }
        require(pin.isNotBlank()) { "pin is blank" }

        val expiry = System.currentTimeMillis() + ttlMillis
        val payload = listOf(VERSION, channelId, transportToken, chatPassword, expiry.toString()).joinToString(SEP)
        val salt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val key = deriveKeyArgon2(pin, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
            val blob = salt + nonce + ciphertext
            return "$PREFIX${Base64.encodeToString(blob, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)}"
        } finally {
            key.fill(0)
        }
    }

    /**
     * Декодирует зашифрованный invite (v3 Argon2id, затем v2 PBKDF2 для обратной совместимости).
     * @throws ExpiredException если invite v3 просрочен.
     * @throws IllegalArgumentException если PIN неверный или данные повреждены.
     * @return null если формат вообще не похож на invite.
     */
    fun decode(invite: String, pin: String): Decoded? {
        val trimmed = invite.trim().replace("\\s".toRegex(), "")
        if (!trimmed.startsWith(PREFIX)) return null
        val b64 = trimmed.removePrefix(PREFIX)
        if (b64.isEmpty()) return null

        val blob = try {
            Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING)
        } catch (_: Exception) {
            return null
        }
        if (blob.size <= SALT_LEN + NONCE_LEN + GCM_TAG_BITS / 8) return null

        val salt = blob.copyOfRange(0, SALT_LEN)
        val nonce = blob.copyOfRange(SALT_LEN, SALT_LEN + NONCE_LEN)
        val ciphertext = blob.copyOfRange(SALT_LEN + NONCE_LEN, blob.size)

        // PIN-кандидаты. Сторона шеринга авто-капсит код (textCapCharacters) и генератор
        // даёт ТОЛЬКО заглавные; сторона ввода — без авто-капса, поэтому получатель мог
        // набрать в другом регистре. Пробуем как ввели И в верхнем регистре (+trim).
        // Чисто аддитивно: рабочие инвайты возвращаются на первом же кандидате.
        val pinCandidates = linkedSetOf(pin.trim(), pin.trim().uppercase(java.util.Locale.ROOT))

        for (candidate in pinCandidates) {
            if (candidate.isEmpty()) continue

            // v3 — Argon2id
            val v3 = tryGcmDecrypt(deriveKeyArgon2(candidate, salt), nonce, ciphertext)
            if (v3 != null) {
                val parts = v3.split(SEP)
                if (parts.size >= 5 && parts[0] == VERSION) {
                    val expiry = parts[4].toLongOrNull() ?: 0L
                    if (System.currentTimeMillis() > expiry) throw ExpiredException()
                    return validated(parts[1], parts[2], parts[3])
                }
            }

            // v2 — PBKDF2 (обратная совместимость)
            val v2 = tryGcmDecrypt(deriveKeyPbkdf2(candidate, salt), nonce, ciphertext)
            if (v2 != null) {
                val parts = v2.split(SEP)
                if (parts.size >= 4 && parts[0] == VERSION_PBKDF2) {
                    return validated(parts[1], parts[2], parts[3])
                }
            }
        }

        // Ни один ключ не подошёл — неверный PIN или повреждённый invite.
        throw IllegalArgumentException("Wrong PIN or corrupted invite")
    }

    private fun validated(channelId: String, transportToken: String, chatPassword: String): Decoded? {
        if (channelId.isBlank() || transportToken.isBlank() || chatPassword.isBlank()) return null
        return Decoded(channelId, transportToken, chatPassword)
    }

    /** GCM-дешифровка. Возвращает null если тег не сошёлся (неверный ключ). */
    private fun tryGcmDecrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            key.fill(0)
        }
    }

    /**
     * Декодирует legacy (v1) invite без PIN. Только для чтения старых invite-строк.
     */
    fun decodeLegacy(input: String): Decoded? {
        val trimmed = input.trim().replace("\\s".toRegex(), "")
        if (!trimmed.startsWith(PREFIX)) return null
        val b64 = trimmed.removePrefix(PREFIX)
        if (b64.isEmpty()) return null

        return try {
            val bytes = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            val payload = String(bytes, Charsets.UTF_8)
            val parts = payload.split(SEP)
            if (parts.size < 4) return null
            if (parts[0] != VERSION_LEGACY) return null
            validated(parts[1], parts[2], parts[3])
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Похоже ли это на зашифрованный invite (v2/v3). Если да — UI запросит PIN.
     */
    fun looksLikeEncryptedInvite(input: String): Boolean {
        val trimmed = input.trim().replace("\\s".toRegex(), "")
        if (!trimmed.startsWith(PREFIX)) return false
        val b64 = trimmed.removePrefix(PREFIX)
        return try {
            val blob = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING)
            if (blob.size <= SALT_LEN + NONCE_LEN + GCM_TAG_BITS / 8) return false
            // Если декодируется как legacy (version "1" открытым текстом) — это не шифрованный.
            val legacyPayload = String(blob, Charsets.UTF_8)
            val legacyParts = legacyPayload.split(SEP)
            !(legacyParts.size >= 4 && legacyParts[0] == VERSION_LEGACY)
        } catch (_: Throwable) {
            false
        }
    }

    /** Быстрая эвристика — похоже ли это на invite (не запуская полный decode). */
    fun looksLikeInvite(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith(PREFIX) && trimmed.length > PREFIX.length + 10
    }

    /** Префикс deep-link приглашения. Открывается штатной камерой телефона и сканером Atrum. */
    const val DEEPLINK_PREFIX = "atrum://join"

    /** Оборачивает invite-строку в deep-link для QR (инвайт в фрагменте — на сервер ничего не уходит). */
    fun toDeepLink(invite: String): String = "$DEEPLINK_PREFIX#" + invite

    /**
     * Достаёт чистую invite-строку из отсканированного текста.
     * Понимает и deep-link (atrum://join#ATRM...), и «голый» ATRM... код. null — если это не invite.
     */
    fun extractInvite(scanned: String?): String? {
        val s = scanned?.trim()?.replace("\\s".toRegex(), "") ?: return null
        val idx = s.indexOf(PREFIX)
        return when {
            s.startsWith(DEEPLINK_PREFIX, ignoreCase = true) && idx >= 0 -> s.substring(idx)
            s.startsWith(PREFIX) -> s
            else -> null
        }
    }

    /** Argon2id (текущий KDF, GPU-стойкий). Параллелизм фиксирован — детерминизм между устройствами. */
    private fun deriveKeyArgon2(pin: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(ARGON2_MEM_KB)
            .withIterations(ARGON2_ITER)
            .withParallelism(ARGON2_PARALLEL)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val key = ByteArray(KEY_LEN)
        gen.generateBytes(pin.toCharArray(), key)
        return key
    }

    /** PBKDF2 (старый KDF v2 — только для чтения старых invite). */
    private fun deriveKeyPbkdf2(pin: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return factory.generateSecret(spec).encoded
    }
}
