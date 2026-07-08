package com.atrum.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatParticipantDao {

    @Query("SELECT * FROM chat_participants WHERE ownerId = :ownerId ORDER BY joinedAtMs ASC")
    fun observeForChat(ownerId: Long): Flow<List<ChatParticipant>>

    @Query("SELECT * FROM chat_participants WHERE ownerId = :ownerId ORDER BY joinedAtMs ASC")
    suspend fun getForChat(ownerId: Long): List<ChatParticipant>

    @Query("SELECT * FROM chat_participants WHERE ownerId = :ownerId AND userId = :userId LIMIT 1")
    suspend fun getOne(ownerId: Long, userId: String): ChatParticipant?

    @Query("SELECT COUNT(*) FROM chat_participants WHERE ownerId = :ownerId AND banned = 0")
    suspend fun countActive(ownerId: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(participant: ChatParticipant)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(participants: List<ChatParticipant>)

    /** Убирает локальные записи об участниках, которых больше нет в свежем members.txt. */
    @Query("DELETE FROM chat_participants WHERE ownerId = :ownerId AND userId NOT IN (:keepUserIds)")
    suspend fun pruneRemoved(ownerId: Long, keepUserIds: List<String>)

    @Query("UPDATE chat_participants SET banned = 1 WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun ban(ownerId: Long, userId: String)

    /**
     * Разбан (ADR-001, §Меню забаненных). Локально снимает флаг сразу — источник
     * истины всё равно members.txt, публикуемый вызывающим кодом отдельно
     * (см. PartnerProfileActivity.doUnbanReal). Само по себе НЕ возвращает человеку
     * доступ в чат — ему нужно заново получить приглашение (его локальный чат и
     * секреты были удалены на его устройстве в момент бана, см. ChatActivity.checkSelfBanned).
     */
    @Query("UPDATE chat_participants SET banned = 0 WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun unban(ownerId: Long, userId: String)

    @Query("DELETE FROM chat_participants WHERE ownerId = :ownerId")
    suspend fun deleteForChat(ownerId: Long)
}
