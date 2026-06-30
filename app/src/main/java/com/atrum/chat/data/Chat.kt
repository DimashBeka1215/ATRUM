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
    val partnerEphemeralPubKeyB64: String? = null
)
