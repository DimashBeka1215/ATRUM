package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * Узорный фон экрана QR-приглашения в стиле фирменной монограммы: ровные ряды
 * «ATRUM» чередуются с рядами «ATR» (со сдвигом «кирпичом»), между словами — мелкие
 * мотивы (точки, плюсы, кружки, ромбы). Весь паттерн наклонён на единый угол —
 * равномерно, ритмично, по диагонали.
 *
 * Тёмная и светлая:
 *  — фон берётся из токена @color/bg (адаптивный),
 *  — контур — акцент с низкой прозрачностью (акцент одинаков в обеих темах).
 */
class QrPatternView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val d = resources.displayMetrics.density

    private val bgPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.bg)
    }

    private val faint = ColorUtils.setAlphaComponent(
        ContextCompat.getColor(context, R.color.accent), 82
    )

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
        color = faint
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * d
        color = faint
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        letterSpacing = 0.14f
    }

    private val tilt = 13f

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        canvas.save()
        canvas.rotate(tilt, w / 2f, h / 2f)
        drawMonogram(canvas, w, h)
        canvas.restore()
    }

    private fun drawMonogram(canvas: Canvas, w: Float, h: Float) {
        val dx = 138f * d
        val dy = 56f * d
        val margin = (w + h) * 0.2f      // запас под поворот, чтобы углы были закрыты
        var iy = 0
        var y = -margin
        while (y < h + margin) {
            val atrum = iy % 2 == 0
            val off = if (atrum) 0f else dx / 2f
            textPaint.textSize = (if (atrum) 20f else 17f) * d
            val s = if (atrum) "ATRUM" else "ATR"
            var x = -margin
            var k = iy
            while (x < w + margin) {
                word(canvas, s, x + off, y)
                motif(canvas, x + off + dx / 2f, y, k)
                x += dx; k++
            }
            iy++; y += dy
        }
    }

    private fun word(c: Canvas, s: String, cx: Float, cy: Float) {
        val tw = textPaint.measureText(s)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        c.drawText(s, cx - tw / 2f, baseline, textPaint)
    }

    private fun motif(c: Canvas, cx: Float, cy: Float, k: Int) {
        when (k % 4) {
            0 -> {
                val r = 2.6f * d
                val fillDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = faint; style = Paint.Style.FILL }
                c.drawCircle(cx, cy, r, fillDot)
            }
            1 -> {
                val s = 5.5f * d
                c.drawLine(cx - s, cy, cx + s, cy, strokePaint)
                c.drawLine(cx, cy - s, cx, cy + s, strokePaint)
            }
            2 -> {
                val s = 5f * d
                c.drawCircle(cx, cy, s, strokePaint)
            }
            else -> {
                val s = 5f * d
                c.drawLine(cx, cy - s, cx + s, cy, strokePaint)
                c.drawLine(cx + s, cy, cx, cy + s, strokePaint)
                c.drawLine(cx, cy + s, cx - s, cy, strokePaint)
                c.drawLine(cx - s, cy, cx, cy - s, strokePaint)
            }
        }
    }
}
