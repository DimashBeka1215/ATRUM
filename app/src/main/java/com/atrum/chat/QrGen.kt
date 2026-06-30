package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * Генерация QR-кода из строки. Использует уже подключённый ZXing-core.
 *  - [make] — простой чёрно-белый Bitmap (для Bluetooth-токена).
 *  - [makeStyled] — фирменный стиль Atrum: скруглённые точки, акцентные «глаза»,
 *    контурное лого в центре. QR всегда на белой подложке (читаемость сканером).
 */
object QrGen {

    /** Префикс полезной нагрузки QR для BLE-подключения Atrum. */
    const val BT_PREFIX = "ATRUMBT:"

    /** Цвет модулей фирменного QR (акцент). QR рисуется только на белом — цвет фиксирован. */
    private const val ACCENT = 0xFF9D4EDD.toInt()

    fun btPayload(token: String): String = BT_PREFIX + token

    /** Извлекает токен из отсканированного QR или null, если это не Atrum-BT QR. */
    fun parseBtToken(scanned: String?): String? {
        val s = scanned?.trim() ?: return null
        return if (s.startsWith(BT_PREFIX)) s.removePrefix(BT_PREFIX).takeIf { it.isNotBlank() } else null
    }

    /** Рисует простой QR указанного размера (px). null при ошибке/пустой строке. */
    fun make(text: String, sizePx: Int): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val hints = mapOf(EncodeHintType.MARGIN to 1)
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) {
                val off = y * w
                for (x in 0 until w) {
                    pixels[off + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Фирменный стилизованный QR.
     * @param sizePx сторона результата (px). Фон — белый (нужен сканеру).
     * @param withLogo рисовать ли контурное лого по центру (ECC=H терпит перекрытие ~30%).
     */
    fun makeStyled(
        context: Context,
        text: String,
        sizePx: Int,
        withLogo: Boolean = true,
        centerBitmap: Bitmap? = null
    ): Bitmap? {
        if (text.isBlank() || sizePx <= 0) return null
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val qr = Encoder.encode(text, ErrorCorrectionLevel.H, hints)
            val matrix = qr.matrix ?: return null
            val n = matrix.width                       // модулей на сторону
            val quiet = 4                              // тихая зона (модулей)
            val total = n + quiet * 2
            val cell = sizePx.toFloat() / total

            val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)

            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.FILL }
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

            val finder = 7
            fun inFinder(mx: Int, my: Int): Boolean =
                (mx < finder && my < finder) ||
                (mx >= n - finder && my < finder) ||
                (mx < finder && my >= n - finder)

            // Центральная зона под аватар — вырезаем КРУГОМ (точки QR огибают круглый
            // аватар, без пустых углов как при квадратном вырезе).
            val logoModules = if (withLogo) (n * 0.30f).toInt().coerceAtLeast(5) else 0
            val centerMod = (n - 1) / 2f
            val logoRadMod = logoModules / 2f
            fun inLogo(mx: Int, my: Int): Boolean {
                if (!withLogo) return false
                val ddx = mx - centerMod
                val ddy = my - centerMod
                return ddx * ddx + ddy * ddy <= logoRadMod * logoRadMod
            }

            // Точки данных — кружки (никаких квадратов).
            val r = cell * 0.46f
            for (my in 0 until n) {
                for (mx in 0 until n) {
                    if (matrix.get(mx, my).toInt() != 1) continue
                    if (inFinder(mx, my) || inLogo(mx, my)) continue
                    val cx = (quiet + mx) * cell + cell / 2f
                    val cy = (quiet + my) * cell + cell / 2f
                    canvas.drawCircle(cx, cy, r, fill)
                }
            }

            // Три «глаза» — скруглённые квадраты с кольцом и зрачком.
            fun drawEye(fx: Int, fy: Int) {
                val left = (quiet + fx) * cell
                val top = (quiet + fy) * cell
                val size = finder * cell
                val outerR = cell * 1.9f
                canvas.drawRoundRect(RectF(left, top, left + size, top + size), outerR, outerR, fill)
                val pad = cell
                val midR = outerR * 0.7f
                canvas.drawRoundRect(
                    RectF(left + pad, top + pad, left + size - pad, top + size - pad),
                    midR, midR, white
                )
                val pupilR = cell * 1.1f
                canvas.drawRoundRect(
                    RectF(left + 2 * cell, top + 2 * cell, left + 5 * cell, top + 5 * cell),
                    pupilR, pupilR, fill
                )
            }
            drawEye(0, 0)
            drawEye(n - finder, 0)
            drawEye(0, n - finder)

            // Центр на белом круге: аватар пользователя, иначе — контурное лого.
            if (withLogo) {
                val c = sizePx / 2f
                val logoR = logoModules * cell / 2f
                canvas.drawCircle(c, c, logoR * 0.98f, white)
                if (centerBitmap != null) {
                    // Аватар, обрезанный по кругу, вписываем в центр (тонкое белое кольцо вокруг).
                    val avatarR = logoR * 0.9f
                    val circ = AvatarUtils.toCircle(centerBitmap)
                    val dst = RectF(c - avatarR, c - avatarR, c + avatarR, c + avatarR)
                    canvas.drawBitmap(
                        circ, null, dst,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
                    )
                } else {
                    val d = ContextCompat.getDrawable(context, R.drawable.ic_qr_logo)?.mutate()
                    if (d != null) {
                        val s = (logoR * 1.5f).toInt()
                        d.setBounds((c - s / 2).toInt(), (c - s / 2).toInt(), (c + s / 2).toInt(), (c + s / 2).toInt())
                        d.draw(canvas)
                    }
                }
            }
            bmp
        } catch (_: Throwable) {
            null
        }
    }
}
