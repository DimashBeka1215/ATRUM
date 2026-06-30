package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Фон-заполнитель для просмотрщика: рисует то же изображение в ТОМ ЖЕ масштабе и позиции,
 * что и переднее фото (fit-center), а letterbox-полосы заполняет продолжением крайних
 * пикселей (TileMode.CLAMP — растяжка края). Поверх накладывается размытие (RenderEffect).
 *
 * Зачем именно так: при обычном centerCrop фон увеличен относительно резкого фото, поэтому
 * у границы их содержимое не совпадает и виден «двойной» край. Здесь фон под краем фото —
 * ровно то же содержимое в той же позиции, а полосы — гладкое продолжение края. Растворение
 * краёв переднего фото (FeatherImageView) превращается в чистый переход резкости — без шва.
 */
class BlurFillView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bmp: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    fun setBitmap(b: Bitmap?) {
        bmp = b
        rebuildShader()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildShader()
    }

    private fun rebuildShader() {
        val b = bmp
        if (b == null || width == 0 || height == 0) { paint.shader = null; return }
        // FIT-масштаб (как у переднего фото): min — изображение целиком, центрировано.
        val s = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        matrix.reset()
        matrix.postScale(s, s)
        matrix.postTranslate((width - b.width * s) / 2f, (height - b.height * s) / 2f)
        val shader = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        shader.setLocalMatrix(matrix)
        paint.shader = shader
    }

    override fun onDraw(canvas: Canvas) {
        if (paint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }
}
