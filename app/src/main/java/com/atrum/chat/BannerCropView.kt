package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * Кадратор шапки в стиле ATRUM: зум/пан фото внутри фиксированной рамки 3:1.
 * Изображение всегда покрывает рамку (без пустот). Снаружи рамки — затемнение,
 * рамка — accent. Опционально показывает safe-zone (где аватар и имя перекроют шапку).
 *
 * Зум управляется и жестом (pinch), и внешним слайдером (setZoomFraction/onZoom).
 * getCroppedBitmap() отдаёт вырезанную область рамки в целевом разрешении.
 */
class BannerCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var bmp: Bitmap? = null
    private val matrix = Matrix()
    private val frame = RectF()
    private val tmp = RectF()
    private val mvals = FloatArray(9)

    private var minScale = 1f
    private var maxScale = 4f
    // Соотношение рамки. По умолчанию 3:1 (шапка). Для аватара — 1:1 + [circleMode].
    var ratioW = 3f
    var ratioH = 1f
    private val sideMarginDp = 12f

    /**
     * Круглый режим (аватар): рамка квадратная (ratio 1:1), но рисуется КРУГ — затемнение
     * вне круга, акцентная обводка по окружности, без сетки/safe-zone. Вырезанная область —
     * квадрат, описывающий круг (аватар маскируется по кругу при рендере, AvatarUtils.toCircle).
     * Настраивается через [configure]; банерный путь (3:1) не затрагивается.
     */
    var circleMode = false
        set(v) { field = v; invalidate() }
    private val dimPath = Path()

    /** Настроить рамку: круг+1:1 (аватар) или прямоугольник заданного соотношения (шапка). */
    fun configure(circle: Boolean, aspectW: Float, aspectH: Float) {
        ratioW = aspectW; ratioH = aspectH; circleMode = circle
        setup()
    }

    var showSafeZone = false
        set(v) { field = v; invalidate() }

    /** Колбэк при изменении зума жестом — чтобы синхронизировать слайдер. */
    var onZoom: ((fraction: Float) -> Unit)? = null
    private var suppressZoomCb = false

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val gridPaint = Paint().apply { color = 0x40FFFFFF; strokeWidth = dp(0.8f) }
    private val safeFill = Paint().apply { color = 0x33FF5252 }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.5f); color = Color.WHITE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(5f)), 0f)
    }
    private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7B2CBF.toInt() }
    private val avatarStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2f); color = Color.WHITE
    }
    private val txtPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(13f); isFakeBoldText = true
        setShadowLayer(dp(3f), 0f, dp(1f), 0x99000000.toInt())
    }
    private val avInitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = dp(16f); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = currentScale()
            var f = d.scaleFactor
            val target = (cur * f).coerceIn(minScale, maxScale)
            f = target / cur
            matrix.postScale(f, f, d.focusX, d.focusY)
            clamp(); invalidate(); notifyZoom()
            return true
        }
    })
    private var lastX = 0f; private var lastY = 0f; private var dragging = false

    fun setBitmap(b: Bitmap?) { bmp = b; setup() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); setup() }

    private fun setup() {
        val b = bmp ?: return
        if (width == 0 || height == 0) return
        val m = dp(sideMarginDp)
        // Вписываем рамку заданного соотношения в доступную область по ОБЕИМ сторонам —
        // иначе 1:1 (аватар) на широком экране вылезал бы за высоту. Для 3:1 (шапка)
        // fh = fw/3 обычно меньше высоты → поведение прежнее (рамка по ширине, центр).
        val availW = width - 2 * m
        val availH = height - 2 * m
        var fw = availW
        var fh = fw * ratioH / ratioW
        if (fh > availH) { fh = availH; fw = fh * ratioW / ratioH }
        val left = (width - fw) / 2f
        val top = (height - fh) / 2f
        frame.set(left, top, left + fw, top + fh)

        minScale = max(frame.width() / b.width, frame.height() / b.height)
        maxScale = minScale * 4f
        matrix.reset()
        matrix.postScale(minScale, minScale)
        tmp.set(0f, 0f, b.width.toFloat(), b.height.toFloat()); matrix.mapRect(tmp)
        matrix.postTranslate(frame.centerX() - tmp.centerX(), frame.centerY() - tmp.centerY())
        invalidate(); notifyZoom()
    }

    private fun currentScale(): Float { matrix.getValues(mvals); return mvals[Matrix.MSCALE_X] }

    private fun clamp() {
        val b = bmp ?: return
        tmp.set(0f, 0f, b.width.toFloat(), b.height.toFloat()); matrix.mapRect(tmp)
        var dx = 0f; var dy = 0f
        if (tmp.width() >= frame.width()) {
            if (tmp.left > frame.left) dx = frame.left - tmp.left
            else if (tmp.right < frame.right) dx = frame.right - tmp.right
        } else dx = frame.centerX() - tmp.centerX()
        if (tmp.height() >= frame.height()) {
            if (tmp.top > frame.top) dy = frame.top - tmp.top
            else if (tmp.bottom < frame.bottom) dy = frame.bottom - tmp.bottom
        } else dy = frame.centerY() - tmp.centerY()
        matrix.postTranslate(dx, dy)
    }

    private fun notifyZoom() {
        if (suppressZoomCb) return
        val f = if (maxScale > minScale) (currentScale() - minScale) / (maxScale - minScale) else 0f
        onZoom?.invoke(f.coerceIn(0f, 1f))
    }

    /** Внешний зум (слайдер): доля 0..1 → масштаб minScale..maxScale вокруг центра рамки. */
    fun setZoomFraction(fraction: Float) {
        if (bmp == null) return
        val target = minScale + fraction.coerceIn(0f, 1f) * (maxScale - minScale)
        val cur = currentScale()
        if (cur <= 0f) return
        val f = target / cur
        suppressZoomCb = true
        matrix.postScale(f, f, frame.centerX(), frame.centerY())
        clamp(); invalidate()
        suppressZoomCb = false
    }

    fun resetZoom() { setup() }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; dragging = true }
            MotionEvent.ACTION_MOVE -> if (dragging && !scaleDetector.isInProgress) {
                matrix.postTranslate(event.x - lastX, event.y - lastY)
                lastX = event.x; lastY = event.y
                clamp(); invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        canvas.drawBitmap(b, matrix, imgPaint)

        if (circleMode) {
            // Круг (аватар): затемняем всё ВНЕ круга (rect минус circle, even-odd) и
            // рисуем акцентную обводку по окружности. Без сетки/safe-zone.
            val cx = frame.centerX(); val cy = frame.centerY()
            val r = minOf(frame.width(), frame.height()) / 2f
            dimPath.reset()
            dimPath.fillType = Path.FillType.EVEN_ODD
            dimPath.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            dimPath.addCircle(cx, cy, r, Path.Direction.CW)
            canvas.drawPath(dimPath, dimPaint)
            canvas.drawCircle(cx, cy, r, framePaint)
            return
        }

        // затемнение вне рамки (4 полосы)
        canvas.drawRect(0f, 0f, width.toFloat(), frame.top, dimPaint)
        canvas.drawRect(0f, frame.bottom, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(0f, frame.top, frame.left, frame.bottom, dimPaint)
        canvas.drawRect(frame.right, frame.top, width.toFloat(), frame.bottom, dimPaint)

        if (showSafeZone) {
            // зона, перекрываемая аватаром и именем (низ рамки)
            val coverTop = frame.bottom - frame.height() * 0.42f
            canvas.drawRect(frame.left, coverTop, frame.right, frame.bottom, safeFill)
            canvas.drawLine(frame.left, coverTop, frame.right, coverTop, dashPaint)
            // макет аватара + имени
            val r = dp(22f)
            val cx = frame.left + dp(14f) + r
            val cy = frame.bottom - dp(12f) - r
            canvas.drawCircle(cx, cy, r, avatarPaint)
            canvas.drawCircle(cx, cy, r, avatarStroke)
            canvas.drawText("А", cx, cy + dp(6f), avInitPaint)
            canvas.drawText(context.getString(R.string.bcrop_sample_name), cx + r + dp(10f), cy - dp(2f), txtPaint)
        } else {
            // сетка третей
            val tw = frame.width() / 3f; val th = frame.height() / 3f
            canvas.drawLine(frame.left + tw, frame.top, frame.left + tw, frame.bottom, gridPaint)
            canvas.drawLine(frame.left + 2 * tw, frame.top, frame.left + 2 * tw, frame.bottom, gridPaint)
            canvas.drawLine(frame.left, frame.top + th, frame.right, frame.top + th, gridPaint)
            canvas.drawLine(frame.left, frame.top + 2 * th, frame.right, frame.top + 2 * th, gridPaint)
        }
        canvas.drawRect(frame, framePaint)
    }

    /** Вырезает область рамки в целевом разрешении (по умолчанию 2400×800, 3:1). */
    fun getCroppedBitmap(outW: Int = 2400, outH: Int = 800): Bitmap? {
        val b = bmp ?: return null
        // рамка в координатах исходного битмапа = inverse(matrix) применить к frame
        val inv = Matrix()
        if (!matrix.invert(inv)) return null
        val src = RectF(frame)
        inv.mapRect(src)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val m = Matrix()
        m.setRectToRect(src, RectF(0f, 0f, outW.toFloat(), outH.toFloat()), Matrix.ScaleToFit.FILL)
        c.drawBitmap(b, m, imgPaint)
        return out
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
