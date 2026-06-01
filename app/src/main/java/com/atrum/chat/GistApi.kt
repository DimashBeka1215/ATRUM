package com.atrum.chat

import com.atrum.chat.transport.AllGistData
import com.atrum.chat.transport.ChatAndReactions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Работа с GitHub Gist API. Все методы — suspend, должны вызываться из корутин.
 *
 * Один gist может содержать несколько файлов. По умолчанию работаем с chat.txt
 * (зашифрованные сообщения), но методы *File принимают произвольное имя — это
 * нужно для profiles.txt (обмен профилями).
 */
class GistApi(
    val token: String,          // internal val — нужен GistTransport для image gist
    val gistId: String,         // public — нужен GistTransport.chatId для Argon2 соли
    private val fileName: String = "chat.txt"
) {
    /** Обычный клиент: таймауты для чтения/записи сообщений и профилей. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "GithubChat-App")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Клиент для загрузки изображений: увеличенные таймауты.
     * POST при создании image gist может занять до 60+ сек для больших файлов.
     */
    private val uploadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "GithubChat-App")
                .build()
            chain.proceed(request)
        }
        .build()

    private val gistUrl = "https://api.github.com/gists/$gistId"

    /**
     * ETag последнего успешного GET /gists/:id.
     * Используется в loadContentIfChanged() для условных запросов (If-None-Match).
     * При 304 Not Modified контент не изменился — экономим API-квоту.
     */
    @Volatile private var gistEtag: String? = null

    /**
     * Кэш-подсказка для appendLine: последнее известное содержимое chat.txt.
     *
     * Обновляется после каждого успешного poll'а (через updateChatContentHint)
     * и после каждого успешного appendLine. Когда подсказка свежая (<15 сек),
     * appendLine пропускает GET-запрос и строит новый контент локально —
     * это устраняет рост задержки отправки по мере накопления истории.
     */
    @Volatile private var appendHintContent: String = ""
    @Volatile private var appendHintMs: Long = 0L

    /**
     * Обновляет кэш-подсказку для appendLine.
     * Вызывать из ChatActivity после каждого успешного чтения chatContent.
     */
    fun updateChatContentHint(content: String) {
        appendHintContent = content
        appendHintMs = System.currentTimeMillis()
    }

    /**
     * Обновляет только временну́ю метку подсказки без изменения содержимого.
     *
     * Вызывать когда сервер вернул 304 Not Modified — это подтверждение что
     * наш кэш актуален, поэтому можно продлить TTL без повторного GET.
     * Без этого подсказка протухала бы через 60 сек даже при активном чате
     * без новых сообщений, и каждый следующий append снова делал бы GET.
     */
    fun touchChatContentHint() {
        if (appendHintContent.isNotEmpty()) {
            appendHintMs = System.currentTimeMillis()
        }
    }

    /** Сбрасывает кэшированный ETag — нужно вызывать после clearHistory. */
    fun resetEtag() { gistEtag = null }

    /**
     * Мьютекс для сериализации всех PATCH-запросов к gist.
     *
     * GitHub API отвечает HTTP 409 ("Gist cannot be updated") если два PATCH
     * к одному gist'у летят одновременно. В нашем приложении несколько корутин
     * могут одновременно писать: online-heartbeat, typing-pulse, отправка картинки,
     * push read-receipt. Мьютекс гарантирует что в каждый момент идёт ≤ 1 PATCH.
     */
    private val writeMutex = Mutex()

    /**
     * Минимальный интервал между последовательными PATCH-запросами.
     *
     * GitHub secondary rate limit срабатывает при слишком частых PATCH.
     * После каждого успешного saveFile делаем паузу MIN_PATCH_INTERVAL_MS
     * внутри мьютекса — это гарантирует что между любыми двумя PATCH
     * пройдёт не менее этого времени, независимо от числа воркеров.
     */
    private val MIN_PATCH_INTERVAL_MS = 350L

    /** Скачивает контент дефолтного файла (chat.txt). Всегда полный запрос. */
    suspend fun loadContent(): String = loadFile(fileName)

    /**
     * Загружает содержимое chat.txt только если гист изменился с последнего запроса.
     *
     * Использует HTTP ETag (If-None-Match): если сервер вернул 304 Not Modified —
     * контент тот же, возвращаем null. UI обновлять не нужно, API-квота не тратится.
     *
     * Вызывать ТОЛЬКО из polling-цикла. Для appendLine/replaceLine использовать loadContent().
     */
    suspend fun loadContentIfChanged(): String? {
        val json = fetchGistJson(useEtag = true) ?: return null
        val files = json.getJSONObject("files")
        if (!files.has(fileName)) throw RuntimeException("Файл '$fileName' не найден в gist")
        return files.getJSONObject(fileName).getString("content")
    }

    /**
     * Загружает chat.txt и reactions.txt за ОДИН GET-запрос (с ETag-оптимизацией).
     *
     * При 304 Not Modified возвращает null — ни один файл не изменился.
     * При 200 извлекает оба файла из одного JSON-ответа без дополнительных запросов.
     * Вдвое сокращает число GET в polling-цикле ChatActivity.
     */
    suspend fun loadChatAndReactionsIfChanged(): ChatAndReactions? {
        val json = fetchGistJson(useEtag = true) ?: return null
        return parseChatAndReactions(json)
    }

    /**
     * Загружает chat.txt и reactions.txt за ОДИН GET-запрос (полный, без ETag).
     * Используется когда нам нужен свежий контент независимо от ETag.
     */
    suspend fun loadChatAndReactions(): ChatAndReactions {
        val json = fetchGistJson(useEtag = false)
            ?: throw RuntimeException("Unexpected 304 on unconditional request")
        return parseChatAndReactions(json)
    }

    /** Извлекает chat.txt и reactions.txt из уже загруженного JSON-объекта гиста. */
    private fun parseChatAndReactions(json: JSONObject): ChatAndReactions {
        val files = json.getJSONObject("files")
        val chatContent = if (files.has(fileName))
            files.getJSONObject(fileName).getString("content") else ""
        val reactionsContent = if (files.has("reactions.txt"))
            files.getJSONObject("reactions.txt").getString("content") else ""
        return ChatAndReactions(chatContent, reactionsContent)
    }

    /** Извлекает chat.txt, reactions.txt и profiles.txt из одного JSON-объекта гиста. */
    private fun parseAllFiles(json: JSONObject): AllGistData {
        val files = json.getJSONObject("files")
        val chatContent = if (files.has(fileName))
            files.getJSONObject(fileName).getString("content") else ""
        val reactionsContent = if (files.has("reactions.txt"))
            files.getJSONObject("reactions.txt").getString("content") else ""
        val profilesContent = if (files.has("profiles.txt"))
            files.getJSONObject("profiles.txt").getString("content") else ""
        return AllGistData(chatContent, reactionsContent, profilesContent)
    }

    /**
     * ETag-оптимизированный единый GET: chat.txt + reactions.txt + profiles.txt.
     * Возвращает null при 304 Not Modified — ничего не изменилось.
     * Один запрос вместо двух отдельных (сообщения + профили).
     */
    suspend fun loadAllIfChanged(): AllGistData? {
        val json = fetchGistJson(useEtag = true) ?: return null
        return parseAllFiles(json)
    }

    /**
     * Полный (без ETag) единый GET: chat.txt + reactions.txt + profiles.txt.
     * Используется при форсированном обновлении (после отправки, onResume).
     */
    suspend fun loadAll(): AllGistData {
        val json = fetchGistJson(useEtag = false)
            ?: throw RuntimeException("Unexpected 304 on unconditional request")
        return parseAllFiles(json)
    }

    /** Перезаписывает контент дефолтного файла (chat.txt). */
    suspend fun saveContent(newContent: String): Unit = saveFile(fileName, newContent)

    /**
     * Загружает полный JSON гиста с сервера.
     *
     * [useEtag] = true  → шлёт If-None-Match; при 304 возвращает null (контент не изменился).
     * [useEtag] = false → всегда полный запрос (для appendLine, replaceLine и т.д.).
     *
     * Побочный эффект: при успешном 200 обновляет [gistEtag] для будущих условных запросов.
     * При 429 бросает [RateLimitException] с паузой из заголовка Retry-After.
     */
    private suspend fun fetchGistJson(useEtag: Boolean = false): JSONObject? =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("$gistUrl?t=${System.currentTimeMillis()}")
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .apply {
                    if (token.isNotBlank()) header("Authorization", "token $token")
                    if (useEtag) gistEtag?.let { header("If-None-Match", it) }
                }
                .build()

            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 304 -> null   // Not Modified — контент не изменился
                    resp.isSuccessful -> {
                        // Сохраняем ETag для следующего условного запроса
                        resp.header("ETag")?.let { gistEtag = it }
                        val body = resp.body?.string() ?: throw RuntimeException("Пустой ответ")
                        JSONObject(body)
                    }
                    resp.code == 401 -> throw TokenExpiredException()
                    resp.code == 403 -> {
                        // GitHub возвращает 403 для двух разных случаев:
                        // 1. Токен не имеет прав (истёк, отозван, неверный scope)
                        // 2. Secondary rate limit — слишком много запросов
                        // Отличаем по телу ответа.
                        val errBody = resp.body?.string().orEmpty()
                        if (isSecondaryRateLimit(errBody)) {
                            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 60L
                            throw RateLimitException(retryAfterSec * 1_000L)
                        }
                        throw TokenExpiredException()
                    }
                    resp.code == 429 -> {
                        // Too Many Requests — читаем рекомендованную паузу из заголовка
                        val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 60L
                        throw RateLimitException(retryAfterSec * 1_000L)
                    }
                    else -> throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
                }
            }
        }

    /**
     * Скачивает контент произвольного файла из gist.
     * Бросает исключение если gist недоступен или файла нет.
     *
     * Всегда делает полный запрос (без ETag) — используется в write-операциях
     * (appendLine, replaceLine) где нам нужны актуальные данные.
     * Как побочный эффект — обновляет [gistEtag] из ответа сервера.
     */
    suspend fun loadFile(name: String): String = withContext(Dispatchers.IO) {
        val json = fetchGistJson(useEtag = false)
            ?: throw RuntimeException("Неожиданный 304 при безусловном запросе")
        val files = json.getJSONObject("files")
        if (!files.has(name)) {
            throw RuntimeException("Файл '$name' не найден в gist")
        }
        files.getJSONObject(name).getString("content")
    }

    /**
     * Безопасный загрузчик: возвращает null если файла нет или произошла ошибка.
     * Нужен для profiles.txt — он может ещё не существовать при первом запуске чата.
     */
    suspend fun loadFileOrNull(name: String): String? = try {
        loadFile(name)
    } catch (e: Exception) {
        null
    }

    /**
     * Сырой PATCH нескольких файлов за один запрос.
     * Вызывать ТОЛЬКО внутри блока writeMutex.withLock.
     */
    private suspend fun patchFilesRaw(files: Map<String, String>) =
        withContext(Dispatchers.IO) {
            if (files.isEmpty()) return@withContext

            val filesJson = JSONObject()
            files.forEach { (name, content) ->
                val safeContent = content.ifBlank { "\n" }
                val safeName = name.ifBlank { return@forEach }
                filesJson.put(safeName, JSONObject().apply { put("content", safeContent) })
            }

            val bodyStr = JSONObject().apply {
                put("files", filesJson)
            }.toString()

            var lastError: RuntimeException? = null
            for (attempt in 0..1) {
                if (attempt > 0) delay(PATCH_CONFLICT_RETRY_MS)

                val req = Request.Builder()
                    .url(gistUrl)
                    .header("Authorization", "token $token")
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .patch(bodyStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                    .build()

                val done = client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@use true
                    val errBody = resp.body?.string().orEmpty()
                    when (resp.code) {
                        401 -> throw TokenExpiredException()
                        403 -> {
                            if (isSecondaryRateLimit(errBody)) {
                                val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 60L
                                throw RateLimitException(retryAfterSec * 1_000L)
                            }
                            throw TokenExpiredException()
                        }
                        429 -> {
                            val retryAfterSec = resp.header("Retry-After")?.toLongOrNull() ?: 60L
                            throw RateLimitException(retryAfterSec * 1_000L)
                        }
                        409 -> {
                            lastError = RuntimeException(parseSaveError(resp.code, errBody))
                            false
                        }
                        else -> throw RuntimeException(parseSaveError(resp.code, errBody))
                    }
                }
                if (done) return@withContext
            }
            throw lastError ?: RuntimeException("PATCH failed after retries")
        }

    /**
     * Перезаписывает произвольный файл в gist (создаёт если не было).
     */
    suspend fun saveFile(name: String, newContent: String): Unit = writeMutex.withLock {
        patchFilesRaw(mapOf(name to newContent))
        delay(MIN_PATCH_INTERVAL_MS)
    }

    /**
     * Атомарно сохраняет несколько файлов за ОДИН PATCH-запрос.
     * Используй вместо нескольких последовательных [saveFile], когда файлы
     * логически связаны (например chat.txt + profiles.txt после отправки сообщения).
     * Это вдвое сокращает количество PATCH-запросов и исключает окна рассогласования.
     */
    suspend fun saveFiles(files: Map<String, String>): Unit = writeMutex.withLock {
        if (files.isEmpty()) return@withLock
        patchFilesRaw(files)
        delay(MIN_PATCH_INTERVAL_MS)
    }

    /**
     * Сохраняет файл с автоматическим разбиением на чанки для обхода
     * GitHub API rate limit.
     *
     * Если [encryptedContent] умещается в CHUNK_SIZE_CHARS символов —
     * вызывается обычный [saveFile] (один PATCH-запрос, поведение как раньше).
     *
     * Иначе:
     *   1. Разбиваем [encryptedContent] на части по [ImageChunker.CHUNK_SIZE_CHARS].
     *   2. Каждую часть сохраняем как отдельный файл `<baseName>_cN.txt`.
     *      Между PATCH-запросами — пауза [ImageChunker.CHUNK_DELAY_MS].
     *   3. Сохраняем зашифрованный манифест под именем [name] — именно на него
     *      ссылается chat.txt. Манифест содержит список имён чанков.
     *
     * Получатель (ImageLoader) прозрачно собирает чанки обратно.
     *
     * @param onProgress вызывается после сохранения каждого чанка:
     *                   (chunkIndex, totalChunks) — для UI-прогресса.
     */
    suspend fun saveFileChunked(
        name: String,
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) {
        if (!ImageChunker.needsChunking(encryptedContent)) {
            saveFile(name, encryptedContent)
            return
        }

        val chunks = ImageChunker.splitIntoChunks(encryptedContent)
        val chunkNames = mutableListOf<String>()

        chunks.forEachIndexed { idx, chunkContent ->
            val chunkName = ImageChunker.chunkName(name, idx)
            chunkNames.add(chunkName)
            saveFile(chunkName, chunkContent)
            onProgress?.invoke(idx + 1, chunks.size)
            // Дополнительная пауза между чанками поверх MIN_PATCH_INTERVAL_MS.
            // MIN_PATCH_INTERVAL_MS уже есть внутри saveFile, поэтому здесь
            // добавляем только остаток до CHUNK_DELAY_MS.
            val extra = ImageChunker.CHUNK_DELAY_MS - MIN_PATCH_INTERVAL_MS
            if (extra > 0 && idx < chunks.size - 1) delay(extra)
        }

        // Сохраняем манифест последним: к этому моменту все чанки уже в gist,
        // поэтому получатель, увидевший манифест, гарантированно найдёт чанки.
        val manifestPlain = ImageChunker.makeManifestPlain(chunkNames)
        val encryptedManifest = CryptoHelper.encrypt(manifestPlain, password, gistId)
        saveFile(name, encryptedManifest)
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Отдельный gist для каждого изображения
    // ───────────────────────────────────────────────────────────────────────────

    /**
     * Создаёт новый приватный gist только для одного изображения.
     *
     * Всё изображение передаётся в ОДНОМ POST-запросе — не PATCH к основному
     * чат-gist'у. Это полностью исключает конкуренцию с heartbeat/typing/read-
     * receipt и обходит secondary rate limit GitHub без каких-либо задержек.
     *
     * Если [encryptedContent] длиннее [IMAGE_GIST_CHUNK_SIZE] символов —
     * разбивается на несколько файлов img_c00.txt, img_c01.txt … внутри
     * нового gist'а. Каждый файл ≤ 900 КБ, поэтому GitHub не усекает
     * содержимое в API-ответе (truncated: false).
     *
     * @return "gist:<GIST_ID>" — ссылка для хранения в imageFileName
     */
    suspend fun createImageGist(encryptedContent: String): String = withContext(Dispatchers.IO) {
        val filesJson = buildImageGistFiles(encryptedContent)

        val body = JSONObject().apply {
            put("description", "")
            put("public", false)
            put("files", filesJson)
        }.toString()

        val req = Request.Builder()
            .url("https://api.github.com/gists")
            .header("Authorization", "token $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Content-Type", "application/json; charset=utf-8")
            
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .build()

        uploadClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty()
                throw RuntimeException(parseSaveError(resp.code, errBody))
            }
            val respBody = resp.body?.string() ?: throw RuntimeException("Empty response")
            "gist:${JSONObject(respBody).getString("id")}"
        }
    }

    /**
     * Загружает все файлы из произвольного gist по [gistId].
     * Файлы > 1 МБ автоматически дозагружаются по raw_url
     * (GitHub усекает содержимое в JSON-ответе при truncated=true).
     *
     * Используется для загрузки изображений из отдельных image gist-ов.
     */
    suspend fun loadGistAllFiles(imageGistId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url("https://api.github.com/gists/$imageGistId?t=${System.currentTimeMillis()}")
                .header("Accept", "application/vnd.github+json")
                .header("Cache-Control", "no-cache")
                .apply {
                    if (token.isNotBlank()) header("Authorization", "token $token")
                }
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
                }
                val body = resp.body?.string() ?: throw RuntimeException("Empty response")
                val json = JSONObject(body)
                val files = json.getJSONObject("files")
                val result = mutableMapOf<String, String>()

                for (name in files.keys()) {
                    val fileObj = files.getJSONObject(name)
                    val truncated = fileObj.optBoolean("truncated", false)
                    result[name] = if (truncated) {
                        loadRawUrl(fileObj.getString("raw_url"))
                    } else {
                        fileObj.getString("content")
                    }
                }
                result
            }
        }

    /**
     * Загружает сырой контент файла по прямому URL (обход усечения gist API).
     * Используется когда truncated=true в ответе /gists/:id.
     */
    private fun loadRawUrl(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .apply {
                if (token.isNotBlank()) header("Authorization", "token $token")
            }
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} (raw url)")
            resp.body?.string() ?: throw RuntimeException("Empty raw response")
        }
    }

    /**
     * Формирует JSON-объект файлов для создания image gist.
     * Один файл img.txt для маленьких изображений,
     * несколько img_c00.txt … для больших.
     */
    private fun buildImageGistFiles(encryptedContent: String): JSONObject {
        val filesJson = JSONObject()
        if (encryptedContent.length <= IMAGE_GIST_CHUNK_SIZE) {
            filesJson.put("img.txt", JSONObject().apply {
                put("content", encryptedContent.ifBlank { "\n" })
            })
        } else {
            var offset = 0
            var idx = 0
            while (offset < encryptedContent.length) {
                val end = minOf(offset + IMAGE_GIST_CHUNK_SIZE, encryptedContent.length)
                val chunk = encryptedContent.substring(offset, end)
                val name = "img_c%02d.txt".format(idx)
                filesJson.put(name, JSONObject().apply {
                    put("content", chunk.ifBlank { "\n" })
                })
                offset = end
                idx++
            }
        }
        return filesJson
    }

    /**
     * Проверяет является ли тело 403-ответа secondary rate limit GitHub,
     * а не ошибкой прав доступа токена.
     */
    private fun isSecondaryRateLimit(body: String): Boolean =
        body.contains("secondary rate limit", ignoreCase = true) ||
        (body.contains("rate limit", ignoreCase = true) && body.contains("exceeded", ignoreCase = true))

    private fun parseSaveError(code: Int, body: String): String {
        if (body.isBlank()) return "HTTP $code (empty body)"
        return try {
            val json = JSONObject(body)
            val message = json.optString("message", "")
            val errors = json.optJSONArray("errors")
            if (errors != null && errors.length() > 0) {
                val first = errors.optJSONObject(0)
                val field = first?.optString("field", "")
                val errCode = first?.optString("code", "")
                "HTTP $code: $message [field=$field, code=$errCode]"
            } else "HTTP $code: $message"
        } catch (e: Exception) {
            "HTTP $code: ${body.take(200)}"
        }
    }

    /**
     * Дозаписывает строку в конец файла chat.txt.
     *
     * @param encryptedLine Новая строка сообщения
     * @param extraFiles Дополнительные файлы для сохранения в этом же PATCH (атомарно)
     */
    suspend fun appendLine(encryptedLine: String, extraFiles: Map<String, String> = emptyMap()) = writeMutex.withLock {
        val now = System.currentTimeMillis()
        val hintFresh = (now - appendHintMs) < APPEND_HINT_TTL_MS && appendHintContent.isNotEmpty()
        val old = if (hintFresh) appendHintContent else loadContent()

        val newContent = if (old.isBlank()) encryptedLine else "$old\n$encryptedLine"
        
        val allFiles = extraFiles.toMutableMap()
        allFiles[fileName] = newContent

        patchFilesRaw(allFiles)

        // Обновляем кэш свежим содержимым
        appendHintContent = newContent
        appendHintMs = System.currentTimeMillis()

        delay(MIN_PATCH_INTERVAL_MS)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reactions (reactions.txt — plaintext, one line per active reaction)
    // Формат строки: "msgId|emoji|userId"
    // ─────────────────────────────────────────────────────────────────────────

    private val reactionsFileName = "reactions.txt"

    /** Загружает содержимое reactions.txt; пустая строка если файл не существует. */
    suspend fun loadReactionsContent(): String =
        loadFileOrNull(reactionsFileName) ?: ""

    /**
     * Атомарно переключает реакцию (toggle add/remove).
     * [plainLine] = "msgId|emoji|userId"
     * Возвращает true если реакция добавлена, false если удалена.
     *
     * GET выполняется ДО захвата writeMutex — это предотвращает блокировку
     * отправки сообщений на время сетевого чтения (~500 мс).
     * Аналогично паттерну appendLine: там тоже GET снаружи мьютекса.
     *
     * Небольшое окно гонки (партнёр мог нажать реакцию между нашим GET и PATCH)
     * существует независимо от расположения GET — GitHub Gist не поддерживает
     * атомарный CAS на уровне файла.
     */
    suspend fun toggleReaction(plainLine: String): Boolean = writeMutex.withLock {
        val currentContent = loadFileOrNull(reactionsFileName) ?: ""
        val lines = currentContent.split("\n")
            .map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val trimmed = plainLine.trim()
        val idx = lines.indexOfFirst { it == trimmed }
        if (idx != -1) {
            lines.removeAt(idx)
            patchFilesRaw(mapOf(reactionsFileName to lines.joinToString("\n").ifBlank { "\n" }))
            delay(MIN_PATCH_INTERVAL_MS)
            false
        } else {
            lines.add(trimmed)
            patchFilesRaw(mapOf(reactionsFileName to lines.joinToString("\n")))
            delay(MIN_PATCH_INTERVAL_MS)
            true
        }
    }

    suspend fun replaceLine(oldLine: String, newLine: String): Boolean =
        writeMutex.withLock {
            val content = loadContent()
            val lines = content.split("\n").toMutableList()
            val idx = lines.indexOfFirst { it.trim() == oldLine.trim() }
            if (idx == -1) return@withLock false
            lines[idx] = newLine
            patchFilesRaw(mapOf(fileName to lines.joinToString("\n")))
            delay(MIN_PATCH_INTERVAL_MS)
            true
        }

    suspend fun deleteLine(line: String): Boolean =
        writeMutex.withLock {
            val content = loadContent()
            val lines = content.split("\n").toMutableList()
            val idx = lines.indexOfFirst { it.trim() == line.trim() }
            if (idx == -1) return@withLock false
            lines.removeAt(idx)
            patchFilesRaw(mapOf(fileName to lines.joinToString("\n")))
            delay(MIN_PATCH_INTERVAL_MS)
            true
        }

    companion object {

        /**
         * TTL кэш-подсказки для appendLine.
         * 60 сек: достаточно для ~7 тиков поллинга (8 сек каждый).
         * touchChatContentHint() продлевает TTL на каждый 304-ответ, поэтому
         * протухание происходит только при полном отсутствии сети > 60 сек.
         */
        const val APPEND_HINT_TTL_MS = 60_000L

        /**
         * Максимум строк в chat.txt перед автообрезкой.
         * ~2000 зашифрованных строк ≈ 1–2 МБ. Выше — каждый GET/PATCH ощутимо медленнее.
         */
        const val MAX_CHAT_LINES = 2_000

        /**
         * До скольких строк обрезаем при автообрезке.
         * 500 строк буфера гарантируют что обрезка происходит редко.
         */
        const val TRIM_TO_LINES = 1_500

        /**
         * Максимальный размер одного файла в image gist (символов).
         *
         * 900 000 символов ≈ 900 КБ — чуть ниже лимита усечения GitHub (1 МБ).
         * При truncated=false весь контент приходит сразу в JSON-ответе без
         * дополнительных запросов к raw_url, что упрощает и ускоряет загрузку.
         *
         * Для изображений до ~660 КБ (≈ 500 КБ base64 зашифрованного) — один файл.
         * Обычное фото с телефона (3–10 МБ) → 4–14 файлов → всё в одном POST.
         */
        const val IMAGE_GIST_CHUNK_SIZE = 900_000

        /**
         * Пауза перед повторным PATCH при 409 Conflict.
         * 1.5 сек достаточно чтобы конкурирующий запрос с другого устройства завершился.
         */
        private const val PATCH_CONFLICT_RETRY_MS = 1_500L

        /**
         * Создаёт новый приватный gist с пустым chat.txt и возвращает его ID.
         * Используется в Auto режиме создания чата — пользователю не нужно
         * руками создавать gist на github.com.
         *
         * ВАЖНО (см. промпт по архитектуре безопасности):
         *   token используется ТОЛЬКО как transport-credential для создания
         *   ресурса в GitHub. Он не участвует в шифровании сообщений и не
         *   связан с паролем комнаты. Безопасность Auto и Manual режимов
         *   эквивалентна.
         *
         * @throws RuntimeException если создание не удалось (неверный токен,
         *   rate limit, отсутствие интернета).
         */
        suspend fun createGist(
            token: String,
            description: String = "",
            fileName: String = "chat.txt"
        ): String = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "GitHub-CLI/2.0.0")
                        .build()
                    chain.proceed(request)
                }
                .build()

            // ─── НОРМАЛИЗАЦИЯ ВХОДНЫХ ДАННЫХ ───
            // GitHub Gist API очень строгий по валидации:
            //  • description: убираем переводы строк (ломают JSON-валидацию)
            //    и trim'им до 256 символов (мягкий лимит API)
            //  • filename: не должен начинаться с точки и содержать слэши
            //  • content: НЕ blank (даже " " теперь не проходит — API считает его
            //    blank после trim'а). Кладём реальный placeholder с переводом
            //    строки — он же будет первой "пустой" строкой в chat.txt и
            //    при первом appendLine() перезапишется реальным сообщением.
            val safeDescription = description
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim()
                .take(256)
                .ifBlank { "" }

            val safeFileName = fileName.ifBlank { "chat.txt" }

            // Placeholder content: должен быть не пустым. 
            // Используем точку как минимальный валидный контент.
            val initialContent = "."

            val body = JSONObject().apply {
                put("description", safeDescription)
                put("public", false)
                put("files", JSONObject().apply {
                    put(safeFileName, JSONObject().apply {
                        put("content", initialContent)
                    })
                })
            }.toString()

            val req = Request.Builder()
                .url("https://api.github.com/gists")
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json; charset=utf-8")
                
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .build()

            client.newCall(req).execute().use { resp ->
                val respBody = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    // Полный диагностический текст: статус + GitHub error JSON.
                    // Достаточно чтобы быстро увидеть "content can't be blank",
                    // "description too long" и т.п.
                    val msg = parseGitHubError(resp.code, respBody)
                    throw RuntimeException(msg)
                }
                if (respBody.isBlank()) throw RuntimeException("Empty response from GitHub")
                JSONObject(respBody).getString("id")
            }
        }

        /**
         * Удаляет gist целиком. Используется при автоматической чистке
         * протухших чатов (по expiresAtMs).
         *
         * Безопасно к 404 (если gist уже удалён) — просто игнорируем.
         */
        suspend fun deleteGist(token: String, gistId: String) = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "GitHub-CLI/2.0.0")
                        .build()
                    chain.proceed(request)
                }
                .build()
            val req = Request.Builder()
                .url("https://api.github.com/gists/$gistId")
                .header("Authorization", "token $token")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                
                .delete()
                .build()
            client.newCall(req).execute().use { resp ->
                // 204 = OK, 404 = уже нет — считаем успехом обоих
                if (!resp.isSuccessful && resp.code != 404) {
                    val body = resp.body?.string().orEmpty()
                    throw RuntimeException(parseGitHubError(resp.code, body))
                }
            }
        }

        /**
         * Парсит {message, errors:[{resource, code, field}], documentation_url}
         * в читаемое сообщение. GitHub возвращает структурированные ошибки на 422.
         */
        private fun parseGitHubError(code: Int, body: String): String {
            if (body.isBlank()) return "HTTP $code (empty body)"
            return try {
                val json = JSONObject(body)
                val message = json.optString("message", "")
                val errors = json.optJSONArray("errors")
                if (errors != null && errors.length() > 0) {
                    val first = errors.optJSONObject(0)
                    val field = first?.optString("field", "")
                    val errCode = first?.optString("code", "")
                    val res = first?.optString("resource", "")
                    "HTTP $code: $message [$res.$field $errCode]"
                } else {
                    "HTTP $code: $message"
                }
            } catch (e: Exception) {
                "HTTP $code: ${body.take(200)}"
            }
        }
    }
}
