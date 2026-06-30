package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Оверлей сканера QR: затемняет всё вне центрального квадрата (~82%) и рисует
 * Г-образные уголки по его углам. Поверх камеры (фон гарантированно тёмный),
 * поэтому акцентный цвет уголков допустим в обеих темах.
 */
class ScanOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(209, 0, 0, 0) // ~82% чёрного
        style = Paint.Style.FILL
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_light)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val scrimPath = Path()
    private val cornerPath = Path()
    private val square = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val side = minOf(w, h) * 0.62f
        val left = (w - side) / 2f
        val top = (h - side) / 2f
        square.set(left, top, left + side, top + side)
        val r = dp(16f)

        // Затемнение вне квадрата: полный экран минус закруглённый квадрат (even-odd).
        scrimPath.reset()
        scrimPath.fillType = Path.FillType.EVEN_ODD
        scrimPath.addRect(0f, 0f, w, h, Path.Direction.CW)
        scrimPath.addRoundRect(square, r, r, Path.Direction.CW)
        canvas.drawPath(scrimPath, scrimPaint)

        // Уголки.
        val len = side * 0.12f
        drawCorner(canvas, square.left, square.top, len, r, 1, 1)
        drawCorner(canvas, square.right, square.top, len, r, -1, 1)
        drawCorner(canvas, square.left, square.bottom, len, r, 1, -1)
        drawCorner(canvas, square.right, square.bottom, len, r, -1, -1)
    }

    private fun drawCorner(c: Canvas, x: Float, y: Float, len: Float, r: Float, sx: Int, sy: Int) {
        cornerPath.reset()
        cornerPath.moveTo(x, y + sy * (r + len))
        cornerPath.lineTo(x, y + sy * r)
        cornerPath.quadTo(x, y, x + sx * r, y)
        cornerPath.lineTo(x + sx * (r + len), y)
        c.drawPath(cornerPath, cornerPaint)
    }
}
