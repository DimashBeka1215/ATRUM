package com.atrum.chat

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Дисковый кеш готовых кадров webm-стикеров (как в Telegram).
 *
 * Зачем: MediaCodec+EGL-декод тяжёлый и при каждом открытии чата повторялся для всех webm
 * (in-memory кеш чистится). Теперь стикер декодируется ОДИН раз за всё время, кадры с
 * впечатанной прозрачностью пишутся на диск, и дальше читаются мгновенно — без декодера.
 *
 * Формат файла (gzip): [int frameCount][int w][int h] + frameCount * (w*h*4 байт ARGB_8888).
 * Прозрачные стикеры жмутся отлично (огромные нулевые области), так что диск расходуется мало.
 * Лежит в cacheDir — система сама очистит при нехватке места.
 */
object StickerDiskCache {

    private const val MAGIC = 0x53544B34  // "STK4" — + срез пустых кадров по краям (фикс мигания)
    private const val MAX_FRAMES = 120
    private const val MAX_DIM = 1024
    private const val MAX_CACHE_BYTES = 64L * 1024 * 1024  // потолок диск-кеша кадров

    // v4 — добавлен срез почти-прозрачных кадров по краям (фикс мигания на стыке петли).
    // Имя сменили, чтобы уже запечённые кадры с пустым крайним кадром перекодировались.
    private const val CURRENT_DIR = "sticker_frames_v4"

    private fun dir(cacheDir: File): File =
        File(cacheDir, CURRENT_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Удаляет директории кадров прошлых версий (sticker_frames_v1/v2/…), кроме текущей —
     * после смены версии формата они становятся мёртвым грузом. Вызывать в фоне при старте.
     */
    fun cleanupOldVersions(cacheDir: File) {
        try {
            cacheDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("sticker_frames_v") && it.name != CURRENT_DIR }
                ?.forEach { try { it.deleteRecursively() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    private fun fileFor(cacheDir: File, key: String): File =
        File(dir(cacheDir), md5(key) + ".sfc")

    /** Быстрая проверка: кадры этого стикера уже лежат на диске. */
    fun has(cacheDir: File, key: String): Boolean {
        val f = fileFor(cacheDir, key)
        return f.exists() && f.length() > 0L
    }

    /**
     * Урезает папку до maxBytes, удаляя самые старые файлы (как кеш в Telegram).
     * Универсально — применимо и к temp .webm.
     */
    fun trimDir(targetDir: File, maxBytes: Long, suffix: String) {
        try {
            val files = targetDir.listFiles()?.filter { it.isFile && it.name.endsWith(suffix) } ?: return
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            files.sortedBy { it.lastModified() }.forEach { f ->
                if (total <= maxBytes) return
                total -= f.length()
                try { f.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /** Читает готовые кадры + задержку с диска, либо null если кеша нет/повреждён/старый формат. */
    fun load(cacheDir: File, key: String): StickerFrames? {
        val f = fileFor(cacheDir, key)
        if (!f.exists() || f.length() == 0L) return null
        return try {
            DataInputStream(GZIPInputStream(BufferedInputStream(FileInputStream(f)))).use { din ->
                if (din.readInt() != MAGIC) return null  // старый/чужой формат → перекодируем
                val n = din.readInt()
                val w = din.readInt()
                val h = din.readInt()
                val delayMs = din.readInt().toLong().coerceIn(20L, 400L)
                if (n <= 0 || n > MAX_FRAMES || w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM) return null
                val out = ArrayList<Bitmap>(n)
                val buf = ByteArray(w * h * 4)
                for (i in 0 until n) {
                    din.readFully(buf)
                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    b.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
                    out.add(b)
                }
                StickerFrames(out, delayMs)
            }
        } catch (_: Exception) {
            try { f.delete() } catch (_: Exception) {}
            null
        }
    }

    /** Пишет кадры + задержку на диск (атомарно через .tmp). Кадры одного размера. */
    fun save(cacheDir: File, key: String, value: StickerFrames) {
        val frames = value.frames
        if (frames.isEmpty()) return
        val w = frames[0].width
        val h = frames[0].height
        if (w <= 0 || h <= 0 || w > MAX_DIM || h > MAX_DIM) return
        val target = fileFor(cacheDir, key)
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            DataOutputStream(GZIPOutputStream(BufferedOutputStream(FileOutputStream(tmp)))).use { dout ->
                dout.writeInt(MAGIC)
                dout.writeInt(frames.size)
                dout.writeInt(w)
                dout.writeInt(h)
                dout.writeInt(value.delayMs.toInt())
                val buf = ByteBuffer.allocate(w * h * 4)
                for (b in frames) {
                    if (b.width != w || b.height != h) return@use
                    buf.rewind()
                    b.copyPixelsToBuffer(buf)
                    dout.write(buf.array())
                }
            }
            if (tmp.renameTo(target)) {
                trimDir(dir(cacheDir), MAX_CACHE_BYTES, ".sfc")
            } else {
                tmp.delete()
            }
        } catch (_: Exception) {
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    private fun md5(s: String): String {
        val d = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return d.joinToString("") { "%02x".format(it) }
    }
}
