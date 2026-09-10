package com.atrum.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

/**
 * Кастомная замена стоковой полоски прокрутки для rv_messages: тонкая фиолетовая
 * «живая» полоса — сужается сверху и снизу (эллипс на тонком прямоугольнике даёт
 * форму линзы), с мягким пульсирующим свечением. Толщина ядра — как у стоковой
 * One UI (тонкая), а не жирный стандартный Android scrollbar.
 *
 * Длина и позиция — пропорциональны видимой части списка относительно всего
 * контента (обычная логика scrollbar-а: computeVerticalScrollRange/Extent/Offset,
 * тот же приём, что использует внутренний FastScroller RecyclerView).
 *
 * Самодостаточный компонент: единственная точка интеграции — [attach] из
 * ChatActivity (см. вызов рядом с rvMessages.adapter = adapter). Пауза пульса на
 * фоне — через onWindowVisibilityChanged, без завязки на lifecycle Activity.
 */
class PulseScrollIndicator @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density

    // Толщина "ядра" полосы — тонкая, как в One UI (не жирный стоковый Android scrollbar).
    private val coreWidth = 2.5f * density
    private val glowRadius = 5f * density
    private val minThumbLength = 24f * density  // не даём полосе схлопнуться в точку на очень длинной ленте
    private val edgeMargin = 2f * density        // отступ от правого края — как у стокового scrollbar
    private val fadeOutDelayMs = 900L

    private val colorLight = ContextCompat.getColor(context, R.color.accent_light)
    private val colorCore = ContextCompat.getColor(context, R.color.accent)
    private val colorDark = ContextCompat.getColor(context, R.color.accent_dark)

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorCore
        maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
    }

    private var thumbTop = 0f
    private var thumbHeight = 0f
    private var thumbAlpha = 0f   // 0..1 — fade-in при скролле / fade-out при простое
    private var pulsePhase = 0f   // 0..1 — драйвится бесконечным ValueAnimator

    private var recyclerView: RecyclerView? = null
    private var pulseAnimator: ValueAnimator? = null
    private var fadeAnimator: ValueAnimator? = null
    private val hideRunnable = Runnable { fadeOut() }

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = updateThumb(rv)
    }
    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = refreshFromRv()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = refreshFromRv()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = refreshFromRv()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = refreshFromRv()
        private fun refreshFromRv() { recyclerView?.let { updateThumb(it) } }
    }

    /** Единственный вызов, нужный снаружи: привязать индикатор к RecyclerView. */
    fun attach(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnScrollListener(scrollListener)
        rv.adapter?.registerAdapterDataObserver(dataObserver)
        rv.post { updateThumb(rv) }
    }

    private fun updateThumb(rv: RecyclerView) {
        val range = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        val offset = rv.computeVerticalScrollOffset()
        if (range <= extent || range <= 0) {
            if (thumbAlpha != 0f) fadeOut()
            return
        }
        val trackHeight = height.toFloat()
        if (trackHeight <= 0f) return

        var h = trackHeight * extent / range
        if (h < minThumbLength) h = minThumbLength
        val maxTop = trackHeight - h
        var top = if (range - extent > 0) maxTop * offset / (range - extent) else 0f
        if (top < 0f) top = 0f
        if (top > maxTop) top = maxTop

        thumbTop = top
        thumbHeight = h
        showThumb()
        invalidate()
    }

    private fun showThumb() {
        removeCallbacks(hideRunnable)
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(thumbAlpha, 1f).apply {
            duration = 120
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        postDelayed(hideRunnable, fadeOutDelayMs)
    }

    private fun fadeOut() {
        fadeAnimator?.cancel()
        fadeAnimator = ValueAnimator.ofFloat(thumbAlpha, 0f).apply {
            duration = 260
            addUpdateListener { thumbAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2200
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                if (thumbAlpha > 0f) invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // BlurMaskFilter (свечение) требует программный рендер.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startPulse()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopPulse()
        removeCallbacks(hideRunnable)
        fadeAnimator?.cancel()
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView?.adapter?.unregisterAdapterDataObserver(dataObserver)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startPulse() else stopPulse()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recyclerView?.let { updateThumb(it) }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (thumbAlpha <= 0f || thumbHeight <= 0f) return

        val cx = width - edgeMargin - coreWidth / 2f
        val rect = RectF(
            cx - coreWidth / 2f, thumbTop,
            cx + coreWidth / 2f, thumbTop + thumbHeight
        )

        // «Живой пульс» — свечение дышит между мягким и ярким состоянием.
        val glowStrength = 0.45f + 0.55f * pulsePhase
        glowPaint.alpha = (255 * thumbAlpha * glowStrength).toInt().coerceIn(0, 255)
        canvas.drawOval(rect, glowPaint)

        // Резкое ядро: градиент светлый → основной → тёмный сверху вниз; сужение
        // к краям — естественный эффект drawOval на тонком высоком прямоугольнике.
        corePaint.shader = LinearGradient(
            0f, rect.top, 0f, rect.bottom,
            intArrayOf(colorLight, colorCore, colorDark),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        corePaint.alpha = (255 * thumbAlpha).toInt().coerceIn(0, 255)
        canvas.drawOval(rect, corePaint)
    }
}
