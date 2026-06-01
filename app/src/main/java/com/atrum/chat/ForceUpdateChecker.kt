package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ══════════════════════════════════════════════════════════════════════
 * Force-update checker — проверяет минимальную допустимую версию.
 * ══════════════════════════════════════════════════════════════════════
 *
 * Принцип работы:
 *   1. При старте ChatsListActivity вызывается [check].
 *   2. Загружается JSON из публичного gist (CONFIG_URL).
 *   3. Если текущий versionCode < minVersionCode — показывается
 *      блокирующий диалог без кнопки "Закрыть".
 *   4. Кнопка "Обновить" открывает ссылку из JSON (Google Play / GitHub Release).
 *   5. При недоступной сети — проверка молча пропускается.
 *   6. Результат кэшируется на 6 часов чтобы не тормозить каждый запуск.
 *
 * Настройка (один раз):
 *   Создай публичный gist с файлом "atrum_update.json":
 *   {
 *     "minVersionCode": 137,
 *     "minVersionName": "4.1.0",
 *     "message": "Вышла важная версия с исправлениями безопасности.",
 *     "updateUrl": "https://github.com/your-repo/releases"
 *   }
 *   Вставь raw URL файла в CONFIG_URL ниже.
 *
 * Поля JSON:
 *   minVersionCode  — int, минимальный допустимый versionCode
 *   minVersionName  — string, для отображения пользователю (например "4.1.0")
 *   message         — string, причина обновления (показывается в диалоге)
 *   updateUrl       — string, ссылка для кнопки "Обновить"
 *
 * ⚠️ AI INSTRUCTION:
 *   При каждом релизе с breaking changes или security fixes — обновляй
 *   JSON по адресу CONFIG_URL, выставляя актуальный minVersionCode.
 *   Пользователи старых версий увидят блокирующий диалог при следующем запуске.
 * ══════════════════════════════════════════════════════════════════════
 */
object ForceUpdateChecker {

    /**
     * Raw URL файла atrum_update.json в публичном gist.
     * Замените на реальный URL вашего гиста перед публикацией.
     */
    private const val CONFIG_URL = "https://gist.githubusercontent.com/atrum-chat/f4d7b2e1c0a5b6d7e8f9/raw/atrum_update.json"

    private const val PREFS_NAME          = "atrum_force_update"
    private const val KEY_CACHED_JSON     = "cached_json"
    private const val KEY_CACHE_TIMESTAMP = "cache_ts"
    private const val CACHE_TTL_MS        = 6 * 60 * 60 * 1000L  // 6 часов
    private const val CONNECT_TIMEOUT_MS  = 5_000
    private const val READ_TIMEOUT_MS     = 5_000

    data class UpdateConfig(
        val minVersionCode: Int,
        val minVersionName: String,
        val message: String,
        val updateUrl: String
    )

    /**
     * Проверяет необходимость обновления.
     * Возвращает [UpdateConfig] если текущая версия устарела, null — если всё ок или нет сети.
     *
     * Suspend — вызывать из корутины (например lifecycleScope.launch).
     */
    suspend fun check(context: Context): UpdateConfig? = withContext(Dispatchers.IO) {
        try {
            val config = fetchConfig(context) ?: return@withContext null
            val currentVersionCode = currentVersionCode(context)
            if (currentVersionCode < config.minVersionCode) config else null
        } catch (_: Exception) {
            // Сеть недоступна или JSON кривой — молча пропускаем
            null
        }
    }

