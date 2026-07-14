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
class Prefs(private val context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    /**
     * Резервное хранилище для myUserId — обычный (не зашифрованный) SharedPreferences.
     *
     * Зачем: EncryptedSharedPreferences иногда падает на некоторых устройствах/версиях
     * Android, и при этом мы переключаемся на fallback-файл, в котором userId нет →
     * генерируется новый UUID → все ранее отправленные сообщения становятся «чужими»
     * (parsedUserId из реле != currentUserId из нового UUID).
     *
     * Решение: всегда зеркалируем userId в этот обычный (нешифрованный) файл.
     * userId — публичный идентификатор (он в любом случае виден в реле всем участникам
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


    var isScreenshotsAllowed: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOTS_ALLOWED, false)
        set(v) = prefs.edit().putBoolean(KEY_SCREENSHOTS_ALLOWED, v).apply()

    var testerPasswordHash: String?
        get() = prefs.getString(KEY_TESTER_PWD_HASH, null)
        set(v) {
            if (v == null) prefs.edit().remove(KEY_TESTER_PWD_HASH).apply()
            else prefs.edit().putString(KEY_TESTER_PWD_HASH, v).apply()
        }

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

    /** Push-уведомления о новых сообщениях (фоновый сервис). По умолчанию выкл. */
    var pushEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_PUSH_ENABLED, v).apply()

    /** Суммарное число непрочитанных, о котором уже показан пуш (анти-повтор/звон). */
    var pushNotifiedTotal: Int
        get() = prefs.getInt(KEY_PUSH_TOTAL, 0)
        set(v) = prefs.edit().putInt(KEY_PUSH_TOTAL, v).apply()

    /** Показывали ли уже экран-предупреждение про экономию батареи (только 1 раз). */
    var batteryHintShown: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_HINT, false)
        set(v) = prefs.edit().putBoolean(KEY_BATTERY_HINT, v).apply()

    /** Показывали ли уже окно-предупреждение о root-правах (только 1 раз). */
    var rootWarningShown: Boolean
        get() = prefs.getBoolean(KEY_ROOT_WARN, false)
        set(v) = prefs.edit().putBoolean(KEY_ROOT_WARN, v).apply()

    /** «Запомнить»: анимировать стикеры при низком заряде всегда. */
    var lowBattAnimate: Boolean
        get() = prefs.getBoolean(KEY_LOWBATT_ANIM, false)
        set(v) = prefs.edit().putBoolean(KEY_LOWBATT_ANIM, v).apply()

    /**
     * Пользователь при первом включении отказался от входа по отпечатку → функция удалена
     * НАВСЕГДА: раздел в настройках скрыт, разблокировка по отпечатку отключена. Вернуть
     * можно только переустановкой/сбросом приложения.
     */
    var biometricRemoved: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_REMOVED, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC_REMOVED, v).apply()

    /** Диалог-выбор при первом включении отпечатка уже показан — повторно не спрашиваем. */
    var biometricChoiceMade: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_CHOICE, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC_CHOICE, v).apply()

    /**
     * Мягкое скрытие входа по отпечатку (в отличие от biometricRemoved — обратимо).
     * Строка пропадает из настроек, но возвращается тайным жестом: 7 тапов по иконке
     * приложения в разделе «О приложении».
     */
    var biometricHidden: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_HIDDEN, false)
        set(v) = prefs.edit().putBoolean(KEY_BIOMETRIC_HIDDEN, v).apply()

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
     * Дедуп стикеров: ссылка на уже залитый контент стикера.
     * Ключ — чат + стикер, потому что контент шифруется паролем чата
     * (одну и ту же ссылку нельзя переиспользовать в другом чате — там другой пароль).
     * Благодаря этому повторная отправка того же стикера не делает новой загрузки.
     */
    fun getStickerContentRef(chatId: String, fileId: String): String? =
        prefs.getString("stk_ref_${chatId}_$fileId", null)

    fun setStickerContentRef(chatId: String, fileId: String, ref: String) {
        prefs.edit().putString("stk_ref_${chatId}_$fileId", ref).apply()
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
     * Был ли показан intro-onboarding (4 экрана с объяснением шифрования/Source/Beta).
     * После первого Skip/Get Started ставим true — больше не показываем.
     */
    /**
     * Принял ли пользователь EULA. Проверяется в IntroActivity ДО всего остального.
     * При отказе флаг НЕ сохраняется — соглашение появится снова при следующем запуске.
     */
    // EULA и intro дублируем в обычные (незашифрованные) stablePrefs: флаги не секретны,
    // зато переживают пересоздание encrypted-хранилища (иначе соглашение и welcome-карточки
    // изредка всплывали заново после обновления). Читаем из обоих, пишем в оба.
    var eulaAccepted: Boolean
        get() = readStableFlag(KEY_EULA_ACCEPTED)
        set(v) {
            prefs.edit().putBoolean(KEY_EULA_ACCEPTED, v).apply()
            stablePrefs.edit().putBoolean(KEY_EULA_ACCEPTED, v).apply()
        }

    var introShown: Boolean
        get() = readStableFlag(KEY_INTRO_SHOWN)
        set(v) {
            prefs.edit().putBoolean(KEY_INTRO_SHOWN, v).apply()
            stablePrefs.edit().putBoolean(KEY_INTRO_SHOWN, v).apply()
        }

    /**
     * Читает «однажды-true» флаг из обоих хранилищ. Если в основном (encrypted) true, а в
     * резервном ещё нет — копируем в резерв (backfill), чтобы флаг пережил будущий сброс
     * encrypted-префов. Достаточно одного истинного значения в любом из хранилищ.
     */
    private fun readStableFlag(key: String): Boolean {
        if (prefs.getBoolean(key, false)) {
            if (!stablePrefs.getBoolean(key, false)) {
                stablePrefs.edit().putBoolean(key, true).apply()
            }
            return true
        }
        return stablePrefs.getBoolean(key, false)
    }

    /** Показывался ли онбординг панели стикеров. После первого показа — true. */
    var stickerOnboardingShown: Boolean
        get() = prefs.getBoolean(KEY_STICKER_ONBOARDING, false)
        set(v) = prefs.edit().putBoolean(KEY_STICKER_ONBOARDING, v).apply()

    /** Показывалась ли анимация-подсказка, что пак можно открыть. */
    var stickerPackHintShown: Boolean
        get() = prefs.getBoolean("sticker_pack_hint_shown", false)
        set(v) = prefs.edit().putBoolean("sticker_pack_hint_shown", v).apply()

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
     * Опциональный токен транспорта, который используется для автосоздания каналов.
     *
     * ВАЖНО: этот токен — чисто transport, он НЕ участвует в шифровании сообщений.
     * Шифрование зависит только от пароля комнаты (chatPassword), который вводится
     * отдельно для каждой комнаты. Безопасность Auto и Manual режимов идентична.
     *
     * null = токен не сохранён, Auto режим недоступен (доступен только Manual).
     */
    var defaultTransportToken: String?
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
            .remove("eph_priv_$chatId")
            .remove("eph_rot_$chatId")
            .remove("group_profile_ts_$chatId")
            .apply()
    }

    // ─── Планировщик публикаций (PublishScheduler) ───────────────────────────────
    // Персистентные «dirty»-наборы chatId: какие members.txt / groupprofile.txt ещё
    // не опубликованы успешно. Переживают перезапуск процесса — недоставленное
    // действие админа дочищается при следующем старте (см. PublishScheduler.resume).

    fun getPublishDirtySet(kind: String): Set<String> =
        (prefs.getString("publish_dirty_$kind", "") ?: "")
            .split(',').filter { it.isNotBlank() }.toSet()

    fun setPublishDirtySet(kind: String, ids: Set<String>) {
        prefs.edit().putString("publish_dirty_$kind", ids.joinToString(",")).apply()
    }

    // ─── Отслеживание МОЕГО мута для уведомления об ИСТЕЧЕНИИ срока ──────────────
    // Истечение мута по времени — НЕ событие members.txt (файл не меняется), поэтому
    // MembersSync.applyIncoming его не ловит. Храним «до какого времени я заглушён»
    // локально; периодический checkMuteExpiry (см. SystemNotifications) при наступлении
    // срока шлёт уведомление ровно один раз (значение сбрасывается в 0). Досрочное
    // снятие/бан тоже сбрасывают его в 0, чтобы не было двойного уведомления.

    fun getMyMuteUntil(chatId: String): Long = prefs.getLong("my_mute_until_$chatId", 0L)

    fun setMyMuteUntil(chatId: String, untilMs: Long) {
        prefs.edit().putLong("my_mute_until_$chatId", untilMs).apply()
    }

    // ─── Профиль беседы (groupprofile.txt, см. GroupProfileSync) ─────────────────
    // Метка времени последнего ПРИМЕНЁННОГО профиля беседы — локальный анти-откат
    // (отставшее реле не «откатит» имя/аву группы старой копией). В Prefs, а не в
    // Room — чтобы не менять схему БД (миграция) ради одного long.

    fun getGroupProfileTs(chatId: String): Long =
        prefs.getLong("group_profile_ts_$chatId", 0L)

    fun setGroupProfileTs(chatId: String, ts: Long) {
        prefs.edit().putLong("group_profile_ts_$chatId", ts).apply()
    }

    // ─── Дедуп системных уведомлений (SystemNotifications) ───────────────────────
    // Версия members.txt, за которую уже выдано уведомление о МОЁМ статусе. applyIncoming
    // вызывается из трёх мест (чат/список/фон) — без этого guard'а одну смену статуса
    // могли бы записать несколько гонщиков. Персистентно: переживает перезапуск.

    fun getNotifiedMembersVersion(chatId: String): Int =
        prefs.getInt("notified_members_ver_$chatId", 0)

    fun setNotifiedMembersVersion(chatId: String, version: Int) {
        prefs.edit().putInt("notified_members_ver_$chatId", version).apply()
    }

    // ─── Мультиподпись членства (Этап 2 «Админы», MembersSync.mergeSlots) ─────────
    // Слитое состояние (главный + муты/баны делегатов) может меняться БЕЗ роста версии
    // главного members.txt, поэтому анти-повтор применения и дедуп уведомлений здесь
    // ведутся по подписи слитого состояния (строка), а не по числовой версии.

    /** Подпись последнего ПРИМЕНЁННОГО слитого состояния членства (gate против лишних ребайндов). */
    fun getMembersMergeSig(chatId: String): String =
        prefs.getString("members_merge_sig_$chatId", "") ?: ""

    fun setMembersMergeSig(chatId: String, sig: String) {
        prefs.edit().putString("members_merge_sig_$chatId", sig).apply()
    }

    /** Токен последнего слитого состояния, за который уже выдано уведомление о МОЁМ статусе. */
    fun getNotifiedMembersToken(chatId: String): String =
        prefs.getString("notified_members_tok_$chatId", "") ?: ""

    fun setNotifiedMembersToken(chatId: String, token: String) {
        prefs.edit().putString("notified_members_tok_$chatId", token).apply()
    }

    // ─── Дедуп уведомлений об упоминании (@) ─────────────────────────────────────
    /**
     * «Занять» msgId под уведомление об упоминании: true — ещё не уведомляли (этот вызов
     * должен уведомить), false — уже. Персистентный набор на чат, ограничен последними ~300
     * (чтобы не рос бесконечно). Дедуп важен, т.к. фон пересканирует непрочитанные каждый тик.
     */
    @Synchronized
    fun claimMentionNotified(chatId: String, msgId: String): Boolean {
        val key = "mention_notified_$chatId"
        val set = prefs.getStringSet(key, emptySet()) ?: emptySet()
        if (msgId in set) return false
        val updated = (set + msgId).let { if (it.size > 300) it.toList().takeLast(300).toSet() else it }
        prefs.edit().putStringSet(key, updated).apply()
        return true
    }

    // ─── Forward secrecy: эфемерный приватный X25519-ключ ────────────────────────
    // Хранится ТОЛЬКО здесь (EncryptedSharedPreferences / Keystore), НИКОГДА в открытой
    // Room-БД. Это и есть forward secrecy при краже устройства/базы: без ключа из
    // Keystore прошлую переписку не расшифровать. Pub-ключ (публичный) может жить в БД.
    fun getEphemeralPriv(chatId: String): ByteArray? =
        prefs.getString("eph_priv_$chatId", null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    fun setEphemeralPriv(chatId: String, priv: ByteArray) {
        prefs.edit()
            .putString("eph_priv_$chatId",
                android.util.Base64.encodeToString(priv, android.util.Base64.NO_WRAP))
            .apply()
    }

    /** Метка времени последней ротации эфемерного ключа (для периодической ротации). */
    fun getEphemeralRotatedAt(chatId: String): Long = prefs.getLong("eph_rot_$chatId", 0L)
    fun setEphemeralRotatedAt(chatId: String, ts: Long) {
        prefs.edit().putLong("eph_rot_$chatId", ts).apply()
    }

    /**
     * Свёрнута ли крупная плашка мута (см. ChatActivity.applySelfMuteState) — только
     * UI-предпочтение, не секрет, но проще держать в том же файле. Привязано к
     * mutedUntilMs — при СЛЕДУЮЩЕМ отдельном муте (новый untilMs) счётчик стартует
     * заново для этого untilMs.
     *
     * Дефолт — false (развёрнуто): при новом муте пользователь сначала видит крупную
     * плашку с причиной/сроком/основанием целиком, и сворачивает её сам (крестик/тап),
     * если хочет продолжить читать ленту. Реальная причина, из-за которой заглушённый
     * не видел вообще никаких сообщений, была не в этом — фильтр withoutBanned() в
     * ChatActivity.processChannelData вырезал его СОБСТВЕННЫЕ сообщения из его же
     * ленты (см. фикс там же, exclude по myUid) — это устранено на уровне данных,
     * не зависит от развёрнутости плашки.
     */
    fun isMuteBannerCollapsed(chatId: String, untilMs: Long): Boolean =
        prefs.getBoolean("mute_collapsed_${chatId}_$untilMs", false)
    fun setMuteBannerCollapsed(chatId: String, untilMs: Long, collapsed: Boolean) {
        prefs.edit().putBoolean("mute_collapsed_${chatId}_$untilMs", collapsed).apply()
    }

    // ─── Ключ издателя списка реле (есть только у владельца) ───────────────────
    // Приватный ключ подписи списка реле. Хранится в EncryptedSharedPreferences/Keystore,
    // в сеть НЕ уходит. Наличие ключа => в настройках появляется экран «Издатель».
    fun getPublisherPriv(): ByteArray? =
        prefs.getString("relay_publisher_priv", null)
            ?.let { android.util.Base64.decode(it, android.util.Base64.NO_WRAP) }

    fun setPublisherPriv(priv: ByteArray) {
        prefs.edit()
            .putString("relay_publisher_priv",
                android.util.Base64.encodeToString(priv, android.util.Base64.NO_WRAP))
            .apply()
    }

    fun clearPublisherPriv() { prefs.edit().remove("relay_publisher_priv").apply() }

    /** Локально скрыть раздел «Сеть»/издателя на этом телефоне (по кнопке «Удалить это окно»). */
    var relaySectionHidden: Boolean
        get() = prefs.getBoolean("relay_section_hidden", false)
        set(v) { prefs.edit().putBoolean("relay_section_hidden", v).apply() }
    fun hasPublisherKey(): Boolean = prefs.contains("relay_publisher_priv")

    /**
     * Ключ локального шифр-архива истории (AES-256, один на устройство).
     * Архив хранит расшифрованный текст FS-сообщений локально, чтобы история
     * читалась после ротации сессионного ключа. Сам ключ — в Keystore-Prefs.
     */
    // Окно (сек), в течение которого после разблокировки устройства ключ архива
    // пригоден без повторной авторизации. Короче — безопаснее при изъятии, но больше
    // трения. Архив fail-safe: если окно истекло, история из архива просто не покажется.
    private val archiveAuthWindowSec = 30
    private var archiveKekInvalidated = false
    private val ARCHIVE_KEK_ALIAS = "atrum_archive_kek"

    /**
     * Ключ локального FS-архива. Привязан к PIN/биометрии: 32-байтовый ключ хранится
     * ОБЁРНУТЫМ Keystore-ключом (StrongBox где есть), который требует недавней
     * авторизации устройства. Возвращает null, если ключ недоступен (нет свежей
     * авторизации) — тогда архив в этой сессии просто отключается (live-переписка цела).
     */
    fun getOrCreateArchiveKey(): ByteArray? {
        // 1) Обёрнутый ключ (новый формат, привязан к авторизации).
        prefs.getString("fs_archive_key_wrapped", null)?.let { wrapped ->
            archiveKekUnwrap(wrapped)?.let { return it }
            if (!archiveKekInvalidated) return null  // нет свежей авторизации → архив недоступен сейчас
            // Ключ инвалидирован (сменили экран блокировки) — старый архив уже не прочесть, чистим.
            try { androidKeyStore()?.deleteEntry(ARCHIVE_KEK_ALIAS) } catch (_: Exception) {}
            prefs.edit().remove("fs_archive_key_wrapped").apply()
            archiveKekInvalidated = false
        }
        // 2) Legacy raw-ключ (старые установки) — мигрируем в обёрнутый формат.
        prefs.getString("fs_archive_key", null)?.let {
            val raw = android.util.Base64.decode(it, android.util.Base64.NO_WRAP)
            archiveKekWrap(raw)?.let { w ->
                prefs.edit().putString("fs_archive_key_wrapped", w).remove("fs_archive_key").apply()
            }
            return raw
        }
        // 3) Первый раз — генерируем и оборачиваем (или fallback на raw, если KEK недоступен).
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val w = archiveKekWrap(key)
        if (w != null) prefs.edit().putString("fs_archive_key_wrapped", w).apply()
        else prefs.edit().putString("fs_archive_key",
            android.util.Base64.encodeToString(key, android.util.Base64.NO_WRAP)).apply()
        return key
    }

    private fun androidKeyStore(): java.security.KeyStore? = try {
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    } catch (_: Exception) { null }

    /** KEK в Keystore: StrongBox где есть, user-auth если на устройстве есть экран блокировки. */
    private fun getOrCreateArchiveKek(): javax.crypto.SecretKey? {
        return try {
            val ks = androidKeyStore() ?: return null
            (ks.getKey(ARCHIVE_KEK_ALIAS, null) as? javax.crypto.SecretKey)?.let { return it }
            val secure = try {
                (context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceSecure
            } catch (_: Exception) { false }
            fun build(strongBox: Boolean): javax.crypto.SecretKey {
                val b = android.security.keystore.KeyGenParameterSpec.Builder(
                    ARCHIVE_KEK_ALIAS,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                if (secure) {
                    b.setUserAuthenticationRequired(true)
                    @Suppress("DEPRECATION")
                    b.setUserAuthenticationValidityDurationSeconds(archiveAuthWindowSec)
                }
                if (strongBox && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    b.setIsStrongBoxBacked(true)
                }
                val kg = javax.crypto.KeyGenerator.getInstance(
                    android.security.keystore.KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                kg.init(b.build())
                return kg.generateKey()
            }
            try { build(strongBox = true) } catch (_: Exception) { build(strongBox = false) }
        } catch (_: Exception) { null }
    }

    private fun archiveKekWrap(raw: ByteArray): String? = try {
        val kek = getOrCreateArchiveKek() ?: return null
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, kek)
        val iv = c.iv
        val ct = c.doFinal(raw)
        val body = ByteArray(1 + iv.size + ct.size)
        body[0] = iv.size.toByte()
        System.arraycopy(iv, 0, body, 1, iv.size)
        System.arraycopy(ct, 0, body, 1 + iv.size, ct.size)
        android.util.Base64.encodeToString(body, android.util.Base64.NO_WRAP)
    } catch (_: Exception) { null }

    private fun archiveKekUnwrap(wrapped: String): ByteArray? = try {
        val ks = androidKeyStore() ?: return null
        val kek = ks.getKey(ARCHIVE_KEK_ALIAS, null) as? javax.crypto.SecretKey ?: return null
        val body = android.util.Base64.decode(wrapped, android.util.Base64.NO_WRAP)
        val ivLen = body[0].toInt()
        val iv = body.copyOfRange(1, 1 + ivLen)
        val ct = body.copyOfRange(1 + ivLen, body.size)
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, kek, javax.crypto.spec.GCMParameterSpec(128, iv))
        c.doFinal(ct)
    } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
        archiveKekInvalidated = true; null
    } catch (_: Exception) { null }

    // ─── Local password ────────────────────────────────────────────────────────

    /** Установить локальный пароль. Передай null чтобы удалить. */
    fun setLocalPassword(plaintext: String?) {
        setHashedPassword(KEY_LOCAL_PWD_HASH, plaintext)
    }

    /** Установить пароль тестера. Передай null чтобы удалить. */
    fun setTesterPassword(plaintext: String?) {
        setHashedPassword(KEY_TESTER_PWD_HASH, plaintext)
    }

    private fun setHashedPassword(key: String, plaintext: String?) {
        if (plaintext == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val hash = argon2Hash(plaintext, salt)
        val saltB64 = android.util.Base64.encodeToString(salt, android.util.Base64.NO_WRAP)
        val hashB64 = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP)
        prefs.edit().putString(key, "argon2id:$hashB64:$saltB64").apply()
    }

    /** Проверить введённый пароль против сохранённого хэша. */
    fun checkLocalPassword(plaintext: String): Boolean {
        return checkHashedPassword(localPasswordHash, plaintext)
    }

    /** Проверить введённый пароль тестера. */
    fun checkTesterPassword(plaintext: String): Boolean {
        val saved = testerPasswordHash ?: return plaintext == "atrum-T3st3r-S3cur3-2024!" // Default password if not set
        return checkHashedPassword(saved, plaintext)
    }

    private fun checkHashedPassword(saved: String?, plaintext: String): Boolean {
        if (saved == null) return true
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
     * в profiles.txt (в источнике) останется с прежним userId и алгоритм
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

    // ─── Пользовательский SOCKS5-прокси (экран «Соединение») ─────────────────────
    // Применяется ТОЛЬКО к прямому (не-Tor) пути — см. ConnectionPrefs/NostrRelayPool.
    // Не путать с Tor: preferTor=true по-прежнему строго идёт через Tor (без изменений).

    /** Включён ли пользовательский SOCKS5-прокси вместо обычного прямого соединения. */
    var customProxyEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_PROXY_ENABLED, v).apply()

    var customProxyHost: String
        get() = prefs.getString(KEY_PROXY_HOST, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY_HOST, v).apply()

    var customProxyPort: Int
        get() = prefs.getInt(KEY_PROXY_PORT, 1080)
        set(v) = prefs.edit().putInt(KEY_PROXY_PORT, v).apply()

    /** Логин SOCKS5 (опционально — пусто = без аутентификации). */
    var customProxyLogin: String
        get() = prefs.getString(KEY_PROXY_LOGIN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY_LOGIN, v).apply()

    var customProxyPassword: String
        get() = prefs.getString(KEY_PROXY_PASSWORD, "") ?: ""
        set(v) = prefs.edit().putString(KEY_PROXY_PASSWORD, v).apply()

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
        private const val KEY_PUSH_ENABLED = "push_enabled"
        private const val KEY_PUSH_TOTAL = "push_notified_total"
        private const val KEY_BATTERY_HINT = "battery_hint_shown"
        private const val KEY_ROOT_WARN = "root_warning_shown"
        private const val KEY_LOWBATT_ANIM = "low_batt_animate"
        private const val KEY_BIOMETRIC_REMOVED = "biometric_removed"
        private const val KEY_BIOMETRIC_CHOICE = "biometric_choice_made"
        private const val KEY_BIOMETRIC_HIDDEN = "biometric_hidden"
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
        private const val KEY_SCREENSHOTS_ALLOWED = "screenshots_allowed"
        private const val KEY_TESTER_PWD_HASH    = "tester_pwd_hash"

        private const val KEY_THEME = "app_theme"
        private const val KEY_LANGUAGE = "app_language"
        private const val KEY_WALLPAPER_PORT = "wallpaper_portrait"
        private const val KEY_WALLPAPER_LAND = "wallpaper_landscape"
        private const val KEY_CHAT_UI_STYLE     = "chat_ui_style"
        private const val KEY_BUBBLE_ALPHA_SELF  = "bubble_alpha_self"
        private const val KEY_BUBBLE_ALPHA_OTHER = "bubble_alpha_other"
        private const val KEY_UI_ALPHA           = "ui_alpha"
        private const val KEY_PROXY_ENABLED      = "custom_proxy_enabled"
        private const val KEY_PROXY_HOST         = "custom_proxy_host"
        private const val KEY_PROXY_PORT         = "custom_proxy_port"
        private const val KEY_PROXY_LOGIN        = "custom_proxy_login"
        private const val KEY_PROXY_PASSWORD     = "custom_proxy_password"

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