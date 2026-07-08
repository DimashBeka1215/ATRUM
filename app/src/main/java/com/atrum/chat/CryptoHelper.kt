package com.atrum.chat

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
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
 * V5 — Argon2id + AES-GCM + Random Salt (улучшенный формат по умолчанию):
 *   "$G5$" + base64( salt[16] + nonce[12] + ciphertext+authtag[16] )
 *   Ключ: Argon2id(password, salt=random[16], m=64МиБ, t=3, p=4)
 *   Шифр: AES-256-GCM
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * АВТООПРЕДЕЛЕНИЕ ФОРМАТА
 * ─────────────────────────────────────────────────────────────────────────────
 *   encrypt(): V4-S если сессионный ключ установлен → V5 (Argon2 + Random Salt)
 *   decrypt(): "$G5$" → V5 (GCM), "$G4S$" → V4-S (GCM), "$S1$" → V3 (CBC),
 *              "$G4$" → V4 (GCM), "$A2$" → V2 (CBC), иначе → V1
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

    // ─── V5 — Argon2id + AES-GCM + Random Salt (текущий формат) ──────────────

    private fun encryptV5(plaintext: String, password: String): String {
        val salt = ByteArray(SALT_LEN).also { secureRandom.nextBytes(it) }
        val key = deriveArgon2KeyWithSalt(password, salt, ARGON2_PARALLEL_V4)
        val nonce = ByteArray(GCM_NONCE_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(padBytes(plaintext.toByteArray(Charsets.UTF_8)))

        // Format: salt[16] + nonce[12] + ciphertext + tag[16]
        val body = ByteArray(SALT_LEN + GCM_NONCE_LEN + ct.size)
        System.arraycopy(salt,  0, body, 0, SALT_LEN)
        System.arraycopy(nonce, 0, body, SALT_LEN, GCM_NONCE_LEN)
        System.arraycopy(ct,    0, body, SALT_LEN + GCM_NONCE_LEN, ct.size)
        key.fill(0) // Очищаем ключ сразу после использования
        return V5_PREFIX + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decryptV5(ciphertextB64: String, password: String): String? {
        return try {
            val body = Base64.decode(ciphertextB64.removePrefix(V5_PREFIX), Base64.DEFAULT)
            if (body.size < SALT_LEN + GCM_NONCE_LEN + GCM_TAG_BITS / 8) return null
            val salt = body.copyOfRange(0, SALT_LEN)
            val nonce = body.copyOfRange(SALT_LEN, SALT_LEN + GCM_NONCE_LEN)
            val ct = body.copyOfRange(SALT_LEN + GCM_NONCE_LEN, body.size)

            val key = deriveArgon2KeyWithSalt(password, salt, ARGON2_PARALLEL_V4)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val pt = String(unpadBytes(cipher.doFinal(ct)), Charsets.UTF_8)
            key.fill(0)
            pt
        } catch (_: Exception) {
            null
        }
    }

    private fun deriveArgon2KeyWithSalt(password: String, salt: ByteArray, parallelism: Int): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(ARGON2_MEM_KB)
            .withIterations(ARGON2_ITER)
            .withParallelism(parallelism)
            .build()
        val pwBytes = password.toByteArray(Charsets.UTF_8)
        try { return runArgon2(params, pwBytes) } finally { pwBytes.fill(0) } // стираем байтовую копию пароля
    }

    // ─── Запуск Argon2id с ограничением параллелизма по памяти ───────────────
    // Argon2id(m=64МиБ) аллоцирует ~64МБ блоков НА КАЖДЫЙ вызов. V5 (random salt)
    // деривирует ключ на каждое сообщение, поэтому при открытии чата много 64-МБ
    // аллокаций → риск OutOfMemoryError. Семафор argon2Semaphore ограничивает число
    // одновременных деривas: 1 на бюджетных (heap ~128МБ, прежнее анти-OOM поведение),
    // 2–3 на устройствах с большим heap → быстрее холодная загрузка истории.
    // Параллелизм Argon2id ограничен по объёму heap (каждый прогон ~64МиБ). На устройствах
    // с большим heap (largeHeap) разрешаем 2–3 параллельных прогона → быстрее холодная
    // загрузка чата (V5 деривирует ключ на КАЖДОЕ сообщение). На бюджетных (heap ~128МБ)
    // остаётся 1 — прежнее анти-OOM поведение. acquireUninterruptibly — как старый synchronized.
    private val argon2Permits: Int = run {
        val maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L)
        when {
            maxMb >= 512 -> 3
            maxMb >= 256 -> 2
            else -> 1
        }
    }
    private val argon2Semaphore = java.util.concurrent.Semaphore(argon2Permits)

    private fun runArgon2(params: Argon2Parameters, passwordBytes: ByteArray): ByteArray {
        argon2Semaphore.acquireUninterruptibly()
        try {
            var attempt = 0
            while (true) {
                try {
                    val gen = Argon2BytesGenerator()
                    gen.init(params)
                    val key = ByteArray(KEY_LEN)
                    gen.generateBytes(passwordBytes, key)
                    return key
                } catch (e: OutOfMemoryError) {
                    // Память фрагментирована — отдаём GC шанс собрать блоки и пробуем ещё раз.
                    if (attempt++ >= 1) throw e
                    System.gc()
                    try { Thread.sleep(120) } catch (_: InterruptedException) {}
                }
            }
        } finally {
            argon2Semaphore.release()
        }
    }

    // ─── V4 — Argon2id + AES-GCM (legacy GCM формат) ─────────────────────────
    private const val V4_PREFIX          = "\$G4\$"
    /** p=4 для V4 (оптимальный параллелизм на 4-ядерных устройствах). */
    private const val ARGON2_PARALLEL_V4 = 4

    // ─── V5 — Argon2id + AES-GCM + Random Salt (текущий формат) ──────────────
    private const val V5_PREFIX = "\$G5\$"
    private const val SALT_LEN  = 16

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

    // ─── V3/V4-S session key store: chatId → кольцо сессионных ключей ─────────
    // Кольцо (текущий спереди + несколько предыдущих) нужно для ротации: новые
    // сообщения шифруются ТЕКУЩИМ ключом, а присланные в окне рукопожатия под
    // прошлым ключом ещё расшифровываются. Вытесненные ключи затираются.
    private const val SESSION_RING_MAX = 3
    private val sessionKeys     = HashMap<String, ArrayDeque<ByteArray>>()
    private val sessionKeysLock = Any()

    // ─── Хук локального шифр-архива истории (forward secrecy + сохранение истории) ──
    interface FsArchiveHook {
        fun remember(chatId: String, ciphertext: String, plaintext: String)
        fun recall(chatId: String, ciphertext: String): String?
    }
    @Volatile private var fsArchive: FsArchiveHook? = null
    fun setArchive(hook: FsArchiveHook?) { fsArchive = hook }

    // ─── Кэш результатов расшифровки (анти-шторм Argon2id) ──────────────────────
    // V5/V4/V2 деривируют Argon2id (64 МБ) НА КАЖДЫЙ вызов. При перезаходе/опросе
    // вся история расшифровывается заново → лавина 64МБ-аллокаций → GC-фриз/OOM.
    // Шифртекст детерминированно даёт один и тот же plaintext (пароль/chatId стабильны),
    // поэтому мемоизируем результат. Сессионные V4-S/V3 НЕ кэшируем — их читаемость
    // зависит от живого сессионного ключа (forward secrecy).
    private const val DECRYPT_CACHE_MAX = 1500 // ↑ с 600: меньше повторных Argon2 на больших чатах (память ≈ сотни КБ)
    private val decryptCacheLock = Any()
    private val decryptCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean = size > DECRYPT_CACHE_MAX
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC API — шифрование / дешифрование
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Шифрует plaintext.
     * Приоритет: V4-S (сессионный ключ GCM) > V5 (Argon2id GCM + Random Salt).
     */
    fun encrypt(plaintext: String, password: String, chatId: String = ""): String {
        require(chatId.isNotBlank()) { "chatId must not be blank" }
        val sk = synchronized(sessionKeysLock) { sessionKeys[chatId]?.firstOrNull() }
        if (sk != null) return encryptAesGcm(plaintext, sk, V4S_PREFIX)
        return encryptV5(plaintext, password)
    }

    /**
     * Шифрует метаданные (профили/presence/reactions) через V4 (Argon2id GCM с солью
     * от chatId) — НЕ через V5.
     *
     * ⚠️ ПРОИЗВОДИТЕЛЬНОСТЬ: presence/profiles.txt переписывается каждые ~2с, и партнёр
     * расшифровывает их КАЖДЫЙ тик опроса. У V5 соль СЛУЧАЙНА и зашита в каждый шифртекст,
     * поэтому ни ключ, ни результат закэшировать нельзя → полный Argon2id (64 МБ) на каждый
     * тик → беспрерывный GC и фризы UI на 5–8с (видно в логах: "GC freed 66MB", "Skipped
     * 617 frames"). У V4 соль детерминирована от chatId → ключ Argon2 деривируется ОДИН раз
     * и переиспользуется (getOrDeriveArgon2KeyV4 кэширует) → расшифровка presence почти
     * бесплатна. Конфиденциальность сохраняется (GCM со случайным nonce на каждое шифрование),
     * а источник соли возвращается к channelId — как и требуют правила крипто (§1).
     *
     * Обратная совместимость: decrypt() определяет формат по префиксу и по-прежнему читает
     * старые V5-метаданные, так что смешанные версии у двух телефонов работают.
     */
    fun encryptMetadata(plaintext: String, password: String, chatId: String): String =
        encryptV4(plaintext, password, chatId)

    /**
     * Шифрует строку-анонс сообщения (текст/подпись к медиа) в ГРУППОВОМ чате.
     *
     * ⚠️ ПРОИЗВОДИТЕЛЬНОСТЬ (ADR_GROUP_CHATS.md §Технический долг): группы НИКОГДА не
     * устанавливают forward-secrecy сессионный ключ (осознанное ограничение — общий
     * пароль V5 навсегда), поэтому обычный [encrypt] для группового чата всегда падает
     * на V5-фоллбэк: Argon2id (64 МБ) со СЛУЧАЙНОЙ солью на КАЖДЫЙ вызов → ключ нельзя
     * закэшировать → полный пересчёт на каждое отправленное сообщение (часики висят,
     * пока не досчитается — в 1:1 после рукопожатия используется быстрый сессионный
     * ключ, поэтому там мгновенно). Здесь — тот же приём, что уже применяется в группах
     * для profiles.txt/members.txt ([encryptMetadata]): V4 с детерминированной солью от
     * chatId, ключ Argon2 кэшируется (getOrDeriveArgon2KeyV4) → отправка почти бесплатна.
     *
     * Компромисс: соль перестаёт быть случайной на каждое сообщение (защита от
     * precompute-атак). Осознанно принят — группа и так без forward secrecy и с общим
     * паролем у ВСЕХ участников (см. ADR), так что это не меняет реальную модель угроз.
     * decrypt() как обычно определяет формат по префиксу — на приёме менять ничего не нужно.
     */
    fun encryptGroupMessage(plaintext: String, password: String, chatId: String): String =
        encryptV4(plaintext, password, chatId)

    /**
     * Шифрует ДОЛГОВЕЧНЫЙ медиа-контент (фото/голос/стикеры/превью ссылок/манифест чанков).
     *
     * ⚠️ НЕ через [encrypt]: тот при активной forward-secrecy сессии шифрует ЭФЕМЕРНЫМ
     * сессионным ключом (V4-S), который живёт только в памяти и ротируется. Текст читается
     * сразу в живой сессии — ок. А медиа долговечно: получатель грузит файл ОТДЕЛЬНЫМ
     * запросом и часто ПОЗЖЕ (перемотка истории, перезагрузка, повторный тап) — к этому
     * моменту сессионный ключ уже другой, и V4-S не расшифровать → diagnose показывает
     * «найден, но не расшифровался», пузырёк фото пустой, голос не грузится.
     *
     * V4 даёт ДЕТЕРМИНИРОВАННЫЙ ключ Argon2id от (password, соль=chatId): оба телефона
     * выводят его одинаково, пока известен пароль чата — независимо от эфемерной сессии.
     * Источник соли (chatId) НЕ меняется (см. §1). decrypt() читает V4 автоматически по
     * префиксу, поэтому на стороне получателя править ничего не нужно. Forward secrecy для
     * САМИХ СООБЩЕНИЙ (текст) сохраняется — они по-прежнему идут через [encrypt].
     */
    fun encryptFileContent(plaintext: String, password: String, chatId: String): String =
        encryptV4(plaintext, password, chatId)

    /**
     * Расшифровывает сообщение. Формат определяется автоматически по префиксу.
     * V4-S/V3-сообщения возвращают null если сессионный ключ уже уничтожен — это норма
     * при forward secrecy: сообщения прошлой сессии становятся нечитаемыми.
     */
    fun decrypt(ciphertextB64: String, password: String, chatId: String = ""): String? {
        val s = ciphertextB64.trim()
        // Сессионные форматы зависят от живого ключа сессии — их не кэшируем.
        val isSession = s.startsWith(V4S_PREFIX) || s.startsWith(V3_PREFIX)
        val cacheKey = if (isSession) null else chatId + "\u0000" + s
        if (cacheKey != null) {
            synchronized(decryptCacheLock) { decryptCache[cacheKey] }?.let { return it }
        }
        var result = when {
            s.startsWith(V5_PREFIX)  -> decryptV5(s, password)
            s.startsWith(V4S_PREFIX) -> decryptSessionMulti(s, chatId, V4S_PREFIX, gcm = true)
            s.startsWith(V3_PREFIX)  -> decryptSessionMulti(s, chatId, V3_PREFIX, gcm = false)
            s.startsWith(V4_PREFIX) -> decryptV4(s, password, chatId)
            s.startsWith(V2_PREFIX) -> decryptV2(s, password, chatId)
            else                    -> decryptV1(s, password)
        }
        // FS-архив: успешную расшифровку запоминаем локально; если сессионный ключ
        // уже ротирован и живой расшифровки нет — достаём текст из архива.
        if (isSession) {
            if (result != null) fsArchive?.remember(chatId, s, result)
            else result = fsArchive?.recall(chatId, s)
        }
        if (cacheKey != null && result != null) {
            synchronized(decryptCacheLock) { decryptCache[cacheKey] = result }
        }
        return result
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
     * Устанавливает сессионный ключ из эфемерных ключей, ЕСЛИ он ещё не установлен.
     * Нужен фоновым контекстам (служба уведомлений, опрос списка чатов), чтобы они
     * могли расшифровать V4-S сообщения собеседника (иначе непрочитанные/превью/пуши
     * не видят forward-secrecy сообщений). Дёшево: X25519 ECDH + HKDF, без Argon2.
     */
    fun ensureSessionKey(chatId: String, myEphemeralPriv: ByteArray?, partnerEphemeralPub: String?) {
        if (hasSessionKey(chatId)) return
        if (myEphemeralPriv == null || partnerEphemeralPub.isNullOrBlank()) return
        computeSessionKey(myEphemeralPriv, partnerEphemeralPub, chatId)?.let {
            setSessionKey(chatId, it)
            it.fill(0)
        }
    }

    /**
     * Регистрирует сессионный ключ для чата. После этого все encrypt() → V4-S (GCM).
     * Вызывать из ChatActivity после успешного ECDH-рукопожатия.
     */
    fun setSessionKey(chatId: String, key: ByteArray) {
        synchronized(sessionKeysLock) {
            val ring = sessionKeys.getOrPut(chatId) { ArrayDeque() }
            if (ring.isNotEmpty() && ring.first().contentEquals(key)) return  // уже текущий
            ring.addFirst(key.clone())
            while (ring.size > SESSION_RING_MAX) ring.removeLast().fill(0)
        }
    }

    /**
     * Удаляет сессионный ключ и обнуляет байты в памяти.
     * Вызывать из ChatActivity.onDestroy() — это момент утраты forward secrecy.
     */
    fun clearSessionKey(chatId: String) {
        synchronized(sessionKeysLock) { sessionKeys.remove(chatId)?.forEach { it.fill(0) } }
    }

    /**
     * Проверяет есть ли в тексте V4-S/V3-строки без активного сессионного ключа.
     * Используется в ChatActivity чтобы показать баннер о forward secrecy.
     */
    fun hasLockedV3Messages(content: String, chatId: String): Boolean {
        val hasSessionKey = synchronized(sessionKeysLock) { sessionKeys[chatId]?.isNotEmpty() == true }
        if (hasSessionKey) return false
        return content.lineSequence().any { line ->
            val t = line.trim()
            t.startsWith(V4S_PREFIX) || t.startsWith(V3_PREFIX)
        }
    }

    /** Установлен ли сессионный ключ (V4-S) для чата. */
    fun hasSessionKey(chatId: String): Boolean =
        synchronized(sessionKeysLock) { sessionKeys[chatId]?.isNotEmpty() == true }

    fun getSessionKeyFingerprint(chatId: String): String? {
        synchronized(sessionKeysLock) {
            val key = sessionKeys[chatId]?.firstOrNull() ?: return null
            return computeFingerprint(key)
        }
    }

    // ─── Identity Key (Ed25519) — подпись эфемерных ключей (защита от MITM) ──

    /** Генерирует долговременную Ed25519 пару идентичности. @return (privBytes, pubB64). */
    fun generateIdentityKeyPair(): Pair<ByteArray, String> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(secureRandom))
        val kp = gen.generateKeyPair()
        val priv = (kp.private as Ed25519PrivateKeyParameters).encoded
        val pubB64 = Base64.encodeToString(
            (kp.public as Ed25519PublicKeyParameters).encoded, Base64.NO_WRAP
        )
        return priv to pubB64
    }

    /** Подписывает данные приватным identity-ключом. @return подпись base64 или null. */
    fun signWithIdentity(privKeyBytes: ByteArray, data: ByteArray): String? {
        return try {
            val priv = Ed25519PrivateKeyParameters(privKeyBytes, 0)
            val signer = Ed25519Signer()
            signer.init(true, priv)
            signer.update(data, 0, data.size)
            Base64.encodeToString(signer.generateSignature(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    /** Проверяет подпись публичным identity-ключом партнёра. */
    fun verifyIdentitySignature(pubKeyB64: String, data: ByteArray, sigB64: String): Boolean {
        return try {
            val pub = Ed25519PublicKeyParameters(Base64.decode(pubKeyB64, Base64.NO_WRAP), 0)
            val signer = Ed25519Signer()
            signer.init(false, pub)
            signer.update(data, 0, data.size)
            signer.verifySignature(Base64.decode(sigB64, Base64.NO_WRAP))
        } catch (_: Exception) {
            false
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

    // ─── «Тёплое» удержание выведенных ключей 30 минут ───────────────────────
    // При выходе из чата НЕ чистим Argon2-кеш сразу, а откладываем на 30 минут:
    // переоткрытие в течение окна не запускает заново тяжёлый Argon2id (64 МиБ),
    // чат открывается мгновенно. Это НЕ касается forward secrecy — эфемерный
    // сессионный ключ (V3/V4-S) по-прежнему стирается немедленно в onDestroy.
    const val WARM_RETENTION_MS = 30 * 60 * 1000L
    private val warmScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "atrum-warm-clear").apply { isDaemon = true }
    }
    private val pendingClears =
        java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.ScheduledFuture<*>>()

    /** Отложить очистку Argon2-кеша чата на [WARM_RETENTION_MS] вместо немедленной. */
    fun scheduleClearCachedKey(chatId: String, password: String) {
        cancelScheduledClear(chatId)
        val f = warmScheduler.schedule({
            clearCachedKey(chatId, password)
            pendingClears.remove(chatId)
        }, WARM_RETENTION_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        pendingClears[chatId] = f
    }

    /** Отменить отложенную очистку (чат снова открыт — кеш ещё нужен). */
    fun cancelScheduledClear(chatId: String) {
        pendingClears.remove(chatId)?.cancel(false)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — Паддинг размера (скрытие длины сообщения от реле)
    // ═════════════════════════════════════════════════════════════════════════
    // Длина события напрямую выдаёт длину текста. Добиваем открытый текст до
    // фиксированной "корзины" ПЕРЕД шифрованием и отрезаем ПОСЛЕ расшифровки —
    // паддинг попадает под GCM, реле видит только выровненный размер.
    // Обратная совместимость: старые сообщения без маркера возвращаются как есть.
    private val PAD_MAGIC = byteArrayOf(0x01, 0x41, 0x50, 0x01) // SOH A P SOH — не бывает в начале UTF-8 текста

    private fun bucketFor(n: Int): Int = when {
        n <= 128  -> 128
        n <= 512  -> 512
        n <= 2048 -> 2048
        else      -> ((n / 2048) + 1) * 2048
    }

    /** MAGIC(4) + LEN(4, big-endian) + plaintext + нулевая добивка до корзины. */
    private fun padBytes(pt: ByteArray): ByteArray {
        val bucket = bucketFor(pt.size)
        val out = ByteArray(PAD_MAGIC.size + 4 + bucket)
        System.arraycopy(PAD_MAGIC, 0, out, 0, PAD_MAGIC.size)
        val off = PAD_MAGIC.size
        out[off]     = ((pt.size ushr 24) and 0xFF).toByte()
        out[off + 1] = ((pt.size ushr 16) and 0xFF).toByte()
        out[off + 2] = ((pt.size ushr 8) and 0xFF).toByte()
        out[off + 3] = (pt.size and 0xFF).toByte()
        System.arraycopy(pt, 0, out, off + 4, pt.size)
        return out
    }

    /** Снимает паддинг если в начале стоит MAGIC; иначе возвращает как есть (back-compat). */
    private fun unpadBytes(b: ByteArray): ByteArray {
        if (b.size < PAD_MAGIC.size + 4) return b
        for (i in PAD_MAGIC.indices) if (b[i] != PAD_MAGIC[i]) return b
        val off = PAD_MAGIC.size
        val len = ((b[off].toInt() and 0xFF) shl 24) or
                  ((b[off + 1].toInt() and 0xFF) shl 16) or
                  ((b[off + 2].toInt() and 0xFF) shl 8) or
                  (b[off + 3].toInt() and 0xFF)
        if (len < 0 || len > b.size - (off + 4)) return b // повреждено — fail-safe
        return b.copyOfRange(off + 4, off + 4 + len)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL — AES-GCM helpers (V4, V4-S)
    // ═════════════════════════════════════════════════════════════════════════

    /** Пробует ВСЕ ключи кольца сессии (текущий + предыдущие) — GCM/CBC сам отбракует неверные. */
    private fun decryptSessionMulti(s: String, chatId: String, prefix: String, gcm: Boolean): String? {
        val ring = synchronized(sessionKeysLock) { sessionKeys[chatId]?.toList() } ?: return null
        for (k in ring) {
            val pt = if (gcm) decryptAesGcm(s, k, prefix) else decryptAesCbc(s, k, prefix)
            if (pt != null) return pt
        }
        return null
    }

    /** Шифрует с произвольным ключом через AES-256-GCM, добавляет [prefix] перед base64. */
    private fun encryptAesGcm(plaintext: String, key: ByteArray, prefix: String): String {
        val nonce = ByteArray(GCM_NONCE_LEN).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ct = cipher.doFinal(padBytes(plaintext.toByteArray(Charsets.UTF_8)))
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
            String(unpadBytes(cipher.doFinal(ct)), Charsets.UTF_8)
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

    /**
     * ОТЛАДКА: расшифровывает V4-контент и возвращает текстовую причину провала
     * (исключение GCM, размеры base64), вместо тихого null. Только для диагностики медиа.
     */
    /** Хеш V4-ключа (Argon2 от password+chatId) — для сверки ключа между устройствами. */
    fun v4KeyHash(password: String, chatId: String): String {
        val k = getOrDeriveArgon2KeyV4(password, chatId)
        return MessageDigest.getInstance("SHA-256").digest(k)
            .joinToString("") { "%02x".format(it) }.take(8)
    }

    fun decryptV4Diag(ciphertextB64: String, password: String, chatId: String): String {
        val s = ciphertextB64.trim()
        if (!s.startsWith(V4_PREFIX)) return "notV4(${s.take(6)})"
        val key = getOrDeriveArgon2KeyV4(password, chatId)
        val kh = MessageDigest.getInstance("SHA-256").digest(key)
            .joinToString("") { "%02x".format(it) }.take(8)
        val bodyStr = s.removePrefix(V4_PREFIX)
        val szDef = runCatching { Base64.decode(bodyStr, Base64.DEFAULT).size }.getOrDefault(-1)
        val szNW = runCatching { Base64.decode(bodyStr, Base64.NO_WRAP).size }.getOrDefault(-1)
        val body = runCatching { Base64.decode(bodyStr, Base64.DEFAULT) }.getOrElse { return "b64fail" }
        if (body.size < GCM_NONCE_LEN + GCM_TAG_BITS / 8 + 1) return "short${body.size}"
        val nonce = body.copyOfRange(0, GCM_NONCE_LEN)
        val ct = body.copyOfRange(GCM_NONCE_LEN, body.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            val pt = cipher.doFinal(ct)
            "GCMok kh=$kh ptlen=${pt.size} unpad=${unpadBytes(pt).size}"
        } catch (e: Exception) {
            "GCMfail kh=$kh ${e.javaClass.simpleName} bodysize=${body.size} ctlen=${ct.size}"
        }
    }

    private fun getOrDeriveArgon2KeyV4(password: String, chatId: String): ByteArray {
        val cacheKey = buildCacheKey(chatId, password)
        // Быстрая проверка кэша под локом.
        synchronized(keyCacheLock) { keyCacheV4[cacheKey]?.let { return it } }
        // ⚠️ Argon2id (64 МБ, сотни мс) считаем ВНЕ keyCacheLock. Раньше деривация шла
        // ВНУТРИ synchronized — и любой поток, зашедший за ключом (в т.ч. главный),
        // блокировался на всё время вычисления → "Long monitor contention" и ANR
        // ("Waited 10000ms for MotionEvent"). Теперь лок держится лишь на доли мс.
        val key = deriveArgon2Key(password, chatId, ARGON2_PARALLEL_V4)
        return synchronized(keyCacheLock) {
            // Гонка: другой поток мог уже посчитать тот же ключ — переиспользуем его.
            keyCacheV4[cacheKey] ?: key.also { keyCacheV4[cacheKey] = it }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // V2 — Argon2id + AES-CBC (устарел, только для чтения)
    // ═════════════════════════════════════════════════════════════════════════

    private fun encryptV2(plaintext: String, password: String, chatId: String): String =
        throw UnsupportedOperationException("V2 encryption is deprecated")

    private fun decryptV2(ciphertextB64: String, password: String, chatId: String): String? =
        decryptAesCbc(ciphertextB64, getOrDeriveArgon2KeyV2(password, chatId), V2_PREFIX)

    private fun getOrDeriveArgon2KeyV2(password: String, chatId: String): ByteArray {
        val cacheKey = buildCacheKey(chatId, password)
        synchronized(keyCacheLock) { keyCacheV2[cacheKey]?.let { return it } }
        // Argon2id считаем ВНЕ лока (см. getOrDeriveArgon2KeyV4) — чтобы не блокировать
        // другие потоки на время деривации.
        val key = deriveArgon2Key(password, chatId, ARGON2_PARALLEL_V2)
        return synchronized(keyCacheLock) {
            keyCacheV2[cacheKey] ?: key.also { keyCacheV2[cacheKey] = it }
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

        val pwBytes = password.toByteArray(Charsets.UTF_8)
        try { return runArgon2(params, pwBytes) } finally { pwBytes.fill(0) } // стираем байтовую копию пароля
    }

    /**
     * Расшифровка самого старого формата V1 (OpenSSL "Salted__" + AES-CBC).
     * Только для чтения старой истории. Восстановлено из git HEAD.
     */
    private fun decryptV1(ciphertextB64: String, password: String): String? {
        return try {
            val raw = Base64.decode(ciphertextB64, Base64.DEFAULT)
            if (raw.size < V1_MAGIC.length + V1_SALT_LEN) return null
            if (String(raw.copyOfRange(0, V1_MAGIC.length), Charsets.US_ASCII) != V1_MAGIC) return null

            val salt = raw.copyOfRange(V1_MAGIC.length, V1_MAGIC.length + V1_SALT_LEN)
            val ct   = raw.copyOfRange(V1_MAGIC.length + V1_SALT_LEN, raw.size)
            val pwBytes = password.toByteArray(Charsets.UTF_8)
            val (key, iv) = try { evpBytesToKey(pwBytes, salt) } finally { pwBytes.fill(0) } // стираем копию пароля
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * OpenSSL EVP_BytesToKey (MD5) — деривация ключа и IV для формата V1.
     * Восстановлено из git HEAD.
     */
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

    /**
     * Безопасный ключ кеша: SHA-256(chatId + ":" + password) в hex.
     * Не раскрывает пароль через hashCode() collision.
     */
    private fun buildCacheKey(chatId: String, password: String): String {
        val data = "$chatId:$password".toByteArray(Charsets.UTF_8)
        val hash = try { MessageDigest.getInstance("SHA-256").digest(data) } finally { data.fill(0) } // стираем копию пароля
        return hash.joinToString("") { "%02x".format(it) }
    }
}
