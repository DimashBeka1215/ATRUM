package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Build
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
 *   2. Сохраняет в SharedPreferences (доступен между запусками).
 *   3. Открывает CrashActivity вместо стандартного диалога "Приложение остановлено".
 *
 * Системный обработчик НЕ вызывается — пользователя не выбрасывает на рабочий стол.
 */
class CrashHandler(private val appContext: Context) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(crashThread: Thread, throwable: Throwable) {
        try {
            val log = buildLog(crashThread, throwable)
            saveLog(log)
            launchCrashActivity(log)
        } catch (_: Exception) {
            // Если даже наш обработчик упал — хотя бы не зависнуть
        }
        // Небольшая пауза чтобы Intent успел доставиться до запуска Activity
        Thread.sleep(300)
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
            appendLine("Поток:        ${crashThread.name}")
            appendLine()
            appendLine("─── Стектрейс ───────────────────")
            appendLine()
            append(stackTrace)
        }
    }

    private fun saveLog(log: String) {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_LOG, log)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()
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

        /** Читает последний сохранённый лог (для повторного показа если нужно). */
        fun getLastLog(context: Context): String? =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_LOG, null)

        /** Устанавливает обработчик. Вызывать из App.onCreate(). */
        fun install(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context.applicationContext))
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

                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_LOG, log)
                    .putLong(KEY_LAST_TIME, System.currentTimeMillis())
                    .apply()

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
            } catch (_: Exception) {
                // последний рубеж — молча
            }
        }
    }
}
