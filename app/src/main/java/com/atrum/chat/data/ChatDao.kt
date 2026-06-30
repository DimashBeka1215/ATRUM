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

    @Query("UPDATE chats SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: Long, pinned: Boolean)

    @Query("SELECT * FROM chats WHERE isFavorites = 1 LIMIT 1")
    suspend fun getFavoritesChat(): Chat?

    @Query("UPDATE chats SET myEphemeralPrivKeyB64 = :priv, myEphemeralPubKeyB64 = :pub WHERE id = :id")
    suspend fun updateMyEphemeralKeys(id: Long, priv: String?, pub: String?)

    @Query("UPDATE chats SET partnerEphemeralPubKeyB64 = :pub WHERE id = :id")
    suspend fun updatePartnerEphemeralKey(id: Long, pub: String?)
}
