package com.atrum.chat

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Шифрование сообщений чата.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ФОРМАТЫ СООБЩЕНИЙ
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * V1 — legacy (CryptoJS-совместимый, все старые чаты):
 *   base64( "Salted__" + salt[8] + ciphertext )
 *   Ключ: EVP_BytesToKey(MD5, 1 итерация)
 *   Шифр: AES-256-CBC (без аутентификации — только для чтения старых данных)
 *
 * V2 — Argon2id (чаты без forward secrecy):
 *   "$A2$" + base64( iv[16] + ciphertext )
 *   Ключ: Argon2id(password, salt=SHA256("atrum_argon2_v1:"+chatId)[0:16], m=64МиБ, t=3, p=1)
 *   Шифр: AES-256-CBC (без аутентификации — только для чтения старых данных)
 *   ⚠️ Устарел — новые сообщения пишутся в V4.
 *
 * V3 — ECDH + HKDF (forward secrecy):
 *   "$S1$" + base64( iv[16] + ciphertext )
 *   Ключ: HKDF-SHA256(X25519(myPrivKey, partnerPubKey), salt=SHA256("atrum_session_v1:"+chatId)[32 байта])
 *   Шифр: AES-256-CBC (без аутентификации — только для чтения старых данных)
 *   ⚠️ Устарел — новые ECDH-сообщения пишутся в V4-S (GCM).
 *
 * V4 — Argon2id + AES-GCM (текущий формат по умолчанию):
 *   "$G4$" + base64( nonce[12] + ciphertext+authtag[16] )
 *   Ключ: Argon2id(password, salt=SHA256("atrum_argon2_v1:"+chatId)[0:16], m=64МиБ, t=3, p=4)
 *   Шифр: AES-256-GCM (аутентифицированное шифрование, целостность данных гарантирована)
 *
 * V4-S — ECDH + HKDF + AES-GCM (forward secrecy, текущий формат):
 *   "$G4S$" + base64( nonce[12] + ciphertext+authtag[16] )
 *   Ключ: HKDF-SHA256(X25519(myPrivKey, partnerPubKey), salt=SHA256("atrum_session_v1:"+chatId)[32 байта])
 *   Шифр: AES-256-GCM
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * АВТООПРЕДЕЛЕНИЕ ФОРМАТА
 * ─────────────────────────────────────────────────────────────────────────────
 *   encrypt(): V4-S если сессионный ключ установлен → V4 если chatId не пуст → V1
 *   decrypt(): "$G4S$" → V4-S (GCM), "$S1$" → V3 (CBC), "$G4$" → V4 (GCM),
 *              "$A2$" → V2 (CBC), иначе → V1
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * FORWARD SECRECY — жизненный цикл сессионного ключа
 * ─────────────────────────────────────────────────────────────────────────────
 *   1. ChatActivity.onCreate  → generateEphemeralKeyPair() → pubKey в profiles.txt
 *   2. Партнёр публикует свой pubKey
 *   3. computeSessionKey(myPrivKey, partnerPubKey, chatId) → sessionKey
 *   4. setSessionKey(chatId, sessionKey)  → все новые encrypt() → V4-S
 *   5. ChatActivity.onDestroy → clearSessionKey(chatId) + wipe privKey bytes
 *   После шага 5: прошлые V4-S/V3-сообщения нечитаемы — ключа нет нигде.
 */
object CryptoHelper {

    // ─── V1 ───────────────────────────────────────────────────────────────────
    private const val V1_MAGIC    = "Salted__"
    private const val V1_SALT_LEN = 8

    // ─── V2 (устарел, только для чтения) ─────────────────────────────────────
    private const val V2_PREFIX       = "\$A2\$"
    private const val ARGON2_MEM_KB   = 65536
    private const val ARGON2_ITER     = 3
    /** p=1 для V2/V3 (backward compat, только decrypt). */
    private const val ARGON2_PARALLEL_V2 = 1

    // ─── V3 (устарел, только для чтения) ─────────────────────────────────────
    private const val V3_PREFIX = "\$S1\$"

