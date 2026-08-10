package com.atrum.chat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Один чат с собеседником. Каждый чат — это отдельный транспортный канал (Nostr/BT).
 */
@Entity(tableName = "chats")
data class Chat(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * «Устойчивая доставка медиа» для ЭТОГО чата: фото/голосовые режутся на заметно более
     * мелкие части и отправляются с паузами между ними (см. NostrTransport.chunkChars).
     *
     * Зачем отдельным флагом, а не глобально: мелкие части заметно ЗАМЕДЛЯЮТ отправку
     * (частей становится вчетверо больше), и платить этим во всех чатах незачем. Включать
     * там, где у собеседника провайдер режет крупные пакеты с медиа (DPI/ТСПУ) и фото или
     * голосовые иначе не доходят.
     *
     * Дефолт false — старые чаты и старые записи в БД продолжают работать в прежнем
     * быстром режиме без каких-либо действий пользователя (§17). На формат это не влияет:
     * манифест перечисляет части по именам, поэтому получатель собирает медиа одинаково
     * при любом размере части, и старые версии приложения тоже (§17).
     */
    val resilientMedia: Boolean = false,

    @ColumnInfo(name = "channelId")
    val chatId: String,

    /**
     * ⚠️ DEPRECATED — всегда пустая строка с версии DB 10.
     * Токен теперь хранится в EncryptedSharedPreferences через Prefs.getChatToken(chatId).
     */
    @Deprecated("Stored in EncryptedSharedPreferences via Prefs. Always empty in DB v10+.")
    @ColumnInfo(name = "transportToken")
    val transportToken: String = "",

    /**
     * ⚠️ DEPRECATED — всегда пустая строка с версии DB 10.
     * Пароль теперь хранится в EncryptedSharedPreferences через Prefs.getChatPassword(chatId).
     */
    @Deprecated("Stored in EncryptedSharedPreferences via Prefs. Always empty in DB v10+.")
    @ColumnInfo(name = "chatPassword")
    val chatPassword: String = "",
    val partnerName: String,
    val partnerTag: String? = null,
    val partnerAvatarBase64: String? = null,
    val lastMessage: String = "",
    val lastTimeMs: Long = System.currentTimeMillis(),

    /** Сколько непрочитанных сообщений (только чужие). */
    val unreadCount: Int = 0,

    /**
     * Сколько сообщений было в канале при последнем открытии чата.
     * Используется для подсчёта новых сообщений с тех пор как пользователь
     * закрыл чат.
     */
    val lastSeenLineCount: Int = 0,

    /**
     * Сколько строк прочитал собеседник (из его профиля в profiles.txt).
     * По этому числу рисуем галочки прочитанности у наших исходящих сообщений:
     *  - индекс сообщения <  partnerLastReadIndex → ✓✓ (прочитано)
     *  - индекс сообщения >= partnerLastReadIndex → ✓  (доставлено)
     */
    val partnerLastReadIndex: Int = 0,

    /**
     * true когда в чате обнаружен второй участник (partner profile в profiles.txt).
     * Используется для блокировки повторных join'ов (чат для двоих) и для
     * отключения кнопки "Поделиться приглашением".
     */
    val partnerJoined: Boolean = false,

    /**
     * Момент истечения срока жизни чата в миллисекундах epoch.
     * null = бессрочно. По этому полю фоновый воркер удаляет протухшие чаты.
     */
    val expiresAtMs: Long? = null,

    /**
     * true если собеседник удалил свой профиль (Profile.deleted == true).
     * В этом случае показывается заглушка вместо аватарки.
     */
    val partnerDeleted: Boolean = false,

    /**
     * Закреплённый чат (пин). Поднимается наверх списка.
     */
    val isPinned: Boolean = false,

    /**
     * Специальный тип чата для "Избранного" (чат с самим собой).
     */
    val isFavorites: Boolean = false,

    /**
     * ⚠️ DEPRECATED — всегда NULL с версии DB 9.
     *
     * Хранение приватного ключа в БД нарушает forward secrecy: при доступе к файлу БД
     * атакующий мог расшифровать всю историю V3/V4-S сообщений. Приватный ключ теперь
     * живёт только в памяти в течение одной сессии ChatActivity.onCreate → onDestroy.
     *
     * Поле оставлено в схеме (SQLite не поддерживает DROP COLUMN), но никогда не заполняется.
     */
    @Deprecated("Приватный эфемерный ключ не должен персистироваться. Всегда null с DB v9.")
    val myEphemeralPrivKeyB64: String? = null,

    /**
     * Мой публичный эфемерный ключ X25519 (Base64).
     */
    val myEphemeralPubKeyB64: String? = null,

    /**
     * Публичный эфемерный ключ партнёра (Base64).
     * Позволяет мгновенно вычислить сессионный ключ при открытии чата.
     */
    val partnerEphemeralPubKeyB64: String? = null,

    // ─── Групповые чаты (ADR-001, см. ADR_GROUP_CHATS.md) ──────────────────────
    // Добавлено в DB v13. Для 1:1-чатов все поля ниже остаются в дефолте (false/null) —
    // существующие чаты не затронуты.

    /**
     * true — это групповой чат (несколько участников). ECDH forward-secrecy
     * рукопожатие (myEphemeralPubKeyB64/partnerEphemeralPubKeyB64) для таких чатов
     * НЕ запускается — оно математически рассчитано только на двух участников
     * (см. ADR_GROUP_CHATS.md). Группа шифруется общим паролем чата (V5,
     * Argon2id + AES-GCM) — тот же путь, что 1:1-чат использует ДО апгрейда на FS.
     */
    val isGroup: Boolean = false,

    /**
     * Максимум участников группы (5/10/15). null = без ограничений.
     * Проверяется на клиенте при джойне (см. JoinChatActivity) — без криптографического
     * принуждения, тот же уровень доверия, что и у обычного инвайт-кода.
     */
    val participantLimit: Int? = null,

    /**
     * userId создателя группы (администратора). Публикует и подписывает members.txt
     * (см. ChatParticipant + ADR_GROUP_CHATS.md). Пустой/null для 1:1-чатов.
     * Пара pubkey админа вычисляется детерминированно из (chatPassword, adminUserId) —
     * так же, как и для любого другого участника (см. NostrTransport.privkey) — отдельный
     * ключ для админа хранить не нужно.
     */
    val adminUserId: String? = null,

    /** Название группы (отдельно от partnerName — у группы нет одного собеседника). */
    val groupName: String? = null,

    /** Аватар группы (отдельно от partnerAvatarBase64). */
    val groupAvatarBase64: String? = null,

    /**
     * Последняя увиденная версия members.txt (анти-откат — принимаем только версию
     * строго больше сохранённой, тот же паттерн, что и в RelayListStore).
     */
    val membersVersion: Int = 0,

    /**
     * Описание группы (добавлено в DB v14). null/пусто — описания нет, карточка на
     * экране профиля скрыта (кроме админа — там всегда виден плейсхолдер-приглашение).
     * Идёт тем же путём, что имя/аватар группы — через подписанный members.txt.
     */
    val groupDescription: String? = null,

    /**
     * Закреплённые сообщения (Этап 3, DB v21) — ПОКАЗЫВАЕМЫЙ набор msgId через запятую
     * (слитые пины главного и делегатов с правом PIN, см. MembersSync.mergeSlots).
     * null/пусто — ничего не закреплено. Обновляется применением members.txt.
     */
    val pinnedMsgIds: String? = null,

    /**
     * МОИ вклады в закрепления (Этап 3, DB v21) — msgId через запятую, которые публикует
     * мой слот members.txt. Отделены от [pinnedMsgIds] (показываемого слияния), чтобы я мог
     * открепить только СВОЁ, не затирая пины других уполномоченных. null/пусто — я ничего
     * не закреплял.
     */
    val myPinnedMsgIds: String? = null,

    /**
     * Непрочитанные @упоминания меня (DB v22) — msgId через запятую сообщений от других,
     * где меня упомянули и я их ещё не прочитал. Считается локально (фоновый скан +
     * MentionUtil), даёт бейдж «@N» в списке чатов и снимок для кнопки перехода в чате.
     * null/пусто — упоминаний нет. Чистится при прочтении чата (markAsRead).
     */
    val mentionMsgIds: String? = null,

    /**
     * Верифицирован ли собеседник 1:1 (DB v23) — для галочки у ника в списке чатов.
     * Считается КРИПТОГРАФИЧЕСКИ фоновым опросом (VerifiedBadge.isVerifiedProfile по подписи
     * identity), неподделываемо. Только для 1:1; у групп/избранного/системного = false.
     * По умолчанию false — старые чаты получат галочку сами после первого опроса (§17).
     */
    val partnerVerified: Boolean = false
)

