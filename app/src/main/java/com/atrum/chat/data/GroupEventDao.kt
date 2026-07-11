package com.atrum.chat.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface GroupEventDao {

    @Insert
    suspend fun insert(entry: GroupEventEntry): Long

    @Insert
    suspend fun insertAll(entries: List<GroupEventEntry>)

    @Query("SELECT * FROM group_events WHERE ownerId = :ownerId ORDER BY atMs ASC")
    suspend fun getForChat(ownerId: Long): List<GroupEventEntry>

    @Query("SELECT COUNT(*) FROM group_events WHERE ownerId = :ownerId")
    suspend fun countForChat(ownerId: Long): Int

    @Query("SELECT COUNT(*) FROM group_events WHERE ownerId = :ownerId AND type = :type")
    suspend fun countByType(ownerId: Long, type: String): Int

    /** Самое раннее событие (проксирует дату создания беседы для отчёта «с создания»). */
    @Query("SELECT MIN(atMs) FROM group_events WHERE ownerId = :ownerId")
    suspend fun firstEventMs(ownerId: Long): Long?

    @Query("DELETE FROM group_events WHERE ownerId = :ownerId")
    suspend fun deleteForChat(ownerId: Long)
}
