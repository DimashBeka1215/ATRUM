package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * Утилиты для работы с аватарками:
 *  - загрузка из Uri (галерея) с автоматическим уменьшением
 *  - сжатие в JPEG
 *  - кодирование/декодирование base64
 *  - подгонка под круг (для предпросмотра)
 *
 * Цель — держать аватарку в base64 размером ~30-50 КБ, чтобы умещалась в gist.
 */
object AvatarUtils {

    /** Максимальный размер стороны аватарки. */
    private const val MAX_SIZE = 256

    /** Качество JPEG (0-100). 70 даёт хороший баланс размер/качество. */
    private const val JPEG_QUALITY = 70

    /**
     * Загружает картинку из Uri, ресайзит и сжимает в JPEG.
     * Возвращает Bitmap который можно сразу показать или закодировать.
     */
    fun loadAndResize(context: Context, uri: Uri): Bitmap? {
        return try {
            // Шаг 1: узнать размеры без полной загрузки
            val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, sizeOpts)
            }
            if (sizeOpts.outWidth <= 0 || sizeOpts.outHeight <= 0) return null

            // Шаг 2: подобрать inSampleSize для экономии памяти
            val sample = calculateSampleSize(sizeOpts.outWidth, sizeOpts.outHeight, MAX_SIZE)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            // Шаг 3: квадратный кроп по центру
            val cropped = centerSquareCrop(bitmap)

            // Шаг 4: точный ресайз до MAX_SIZE x MAX_SIZE
            if (cropped.width != MAX_SIZE || cropped.height != MAX_SIZE) {
                Bitmap.createScaledBitmap(cropped, MAX_SIZE, MAX_SIZE, true)
            } else {
                cropped
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Bitmap → base64 JPEG string. */
    fun toBase64(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** base64 → Bitmap. Возвращает null если строка повреждена. */
    fun fromBase64(base64: String?): Bitmap? {
        if (base64.isNullOrBlank()) return null
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        }
    }

    /** Делает круглый Bitmap из квадратного (для красивого превью). */
    fun toCircle(bitmap: Bitmap): Bitmap {
        val size = minOf(bitmap.width, bitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
        }
        val rect = RectF(0f, 0f, size.toFloat(), size.toFloat())
        canvas.drawOval(rect, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
        val dstRect = Rect(0, 0, size, size)
        canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
        return output
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

    private fun centerSquareCrop(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }
}
