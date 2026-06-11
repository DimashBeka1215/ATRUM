package com.atrum.chat.stickers

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val rootDir: File
        get() = File(context.filesDir, StickerConfig.STICKER_DIR).also { it.mkdirs() }

    companion object {
        // Кеш списка паков, общий для всех экземпляров репозитория (один набор на диске).
        @Volatile private var packsCache: List<StickerPack>? = null
        /** Сбросить кеш паков — после любого изменения на диске (add/remove/rename). */
        fun invalidatePacksCache() { packsCache = null }
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
        val total     = stickersArr.length()

        // 2. Скачиваем каждый стикер параллельно
        val counter = AtomicInteger(0)
        val deferredStickers = (0 until total).map { i ->
            async {
                val stickerObj = stickersArr.getJSONObject(i)
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

                if (!file.exists()) {
                    try {
                        // Для анимаций и видео ВСЕГДА скачиваем основной файл, а не превью
                        downloadFile(fileId, file)
                    } catch (e: Exception) {
                        android.util.Log.e("StickerRepo", "Failed to download sticker $fileId", e)
                    }
                }

                val sticker = Sticker(
                    fileId = fileId,
                    localPath = if (file.exists()) file.absolutePath else null,
                    type = type,
                    emoji = emoji
                )
                
                onProgress?.invoke(counter.incrementAndGet(), total)
                sticker
            }
        }

        val stickers = deferredStickers.awaitAll()

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
        val path = sticker.localPath ?: return@withContext null
        val file = File(path)
        if (!file.exists()) return@withContext null
        try {
            when (sticker.type) {
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
                    val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bmp)
                    drawable.setBounds(0, 0, w, h)
                    drawable.draw(canvas)
                    return@withContext bmp  // уже нужного размера
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
            }?.let { bmp -> if (maxSize > 0) downscale(bmp, maxSize) else bmp }
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
            val stickers = (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Sticker(
                    fileId    = obj.getString("fileId"),
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