    /**
     * Показывает блокирующий диалог обновления.
     * Вызывать из main thread после того как [check] вернул не-null.
     */
    fun showBlockingDialog(context: android.app.Activity, config: UpdateConfig) {
        if (context.isFinishing || context.isDestroyed) return

        var goingToUpdate = false

        val dialog = NeonDialog.showConfirm(
            ctx = context,
            title = "Требуется обновление",
            message = "Версия ${config.minVersionName} обязательна для продолжения работы.\n\n${config.message}",
            positiveText = "Обновить",
            negativeText = "Закрыть приложение",
            positiveIsDestructive = false,
            onPositive = {
                goingToUpdate = true
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(config.updateUrl))
                    )
                } catch (_: Exception) {}
                // После возврата из браузера снова показываем диалог
                context.runOnUiThread { showBlockingDialog(context, config) }
            }
        )
        dialog.setCancelable(false)
        // Negative ("Закрыть приложение") вызывает только dismiss — завершаем процесс.
        // Positive устанавливает goingToUpdate=true перед dismiss — не завершаем.
        dialog.setOnDismissListener {
            if (!goingToUpdate) context.finishAffinity()
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun fetchConfig(context: Context): UpdateConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Возвращаем кэш если свежий
        val cachedJson = prefs.getString(KEY_CACHED_JSON, null)
        val cacheTs    = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        if (cachedJson != null && System.currentTimeMillis() - cacheTs < CACHE_TTL_MS) {
            return parseConfig(cachedJson)
        }

        // Загружаем с сервера
        val conn = URL(CONFIG_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        conn.requestMethod  = "GET"
        conn.setRequestProperty("User-Agent", "AtrumChat/${currentVersionName(context)}")

        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = conn.inputStream.bufferedReader().readText()

            // Кэшируем
            prefs.edit()
                .putString(KEY_CACHED_JSON, json)
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()

            parseConfig(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseConfig(json: String): UpdateConfig? {
        return try {
            val obj = JSONObject(json)
            UpdateConfig(
                minVersionCode = obj.getInt("minVersionCode"),
                minVersionName = obj.optString("minVersionName", ""),
                message        = obj.optString("message", "Пожалуйста, обновите приложение."),
                updateUrl      = obj.optString("updateUrl", "https://github.com")
            )
        } catch (_: Exception) { null }
    }

    private fun currentVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (_: Exception) { Int.MAX_VALUE }

    private fun currentVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    // ══════════════════════════════════════════════════════════════════════
    // GitHub Releases — необязательное обновление с ченджлогом
    // ══════════════════════════════════════════════════════════════════════
    //
    // ⚙️ Включить:
    //   1. Установи RELEASES_ENABLED = true
    //   2. Замени REPO на "username/reponame" своего репозитория
    //
    // Как работает:
    //   • При запуске ChatsListActivity вызывается checkLatestRelease().
    //   • Если на GitHub есть релиз с тегом новее текущей версии —
    //     показывается диалог с ченджлогом (кнопки «Обновить» / «Позже»).
    //   • В настройках строка «Версия» показывает статус и кнопку «Обновить».
    //   • Сравнение по semver: тег «v1.0.2» против versionName «1.0.1».
    //   • Кэш 6 часов — лишних запросов нет.
    // ══════════════════════════════════════════════════════════════════════

    /** Флаг включения. Выключен до указания репозитория. */
    const val RELEASES_ENABLED = true

    /** Репозиторий в формате "username/reponame". Заменить перед включением. */
    private const val REPO = "DimashBeka1215/ATRUM"

    private const val RELEASES_API        = "https://api.github.com/repos/$REPO/releases/latest"
    private const val KEY_RELEASE_JSON    = "cached_release_json"
    private const val KEY_RELEASE_TS      = "cached_release_ts"

    data class ReleaseInfo(
        val tagName: String,
        val changelog: String,
        val htmlUrl: String
    )

    /**
     * Проверяет наличие нового релиза на GitHub.
     * Возвращает [ReleaseInfo] если тег новее текущей версии, иначе null.
     * При выключенном [RELEASES_ENABLED] или нет сети — null.
     */
    suspend fun checkLatestRelease(context: Context): ReleaseInfo? = withContext(Dispatchers.IO) {
        if (!RELEASES_ENABLED) return@withContext null
        try {
            val currentVersion = currentVersionName(context)
            val info = fetchRelease(context) ?: return@withContext null
            if (compareSemver(info.tagName, currentVersion) > 0) info else null
        } catch (_: Exception) { null }
    }

    /**
     * Диалог необязательного обновления с ченджлогом.
     * Вызывать из main thread.
     */
    fun showOptionalUpdateDialog(context: android.app.Activity, info: ReleaseInfo) {
        if (context.isFinishing || context.isDestroyed) return
        val title = context.getString(R.string.update_dialog_title, info.tagName)
        val body  = info.changelog.ifBlank { context.getString(R.string.update_dialog_body_fallback) }
        NeonDialog.showConfirm(
            ctx          = context,
            title        = title,
            message      = body,
            positiveText = context.getString(R.string.update_dialog_positive),
            negativeText = context.getString(R.string.update_dialog_negative),
            onPositive   = {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.htmlUrl)))
                } catch (_: Exception) {}
            }
        )
    }

    // ── internal ─────────────────────────────────────────────────────────

    private fun fetchRelease(context: Context): ReleaseInfo? {
        val prefs    = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached   = prefs.getString(KEY_RELEASE_JSON, null)
        val cacheTs  = prefs.getLong(KEY_RELEASE_TS, 0L)
        if (cached != null && System.currentTimeMillis() - cacheTs < CACHE_TTL_MS) {
            return parseRelease(cached)
        }

        val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

        return try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = conn.inputStream.bufferedReader().readText()
            prefs.edit()
                .putString(KEY_RELEASE_JSON, json)
                .putLong(KEY_RELEASE_TS, System.currentTimeMillis())
                .apply()
            parseRelease(json)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRelease(json: String): ReleaseInfo? = try {
        val obj = org.json.JSONObject(json)
        ReleaseInfo(
            tagName   = obj.optString("tag_name", ""),
            changelog = obj.optString("body", ""),
            htmlUrl   = obj.optString("html_url", "")
        )
    } catch (_: Exception) { null }
}
