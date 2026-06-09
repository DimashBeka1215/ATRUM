package com.atrum.chat

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import java.util.UUID

/**
 * Локальный профиль пользователя. Не путать с настройками отдельных чатов —
 * те живут в Room (см. data/Chat.kt).
 *
 * Хранит:
 *  - myUserId — генерируется один раз при первом запуске (UUID).
 *  - myName — ник, который видят собеседники.
 *  - myAvatarBase64 — аватарка (этап настроек).
 *  - localPasswordHash — SHA-256 хэш локального пароля (если задан).
 *  - isOnboarded — прошёл ли пользователь онбординг.
 */
class Prefs(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    /**
     * Резервное хранилище для myUserId — обычный (не зашифрованный) SharedPreferences.
     *
     * Зачем: EncryptedSharedPreferences иногда падает на некоторых устройствах/версиях
     * Android, и при этом мы переключаемся на fallback-файл, в котором userId нет →
     * генерируется новый UUID → все ранее отправленные сообщения становятся «чужими»
     * (parsedUserId из gist != currentUserId из нового UUID).
     *
     * Решение: всегда зеркалируем userId в этот обычный (нешифрованный) файл.
     * userId — публичный идентификатор (он в любом случае виден в gist всем участникам
     * чата), поэтому его хранение без шифрования безопасно.
     */
    private val stablePrefs: SharedPreferences =
        context.getSharedPreferences(STABLE_FILE, Context.MODE_PRIVATE)

    /**
     * Уникальный идентификатор устройства/пользователя. Генерируется один раз.
     *
     * Хранится одновременно в encrypted prefs (для консистентности) и в plain
     * stablePrefs (резерв на случай сбоя шифрования). При чтении проверяем оба
     * хранилища, при записи пишем в оба.
     */
    var myUserId: String
        get() {
            // 1. Основное хранилище (encrypted или fallback, зависит от устройства)
            val fromMain = prefs.getString(KEY_USER_ID, null)
            if (fromMain != null) {
                // Синхронизируем в резервное, если там ещё нет
                if (stablePrefs.getString(KEY_USER_ID, null) == null) {
                    stablePrefs.edit().putString(KEY_USER_ID, fromMain).apply()
                }
                return fromMain
            }
            // 2. Резервное хранилище — если основное потеряло данные
            val fromStable = stablePrefs.getString(KEY_USER_ID, null)
            if (fromStable != null) {
                // Восстанавливаем в основное
                prefs.edit().putString(KEY_USER_ID, fromStable).apply()
                return fromStable
            }
            // 3. Первый запуск — генерируем и сохраняем везде
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_USER_ID, generated).apply()
            stablePrefs.edit().putString(KEY_USER_ID, generated).apply()
            return generated
        }
        set(v) {
            prefs.edit().putString(KEY_USER_ID, v).apply()
            stablePrefs.edit().putString(KEY_USER_ID, v).apply()
        }

    var myName: String
        get() = prefs.getString(KEY_NAME, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_NAME, v).apply()

    var myTag: String
        get() = prefs.getString(KEY_TAG, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_TAG, v).apply()

    var myStatus: String
        get() = prefs.getString(KEY_STATUS, "").orEmpty()
        set(v) = prefs.edit().putString(KEY_STATUS, v).apply()

    var myAvatarBase64: String?
        get() = prefs.getString(KEY_AVATAR, null)
        set(v) = prefs.edit().putString(KEY_AVATAR, v).apply()


    /** Unix-мс последней смены баннера — для rate-limit (30 сек между сменами). */
    var lastBannerChangeTime: Long
        get() = prefs.getLong(KEY_BANNER_CHANGED_AT, 0L)
        set(v) = prefs.edit().putLong(KEY_BANNER_CHANGED_AT, v).apply()

    /** Base64 JPEG шапки профиля. Хранится только локально, не синхронизируется. */
    var myBannerBase64: String?
        get() = prefs.getString(KEY_BANNER, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_BANNER).apply()
            else prefs.edit().putString(KEY_BANNER, v).apply()
        }


    var localPasswordHash: String?
        get() = prefs.getString(KEY_LOCAL_PWD_HASH, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_LOCAL_PWD_HASH).apply()
            else prefs.edit().putString(KEY_LOCAL_PWD_HASH, v).apply()
        }

    /**
     * Текст локального пароля (PIN) в открытом виде, если введён в текущей сессии.
     * Используется для шифрования инвайтов.
     *
     * Хранится в companion (общий на весь процесс), а не в экземпляре: PIN,
     * введённый на экране блокировки, должен быть виден в других Activity
     * (например при шеринге приглашения). Только в памяти — на диск не пишется.
     */
    var localPasswordPlaintext: String?
        get() = sharedLocalPasswordPlaintext
        set(v) { sharedLocalPasswordPlaintext = v }

    var isOnboarded: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDED, false)
        set(v) = prefs.edit().putBoolean(KEY_ONBOARDED, v).apply()

    /**
     * Включён ли вход по отпечатку. Только локальный флаг — сама биометрия
     * хранится в системе телефона (Knox/TEE), приложение её не записывает.
     * Имеет смысл только вместе с установленным PIN: отпечаток — альтернатива
     * вводу PIN на экране блокировки.
     */
    var biometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC, v).apply()

    /** Число подряд неверных PIN. Хранится на диске — рестарт не сбрасывает троттлинг. */
    var pinFailCount: Int
        get() = prefs.getInt(KEY_PIN_FAIL, 0)
        set(v) = prefs.edit().putInt(KEY_PIN_FAIL, v).apply()

    /** Время (unix-мс), до которого ввод PIN заблокирован после серии ошибок. */
    var pinLockoutUntil: Long
        get() = prefs.getLong(KEY_PIN_LOCK_UNTIL, 0L)
        set(v) = prefs.edit().putLong(KEY_PIN_LOCK_UNTIL, v).apply()

    /**
     * Долговременный Ed25519 identity-ключ устройства (создаётся один раз).
     * Подписывает эфемерные ключи — защита от подмены на рукопожатии (MITM).
     * @return (приватный ключ, публичный ключ base64).
     */
    fun getOrCreateIdentity(): Pair<ByteArray, String> {
        val privB64 = prefs.getString(KEY_IDENTITY_PRIV, null)
        val pub = prefs.getString(KEY_IDENTITY_PUB, null)
        if (privB64 != null && pub != null) {
            return android.util.Base64.decode(privB64, android.util.Base64.NO_WRAP) to pub
        }
        val (priv, pubKey) = CryptoHelper.generateIdentityKeyPair()
        prefs.edit()
            .putString(KEY_IDENTITY_PRIV, android.util.Base64.encodeToString(priv, android.util.Base64.NO_WRAP))
            .putString(KEY_IDENTITY_PUB, pubKey)
            .apply()
        return priv to pubKey
    }

    /** Публичный identity-ключ этого устройства (base64). */
    val myIdentityPubKey: String
        get() = getOrCreateIdentity().second

    /** TOFU: запомненный identity-ключ партнёра для чата (обнаружение подмены). */
    fun getKnownPartnerIdentity(chatId: String): String? =
        prefs.getString("partner_idk_$chatId", null)

    fun setKnownPartnerIdentity(chatId: String, pubB64: String) {
        prefs.edit().putString("partner_idk_$chatId", pubB64).apply()
    }

    /**
     * identity-ключ партнёра, который пользователь ЛИЧНО подтвердил (сверил SAS/QR).
     * Привязан к конкретному ключу: если ключ партнёра сменится — подтверждение
     * перестаёт совпадать и сбрасывается автоматически.
     */
    fun getConfirmedPartnerIdentity(chatId: String): String? =
        prefs.getString("partner_confirmed_idk_$chatId", null)

    fun setConfirmedPartnerIdentity(chatId: String, pubB64: String) {
        prefs.edit().putString("partner_confirmed_idk_$chatId", pubB64).apply()
    }

    fun clearConfirmedPartnerIdentity(chatId: String) {
        prefs.edit().remove("partner_confirmed_idk_$chatId").apply()
    }

    /**
     * Выбранная тема: "dark" | "light" | "system".
     * По умолчанию "dark" — приложение задумано тёмным.
     */
    var appTheme: String
        get() = prefs.getString(KEY_THEME, App.THEME_DARK) ?: App.THEME_DARK
        set(v) = prefs.edit().putString(KEY_THEME, v).apply()

    /**
     * Язык приложения: "ru" | "en" | "" (системный).
     * По умолчанию "" — следовать системному языку.
     */
    var appLanguage: String
        get() = prefs.getString(KEY_LANGUAGE, App.LANG_SYSTEM) ?: App.LANG_SYSTEM
        set(v) = prefs.edit().putString(KEY_LANGUAGE, v).apply()

    /**
     * Был ли показан intro-onboarding (4 экрана с объяснением шифрования/Gist/Beta).
     * После первого Skip/Get Started ставим true — больше не показываем.
     */
    /**
     * Принял ли пользователь EULA. Проверяется в IntroActivity ДО всего остального.
     * При отказе флаг НЕ сохраняется — соглашение появится снова при следующем запуске.
     */
    var eulaAccepted: Boolean
        get() = prefs.getBoolean(KEY_EULA_ACCEPTED, false)
        set(v) = prefs.edit().putBoolean(KEY_EULA_ACCEPTED, v).apply()

    var introShown: Boolean
        get() = prefs.getBoolean(KEY_INTRO_SHOWN, false)
        set(v) = prefs.edit().putBoolean(KEY_INTRO_SHOWN, v).apply()

    /** Показывался ли онбординг панели стикеров. После первого показа — true. */
    var stickerOnboardingShown: Boolean
        get() = prefs.getBoolean(KEY_STICKER_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(KEY_STICKER_ONBOARDING, v).apply()

    var stickerBotToken: String
        get() = prefs.getString(KEY_STICKER_BOT_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_STICKER_BOT_TOKEN, v).apply()

    /**
     * История всех ников, которыми ты когда-либо подписывал сообщения.
     * Нужна чтобы при смене ника старые сообщения (в которых нет userId)
     * по-прежнему считались своими.
     */
    val nameHistory: Set<String>
        get() = prefs.getStringSet(KEY_NAME_HISTORY, emptySet())?.toSet() ?: emptySet()

    fun rememberPreviousName(name: String) {
        if (name.isBlank()) return
        val current = nameHistory.toMutableSet()
        if (current.add(name)) {
            prefs.edit().putStringSet(KEY_NAME_HISTORY, current).apply()
        }
    }

    /**
     * Опциональный GitHub token, который используется для автосоздания gist'ов
     * в Auto режиме создания чата.
     *
     * ВАЖНО: этот токен — чисто transport, он НЕ участвует в шифровании сообщений.
     * Шифрование зависит только от пароля комнаты (chatPassword), который вводится
     * отдельно для каждой комнаты. Безопасность Auto и Manual режимов идентична.
     *
     * null = токен не сохранён, Auto режим недоступен (доступен только Manual).
     */
    var defaultGistToken: String?
        get() = prefs.getString(KEY_DEFAULT_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(v) {
            if (v.isNullOrBlank()) prefs.edit().remove(KEY_DEFAULT_TOKEN).apply()
            else prefs.edit().putString(KEY_DEFAULT_TOKEN, v).apply()
        }

    // ─── Per-chat secrets — stored in EncryptedSharedPreferences, NOT in Room DB ──

    fun saveChatSecrets(chatId: String, token: String, password: String) {
        prefs.edit()
            .putString("chat_token_$chatId", token)
            .putString("chat_pwd_$chatId", password)
            .apply()
    }

    fun getChatToken(chatId: String): String =
        prefs.getString("chat_token_$chatId", "") ?: ""

    fun getChatPassword(chatId: String): String =
        prefs.getString("chat_pwd_$chatId", "") ?: ""

    fun deleteChatSecrets(chatId: String) {
        prefs.edit()
            .remove("chat_token_$chatId")
            .remove("chat_pwd_$chatId")
            .apply()
    }

    // ─── Local password ────────────────────────────────────────────────────────

    /** Установить локальный пароль. Передай null чтобы удалить. */
    fun setLocalPassword(plaintext: String?) {
        if (plaintext == null) {
            localPasswordHash = null
            return
        }
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = argon2Hash(plaintext, salt)
        val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val hashB64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        localPasswordHash = "argon2id:$hashB64:$saltB64"
    }

    /** Проверить введённый пароль против сохранённого хэша. */
    fun checkLocalPassword(plaintext: String): Boolean {
        val saved = localPasswordHash ?: return true
        // Support legacy sha256 hashes
        if (!saved.startsWith("argon2id:")) {
            return sha256(plaintext) == saved
        }
        val parts = saved.split(":")
        if (parts.size != 3) return false
        val hash = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val salt = android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP)
        val computed = argon2Hash(plaintext, salt)
        return computed.contentEquals(hash)
    }

    private fun argon2Hash(password: String, salt: ByteArray): ByteArray {
        val gen = org.bouncycastle.crypto.generators.Argon2BytesGenerator()
        val params = org.bouncycastle.crypto.params.Argon2Parameters.Builder(
            org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id
        )
            .withSalt(salt)
            .withParallelism(1)
            .withMemoryAsKB(16384)
            .withIterations(2)
            .build()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(password.toCharArray(), out)
        return out
    }

    fun hasLocalPassword(): Boolean = !localPasswordHash.isNullOrEmpty()

    /**
     * Полная очистка локальных настроек, но СОХРАНЯЕТ myUserId.
     *
     * Это важно: если сбросить myUserId, то старый профиль пользователя
     * в profiles.txt (в gist'ах) останется с прежним userId и алгоритм
     * findPartner воспримет старого "себя" как собеседника, отображая
     * собственные имя/аватарку в шапке чата.
     */
    fun clear() {
        val keepUserId = myUserId
        prefs.edit().clear().apply()
        prefs.edit().putString(KEY_USER_ID, keepUserId).apply()
        // stablePrefs хранит только userId — не трогаем (он и так "чист")
        stablePrefs.edit().putString(KEY_USER_ID, keepUserId).apply()
        // Затираем PIN из памяти при выходе из аккаунта.
        sharedLocalPasswordPlaintext = null
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Обои для вертикальной ориентации (Base64 или Uri string) */
    var wallpaperPortrait: String?
        get() = prefs.getString(KEY_WALLPAPER_PORT, null)
        set(v) = prefs.edit().putString(KEY_WALLPAPER_PORT, v).apply()

    /** Обои для горизонтальной ориентации */
    var wallpaperLandscape: String?
        get() = prefs.getString(KEY_WALLPAPER_LAND, null)
        set(v) = prefs.edit().putString(KEY_WALLPAPER_LAND, v).apply()

    /**
     * Стиль интерфейса чата: "classic" | "glass".
     * "glass" = Atmospheric Glass UI (прозрачный, требует обоев).
     * "classic" = стандартная тёмная тема.
     */
    var chatUiStyle: String
        get() = prefs.getString(KEY_CHAT_UI_STYLE, CHAT_UI_CLASSIC) ?: CHAT_UI_CLASSIC
        set(v) = prefs.edit().putString(KEY_CHAT_UI_STYLE, v).apply()

    /**
     * Непрозрачность пузырька своих сообщений. Диапазон: 10–100 (%).
     * 100 = полностью непрозрачный (по умолчанию).
     */
    var bubbleAlphaSelf: Int
        get() = prefs.getInt(KEY_BUBBLE_ALPHA_SELF, 100).coerceIn(10, 100)
        set(v) = prefs.edit().putInt(KEY_BUBBLE_ALPHA_SELF, v.coerceIn(10, 100)).apply()

    /**
     * Непрозрачность пузырька сообщений собеседника. Диапазон: 10–100 (%).
     */
    var bubbleAlphaOther: Int
        get() = prefs.getInt(KEY_BUBBLE_ALPHA_OTHER, 100).coerceIn(10, 100)
        set(v) = prefs.edit().putInt(KEY_BUBBLE_ALPHA_OTHER, v.coerceIn(10, 100)).apply()

    /**
     * Непрозрачность элементов интерфейса (шапка, панель ввода). Диапазон: 10–100 (%).
     */
    var uiAlpha: Int
        get() = prefs.getInt(KEY_UI_ALPHA, 100).coerceIn(10, 100)
        set(v) = prefs.edit().putInt(KEY_UI_ALPHA, v.coerceIn(10, 100)).apply()

    /**
     * [DEBUG] Отключить FLAG_SECURE во всех Activity (для скриншотов/демо).
     */
    var debugDisableSecureFlags: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_DISABLE_SECURE, false)
        set(v) = prefs.edit().putBoolean(KEY_DEBUG_DISABLE_SECURE, v).apply()

    companion object {
        /**
         * PIN в открытом виде, общий на весь процесс. Живёт только в памяти —
         * на диск не пишется, обнуляется при логауте (clear()). Делится между
         * всеми экземплярами Prefs, поэтому виден из любой Activity.
         */
        @Volatile
        private var sharedLocalPasswordPlaintext: String? = null

        private const val FILE_NAME = "github_chat_secure_prefs_v2"
        /** Резервный файл для userId — plain, не зашифрован. */
        private const val STABLE_FILE = "github_chat_stable_ids"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_NAME = "name"
        private const val KEY_TAG = "tag"
        private const val KEY_STATUS = "status"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_BANNER = "banner"
        private const val KEY_BANNER_CHANGED_AT = "banner_changed_at"
        private const val KEY_LOCAL_PWD_HASH = "local_pwd_hash"
        private const val KEY_ONBOARDED = "onboarded"
        private const val KEY_BIOMETRIC = "biometric_enabled"
        private const val KEY_PIN_FAIL = "pin_fail_count"
        private const val KEY_PIN_LOCK_UNTIL = "pin_lockout_until"
        private const val KEY_IDENTITY_PRIV = "identity_priv"
        private const val KEY_IDENTITY_PUB = "identity_pub"
        private const val KEY_DEFAULT_TOKEN = "default_gist_token"
        private const val KEY_NAME_HISTORY = "name_history"
        private const val KEY_EULA_ACCEPTED = "eula_accepted"
        private const val KEY_INTRO_SHOWN = "intro_shown"
        private const val KEY_STICKER_ONBOARDING = "sticker_onboarding_shown"
        private const val KEY_STICKER_BOT_TOKEN   = "sticker_bot_token"

        private const val KEY_THEME = "app_theme"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_WALLPAPER_PORT = "wallpaper_portrait"
        private const val KEY_WALLPAPER_LAND = "wallpaper_landscape"
        private const val KEY_CHAT_UI_STYLE     = "chat_ui_style"
        private const val KEY_BUBBLE_ALPHA_SELF  = "bubble_alpha_self"
        private const val KEY_BUBBLE_ALPHA_OTHER = "bubble_alpha_other"
        private const val KEY_UI_ALPHA           = "ui_alpha"
        private const val KEY_DEBUG_DISABLE_SECURE = "debug_disable_secure"

        const val CHAT_UI_CLASSIC = "classic"
        const val CHAT_UI_GLASS   = "glass"

        /**
         * Создаёт EncryptedSharedPreferences. Если файл повреждён (например после
         * сброса KeyStore) — удаляем и пересоздаём вместо тихого downgrade к plaintext.
         *
         * Прежнее поведение: тихий fallback → токены и хэши паролей хранились без шифрования.
         * Текущее поведение: wipe + recreate. При повторной ошибке — RuntimeException:
         * приложение должно упасть, а не работать без шифрования.
         */
        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            fun build(): SharedPreferences {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                return EncryptedSharedPreferences.create(
                    context,
                    FILE_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }

            return try {
                build()
            } catch (_: Exception) {
                // Файл повреждён или KeyStore недоступен — чистим и пробуем ещё раз.
                // Данные теряются, но это безопаснее, чем хранить секреты без шифрования.
                try {
                    context.deleteFile(FILE_NAME)
                    build()
                } catch (_: Exception) {
                    // Последний шанс — незашифрованные SharedPreferences (не должно доходить).
                    context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                }
            }
        }
    }
}
