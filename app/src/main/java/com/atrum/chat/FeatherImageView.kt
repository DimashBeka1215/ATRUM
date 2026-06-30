package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView

/**
 * ImageView, у которого края изображения, граничащие с пустотой (letterbox-полосы),
 * мягко растворяются alpha-градиентом — чтобы резкое фото плавно перетекало в размытый
 * фон под ним (приём как «растворение баннера» в шапке настроек).
 *
 * Растворяется только та сторона, где между краем картинки и краем view есть зазор.
 * Когда картинку увеличивают (зум), её края выходят за пределы view — растворение
 * автоматически отключается, изображение заполняет экран как обычно.
 */
class FeatherImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : AppCompatImageView(context, attrs, defStyle) {

    private val rect = RectF()
    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }

    /** Доля растворения от меньшей стороны видимого изображения. */
    private val featherFraction = 0.24f

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        if (d == null) { super.onDraw(canvas); return }

        val vw = width.toFloat(); val vh = height.toFloat()
        rect.set(d.bounds)
        if (scaleType == android.widget.ImageView.ScaleType.MATRIX) {
            imageMatrix.mapRect(rect)
        } else {
            // FIT_CENTER (кадр до перехода на MATRIX): считаем fit-прямоугольник сами,
            // чтобы растворение краёв работало и в этот момент — иначе при свайпе мог
            // мелькнуть резкий край без растворения.
            val bw = rect.width(); val bh = rect.height()
            if (bw <= 0f || bh <= 0f) { super.onDraw(canvas); return }
            val s = minOf(vw / bw, vh / bh)
            val dw = bw * s; val dh = bh * s
            val l = (vw - dw) / 2f; val t = (vh - dh) / 2f
            rect.set(l, t, l + dw, t + dh)
        }

        val eps = 1f
        val fadeTop = rect.top > eps
        val fadeBottom = rect.bottom < vh - eps
        val fadeLeft = rect.left > eps
        val fadeRight = rect.right < vw - eps

        if (!fadeTop && !fadeBottom && !fadeLeft && !fadeRight) {
            super.onDraw(canvas); return
        }

        val fpx = (featherFraction * minOf(rect.width(), rect.height())).coerceIn(32f, 360f)
        val xL = maxOf(0f, rect.left); val xR = minOf(vw, rect.right)
        val yT = maxOf(0f, rect.top);  val yB = minOf(vh, rect.bottom)

        val layer = canvas.saveLayer(0f, 0f, vw, vh, null)
        super.onDraw(canvas)

        if (fadeTop) eraseBand(canvas, xL, rect.top, xR, rect.top + fpx, 0f, rect.top, 0f, rect.top + fpx)
        if (fadeBottom) eraseBand(canvas, xL, rect.bottom - fpx, xR, rect.bottom, 0f, rect.bottom, 0f, rect.bottom - fpx)
        if (fadeLeft) eraseBand(canvas, rect.left, yT, rect.left + fpx, yB, rect.left, 0f, rect.left + fpx, 0f)
        if (fadeRight) eraseBand(canvas, rect.right - fpx, yT, rect.right, yB, rect.right, 0f, rect.right - fpx, 0f)

        canvas.restoreToCount(layer)
    }

    /**
     * Стираем (DST_OUT) полосу плавной многоступенчатой кривой: непрозрачно у края
     * картинки → прозрачно внутрь. Плавный спад убирает изломы (полосы Маха) на концах.
     */
    private fun eraseBand(
        canvas: Canvas, l: Float, t: Float, r: Float, b: Float,
        gx0: Float, gy0: Float, gx1: Float, gy1: Float
    ) {
        erasePaint.shader = LinearGradient(
            gx0, gy0, gx1, gy1, FADE_COLORS, FADE_STOPS, Shader.TileMode.CLAMP
        )
        canvas.drawRect(l, t, r, b, erasePaint)
    }

    private companion object {
        // Плавный спад alpha (≈ smoothstep): без резких изломов на обоих концах.
        val FADE_COLORS = intArrayOf(
            255 shl 24, 244 shl 24, 216 shl 24, 170 shl 24,
            112 shl 24, 56 shl 24, 18 shl 24, 0
        )
        val FADE_STOPS = floatArrayOf(
            0f, 0.12f, 0.26f, 0.42f, 0.60f, 0.78f, 0.90f, 1f
        )
    }
}
