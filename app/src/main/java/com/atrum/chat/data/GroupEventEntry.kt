package com.atrum.chat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальный (НЕ синхронизируемый) журнал событий группы — приходы/уходы участников,
 * для раздела «Беседа» экрана статистики (см. GroupStatsActivity). Пишется диффом
 * списка участников при каждом применении members.txt (см. MembersSync.applyIncoming):
 * появился новый userId → [TYPE_JOIN], пропал → [TYPE_LEAVE].
 *
 * ⚠️ Начинает копиться С МОМЕНТА установки версии с этой таблицей — прошлые уходы
 * восстановить неоткуда. Полнота — по тому, что видело это устройство (как история
 * мутов); у разных участников журнал может немного отличаться. Приходы известных на
 * момент миграции участников засеиваются из ChatParticipant.joinedAtMs (см.
 * GroupEventLog.seedJoinsIfEmpty), чтобы линия «участники со временем» стартовала с
 * создания беседы, а не с нуля.
 */
@Entity(
    tableName = "group_events",
    indices = [Index(value = ["ownerId"])]
)
data class GroupEventEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK на Chat.id (локальный Room id). */
    val ownerId: Long,

    /** userId участника события. */
    val userId: String,

    /** [TYPE_JOIN] — присоединился, [TYPE_LEAVE] — вышел/удалён из members.txt. */
    val type: String,

    /** Момент события. Для засеянных приходов = ChatParticipant.joinedAtMs. */
    val atMs: Long = System.currentTimeMillis()
) {
    companion object {
        const val TYPE_JOIN = "join"
        const val TYPE_LEAVE = "leave"
    }
}
