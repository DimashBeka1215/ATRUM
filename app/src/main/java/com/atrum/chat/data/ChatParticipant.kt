package com.atrum.chat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальный кэш одного участника группового чата (ADR-001, см. ADR_GROUP_CHATS.md).
 *
 * Источник истины — подписанный members.txt (пишет только администратор группы,
 * см. adminUserId в [Chat]). Эта таблица — локальная проекция для мгновенного
 * рендера списка участников на экране (см. §1.5 CLAUDE.md — "всё грузится на месте",
 * не ждём следующего опроса), обновляется upsert'ом на каждый успешный разбор
 * members.txt в SyncEngine.
 *
 * Имя/аватар/онлайн/lastRead участника НЕ дублируются здесь — они уже живут в
 * ProfileSync (profiles.txt, Map<userId, Profile>), эта таблица хранит только то,
 * чего там нет: членство и бан-статус.
 */
@Entity(
    tableName = "chat_participants",
    indices = [Index(value = ["ownerId", "userId"], unique = true)]
)
data class ChatParticipant(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK на Chat.id (локальный Room id, НЕ сетевой channelId). */
    val ownerId: Long,

    /** Тот же userId, что и в Profile/profiles.txt. */
    val userId: String,

    /** true — забанен админом. Забаненный не может писать, чат у него удаляется локально. */
    val banned: Boolean = false,

    val joinedAtMs: Long = System.currentTimeMillis()
)
