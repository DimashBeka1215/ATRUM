package com.atrum.chat.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastTimeMs DESC")
    fun observeAll(): Flow<List<Chat>>

    @Query("SELECT * FROM chats")
    suspend fun getAll(): List<Chat>

    @Query("SELECT * FROM chats WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Chat?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chat: Chat): Long

    @Update
    suspend fun update(chat: Chat)

    @Delete
    suspend fun delete(chat: Chat)

    @Query("UPDATE chats SET lastMessage = :preview, lastTimeMs = :timeMs WHERE id = :id")
    suspend fun updatePreview(id: Long, preview: String, timeMs: Long)

    @Query("UPDATE chats SET unreadCount = :count WHERE id = :id")
    suspend fun updateUnread(id: Long, count: Int)

    @Query("UPDATE chats SET lastSeenLineCount = :lines, unreadCount = 0 WHERE id = :id")
    suspend fun markAsRead(id: Long, lines: Int)

    @Query("UPDATE chats SET partnerName = :name, partnerTag = :tag, partnerAvatarBase64 = :avatar WHERE id = :id")
    suspend fun updatePartnerProfile(id: Long, name: String, tag: String?, avatar: String?)

    /** Локальный ник собеседника в 1:1-чате (см. Chat.partnerNickname). null/пусто — сброс к синканному имени. */
    @Query("UPDATE chats SET partnerNickname = :nickname WHERE id = :id")
    suspend fun updatePartnerNickname(id: Long, nickname: String?)

    @Query("UPDATE chats SET partnerLastReadIndex = :index WHERE id = :id")
    suspend fun updatePartnerLastRead(id: Long, index: Int)

    /** Помечает чат как занятый (второй участник пришёл). */
    @Query("UPDATE chats SET partnerJoined = 1 WHERE id = :id")
    suspend fun markPartnerJoined(id: Long)

    /** Все чаты у которых истёк срок жизни. */
    @Query("SELECT * FROM chats WHERE expiresAtMs IS NOT NULL AND expiresAtMs < :nowMs")
    suspend fun getExpired(nowMs: Long): List<Chat>

    /** Обновляет флаг удалённого профиля партнёра. */
    @Query("UPDATE chats SET partnerDeleted = :deleted WHERE id = :id")
    suspend fun updatePartnerDeleted(id: Long, deleted: Boolean)

    /** Обновляет транспортный токен чата без пересоздания записи. */
    @Query("UPDATE chats SET transportToken = :token WHERE id = :id")
    suspend fun updateToken(id: Long, token: String)

    /**
     * Смена владельца беседы (передача владения, ADR_MESSAGE_AUTHENTICITY.md §10). Применяется
     * ТОЛЬКО по валидному сертификату (OwnerSync) — меняет корень доверия модерации, поэтому
     * прямых вызовов из UI быть не должно.
     */
    @Query("UPDATE chats SET adminUserId = :adminUserId WHERE id = :id")
    suspend fun updateAdminUserId(id: Long, adminUserId: String)

    /**
     * Флаг «устойчивая доставка медиа» для одного чата (см. [Chat.resilientMedia]).
     * Точечный UPDATE, а не update(chat) целиком — экран настроек мог прочитать запись
     * раньше, и перезапись всей строки затёрла бы поля, изменённые тем временем синком
     * (профиль собеседника, счётчики непрочитанного и т.п.).
     */
    @Query("UPDATE chats SET resilientMedia = :enabled WHERE id = :id")
    suspend fun updateResilientMedia(id: Long, enabled: Boolean)

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    // channelId = 'favorites' — с появлением системного чата «Уведомления»
    // (SystemNotifications, тоже isFavorites = 1 ради переиспользования локальных
    // гвардов) один лишь флаг неоднозначен: без уточнения запрос мог бы вернуть чат
    // уведомлений, и создание «Избранного» при старте пропустилось бы. В SQL — ИМЯ
    // КОЛОНКИ (channelId), а не Kotlin-свойство chatId (см. Chat.@ColumnInfo).
    @Query("SELECT * FROM chats WHERE isFavorites = 1 AND channelId = 'favorites' LIMIT 1")
    suspend fun getFavoritesChat(): Chat?

    @Query("UPDATE chats SET myEphemeralPrivKeyB64 = :priv, myEphemeralPubKeyB64 = :pub WHERE id = :id")
    suspend fun updateMyEphemeralKeys(id: Long, priv: String?, pub: String?)

    @Query("UPDATE chats SET partnerEphemeralPubKeyB64 = :pub WHERE id = :id")
    suspend fun updatePartnerEphemeralKey(id: Long, pub: String?)

    // ─── Групповые чаты (ADR-001) ───────────────────────────────────────────────

    /** Поиск локальной записи чата по сетевому channelId (нужно в SyncEngine при разборе members.txt). */
    @Query("SELECT * FROM chats WHERE channelId = :chatId LIMIT 1")
    suspend fun getByChatId(chatId: String): Chat?

    @Query("UPDATE chats SET groupName = :name, groupAvatarBase64 = :avatar, groupDescription = :description WHERE id = :id")
    suspend fun updateGroupProfile(id: Long, name: String?, avatar: String?, description: String?)

    /** Обновляет версию members.txt только если новая версия строго больше — анти-откат. */
    @Query("UPDATE chats SET membersVersion = :version WHERE id = :id AND membersVersion < :version")
    suspend fun updateMembersVersionIfNewer(id: Long, version: Int)

    /** Показываемый набор закреплённых (Этап 3) — слитые пины (см. MembersSync.applyIncoming). */
    @Query("UPDATE chats SET pinnedMsgIds = :csv WHERE id = :id")
    suspend fun updatePinnedMsgIds(id: Long, csv: String?)

    /** Мои вклады в закрепления (публикуются моим слотом members.txt). */
    @Query("UPDATE chats SET myPinnedMsgIds = :csv WHERE id = :id")
    suspend fun updateMyPinnedMsgIds(id: Long, csv: String?)

    /** Непрочитанные @упоминания (msgId через запятую) — бейдж «@N» и кнопка перехода. */
    @Query("UPDATE chats SET mentionMsgIds = :csv WHERE id = :id")
    suspend fun updateMentionMsgIds(id: Long, csv: String?)

    /** Флаг верификации собеседника 1:1 (галочка у ника в списке чатов, DB v23). */
    @Query("UPDATE chats SET partnerVerified = :verified WHERE id = :id")
    suspend fun updatePartnerVerified(id: Long, verified: Boolean)
}