    // ─── V4 — Argon2id + AES-GCM (текущий формат) ────────────────────────────
    private const val V4_PREFIX          = "\$G4\$"
    /** p=4 для V4 (оптимальный параллелизм на 4-ядерных устройствах). */
    private const val ARGON2_PARALLEL_V4 = 4

    // ─── V4-S — ECDH + HKDF + AES-GCM (текущий forward-secrecy формат) ───────
    private const val V4S_PREFIX = "\$G4S\$"

    // ─── GCM параметры ────────────────────────────────────────────────────────
    private const val GCM_NONCE_LEN = 12   // 96-битный nonce (рекомендован NIST SP 800-38D)
    private const val GCM_TAG_BITS  = 128  // 128-битный auth tag

    // ─── CBC параметры ────────────────────────────────────────────────────────
    private const val KEY_LEN = 32
    private const val IV_LEN  = 16

    private val secureRandom = SecureRandom()

    // ─── V2 key cache (LRU, max 3) ───────────────────────────────────────────
    private val keyCacheV2 = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ByteArray>): Boolean {
            if (size > 3) {
                eldest.value.fill(0)  // zero key bytes before eviction
                return true
            }
            return false
        }
    }
    private val keyCacheLock = Any()

    // ─── V4 key cache (LRU, max 3) ───────────────────────────────────────────
    private val keyCacheV4 = object : LinkedHashMap<String, ByteArray>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ByteArray>): Boolean {
            if (size > 3) {
                eldest.value.fill(0)  // zero key bytes before eviction
                return true
            }
            return false
        }
    }

    // ─── V3/V4-S session key store: chatId → sessionKey ─────────────────────
    private val sessionKeys     = HashMap<String, ByteArray>()
    private val sessionKeysLock = Any()

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API — шифрование / дешифрование
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Шифрует plaintext.
     * Приоритет: V4-S (сессионный ключ GCM) > V4 (Argon2id GCM) > V1 (legacy).
     */
    fun encrypt(plaintext: String, password: String, chatId: String = ""): String {
        require(chatId.isNotBlank()) { "chatId must not be blank — V1 encryption is disabled" }
        val sk = synchronized(sessionKeysLock) { sessionKeys[chatId] }
        if (sk != null) return encryptAesGcm(plaintext, sk, V4S_PREFIX)
        return encryptV4(plaintext, password, chatId)
    }

    /**
     * Шифрует метаданные ВСЕГДА через V4 (Argon2id GCM), никогда V4-S.
     *
     * Используется для profiles.txt — файла который обязан читаться при каждом
     * открытии чата, включая новые сессии после уничтожения V4-S ключа.
     */
    fun encryptMetadata(plaintext: String, password: String, chatId: String): String =
        encryptV4(plaintext, password, chatId)

    /**
     * Расшифровывает сообщение. Формат определяется автоматически по префиксу.
     * V4-S/V3-сообщения возвращают null если сессионный ключ уже уничтожен — это норма
     * при forward secrecy: сообщения прошлой сессии становятся нечитаемыми.
     */
    fun decrypt(ciphertextB64: String, password: String, chatId: String = ""): String? {
        val s = ciphertextB64.trim()
        return when {
            s.startsWith(V4S_PREFIX) -> {
                val sk = synchronized(sessionKeysLock) { sessionKeys[chatId] }
                if (sk != null) decryptAesGcm(s, sk, V4S_PREFIX) else null
            }
            s.startsWith(V3_PREFIX) -> {
                // Старый V3 (CBC) — читаем для backward compat
                val sk = synchronized(sessionKeysLock) { sessionKeys[chatId] }
                if (sk != null) decryptAesCbc(s, sk, V3_PREFIX) else null
            }
            s.startsWith(V4_PREFIX) -> decryptV4(s, password, chatId)
            s.startsWith(V2_PREFIX) -> decryptV2(s, password, chatId)
            else                    -> decryptV1(s, password)
        }
    }

    /**
     * Прогревает Argon2-кеш (V4) в фоне чтобы убрать фриз первого сообщения.
     * Вызывать из Dispatchers.Default сразу после открытия чата.
     */
    fun warmUp(password: String, chatId: String) {
        if (chatId.isNotEmpty()) {
            getOrDeriveArgon2KeyV4(password, chatId)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API — forward secrecy (V4-S / V3)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Генерирует одноразовую X25519 пару ключей для текущей сессии.
     * @return Pair(privKeyBytes, pubKeyBase64)
     */
    fun generateEphemeralKeyPair(): Pair<ByteArray, String> {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(secureRandom))
        val kp      = gen.generateKeyPair()
        val privKey = (kp.private  as X25519PrivateKeyParameters).encoded
        val pubKeyB64 = Base64.encodeToString(
            (kp.public as X25519PublicKeyParameters).encoded, Base64.NO_WRAP
        )
        return privKey to pubKeyB64
    }

    /**
     * Вычисляет сессионный ключ через ECDH + HKDF.
     *
     * X25519(myPrivKey, partnerPubKey) → 32-байтный общий секрет
     * HKDF-SHA256(sharedSecret, salt=SHA256("atrum_session_v1:"+chatId)[32 байта]) → 32-байтный AES-ключ
     *
     * Обе стороны чата получают одинаковый результат — это математическое свойство ECDH.
     * @return 32-байтный ключ или null при ошибке (повреждённые данные, несовместимые ключи).
     */
    fun computeSessionKey(myPrivKeyBytes: ByteArray, partnerPubKeyBase64: String, chatId: String): ByteArray? {
        return try {
            val myPriv    = X25519PrivateKeyParameters(myPrivKeyBytes, 0)
            val partnerPub = X25519PublicKeyParameters(
                Base64.decode(partnerPubKeyBase64, Base64.NO_WRAP), 0
            )

            // ECDH
            val agreement    = X25519Agreement()
            agreement.init(myPriv)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(partnerPub, sharedSecret, 0)

            // HKDF-SHA256 — полный 32-байтный SHA-256 в качестве соли (не усечённый)
            val salt = MessageDigest.getInstance("SHA-256")
                .digest("atrum_session_v1:$chatId".toByteArray(Charsets.UTF_8))
            val info = "atrum_session_key".toByteArray(Charsets.UTF_8)

            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, salt, info))
            val sessionKey = ByteArray(KEY_LEN)
            hkdf.generateBytes(sessionKey, 0, KEY_LEN)

            sharedSecret.fill(0)  // зачищаем промежуточный секрет
            sessionKey
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Регистрирует сессионный ключ для чата. После этого все encrypt() → V4-S (GCM).
     * Вызывать из ChatActivity после успешного ECDH-рукопожатия.
     */
    fun setSessionKey(chatId: String, key: ByteArray) {
        synchronized(sessionKeysLock) { sessionKeys[chatId] = key.clone() }
    }

    /**
     * Удаляет сессионный ключ и обнуляет байты в памяти.
     * Вызывать из ChatActivity.onDestroy() — это момент утраты forward secrecy.
     */
    fun clearSessionKey(chatId: String) {
        synchronized(sessionKeysLock) { sessionKeys.remove(chatId)?.fill(0) }
    }

    /**
     * Проверяет есть ли в тексте V4-S/V3-строки без активного сессионного ключа.
     * Используется в ChatActivity чтобы показать баннер о forward secrecy.
     */
    fun hasLockedV3Messages(content: String, chatId: String): Boolean {
        val hasSessionKey = synchronized(sessionKeysLock) { sessionKeys.containsKey(chatId) }
        if (hasSessionKey) return false
        return content.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith(V4S_PREFIX) || t.startsWith(V3_PREFIX)
        }
    }

    /**
     * Вычисляет короткий код сверки (fingerprint) из сессионного ключа.
     * SHA-256(key)[0:20] → 5 групп по 8 hex-символов, 160 бит.
     * Обе стороны вычисляют одинаковый код из одного сессионного ключа.
     * Если код у обоих совпадает — MITM исключён.
     */
    fun computeFingerprint(sessionKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(sessionKey)
        return buildString {
            for (i in 0 until 20) {
                if (i > 0 && i % 4 == 0) append(" · ")
                append("%02X".format(hash[i]))
            }
        }
    }

    /** Удаляет Argon2-ключ из всех кешей. Вызывать из ChatActivity.onDestroy(). */
    fun clearCachedKey(chatId: String, password: String) {
        val ck = buildCacheKey(chatId, password)
        synchronized(keyCacheLock) {
            keyCacheV2.remove(ck)
            keyCacheV4.remove(ck)
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — AES-GCM helpers (V4, V4-S)
    // ═════════════════════════════════════════════════════════════════════════

    /** Шифрует с произвольным ключом через AES-256-GCM, добавляет [prefix] перед base64. */
    private fun encryptAesGcm(plaintext: String, key: ByteArray, prefix: String): String {
        val nonce = ByteArray(GCM_NONCE_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // ct содержит ciphertext + 16-байтный auth tag в конце
        val body = ByteArray(GCM_NONCE_LEN + ct.size)
        System.arraycopy(nonce, 0, body, 0, GCM_NONCE_LEN)
        System.arraycopy(ct,    0, body, GCM_NONCE_LEN, ct.size)
        return prefix + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    /** Расшифровывает AES-256-GCM, снимает [prefix]. Аутентификация проверяется автоматически. */
    private fun decryptAesGcm(ciphertextB64: String, key: ByteArray, prefix: String): String? {
        return try {
            val body = Base64.decode(ciphertextB64.removePrefix(prefix), Base64.DEFAULT)
            // минимальный размер: nonce[12] + хотя бы 1 байт + authtag[16]
            if (body.size < GCM_NONCE_LEN + GCM_TAG_BITS / 8 + 1) return null
            val nonce = body.copyOfRange(0, GCM_NONCE_LEN)
            val ct    = body.copyOfRange(GCM_NONCE_LEN, body.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — AES-CBC helpers (V2, V3 — только для чтения устаревших данных)
    // ═════════════════════════════════════════════════════════════════════════

    /** Шифрует с произвольным ключом через AES-256-CBC, добавляет [prefix] перед base64.
     *  Используется ТОЛЬКО для encryptV2() (обратная совместимость). */
    private fun encryptAesCbc(plaintext: String, key: ByteArray, prefix: String): String {
        val iv = ByteArray(IV_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val body = ByteArray(IV_LEN + ct.size)
        System.arraycopy(iv, 0, body, 0, IV_LEN)
        System.arraycopy(ct, 0, body, IV_LEN, ct.size)
        return prefix + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    /** Расшифровывает AES-256-CBC, снимает [prefix]. Только для чтения V2/V3. */
    private fun decryptAesCbc(ciphertextB64: String, key: ByteArray, prefix: String): String? {
        return try {
            val body = Base64.decode(ciphertextB64.removePrefix(prefix), Base64.DEFAULT)
            if (body.size < IV_LEN + 16) return null
            val iv = body.copyOfRange(0, IV_LEN)
            val ct = body.copyOfRange(IV_LEN, body.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V4 — Argon2id + AES-GCM (текущий формат)
    // ═════════════════════════════════════════════════════════════════════════

    private fun encryptV4(plaintext: String, password: String, chatId: String): String =
        encryptAesGcm(plaintext, getOrDeriveArgon2KeyV4(password, chatId), V4_PREFIX)

    private fun decryptV4(ciphertextB64: String, password: String, chatId: String): String? =
        decryptAesGcm(ciphertextB64, getOrDeriveArgon2KeyV4(password, chatId), V4_PREFIX)

    private fun getOrDeriveArgon2KeyV4(password: String, chatId: String): ByteArray {
        val cacheKey = buildCacheKey(chatId, password)
        synchronized(keyCacheLock) {
            keyCacheV4[cacheKey]?.let { return it }
            val key = deriveArgon2Key(password, chatId, ARGON2_PARALLEL_V4)
            keyCacheV4[cacheKey] = key
            return key
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V2 — Argon2id + AES-CBC (устарел, только для чтения)
    // ═════════════════════════════════════════════════════════════════════════

    private fun encryptV2(plaintext: String, password: String, chatId: String): String =
        encryptAesCbc(plaintext, getOrDeriveArgon2KeyV2(password, chatId), V2_PREFIX)

    private fun decryptV2(ciphertextB64: String, password: String, chatId: String): String? =
        decryptAesCbc(ciphertextB64, getOrDeriveArgon2KeyV2(password, chatId), V2_PREFIX)

    private fun getOrDeriveArgon2KeyV2(password: String, chatId: String): ByteArray {
        val cacheKey = buildCacheKey(chatId, password)
        synchronized(keyCacheLock) {
            keyCacheV2[cacheKey]?.let { return it }
            val key = deriveArgon2Key(password, chatId, ARGON2_PARALLEL_V2)
            keyCacheV2[cacheKey] = key
            return key
        }
    }

    private fun deriveArgon2Key(password: String, chatId: String, parallelism: Int): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest("atrum_argon2_v1:$chatId".toByteArray(Charsets.UTF_8))
            .copyOf(16)

        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(ARGON2_MEM_KB)
            .withIterations(ARGON2_ITER)
            .withParallelism(parallelism)
            .build()

        val gen = Argon2BytesGenerator()
        gen.init(params)
        val key = ByteArray(KEY_LEN)
        gen.generateBytes(password.toByteArray(Charsets.UTF_8), key)
        return key
    }

    /**
     * Безопасный ключ кеша: SHA-256(chatId + ":" + password) в hex.
     * Не раскрывает пароль через hashCode() collision.
     */
    private fun buildCacheKey(chatId: String, password: String): String {
        val data = "$chatId:$password".toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V1 — legacy EVP_BytesToKey / CryptoJS
    // ═════════════════════════════════════════════════════════════════════════

    private fun encryptV1(plaintext: String, password: String): String {
        val salt = ByteArray(V1_SALT_LEN).also { secureRandom.nextBytes(it) }
        val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val out = ByteArray(V1_MAGIC.length + salt.size + ct.size)
        System.arraycopy(V1_MAGIC.toByteArray(Charsets.US_ASCII), 0, out, 0, V1_MAGIC.length)
        System.arraycopy(salt, 0, out, V1_MAGIC.length, salt.size)
        System.arraycopy(ct,   0, out, V1_MAGIC.length + salt.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decryptV1(ciphertextB64: String, password: String): String? {
        return try {
            val raw = Base64.decode(ciphertextB64, Base64.DEFAULT)
            if (raw.size < V1_MAGIC.length + V1_SALT_LEN) return null
            if (String(raw.copyOfRange(0, V1_MAGIC.length), Charsets.US_ASCII) != V1_MAGIC) return null

            val salt = raw.copyOfRange(V1_MAGIC.length, V1_MAGIC.length + V1_SALT_LEN)
            val ct   = raw.copyOfRange(V1_MAGIC.length + V1_SALT_LEN, raw.size)
            val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** EVP_BytesToKey(MD5, 1 итерация) — точная копия алгоритма CryptoJS. */
    private fun evpBytesToKey(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5      = MessageDigest.getInstance("MD5")
        val totalLen = KEY_LEN + IV_LEN
        val buf      = ByteArray(totalLen)
        var generated = 0
        var prev      = ByteArray(0)
        while (generated < totalLen) {
            md5.reset(); md5.update(prev); md5.update(password); md5.update(salt)
            prev = md5.digest()
            val take = minOf(prev.size, totalLen - generated)
            System.arraycopy(prev, 0, buf, generated, take)
            generated += take
        }
        return buf.copyOfRange(0, KEY_LEN) to buf.copyOfRange(KEY_LEN, totalLen)
    }
}
