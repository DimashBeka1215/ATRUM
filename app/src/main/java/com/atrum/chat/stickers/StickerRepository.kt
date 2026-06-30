package com.atrum.chat.stickers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Загружает стикер-паки через Telegram Bot API и кеширует их в filesDir.
 *
 * Структура кеша:
 *   filesDir/stickers/{packName}/meta.json      — метаданные пака
 *   filesDir/stickers/{packName}/{fileId}.webp  — статичный стикер
 *   filesDir/stickers/{packName}/{fileId}.tgs   — анимированный стикер
 *   filesDir/stickers/{packName}/{fileId}.webm  — видео-стикер
 *
 * Все сетевые операции выполняются на Dispatchers.IO.
 * Никогда не вызывать с главного потока.
 */
class StickerRepository(private val context: Context) {

    private val prefs = com.atrum.chat.Prefs(context)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .proxy(java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress.createUnresolved("127.0.0.1", com.atrum.chat.TorManager.SOCKS_PORT)))
        .build()

    private val rootDir: File
        get() = File(context.filesDir, StickerConfig.STICKER_DIR).also { it.mkdirs() }

    companion object {
        // Кеш списка паков, общий для всех экземпляров репозитория (один набор на диске).
        @Volatile private var packsCache: List<StickerPack>? = null
        /** Сбросить кеш паков — после любого изменения на диске (add/remove/rename). */
        fun invalidatePacksCache() { packsCache = null }

        @Volatile private var favoritesCache: List<Sticker>? = null

        private const val FAVORITES_FILE = "favorites.json"

        /** Сколько стикеров качаем одновременно. Шквал из 100+ параллельных запросов
         *  Telegram режет → часть файлов не скачивалась вовсе. Ограничиваем. */
        private const val MAX_PARALLEL_DOWNLOADS = 6
    }

    // ── Публичное API ────────────────────────────────────────────────────────

    /**
     * Возвращает список всех локально сохранённых паков.
     * Читает meta.json из каждой папки в rootDir.
     */
    suspend fun loadLocalPacks(): List<StickerPack> = withContext(Dispatchers.IO) {
        // Кеш на уровне процесса: подсказки по эмодзи зовут это на каждое нажатие, а раньше
        // каждый раз читались все meta.json с диска. Инвалидация — при add/remove/rename.
        packsCache?.let { return@withContext it }
        val loaded = rootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedByDescending { it.lastModified() }
            ?.mapNotNull { dir -> readMeta(dir) }
            ?: emptyList()
        packsCache = loaded
        loaded
    }

    /**
     * Скачивает пак по ссылке или имени и сохраняет на диск.
     *
     * Принимает:
     *   - "https://t.me/addstickers/PackName"
     *   - "t.me/addstickers/PackName"
     *   - просто "PackName"
     *
     * @throws IllegalArgumentException если ссылка не распознана
     * @throws StickerException если API вернул ошибку
     */
    suspend fun addPack(
        input: String,
        onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    ): StickerPack = withContext(Dispatchers.IO) {
        val packName = parsePackName(input)
        val packDir  = File(rootDir, packName).also { it.mkdirs() }

        // 1. Запрашиваем метаданные пака
        val setJson   = apiGetStickerSet(packName)
        val title     = setJson.getString("title")
        val stickersArr = setJson.getJSONArray("stickers")

        // 2. Дедуп записей: Telegram нередко перечисляет ОДИН и тот же файл под
        //    несколькими эмодзи → иначе в паке видны дубликаты. Ключ — file_unique_id
        //    (стабилен), fallback на file_id. Порядок сохраняем.
        val uniqueDescs = LinkedHashMap<String, JSONObject>()
        for (i in 0 until stickersArr.length()) {
            val o = stickersArr.getJSONObject(i)
            val key = o.optString("file_unique_id", "").ifEmpty { o.optString("file_id", "") }
            if (key.isNotEmpty() && !uniqueDescs.containsKey(key)) uniqueDescs[key] = o
        }
        val descs = uniqueDescs.values.toList()
        val total = descs.size

        // 3. Скачиваем с ОГРАНИЧЕННЫМ параллелизмом + ретраями (см. downloadWithRetry).
        val gate = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val counter = AtomicInteger(0)
        val deferredStickers = descs.map { stickerObj ->
            async {
                val fileId = stickerObj.getString("file_id")
                val emoji = stickerObj.optString("emoji", "")
                val isAnimated = stickerObj.optBoolean("is_animated", false)
                val isVideo = stickerObj.optBoolean("is_video", false)

                val type = when {
                    isVideo -> StickerType.VIDEO
                    isAnimated -> StickerType.ANIMATED
                    else -> StickerType.STATIC
                }
                val ext = extensionFor(type)
                val file = File(packDir, "$fileId$ext")

                if (!file.exists()) gate.withPermit { downloadWithRetry(fileId, file) }

                onProgress?.invoke(counter.incrementAndGet(), total)
                Sticker(
                    fileId = fileId,
                    localPath = if (file.exists()) file.absolutePath else null,
                    type = type,
                    emoji = emoji
                )
            }
        }

        // 4. Лечебный проход: то, что не скачалось в параллельном шквале, добираем
        //    последовательно — так почти не остаётся «непрогружаемых» стикеров.
        val stickers = deferredStickers.awaitAll().map { s ->
            if (s.localPath != null) return@map s
            val file = File(packDir, "${s.fileId}${extensionFor(s.type)}")
            runCatching { downloadWithRetry(s.fileId, file) }
            if (file.exists()) s.copy(localPath = file.absolutePath) else s
        }

        // 3. Сохраняем meta.json
        val thumbPath = stickers.firstOrNull { it.localPath != null }?.localPath
        val pack = StickerPack(
            name      = packName,
            title     = title,
            stickers  = stickers,
            thumbPath = thumbPath
        )
        writeMeta(packDir, pack)
        invalidatePacksCache()
        pack
    }

    /**
     * Удаляет пак с диска.
     */
    suspend fun removePack(packName: String) = withContext(Dispatchers.IO) {
        File(rootDir, packName).deleteRecursively()
        invalidatePacksCache()
    }

    /**
     * Удаляет один стикер из пака.
     */
    suspend fun deleteSticker(sticker: Sticker, packName: String) = withContext(Dispatchers.IO) {
        val packDir = File(rootDir, packName)
        val pack = readMeta(packDir) ?: return@withContext

        // 1. Удаляем файл с диска
        sticker.localPath?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }

        // 2. Обновляем метаданные
        val updatedStickers = pack.stickers.filter { it.fileId != sticker.fileId }
        if (updatedStickers.isEmpty()) {
            removePack(packName)
            return@withContext
        }

        var newThumb = pack.thumbPath
        if (pack.thumbPath == sticker.localPath) {
            newThumb = updatedStickers.firstOrNull { it.localPath != null }?.localPath
        }
        writeMeta(packDir, pack.copy(stickers = updatedStickers, thumbPath = newThumb))

        // 3. Очистка из избранного и кешей
        removeFromFavorites(sticker.fileId)
        sticker.localPath?.let { path ->
            com.atrum.chat.ImageCache.removeBitmap(path)
            com.atrum.chat.ImageCache.removeComposition(path)
            com.atrum.chat.StickerFrameCache.remove(sticker.fileId) // Используем fileId как ключ

            // Удаляем также из дискового кеша фреймов
            com.atrum.chat.StickerDiskCache.remove(context.cacheDir, sticker.fileId)

            // Очищаем Argon2 кеш путей к стикерам
            prefs.setStickerContentRef(packName, sticker.fileId, "")

            // Превью (без размера и в размере списка паков)
            com.atrum.chat.ImageCache.removeBitmap("thumb_${sticker.fileId}_0")
            val thumbPx = (56 * context.resources.displayMetrics.density).toInt()
            com.atrum.chat.ImageCache.removeBitmap("thumb_${sticker.fileId}_$thumbPx")
        }

        // 4. Сбрасываем кеш
        invalidatePacksCache()
    }

    /**
     * Переименовывает пак — меняет ТОЛЬКО отображаемое название (title в meta.json).
     * Техническое имя пака и папка на диске не трогаются.
     */
    suspend fun renamePack(packName: String, newTitle: String) = withContext(Dispatchers.IO) {
        val clean = newTitle.trim().take(64)
        if (clean.isEmpty()) return@withContext
        val packDir = File(rootDir, packName)
        val pack = readMeta(packDir) ?: return@withContext
        writeMeta(packDir, pack.copy(title = clean))
        invalidatePacksCache()
    }

    // ── Избранное ────────────────────────────────────────────────────────────

    /** Возвращает список избранных стикеров. */
    suspend fun loadFavorites(): List<Sticker> = withContext(Dispatchers.IO) {
        favoritesCache?.let { return@withContext it }
        val file = File(rootDir, FAVORITES_FILE)
        if (!file.exists()) return@withContext emptyList()
        val loaded = try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Sticker(
                    fileId = obj.getString("fileId"),
                    localPath = obj.getString("localPath").takeIf { it.isNotBlank() },
                    type = StickerType.valueOf(obj.getString("type")),
                    emoji = obj.optString("emoji", "")
                )
            }.filter { it.localPath != null && File(it.localPath).exists() }
        } catch (_: Exception) {
            emptyList()
        }
        favoritesCache = loaded
        loaded
    }

    /** Синхронно возвращает кеш избранного (может быть null до первой загрузки). */
    fun getFavoritesCached(): List<Sticker>? = favoritesCache

    /** Добавляет стикер в избранное (в начало списка). */
    suspend fun addToFavorites(sticker: Sticker) = withContext(Dispatchers.IO) {
        val current = loadFavorites().toMutableList()
        current.removeAll { it.fileId == sticker.fileId }
        current.add(0, sticker)
        saveFavorites(current.take(100)) // Лимит 100 избранных
    }

    /** Удаляет стикер из избранного. */
    suspend fun removeFromFavorites(fileId: String) = withContext(Dispatchers.IO) {
        val current = loadFavorites().toMutableList()
        if (current.removeAll { it.fileId == fileId }) {
            saveFavorites(current)
        }
    }

    /** Проверяет, в избранном ли стикер. */
    suspend fun isFavorite(fileId: String): Boolean = withContext(Dispatchers.IO) {
        loadFavorites().any { it.fileId == fileId }
    }

    /** Фиксирует использование стикера (для раздела "Часто используемые"). */
    suspend fun recordUsage(sticker: Sticker) = withContext(Dispatchers.IO) {
        val current = loadFavorites().toMutableList()
        current.removeAll { it.fileId == sticker.fileId }
        current.add(0, sticker)
        saveFavorites(current.take(50)) // Храним топ-50
    }

    private fun saveFavorites(list: List<Sticker>) {
        favoritesCache = list
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().apply {
                put("fileId", s.fileId)
                put("localPath", s.localPath ?: "")
                put("type", s.type.name)
                put("emoji", s.emoji)
            })
        }
        File(rootDir, FAVORITES_FILE).writeText(arr.toString())
    }


    /**
     * Возвращает первый кадр стикера в виде PNG-байт (для отправки собеседнику).
     *
     * - STATIC (.webp) — читаем файл напрямую, декодируем как Bitmap и перекодируем в PNG.
     * - ANIMATED (.tgs) — распаковываем Lottie JSON, рендерим первый кадр через LottieDrawable.
     * - VIDEO (.webm)   — используем MediaMetadataRetriever для первого кадра.
     *
     * Возвращает null если файл недоступен или конвертация не удалась.
     */
    suspend fun renderFirstFrame(
        sticker: Sticker,
        maxSize: Int = 0
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val cacheKey = "thumb_${sticker.fileId}_$maxSize"
        com.atrum.chat.ImageCache.getBitmap(cacheKey)?.let { return@withContext it }

        val path = sticker.localPath ?: return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        try {
            val bmp = when (sticker.type) {
                StickerType.STATIC -> {
                    android.graphics.BitmapFactory.decodeFile(path)
                }
                StickerType.ANIMATED -> {
                    // TGS = gzip(Lottie JSON) — рендерим первый кадр
                    val json = java.util.zip.GZIPInputStream(java.io.FileInputStream(file))
                        .bufferedReader().readText()
                    val result = com.airbnb.lottie.LottieCompositionFactory
                        .fromJsonStringSync(json, path)
                    val comp = result?.value ?: return@withContext null
                    val drawable = com.airbnb.lottie.LottieDrawable().apply {
                        composition = comp
                        frame = 0
                    }
                    val bw = comp.bounds.width().takeIf { it > 0 } ?: 512
                    val bh = comp.bounds.height().takeIf { it > 0 } ?: 512
                    // Под превью рендерим сразу в нужный размер — без 512²-аллокации.
                    val (w, h) = scaledDims(bw, bh, maxSize)
                    val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(b)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    b  // уже нужного размера
                }
                StickerType.VIDEO -> {
                    val retriever = android.media.MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(path)
                        retriever.getFrameAtTime(0)
                    } finally {
                        retriever.release()
                    }
                }
            }
            
            bmp?.let {
                val result = if (maxSize > 0) downscale(it, maxSize) else it
                com.atrum.chat.ImageCache.putBitmap(cacheKey, result)
                result
            }
        } catch (_: Exception) { null }
    }

    /** Считает целевые размеры под maxSize (0 = без ограничения, исходный размер). */
    private fun scaledDims(w: Int, h: Int, maxSize: Int): Pair<Int, Int> {
        if (maxSize <= 0) return w to h
        val m = maxOf(w, h)
        if (m <= maxSize) return w to h
        val s = maxSize.toFloat() / m
        return (w * s).toInt().coerceAtLeast(1) to (h * s).toInt().coerceAtLeast(1)
    }

    /** Уменьшает bitmap до maxSize по большей стороне (если больше). Освобождает исходник. */
    private fun downscale(src: android.graphics.Bitmap, maxSize: Int): android.graphics.Bitmap {
        val (w, h) = scaledDims(src.width, src.height, maxSize)
        if (w == src.width && h == src.height) return src
        val scaled = android.graphics.Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    /**
     * Стикеры-подсказки по введённому эмодзи (как в Telegram).
     * Совпадение по сохранённому полю emoji: либо введённый текст содержит emoji стикера,
     * либо наоборот (на случай слитных мульти-эмодзи). Дедуп по localPath, с лимитом.
     */
    suspend fun stickersForEmoji(typed: String, limit: Int = 60): List<Sticker> = withContext(Dispatchers.IO) {
        val q = typed.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Sticker>()
        for (pack in loadLocalPacks()) {
            for (s in pack.stickers) {
                val path = s.localPath ?: continue
                if (s.emoji.isEmpty()) continue
                if ((q.contains(s.emoji) || s.emoji.contains(q)) && seen.add(path)) {
                    out.add(s)
                    if (out.size >= limit) return@withContext out
                }
            }
        }
        out
    }

    // ── Внутренние методы ────────────────────────────────────────────────────

    /** Парсит имя пака из строки ввода пользователя. */
    private fun parsePackName(input: String): String {
        val trimmed = input.trim()
        // https://t.me/addstickers/PackName или t.me/addstickers/PackName
        val regex = Regex("""(?:https?://)?t\.me/addstickers/([A-Za-z0-9_]+)""")
        val match = regex.find(trimmed)
        if (match != null) return match.groupValues[1]
        // Просто имя пака: буквы/цифры/подчёркивание
        if (trimmed.matches(Regex("[A-Za-z0-9_]+"))) return trimmed
        throw IllegalArgumentException("Не удалось распознать ссылку на стикер-пак: $trimmed")
    }

    /** GET /getStickerSet?name=PackName → возвращает объект "result". */
    private fun apiGetStickerSet(packName: String): JSONObject {
        val url = "${StickerConfig.apiBase(context)}/getStickerSet?name=$packName"
        val body = httpGet(url)
        val root = JSONObject(body)
        if (!root.optBoolean("ok", false)) {
            val desc = root.optString("description", "unknown error")
            throw StickerException("Telegram API: $desc")
        }
        return root.getJSONObject("result")
    }

    /** Скачивает файл с ретраями (транзиентные таймауты Telegram при пакетной загрузке). */
    private suspend fun downloadWithRetry(fileId: String, dest: File, attempts: Int = 3) {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                downloadFile(fileId, dest)
                if (dest.exists()) return
            } catch (e: Exception) {
                last = e
            }
            if (i < attempts - 1) delay(500L * (i + 1))
        }
        last?.let { android.util.Log.e("StickerRepo", "Failed to download sticker $fileId", it) }
    }

    /** Скачивает файл по file_id и записывает на диск. */
    private fun downloadFile(fileId: String, dest: File) {
        // Шаг 1: getFile → получаем file_path
        val infoUrl  = "${StickerConfig.apiBase(context)}/getFile?file_id=$fileId"
        val infoBody = httpGet(infoUrl)
        val filePath = JSONObject(infoBody)
            .getJSONObject("result")
            .getString("file_path")

        // Шаг 2: скачиваем байты. Response ОБЯЗАТЕЛЬНО закрываем (.use), иначе на ошибочных
        // ветках соединение течёт. В тексте ошибки НЕ используем URL — он содержит bot-токен.
        val downloadUrl = "${StickerConfig.fileBase(context)}/$filePath"
        val req  = Request.Builder().url(downloadUrl).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw StickerException("HTTP ${resp.code} при скачивании стикера")
            val bytes = resp.body?.bytes() ?: throw StickerException("Пустой ответ при скачивании")
            if (bytes.size > StickerConfig.MAX_STICKER_BYTES) {
                android.util.Log.w("StickerRepo", "Стикер $fileId пропущен: ${bytes.size} Б > лимита")
                return // слишком большой — пропускаем
            }
            dest.writeBytes(bytes)
        }
    }

    /** Простой GET-запрос, возвращает тело как строку. Response закрывается через .use. */
    private fun httpGet(url: String): String {
        val req  = Request.Builder().url(url).build()
        // В сообщениях ошибок НЕ передаём url — он содержит bot-токен (…/bot<token>/…).
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw StickerException("HTTP ${resp.code}")
            return resp.body?.string() ?: throw StickerException("Пустой ответ от Telegram API")
        }
    }

    /** Расширение файла по типу стикера. */
    private fun extensionFor(type: StickerType) = when (type) {
        StickerType.STATIC   -> ".webp"
        StickerType.ANIMATED -> ".tgs"
        StickerType.VIDEO    -> ".webm"
    }

    // ── meta.json ────────────────────────────────────────────────────────────

    private fun writeMeta(packDir: File, pack: StickerPack) {
        val stickersArr = JSONArray()
        pack.stickers.forEach { s ->
            stickersArr.put(JSONObject().apply {
                put("fileId",    s.fileId)
                put("localPath", s.localPath ?: "")
                put("type",      s.type.name)
                put("emoji",     s.emoji)
            })
        }
        val json = JSONObject().apply {
            put("name",      pack.name)
            put("title",     pack.title)
            put("thumbPath", pack.thumbPath ?: "")
            put("addedAt",   pack.addedAt)
            put("stickers",  stickersArr)
        }
        File(packDir, StickerConfig.META_FILE).writeText(json.toString())
    }

    private fun readMeta(packDir: File): StickerPack? {
        val metaFile = File(packDir, StickerConfig.META_FILE)
        if (!metaFile.exists()) return null
        return try {
            val json     = JSONObject(metaFile.readText())
            val arr      = json.getJSONArray("stickers")
            val seenIds = HashSet<String>()
            val stickers = (0 until arr.length()).mapNotNull { i ->
                val obj = arr.getJSONObject(i)
                val fid = obj.getString("fileId")
                if (!seenIds.add(fid)) return@mapNotNull null // дедуп уже скачанных паков
                Sticker(
                    fileId    = fid,
                    localPath = obj.getString("localPath").takeIf { it.isNotBlank() },
                    type      = StickerType.valueOf(obj.getString("type")),
                    emoji     = obj.optString("emoji", "")
                )
            }
       
            StickerPack(
                name      = json.getString("name"),
                title     = json.getString("title"),
                stickers  = stickers,
                thumbPath = json.getString("thumbPath").takeIf { it.isNotBlank() },
                addedAt   = json.optLong("addedAt", packDir.lastModified())
            )
        } catch (_: Exception) { null }
    }
}

/** Ошибка модуля стикеров. */
class StickerException(message: String) : Exception(message)
