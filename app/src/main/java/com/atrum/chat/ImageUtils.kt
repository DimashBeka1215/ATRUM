package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Утилиты для работы с изображениями в чате (НЕ аватарками — для них AvatarUtils).
 *
 * Методы:
 *   loadOriginal()     — читает оригинальные байты из URI без сжатия/ресайза.
 *                        Используется по умолчанию при отправке изображений.
 *                        Большие файлы обрабатываются чанковой загрузкой (ImageChunker).
 *
 *   loadAndCompress()  — оставлен для совместимости, сжимает до ~500 КБ.
 */
object ImageUtils {

    /**
     * Максимальный размер стороны изображения.
     * 1280 — стандарт WhatsApp/Telegram: хорошо выглядит на современных экранах,
     * не создаёт гигантских файлов.
     */
    private const val MAX_SIZE = 1600

    /**
     * Стартовое качество JPEG.
     * 85 — баланс качество/размер: артефакты практически невидимы.
     */
    private const val START_QUALITY = 92

    /** Минимальное качество, ниже которого не опускаемся. */
    private const val MIN_QUALITY = 75

    /**
     * Целевой максимальный размер сжатого base64 (≈ 500 КБ).
     * Каждая картинка хранится в отдельном файле gist'а (лимит ~1 МБ),
     * поэтому 500 КБ — разумный потолок с запасом под шифрование.
     */
    private const val MAX_BASE64_SIZE = 450 * 1024

    /**
     * Загружает картинку, ресайзит и компрессит до целевого размера.
     * Возвращает base64-строку, готовую к отправке в gist. null при ошибке.
     */
    fun loadAndCompress(context: Context, uri: Uri): String? {
        return try {
            // Шаг 1: размеры
            val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, sizeOpts)
            }
            if (sizeOpts.outWidth <= 0 || sizeOpts.outHeight <= 0) return null

            // Шаг 2: загрузка с уменьшением sample
            val sample = calculateSampleSize(sizeOpts.outWidth, sizeOpts.outHeight, MAX_SIZE)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            // Шаг 3: точный ресайз если ещё больше MAX_SIZE
            if (bitmap.width > MAX_SIZE || bitmap.height > MAX_SIZE) {
                val scale = MAX_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
                val newW = (bitmap.width * scale).toInt()
                val newH = (bitmap.height * scale).toInt()
                bitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            }

            // Шаг 4: JPEG сжатие с подбором качества чтобы влезть в MAX_BASE64_SIZE
            var quality = START_QUALITY
            var base64: String
            while (true) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                base64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                if (base64.length <= MAX_BASE64_SIZE || quality <= MIN_QUALITY) break
                quality -= 10
            }
            base64
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Загружает оригинальные байты из URI без какого-либо сжатия или ресайза.
     *
     * Возвращает base64-строку готовую к шифрованию и отправке.
     * Размер не ограничен — крупные изображения нужно передавать чанками
     * через ImageChunker / GistApi.saveFileChunked().
     *
     * null при ошибке чтения.
     */
    fun loadOriginal(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Читает только размеры изображения из URI (без полной декодировки) и
     * возвращает соотношение сторон ширина/высота.
     * Используется для вычисления AR перед загрузкой коллажа.
     */
    fun getAspectRatio(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, opts)
                if (opts.outHeight > 0) opts.outWidth.toFloat() / opts.outHeight else 1f
            } ?: 1f
        } catch (e: Exception) {
            1f
        }
    }

    /** Потолок стороны декодируемой картинки — защита от OOM на гигантском вложении. */
    private const val MAX_IMAGE_DIM = 2560

    /** base64 → Bitmap. С лимитом размеров (картинка от собеседника недоверенная). */
    fun fromBase64(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / sample > MAX_IMAGE_DIM ||
                   bounds.outHeight / sample > MAX_IMAGE_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Calculates the average relative luminance of a bitmap.
     * Uses a scaled-down 1x1 sample for high performance.
     * Returns a value between 0.0 (black) and 1.0 (white).
     */
    fun calculateLuminance(bitmap: Bitmap): Float {
        return try {
            val tiny = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
            val pixel = tiny.getPixel(0, 0)
            if (tiny != bitmap) tiny.recycle()
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, reqSize: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= reqSize && h / 2 >= reqSize) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }
}
