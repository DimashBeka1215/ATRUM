package com.atrum.chat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import android.graphics.Bitmap
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.atrum.chat.nostr.NostrRelayPool
import com.atrum.chat.transport.NostrTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Application-класс. Точка входа: применяет сохранённые тему и язык ДО создания
 * любой Activity, чтобы не было вспышки неправильного цвета/языка при запуске.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        ImageCache.init(this)
        ChatSnapshotCache.init(this)
        com.atrum.chat.transport.NostrMessageStore.init(this)
        // Tor стартует ЛЕНИВО — только когда открыт/создан чат через Tor (это делают
        // TransportFactory / CreateChat / Join). Прямые чаты Tor не поднимают.
        // Как только Tor поднялся — заранее прогреваем цепочки к реле.
        AppScope.launch {
            TorManager.status.first { it == TorManager.TorStatus.READY }
            NostrRelayPool.prewarm(NostrTransport.RELAYS)
        }
        // Подчищаем кадры стикеров прошлых версий формата (в фоне — это файловый I/O).
        Thread {
            StickerDiskCache.cleanupOldVersions(cacheDir)
            // Кэш проигрывания голосовых не должен расти бесконечно.
            runCatching { StickerDiskCache.trimDir(java.io.File(cacheDir, "voice_play"), 32L * 1024 * 1024, ".m4a") }
        }.start()
        // Предзагрузка нейрошумодава GTCRN в фоне (модель тяжело инициализировать) —
        // чтобы первая запись не лагала на загрузке.
        GtcrnDenoiser.preload(this)
        CrashHandler.install(this)

        // Если при прошлом запуске был краш и лог сохранился — показать сразу.
        // Это ловит случай когда процесс умер до того как CrashActivity успела запуститься.
        val savedLog = CrashHandler.getLastLog(this)
        if (savedLog != null) {
            CrashHandler.clearLastLog(this)
            try {
                startActivity(Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.EXTRA_LOG, savedLog)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {}
        }

        val prefs = Prefs(this)
        BatteryUtils.animatePersistOverride = prefs.lowBattAnimate
        // Migrate chat secrets from plaintext Room DB to EncryptedSharedPreferences
        // before the DB migration zeroes them out (MIGRATION_9_10).
        migrateChatSecretsToPrefs(prefs)
        if (prefs.pushEnabled) {
            MessageWatchService.start(this)
            PushCatchupWorker.schedule(this)
        }
        AppCompatDelegate.setDefaultNightMode(modeFromTheme(prefs.appTheme))
        val lang = prefs.appLanguage
        AppCompatDelegate.setApplicationLocales(
            if (lang.isEmpty()) LocaleListCompat.getEmptyLocaleList()
            else LocaleListCompat.forLanguageTags(lang)
        )

        // Повторная блокировка при уходе в фон: когда последний экран
        // останавливается (и это не поворот экрана), помечаем приложение
        // заблокированным, если задан PIN. Возврат на передний план → LockActivity.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedCount = 0
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = startedCount == 0
                startedCount++
                inForeground = true
                // Вернулись на передний план — закрываем «окно шеринга», чтобы дальше
                // автоблокировка работала как обычно.
                if (wasBackground) AppLock.endShareGrace()
                // Пользователь открыл приложение — убираем пуш о непрочитанных и
                // сбрасываем счётчик (если останутся непрочитанные — пуш вернётся в фоне).
                NotificationHelper.cancelMessages(activity)
                prefs.pushNotifiedTotal = 0
            }
            override fun onActivityStopped(activity: Activity) {
                startedCount--
                if (startedCount <= 0) {
                    startedCount = 0
                    inForeground = false
                    // Не блокируем во время исходящего шеринга (см. AppLock.beginShareGrace):
                    // иначе возврат из Telegram перебивается экраном блокировки.
                    if (!activity.isChangingConfigurations && prefs.hasLocalPassword()
                        && !AppLock.shareGraceActive()) {
                        // Запоминаем момент ухода в фон: краткий возврат (в пределах
                        // AUTO_LOCK_GRACE_MS) не будет перепрашивать пароль (см. SecureActivity).
                        AppLock.markBackgrounded()
                        AppLock.locked = true
                    }
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        /** true пока хотя бы один экран на переднем плане. Фоновый сервис пушей по
         *  этому флагу НЕ опрашивает сеть, пока приложение открыто. */
        @Volatile var inForeground: Boolean = false

        /** Transition state for smooth circular reveal theme switching. */
        var screenshot: Bitmap? = null
        var centerX: Int = 0
        var centerY: Int = 0

        /** Converts stored theme string → AppCompat night-mode constant. */
        fun modeFromTheme(theme: String): Int = when (theme) {
            THEME_LIGHT  -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK   -> AppCompatDelegate.MODE_NIGHT_YES
            else         -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }

        const val THEME_DARK   = "dark"
        const val THEME_LIGHT  = "light"
        const val THEME_SYSTEM = "system"

        const val LANG_SYSTEM = ""
        const val LANG_RU     = "ru"
        const val LANG_EN     = "en"
    }

    /**
     * One-time migration: reads chat secrets from plaintext Room DB rows
     * (before MIGRATION_9_10 zeroes them) and saves them in EncryptedSharedPreferences.
     *
     * Safe to call every launch — if secrets already exist in Prefs we skip.
     * Uses raw SQLite query to avoid Room model assumptions about DB version.
     */
    private fun migrateChatSecretsToPrefs(prefs: Prefs) {
        try {
            val dbFile = getDatabasePath("atrum.db")
            if (!dbFile.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.use { sqlite ->
                val cursor = sqlite.rawQuery(
                    "SELECT gistId, gistToken, chatPassword FROM chats WHERE gistToken != '' OR chatPassword != ''",
                    null
                )
                cursor.use { c ->
                    while (c.moveToNext()) {
                        val gistId = c.getString(0) ?: continue
                        val token = c.getString(1) ?: ""
                        val password = c.getString(2) ?: ""
                        // Only save if not already in Prefs (don't overwrite)
                        if (prefs.getChatToken(gistId).isEmpty() && token.isNotEmpty()) {
                            prefs.saveChatSecrets(gistId, token, password)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Non-fatal — DB may not exist yet or may already be at v10
        }
    }
}
