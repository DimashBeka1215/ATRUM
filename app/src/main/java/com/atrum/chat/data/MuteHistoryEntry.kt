package com.atrum.chat.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Локальная (НЕ синхронизируемая между устройствами) история мутов участника
 * группового чата — для экрана статистики (см. GroupStatsActivity, раздел
 * "История мутов"). Источник истины по ТЕКУЩЕМУ мут-статусу остаётся members.txt
 * (см. ChatParticipant.mutedUntilMs) — эта таблица лишь журналирует прошлые события
 * мута на устройстве администратора, который их выдавал, и не претендует на полноту
 * между несколькими админами/устройствами.
 *
 * Пишется только из PartnerProfileActivity.doMuteReal/doUnmuteReal — в момент, когда
 * локальный админ выдаёт или досрочно снимает мут. Не трогать формат существующих
 * записей задним числом — только добавлять новые.
 */
@Entity(
    tableName = "mute_history",
    indices = [Index(value = ["ownerId", "userId"])]
)
data class MuteHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** FK на Chat.id (локальный Room id, НЕ сетевой channelId). */
    val ownerId: Long,

    /** userId заглушённого участника. */
    val userId: String,

    /** userId админа, выдавшего мут (обычно = текущий пользователь, т.к. пишется только у него). */
    val issuedByUserId: String,

    /** Момент выдачи мута. */
    val issuedAtMs: Long = System.currentTimeMillis(),

    /** Запланированный момент окончания мута (см. ChatParticipant.mutedUntilMs). */
    val mutedUntilMs: Long,

    /** Причина мута. null — без причины. */
    val reason: String? = null,

    /** msgId'ы сообщений-оснований через запятую (см. MembersSync.evidenceIdsToStore). null/пусто — не указаны. */
    val evidenceMsgIds: String? = null,

    /** Момент досрочного снятия мута администратором. null — мут не снимался досрочно (истёк сам или ещё активен). */
    val unmutedEarlyAtMs: Long? = null
)
