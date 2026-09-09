package com.atrum.chat

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * Панель ответа (reply preview) с фоном-стеклом.
 *
 * ── ПОЧЕМУ ПЕРЕПИСАНО (баг, найден при работе над §16 CLAUDE.md) ───────────────
 * Раньше здесь был самодельный "backdrop": ручной canvas.translate() + повторная
 * отрисовка target.draw(canvas), а блюр пытались повесить через
 * `Paint::class.java.getMethod("setRenderEffect", ...)`. Такого метода у Paint
 * НЕТ в Android SDK (RenderEffect вешается на View/RenderNode, не на Paint) —
 * reflection всегда падал в catch, и размытия не было НИКОГДА, ни на одном
 * устройстве. Показывался лишь полупрозрачный тёмный "зеркальный" слой без
 * блюра. Ровно такой же баг раньше был у шапки/пилюли ввода (см. комментарий
 * в ChatActivity.applyGlassStyle) и был там пофикшен переводом на
 * AtmosphericGlassBackdrop — эта панель тогда осталась не переведена.
 *
 * ── ЧТО ТЕПЕРЬ ───────────────────────────────────────────────────────────────
 * Glass-режим полностью делегирован в [AtmosphericGlassSurface] — тот же
 * движок, что рисует капсулу шапки и пилюлю ввода (см. AtmosphericGlass.kt).
 * Он сам на каждой отрисовке пересчитывает своё положение относительно общего
 * задника (AtmosphericGlassBackdrop) через getLocationInWindow(), поэтому
 * область блюра всегда привязана к фактическим bounds этой view — двигается,
 * меняет размер и появляется/исчезает вместе с ней, без ручного трекинга.
 *
 * Classic-режим (без обоев/без glass-стиля) не тронут: та же плоская тонированная
 * пилюля, что и раньше.
 */
class BackdropBlurView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val flatTintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC0A0A0A")
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val squirclePath = Path()
    private val tempRect = RectF()

    /** true = стеклянная поверхность (AtmosphericGlassSurface). false = плоская тонированная пилюля. */
    private var glassMode = false

    /** Живёт только пока glassMode=true — тот же движок, что у шапки/пилюли ввода. */
    private var glassSurface: AtmosphericGlassSurface? = null

    init {
        setWillNotDraw(false)
        background = null
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.height / 2f
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    /**
     * Включает/выключает стеклянный материал панели.
     * true  — реальный backdrop-blur + преломление кромки через AtmosphericGlassSurface.
     * false — плоская тонированная пилюля (без обоев/классический стиль).
     */
    fun setGlassMode(enabled: Boolean) {
        if (glassMode == enabled) return
        glassMode = enabled
        if (enabled) {
            val gs = AtmosphericGlassSurface(context, AtmosphericGlassSurface.Style.SURFACE, null)
            gs.host = this
            gs.motionEnabled = !AtmosphericGlass.isMotionReduced(context)
            if (width > 0 && height > 0) gs.setBounds(0, 0, width, height)
            glassSurface = gs
        } else {
            glassSurface?.release()
            glassSurface = null
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val screenW = resources.displayMetrics.widthPixels
        val maxWidth = (screenW * 0.9f).toInt()

        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)

        val newWidthSpec = when (widthMode) {
            MeasureSpec.AT_MOST -> MeasureSpec.makeMeasureSpec(Math.min(widthSize, maxWidth), MeasureSpec.AT_MOST)
            MeasureSpec.EXACTLY -> MeasureSpec.makeMeasureSpec(Math.min(widthSize, maxWidth), MeasureSpec.EXACTLY)
            else -> MeasureSpec.makeMeasureSpec(maxWidth, MeasureSpec.AT_MOST)
        }
        super.onMeasure(newWidthSpec, heightMeasureSpec)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSquirclePath(w, h)
        // Bounds стеклянной поверхности живут отдельно от Drawable-хоста (она не назначена
        // как background/foreground этой view, поэтому фреймворк не выставляет их сам) —
        // держим их синхронными с реальным размером панели вручную, при каждом изменении.
        if (w > 0 && h > 0) glassSurface?.setBounds(0, 0, w, h)
        invalidateOutline()
    }

    private fun updateSquirclePath(w: Int, h: Int) {
        squirclePath.reset()
        tempRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val r = h / 2f // Real pill shape
        squirclePath.addRoundRect(tempRect, r, r, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val gs = glassSurface
        if (glassMode && gs != null && width > 0 && height > 0) {
            val save = canvas.save()
            canvas.clipPath(squirclePath)
            gs.draw(canvas) // преломление/блюр общего задника + свет по кромке — клип уже внутри
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
            return
        }

        // Classic/плоский режим — как и раньше, без изменений.
        val save = canvas.save()
        canvas.drawPath(squirclePath, flatTintPaint)
        drawFlatSpecular(canvas)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    private fun drawFlatSpecular(canvas: Canvas) {
        val specular = LinearGradient(0f, 0f, 0f, height * 0.45f,
            intArrayOf(Color.parseColor("#12FFFFFF"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        specularPaint.shader = specular
        canvas.drawPath(squirclePath, specularPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glassSurface?.release()
        glassSurface = null
    }
}
