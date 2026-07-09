package com.atrum.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MuteHistoryDao {

    @Insert
    suspend fun insert(entry: MuteHistoryEntry): Long

    @Query("SELECT * FROM mute_history WHERE ownerId = :ownerId ORDER BY issuedAtMs DESC")
    suspend fun getForChat(ownerId: Long): List<MuteHistoryEntry>

    @Query("SELECT * FROM mute_history WHERE ownerId = :ownerId ORDER BY issuedAtMs DESC")
    fun observeForChat(ownerId: Long): Flow<List<MuteHistoryEntry>>

    /**
     * Отмечает досрочное снятие мута на САМОЙ ПОСЛЕДНЕЙ ещё не закрытой (unmutedEarlyAtMs
     * IS NULL) записи этого пользователя. Вызывать сразу после doUnmuteReal. Если записи
     * нет (мут выдавался до появления этой таблицы, либо на другом устройстве) — тихо
     * ничего не делает, это не ошибка.
     */
    @Query(
        """
        UPDATE mute_history SET unmutedEarlyAtMs = :atMs
        WHERE id = (
            SELECT id FROM mute_history
            WHERE ownerId = :ownerId AND userId = :userId AND unmutedEarlyAtMs IS NULL
            ORDER BY issuedAtMs DESC LIMIT 1
        )
        """
    )
    suspend fun markLatestUnmutedEarly(ownerId: Long, userId: String, atMs: Long)

    @Query("DELETE FROM mute_history WHERE ownerId = :ownerId")
    suspend fun deleteForChat(ownerId: Long)
}
