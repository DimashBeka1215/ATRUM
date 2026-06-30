package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Кеш картинок: in-memory (LruCache) + persistent (Disk).
 *
 * Храним Bitmap (для отображения в адаптере) и сырой base64
 * (для fullscreen-просмотрщика — открывается через [ImageViewActivity]).
 *
 * При вытеснении bitmap из LruCache base64 остаётся на диске —
 * повторный декод дешевле нового сетевого запроса к источнику.
 */
object ImageCache {

    // ⚠️ Потолки кэшей ОБЯЗАНЫ быть относительными к heap, а не фиксированными.
    // Раньше: 50MB bitmaps + 24MB base64 + 48MB compositions = 122MB фиксированных
    // потолков. На устройстве с heap ~155MB при заполнении (открыли чат с фото) куча
    // упиралась в 155/155MB, 0% free → каждая аллокация триггерит блокирующий GC →
    // main-поток голодает на 99% CPU → ANR/фриз. Теперь бюджеты считаем от maxMemory():
    // даже на largeHeap оставляем запас, чтобы у аллокатора был воздух.
    private val maxMemBytes: Long = Runtime.getRuntime().maxMemory()

    private val MAX_BITMAP_BYTES: Int =
        (maxMemBytes / 8).coerceIn(8L * 1024 * 1024, 40L * 1024 * 1024).toInt()
    private const val DISK_CACHE_SUBDIR = "images_v1"
    // Потолок диск-кеша base64 (.b64). Раньше папка росла без ограничений и копила
    // расшифрованный контент всех картинок/стикеров за всё время. Теперь LRU-trim.
    private const val MAX_DISK_BYTES = 48L * 1024 * 1024

    private val bitmaps = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    // Ограниченный LRU по размеру (UTF-16 ~ length*2). При вытеснении строка остаётся на
    // диске (см. getDiskFile) — повторное чтение дешевле сети. Раньше был unbounded HashMap,
    // что копило base64 всех картинок/стикеров за сеанс и вело к OOM.
    private val MAX_BASE64_BYTES: Int =
        (maxMemBytes / 16).coerceIn(4L * 1024 * 1024, 16L * 1024 * 1024).toInt()
    private val base64s = object : LruCache<String, String>(MAX_BASE64_BYTES) {
        override fun sizeOf(key: String, value: String): Int = value.length * 2
    }

    // Бюджет кеша Lottie-композиций в КБ. Размер оцениваем по площади bounds стикера
    // (w*h*4) как грубый проксированный «вес» — раньше лимит был «100 штук» без учёта размера.
    private val MAX_COMPOSITION_KB: Int =
        ((maxMemBytes / 16) / 1024).coerceIn(4L * 1024, 16L * 1024).toInt()
    private val compositions = object : LruCache<String, com.airbnb.lottie.LottieComposition>(MAX_COMPOSITION_KB) {
        override fun sizeOf(key: String, value: com.airbnb.lottie.LottieComposition): Int {
            val w = value.bounds.width().takeIf { it > 0 } ?: 512
            val h = value.bounds.height().takeIf { it > 0 } ?: 512
            return ((w.toLong() * h * 4) / 1024).toInt().coerceAtLeast(1)
        }
    }

    private var diskCacheDir: File? = null

    /**
     * Refs, для которых анимация подтверждения уже была показана в этой сессии.
     */
    private val shownConfirmations: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    fun init(context: Context) {
        diskCacheDir = File(context.cacheDir, DISK_CACHE_SUBDIR).apply {
            if (!exists()) mkdirs()
        }
    }

    // ── Lottie ─────────────────────────────────────────────────────────────────

    fun getComposition(key: String): com.airbnb.lottie.LottieComposition? = compositions.get(key)

    fun putComposition(key: String, composition: com.airbnb.lottie.LottieComposition) {
        compositions.put(key, composition)
    }

    fun removeComposition(key: String) {
        compositions.remove(key)
    }

    // ── Bitmap ─────────────────────────────────────────────────────────────────

    fun getBitmap(key: String): Bitmap? = bitmaps.get(key)

    fun putBitmap(key: String, bitmap: Bitmap) {
        bitmaps.put(key, bitmap)
    }

    fun removeBitmap(key: String) {
        bitmaps.remove(key)
    }

    // ── Base64 ────────────────────────────────────────────────────────────────

    fun getBase64(key: String): String? {
        // 1. Memory
        synchronized(base64s) {
            base64s.get(key)?.let { return it }
        }

        // 2. Disk
        return try {
            val file = getDiskFile(key)
            if (file.exists()) {
                val content = file.readText()
                // Touch для LRU: недавно прочитанное не должно вытесняться trim'ом.
                try { file.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
                // Подгружаем в память для быстрых повторных тапов
                synchronized(base64s) { base64s.put(key, content) }
                content
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ── Составной статус ──────────────────────────────────────────────────────

    fun isKnown(key: String): Boolean =
        bitmaps.get(key) != null || synchronized(base64s) { base64s.get(key) != null } || getDiskFile(key).exists()

    // ── Put ───────────────────────────────────────────────────────────────────

    fun put(key: String, base64: String, bitmap: Bitmap?) {
        // 1. Memory
        synchronized(base64s) { base64s.put(key, base64) }
        if (bitmap != null) bitmaps.put(key, bitmap)

        // 2. Disk (async safe since we just write)
        try {
            val file = getDiskFile(key)
            if (!file.exists()) {
                file.writeText(base64)
                // Держим папку в пределах лимита (LRU по lastModified).
                diskCacheDir?.let { StickerDiskCache.trimDir(it, MAX_DISK_BYTES, ".b64") }
            }
        } catch (_: Exception) {}
    }

    // ── Confirmation tracking ─────────────────────────────────────────────────

    fun wasShownConfirmation(key: String): Boolean = key in shownConfirmations

    fun markShownConfirmation(key: String) { shownConfirmations.add(key) }

    // ── Clear ─────────────────────────────────────────────────────────────────

    fun clear() {
        bitmaps.evictAll()
        compositions.evictAll()
        synchronized(base64s) { base64s.evictAll() }
        shownConfirmations.clear()
        try {
            diskCacheDir?.listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}
    }

    // ── Utils ─────────────────────────────────────────────────────────────────

    private fun getDiskFile(key: String): File {
        val safeName = md5(key)
        return File(diskCacheDir, "$safeName.b64")
    }

    private fun md5(s: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
