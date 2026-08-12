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
        appCtx = applicationContext
        if (BuildInfo.isTampered(this)) {
            UpdateRequiredActivity.launch(this)
            return
        }
        ImageCache.init(this)
        ChatSnapshotCache.init(this)
        com.atrum.chat.transport.NostrMessageStore.init(this)
        // Tor стартует ЛЕНИВО — только когда открыт/создан чат через Tor (это делают
        // TransportFactory / CreateChat / Join). Прямые чаты Tor не поднимают.
        // Как только Tor поднялся — заранее прогреваем цепочки к реле.
        AppScope.launch {
            if (TorManager.TOR_DISABLED) {
                // Tor выключен (kill-switch) — греем ПРЯМЫЕ соединения сразу, не ждём Tor READY
                // (он не наступит), иначе корутина повисла бы, а прогрев не сработал.
                NostrRelayPool.prewarm(NostrTransport.RELAYS, useTor = false)
            } else {
                TorManager.status.first { it == TorManager.TorStatus.READY }
                NostrRelayPool.prewarm(NostrTransport.RELAYS)
            }
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

        // Планировщик публикаций: дочищаем недоставленные админ-публикации прошлой
        // сессии (dirty-флаги персистентны — см. PublishScheduler.resume).
        PublishScheduler.resume(this)

        // Одноразовая починка стикеров (репорт: «собеседник не видит мои стикеры»): забываем
        // ссылки на контент, залитый эфемерным сессионным ключом, чтобы он перезалился
        // парольным шифрованием и стал читаемым у собеседника навсегда. См. Prefs.
        runCatching { Prefs(this).resetStickerContentRefsOnce() }

        val prefs = Prefs(this)
        BatteryUtils.animatePersistOverride = prefs.lowBattAnimate
        ConnectionPrefs.loadFrom(prefs)
        // Холодный старт: если задан локальный пароль — приложение сразу считается
        // заблокированным. Иначе после запуска процесса (первый заход/после kill'а системой)
        // AppLock.locked == false, и SecureActivity.onStart пускает пользователя прямо в
        // последнее открытое окно БЕЗ запроса пароля. Пароль тогда появлялся только после
        // ухода в фон и возврата (там locked=true) — это и есть баг «иногда пароль не
        // спрашивается». backgroundedAt остаётся 0 → льготный период (grace) не сработает,
        // и LockActivity гарантированно покажется.
        if (prefs.hasLocalPassword()) AppLock.locked = true
        // Migrate chat secrets from plaintext Room DB to EncryptedSharedPreferences
        // before the DB migration zeroes them out (MIGRATION_9_10).
        migrateChatSecretsToPrefs(prefs)
        // Пользователь открыл приложение → снимаем флаг «сам закрыл»: дальше фоновая доставка
        // и резервное воскрешение при Doze-kill снова разрешены. Осознанное закрытие (смахивание
        // из «недавних») снова взведёт флаг, и служба не будет воскресать (репорт: «не могу
        // закрыть»). См. MessageWatchService.onTaskRemoved / PushCatchupWorker.
        prefs.serviceUserDismissed = false
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
                // Пользователь открыл приложение — прячем пуш о непрочитанных, пока приложение
                // открыто. ⚠️ НЕ сбрасываем pushNotifiedTotal (репорт: «"У вас N сообщений"
                // неактуальное и слишком часто напоминает»): раньше сброс в 0 приводил к тому,
                // что после КАЖДОГО открытия-закрытия БЕЗ прочтения фон видел totalUnread > 0 и
                // СНОВА алертил (со звуком) те же, уже виденные сообщения. Теперь baseline
                // сохраняется — повторного алерта об одном и том же нет; звук только на реально
                // НОВЫЕ сообщения. Прочитал чат → фон сам обнулит счётчик и снимет пуш; частично
                // прочитал → фон покажет остаток ТИХО (без звука). См. MessageWatchService.
                NotificationHelper.cancelMessages(activity)
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

        /** Глобальный app-контекст для не-Activity кода. Ставится первым делом в onCreate. */
        @Volatile var appCtx: android.content.Context? = null

        /** chatId открытого сейчас чата (или null). Фоновый синк членделения групп
         *  пропускает его — этот чат и так поллится своим ChatActivity (1с), а лишний
         *  параллельный loadAll через Tor замедлял бы загрузку самой беседы (репорт). */
        @Volatile var currentOpenChatId: String? = null

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
