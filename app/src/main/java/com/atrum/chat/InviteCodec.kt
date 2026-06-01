package com.atrum.chat

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Кодирует и декодирует invite-строки для подключения к существующему чату.
 *
 * Формат v2 (текущий, PIN-защищённый):
 *   "ATRM" + base64url( salt[16] + nonce[12] + AES-256-GCM( VERSION ‖ gistId ‖ gistToken ‖ chatPassword ) )
 *   Ключ деривируется через PBKDF2WithHmacSHA256(PIN, salt, 100_000 итераций, 32 байта).
 *   PIN передаётся собеседнику отдельным каналом — перехват invite без PIN бесполезен.
 *
 * Формат v1 (legacy, plain base64 — только чтение):
 *   "ATRM" + base64url( VERSION ‖ gistId ‖ gistToken ‖ chatPassword )
 *
 * Безопасность:
 *   — invite содержит токен GitHub, поэтому требует PIN.
 *   — MAC/AEAD-тег AES-GCM защищает целостность и подтверждает правильность PIN.
 */
object InviteCodec {

    /** Префикс позволяет UI отличить invite от случайной строки. */
    const val PREFIX = "ATRM"

    /** Версия формата v2 (зашифрованный). */
    private const val VERSION = "2"

    /** Версия формата v1 (legacy plain base64). */
    private const val VERSION_LEGACY = "1"

    /** Разделитель полей. Record Separator (0x1E) — в реальном тексте не встречается. */
    private const val SEP = ""

    /** Результат декодирования invite-строки. */
    data class Decoded(
        val gistId: String,
        val gistToken: String,
        val chatPassword: String
    )

    /**
     * Кодирует данные чата в invite-строку без PIN (формат v1, plain base64).
     * Используется везде — PIN-защита удалена.
     */
    fun encodeLegacy(gistId: String, gistToken: String, chatPassword: String): String {
        require(gistId.isNotBlank()) { "gistId is blank" }
        require(gistToken.isNotBlank()) { "gistToken is blank" }
        require(chatPassword.isNotBlank()) { "chatPassword is blank" }
        val payload = listOf(VERSION_LEGACY, gistId, gistToken, chatPassword).joinToString(SEP)
        val b64 = Base64.encodeToString(
            payload.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$PREFIX$b64"
    }

    /**
     * Кодирует данные чата в invite-строку, зашифрованную PIN-ом.
     * @deprecated Используй encodeLegacy — PIN-защита удалена из UX.
     */
    fun encode(gistId: String, gistToken: String, chatPassword: String, pin: String): String {
        require(gistId.isNotBlank()) { "gistId is blank" }
        require(gistToken.isNotBlank()) { "gistToken is blank" }
        require(chatPassword.isNotBlank()) { "chatPassword is blank" }
        require(pin.isNotBlank()) { "pin is blank" }

        val payload = listOf(VERSION, gistId, gistToken, chatPassword).joinToString(SEP)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        // Format: salt(16) + nonce(12) + ciphertext
        val blob = salt + nonce + ciphertext
        val b64 = Base64.encodeToString(blob, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "$PREFIX$b64"
    }

    /**
     * Декодирует invite-строку (v2, зашифрованный). Бросает исключение при неверном PIN.
     * Возвращает null если формат не совпадает.
     */
    fun decode(invite: String, pin: String): Decoded? {
        val trimmed = invite.trim().replace("\\s".toRegex(), "")
        if (!trimmed.startsWith(PREFIX)) return null
        val b64 = trimmed.removePrefix(PREFIX)
        if (b64.isEmpty()) return null

        return try {
            val blob = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING)
            // Must be at least: salt(16) + nonce(12) + version_sep_data(>=7) + GCM_tag(16)
            if (blob.size <= 28 + 16) {
                // Too short for encrypted format — try legacy
                return null
            }
            val salt = blob.copyOfRange(0, 16)
            val nonce = blob.copyOfRange(16, 28)
            val ciphertext = blob.copyOfRange(28, blob.size)
            val key = deriveKey(pin, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            val plaintext = try {
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            } catch (e: Exception) {
                throw IllegalArgumentException("Wrong PIN or corrupted invite", e)
            }
            val parts = plaintext.split(SEP)
            if (parts.size < 4) return null
            if (parts[0] != VERSION) return null
            val gistId = parts[1]
            val gistToken = parts[2]
            val chatPassword = parts[3]
            if (gistId.isBlank() || gistToken.isBlank() || chatPassword.isBlank()) return null
            Decoded(gistId, gistToken, chatPassword)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Декодирует legacy (v1) invite без PIN.
     * Только для чтения старых invite-строк.
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
            val gistId = parts[1]
            val gistToken = parts[2]
            val chatPassword = parts[3]
            if (gistId.isBlank() || gistToken.isBlank() || chatPassword.isBlank()) return null
            Decoded(gistId, gistToken, chatPassword)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Определяет является ли invite новым зашифрованным форматом (v2).
     * Эвристика: blob после декодирования начинается с 28 байт salt+nonce,
     * затем зашифрованные данные. Мы просто проверяем что это наш PREFIX и
     * blob достаточно большой.
     */
    fun looksLikeEncryptedInvite(input: String): Boolean {
        val trimmed = input.trim().replace("\\s".toRegex(), "")
        if (!trimmed.startsWith(PREFIX)) return false
        val b64 = trimmed.removePrefix(PREFIX)
        // An encrypted invite has at minimum: salt(16)+nonce(12)+version(1)+sep(1)+gistId+... + tag(16) = >50 bytes
        // = >67 base64 chars; legacy invite with version "1" starts differently
        return try {
            val blob = Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING)
            if (blob.size <= 44) return false
            // Try to see if it decodes as legacy (version "1" in plain text)
            val legacyPayload = String(blob, Charsets.UTF_8)
            val legacyParts = legacyPayload.split(SEP)
            if (legacyParts.size >= 4 && legacyParts[0] == VERSION_LEGACY) return false
            // Not decodable as legacy — assume encrypted
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Быстрая эвристика — похоже ли это на invite (не запуская полный decode). */
    fun looksLikeInvite(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith(PREFIX) && trimmed.length > PREFIX.length + 10
    }

    private fun deriveKey(pin: String, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, 100_000, 256)
        return factory.generateSecret(spec).encoded
    }
}
