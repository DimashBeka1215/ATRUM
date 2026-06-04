package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Глобальный перехватчик краша.
 *
 * Устанавливается в App.onCreate() через Thread.setDefaultUncaughtExceptionHandler.
 * При крашe:
 *   1. Формирует лог (версия, устройство, стектрейс).
 *   2. Сохраняет СИНХРОННО в SharedPreferences (.commit()) + дублирует в файл.
 *   3. Открывает CrashActivity вместо стандартного диалога "Приложение остановлено".
 *
 * Каждый шаг обёрнут в отдельный try-catch — сбой одного не убивает остальные.
 * Обработчик установлен на ВСЕ ThreadGroup через setDefaultUncaughtExceptionHandler.
 */
class CrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(crashThread: Thread, throwable: Throwable) {
        // Шаг 1: строим лог. Если не получилось — минимальный fallback.
        val log = try {
            buildLog(crashThread, throwable)
        } catch (e: Throwable) {
            "CRASH (log build failed: ${e.message})\n${throwable}"
        }

        // Шаг 2: сохраняем в SharedPreferences СИНХРОННО (.commit() не .apply()).
        // .apply() асинхронен — процесс может умереть раньше записи.
        try {
            appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_LOG, log)
                .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                .commit()   // ← синхронно, гарантирована запись до killProcess
        } catch (_: Throwable) {}

        // Шаг 3: дублируем в файл на случай если SharedPreferences недоступны.
        try {
            val f = File(appContext.filesDir, "last_crash.txt")
            f.writeText(log, Charsets.UTF_8)
        } catch (_: Throwable) {}

        // Шаг 4: запускаем CrashActivity.
        try {
            launchCrashActivity(log)
        } catch (_: Throwable) {}

        // Пауза чтобы Intent доставился до Activity (startActivity асинхронен).
        try { Thread.sleep(500) } catch (_: Throwable) {}

        android.os.Process.killProcess(android.os.Process.myPid())
    }

    private fun buildLog(crashThread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stackTrace = sw.toString()

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        val versionName = try {
            appContext.packageManager
                .getPackageInfo(appContext.packageName, 0)
                .versionName ?: "?"
        } catch (_: Exception) { "?" }

        val versionCode = try {
            val info = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toString()
            }
        } catch (_: Exception) { "?" }

        return buildString {
            appendLine("═══════════════════════════════════")
            appendLine("  Atrum Chat — отчёт об ошибке")
            appendLine("═══════════════════════════════════")
            appendLine()
            appendLine("Время:        $timestamp")
            appendLine("Версия:       $versionName ($versionCode)")
            appendLine("Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Устройство:   ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Поток:        ${crashThread.name} (id=${crashThread.id})")
            appendLine()
            appendLine("─── Стектрейс ───────────────────")
            appendLine()
            append(stackTrace)
        }
    }

    private fun launchCrashActivity(log: String) {
        val intent = Intent(appContext, CrashActivity::class.java).apply {
            putExtra(CrashActivity.EXTRA_LOG, log)
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        }
        appContext.startActivity(intent)
    }

    companion object {
        const val PREFS_NAME = "atrum_crash"
        const val KEY_LAST_LOG = "last_crash_log"
        const val KEY_LAST_TIME = "last_crash_time"

        /** Очищает сохранённый лог (вызывать после показа). */
        fun clearLastLog(context: Context) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().remove(KEY_LAST_LOG).remove(KEY_LAST_TIME).commit()
            } catch (_: Throwable) {}
            try {
                java.io.File(context.filesDir, "last_crash.txt").delete()
            } catch (_: Throwable) {}
        }

        /** Читает последний сохранённый лог (из SharedPreferences или файла-дубликата). */
        fun getLastLog(context: Context): String? {
            // Пробуем SharedPreferences
            val fromPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_LOG, null)
            if (!fromPrefs.isNullOrBlank()) return fromPrefs

            // Fallback: файловый дубликат
            return try {
                val f = File(context.filesDir, "last_crash.txt")
                if (f.exists()) f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
                else null
            } catch (_: Exception) { null }
        }

        /**
         * Устанавливает обработчик на ВСЕ потоки через setDefaultUncaughtExceptionHandler.
         * Дополнительно ставит обработчик на текущую ThreadGroup для подстраховки.
         * Вызывать из App.onCreate() как можно раньше.
         */
        fun install(context: Context) {
            val handler = CrashHandler(context.applicationContext)
            // Глобальный обработчик для всех потоков
            Thread.setDefaultUncaughtExceptionHandler(handler)
            // Подстраховка: явно ставим на главный поток
            try {
                Thread.currentThread().uncaughtExceptionHandler = handler
            } catch (_: Throwable) {}
        }

        /**
         * Ручной репортинг ошибки — аналог uncaughtException, но без убийства процесса.
         * Используется когда ошибка поймана явно (например в сервисе), но нужно
         * показать CrashActivity с полным логом для отладки.
         */
        fun report(context: Context, title: String, throwable: Throwable) {
            try {
                val sw = java.io.StringWriter()
                throwable.printStackTrace(java.io.PrintWriter(sw))
                val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                    .format(java.util.Date())
                val versionName = try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
                } catch (_: Exception) { "?" }

                val log = buildString {
                    appendLine("═══════════════════════════════════")
                    appendLine("  Atrum Chat — отчёт об ошибке")
                    appendLine("═══════════════════════════════════")
                    appendLine()
                    appendLine("Время:        $timestamp")
                    appendLine("Версия:       $versionName")
                    appendLine("Android:      ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Устройство:   ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Контекст:     $title")
                    appendLine()
                    appendLine("─── Стектрейс ───────────────────")
                    appendLine()
                    append(sw.toString())
                }

                // Синхронная запись
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_LOG, log)
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .commit()

                try { File(context.filesDir, "last_crash.txt").writeText(log, Charsets.UTF_8) } catch (_: Throwable) {}

                context.startActivity(
                    Intent(context, CrashActivity::class.java).apply {
                        putExtra(CrashActivity.EXTRA_LOG, log)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK or
                            Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        )
                    }
                )
            } catch (_: Exception) {}
        }
    }
}