/**
 * Единая точка вычисления отображаемого имени чата (список чатов, меню, диалоги).
 * Для группы — groupName (актуален, обновляется через members.txt), с фоллбэком на
 * partnerName (creation-time снимок — как правило устаревший после переименования,
 * но не пустой). Для 1:1 — как и раньше, просто partnerName.
 *
 * Добавлено при исправлении бага: список чатов показывал старое имя/аву группы
 * после переименования, потому что часть экранов (ChatsAdapter, поиск, заголовок
 * меню чата) читала только partnerName напрямую, не проверяя isGroup/groupName.
 */
fun Chat.displayName(): String =
    if (isGroup) groupName?.takeIf { it.isNotBlank() } ?: partnerName else partnerName

/** См. [displayName] — тот же принцип для аватара. */
fun Chat.displayAvatarBase64(): String? =
    // ⚠️ Фикс (репорт: «вместо авы чата у человека может быть ава админа»): раньше для
    // группы был fallback groupAvatarBase64 ?: partnerAvatarBase64. В partnerAvatarBase64
    // для группы мог попасть профиль произвольного участника (обычно админа — см. фикс в
    // ChatActivity.doSyncProfilesOnce/processParsedProfiles), и пока настоящая ава группы
    // не доехала через members.txt, список чатов показывал аву АДМИНА как аву группы.
    // Нет авы группы — показываем плейсхолдер (инициал), а не чужое лицо.
    if (isGroup) groupAvatarBase64 else partnerAvatarBase64
