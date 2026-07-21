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

    /**
     * Убрать ОДНОГО участника, только если он не забанен (ADR-001, децентрализованный
     * ростер: участник вышел сам — опубликовал profiles.txt с left=true). Забаненного
     * НЕ трогаем: его запись остаётся видимой админу (бан наблюдаем), см. GroupRosterSync.
     */
    @Query("DELETE FROM chat_participants WHERE ownerId = :ownerId AND userId = :userId AND banned = 0")
    suspend fun removeIfNotBanned(ownerId: Long, userId: String)

    /**
     * Мут (см. ChatParticipant.mutedUntilMs). Публикация members.txt — отдельно, см.
     * MembersSync.publish. [evidenceIds] — msgId'ы сообщений-оснований через запятую
     * (см. ChatParticipant.mutedEvidenceIds), null/пусто — без оснований.
     */
    @Query("UPDATE chat_participants SET mutedUntilMs = :untilMs, mutedReason = :reason, mutedEvidenceIds = :evidenceIds WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun mute(ownerId: Long, userId: String, untilMs: Long, reason: String?, evidenceIds: String?)

    /** Досрочное снятие мута администратором. */
    @Query("UPDATE chat_participants SET mutedUntilMs = NULL, mutedReason = NULL, mutedEvidenceIds = NULL WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun unmute(ownerId: Long, userId: String)

    /**
     * Права делегированного администратора (см. AdminPermissions). 0 — снятие роли.
     * Назначает только главный админ; публикация members.txt с новой маской — отдельно
     * (PublishScheduler.markMembersDirty). На устройстве назначенного новая маска
     * применяется MembersSync.applyIncoming и рождает уведомление о роли (§14, 1d).
     */
    @Query("UPDATE chat_participants SET permissions = :permissions WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun setPermissions(ownerId: Long, userId: String, permissions: Int)

    /**
     * TOFU-пиннинг публичного identity-ключа участника (ADR_MESSAGE_AUTHENTICITY.md, Фаза 1).
     * Ставит ключ ТОЛЬКО если он ещё не закреплён (первый наблюдаемый выигрывает) — так
     * поздняя подмена профиля не перепишет уже зафиксированный ключ. Расхождение позже
     * поймает проверка подписи авторства (Фаза 2). Идемпотентно: после установки — no-op.
     */
    @Query("UPDATE chat_participants SET pinnedIdentityPubKey = :idk WHERE ownerId = :ownerId AND userId = :userId AND (pinnedIdentityPubKey IS NULL OR pinnedIdentityPubKey = '')")
    suspend fun pinIdentityIfEmpty(ownerId: Long, userId: String, idk: String)

    /**
     * БЕЗУСЛОВНАЯ установка закреплённого identity-ключа (передача владения, OwnerSync). В отличие
     * от [pinIdentityIfEmpty] перезаписывает существующий — применяется ТОЛЬКО по валидному
     * сертификату передачи владения (новый владелец авторитетнее TOFU). Не вызывать из UI напрямую.
     */
    @Query("UPDATE chat_participants SET pinnedIdentityPubKey = :idk WHERE ownerId = :ownerId AND userId = :userId")
    suspend fun setPinnedIdentity(ownerId: Long, userId: String, idk: String)
}
