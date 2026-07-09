package com.atrum.chat.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Версия 11: повторно обнуляем myEphemeralPrivKeyB64.
 *
 * Миграция 8→9 уже делала это, но чат "Избранное" и другие строки созданные
 * позже могли иметь ненулевое значение из-за устаревшего кода. Дополнительный
 * проход гарантирует что ни одна запись не хранит приватный ключ в БД.
 * init-проверка в Chat убрана — миграция надёжнее аварийного краша.
 */
@Database(entities = [Chat::class, ChatParticipant::class, MuteHistoryEntry::class], version = 17, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun chatParticipantDao(): ChatParticipantDao
    abstract fun muteHistoryDao(): MuteHistoryDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * Версия 17: локальная (не синхронизируемая между устройствами) история мутов
         * для экрана статистики (см. MuteHistoryEntry). Новая таблица, старые данные
         * не затронуты.
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mute_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ownerId INTEGER NOT NULL,
                        userId TEXT NOT NULL,
                        issuedByUserId TEXT NOT NULL,
                        issuedAtMs INTEGER NOT NULL,
                        mutedUntilMs INTEGER NOT NULL,
                        reason TEXT DEFAULT NULL,
                        evidenceMsgIds TEXT DEFAULT NULL,
                        unmutedEarlyAtMs INTEGER DEFAULT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_mute_history_ownerId_userId ON mute_history (ownerId, userId)"
                )
            }
        }

        /** Версия 16: сообщения-основание мута (msgId'ы через запятую) — nullable, старые строки не затронуты. */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_participants ADD COLUMN mutedEvidenceIds TEXT DEFAULT NULL")
            }
        }

        /** Версия 15: мут участника группы (ADR-001) — обе колонки nullable, старые строки не затронуты. */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chat_participants ADD COLUMN mutedUntilMs INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE chat_participants ADD COLUMN mutedReason TEXT DEFAULT NULL")
            }
        }

        /** Версия 14: описание группы (ADR-001) — колонка с дефолтом, 1:1-чаты не затронуты. */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN groupDescription TEXT DEFAULT NULL")
            }
        }

        /**
         * Версия 13: реальные групповые чаты (ADR-001, см. ADR_GROUP_CHATS.md).
         * Новые колонки в chats — все с дефолтами, старые 1:1-чаты не затронуты.
         * Новая таблица chat_participants — локальный кэш членства/бана, источник
         * истины — подписанный members.txt (см. ChatParticipant.kt).
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN isGroup INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN participantLimit INTEGER DEFAULT NULL")
                database.execSQL("ALTER TABLE chats ADD COLUMN adminUserId TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE chats ADD COLUMN groupName TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE chats ADD COLUMN groupAvatarBase64 TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE chats ADD COLUMN membersVersion INTEGER NOT NULL DEFAULT 0")
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS chat_participants (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ownerId INTEGER NOT NULL,
                        userId TEXT NOT NULL,
                        banned INTEGER NOT NULL DEFAULT 0,
                        joinedAtMs INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_chat_participants_ownerId_userId ON chat_participants (ownerId, userId)"
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN partnerTag TEXT")
            }
        }

        /**
         * Миграции для очень старых версий (v1, v2) — добавляем недостающие колонки
         * чтобы не стирать чаты при fallbackToDestructiveMigration.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN lastSeenLineCount INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN partnerLastReadIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN partnerJoined INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN expiresAtMs INTEGER DEFAULT NULL")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN partnerDeleted INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE chats ADD COLUMN isFavorites INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE chats ADD COLUMN myEphemeralPrivKeyB64 TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN myEphemeralPubKeyB64 TEXT")
                database.execSQL("ALTER TABLE chats ADD COLUMN partnerEphemeralPubKeyB64 TEXT")
            }
        }

        /**
         * Обнуляем приватный эфемерный ключ X25519 у всех чатов.
         * Хранение privKey в БД нарушает forward secrecy — ключ должен жить только в RAM.
         * SQLite не поддерживает DROP COLUMN (до версии 3.35), поэтому просто ставим NULL.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE chats SET myEphemeralPrivKeyB64 = NULL")
            }
        }

        /**
         * Обнуляем gistToken и chatPassword — теперь хранятся в EncryptedSharedPreferences.
         * VACUUM освобождает страницы с затёртыми данными (WAL → main DB file).
         *
         * ВАЖНО: Secrets должны быть перенесены в Prefs ДО этой миграции через
         * миграционный код в App.onCreate (см. App.migrateChatSecretsToPrefs).
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Zero out plaintext secrets — they are now stored in EncryptedSharedPreferences
                database.execSQL("UPDATE chats SET gistToken = '', chatPassword = ''")
                database.execSQL("VACUUM")
            }
        }

        /**
         * Повторно обнуляем myEphemeralPrivKeyB64 — страховочная миграция.
         * Устраняет краш при открытии "Избранного" и других чатов где
         * поле могло остаться ненулевым несмотря на MIGRATION_8_9.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("UPDATE chats SET myEphemeralPrivKeyB64 = NULL WHERE myEphemeralPrivKeyB64 IS NOT NULL")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "atrum.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
