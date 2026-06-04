package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Кеш картинок из gist: in-memory (LruCache) + persistent (Disk).
 *
 * Храним Bitmap (для отображения в адаптере) и сырой base64
 * (для fullscreen-просмотрщика — открывается через [ImageViewActivity]).
 *
 * При вытеснении bitmap из LruCache base64 остаётся на диске —
 * повторный декод дешевле нового сетевого запроса к GitHub.
 */
object ImageCache {

    private const val MAX_BITMAP_BYTES = 50 * 1024 * 1024
    private const val DISK_CACHE_SUBDIR = "images_v1"

    private val bitmaps = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** Временный in-memory кеш для base64 (только активная сессия). */
    private val base64s = HashMap<String, String>()

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

    // ── Bitmap ─────────────────────────────────────────────────────────────────

    fun getBitmap(key: String): Bitmap? = bitmaps.get(key)

    // ── Base64 ────────────────────────────────────────────────────────────────

    fun getBase64(key: String): String? {
        // 1. Memory
        synchronized(base64s) {
            base64s[key]?.let { return it }
        }

        // 2. Disk
        return try {
            val file = getDiskFile(key)
            if (file.exists()) {
                val content = file.readText()
                // Подгружаем в память для быстрых повторных тапов
                synchronized(base64s) { base64s[key] = content }
                content
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // ── Составной статус ──────────────────────────────────────────────────────

    fun isKnown(key: String): Boolean =
        bitmaps.get(key) != null || synchronized(base64s) { key in base64s } || getDiskFile(key).exists()

    // ── Put ───────────────────────────────────────────────────────────────────

    fun put(key: String, base64: String, bitmap: Bitmap?) {
        // 1. Memory
        synchronized(base64s) { base64s[key] = base64 }
        if (bitmap != null) bitmaps.put(key, bitmap)

        // 2. Disk (async safe since we just write)
        try {
            val file = getDiskFile(key)
            if (!file.exists()) {
                file.writeText(base64)
            }
        } catch (_: Exception) {}
    }

    // ── Confirmation tracking ─────────────────────────────────────────────────

    fun wasShownConfirmation(key: String): Boolean = key in shownConfirmations

    fun markShownConfirmation(key: String) { shownConfirmations.add(key) }

    // ── Clear ─────────────────────────────────────────────────────────────────

    fun clear() {
        bitmaps.evictAll()
        synchronized(base64s) { base64s.clear() }
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
