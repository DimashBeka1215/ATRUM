package com.atrum.chat

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

/**
 * Advanced Apple-style "Liquid Glass" Backdrop Blur View.
 * Architecture:
 * 1. Live backdrop capture from target view.
 * 2. RenderEffect-based blur applied ONLY to background layer (API 31+).
 * 3. Squircle geometry (Super-ellipse approximation).
 * 4. Adaptive sizing based on content (Pill-style).
 */
class BackdropBlurView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var targetView: View? = null
    private var blurRadius = 45f 
    
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    
    private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.0f
        color = Color.parseColor("#1AFFFFFF") // Even softer glass border
    }

    private val squirclePath = Path()
    private val tempRect = RectF()
    private val tempLoc = IntArray(2)
    private val tempTargetLoc = IntArray(2)

    private var isDrawingBackdrop = false
    private var noiseShader: Any? = null
    private var isDarkBackdrop = false

    /** true = backdrop capture active (glass mode). false = plain solid tint (default). */
    private var glassMode = false

    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        if (visibility == VISIBLE && isAttachedToWindow) {
            invalidate()
        }
        true
    }

    init {
        setWillNotDraw(false)
        background = null
        // RenderEffect применяется адаптивно через setLuminanceAdaptation()
        // Blur через saveLayer удалён — он давал прямоугольный артефакт по краям пилюли.
        // Визуальный эффект "стекла" реализован через backdrop-захват + тонирующий слой.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.height / 2f
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setRenderEffectCompat(paint: Paint, effect: Any?) {
        try {
            val method = Paint::class.java.getMethod("setRenderEffect", RenderEffect::class.java)
            method.invoke(paint, effect)
        } catch (e: Exception) {
            // Fallback or log
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun setupRenderEffect() {
        val blur = RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP)
        
        // Adaptive brightness & contrast boost based on backdrop luminance
        val brightness = if (isDarkBackdrop) 10f else 5f
        val contrast = if (isDarkBackdrop) 1.2f else 1.1f
        val blueBoost = if (isDarkBackdrop) 15f else 10f

        val colorFilter = ColorMatrixColorFilter(ColorMatrix().apply {
            set(floatArrayOf(
                contrast, 0f, 0f, 0f, brightness,
                0f, contrast, 0f, 0f, brightness,
                0f, 0f, contrast + 0.1f, 0f, blueBoost,
                0f, 0f, 0f, 1f, 0f
            ))
        })
        val filterEffect = RenderEffect.createColorFilterEffect(colorFilter)
        
        // Chain with Noise if API 33+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val shaderCode = """
                uniform shader content;
                uniform float2 resolution;
                float noise(float2 p) {
                    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
                }
                half4 main(float2 fragCoord) {
                    half4 color = content.eval(fragCoord);
                    float n = (noise(fragCoord) - 0.5) * 0.05;
                    return color + n;
                }
            """.trimIndent()
            try {
                val shader = RuntimeShader(shaderCode)
                noiseShader = shader
                val runtimeEffect = RenderEffect.createRuntimeShaderEffect(shader, "content")
                val combined = RenderEffect.createChainEffect(runtimeEffect, RenderEffect.createChainEffect(filterEffect, blur))
                setRenderEffectCompat(blurPaint, combined)
            } catch (e: Exception) {
                setRenderEffectCompat(blurPaint, RenderEffect.createChainEffect(filterEffect, blur))
            }
        } else {
            setRenderEffectCompat(blurPaint, RenderEffect.createChainEffect(filterEffect, blur))
        }
    }

    fun setLuminanceAdaptation(isDark: Boolean) {
        if (isDarkBackdrop != isDark) {
            isDarkBackdrop = isDark
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setupRenderEffect()
                invalidate()
            }
        }
    }

    /**
     * Enable or disable backdrop blur (glass mode).
     * When disabled, the view draws a simple semi-transparent pill — no GPU offscreen layers.
     * Default: false (disabled) to avoid GPU driver crashes on devices without proper AGSL support.
     */
    fun setGlassMode(enabled: Boolean) {
        if (glassMode == enabled) return
        glassMode = enabled
        if (!enabled) {
            // Clear RenderEffect to release GPU resources
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try { setRenderEffectCompat(blurPaint, null) } catch (_: Throwable) {}
            }
        }
        invalidate()
    }

    fun setTarget(view: View?) {
        targetView?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
        targetView = view
        targetView?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (noiseShader as? RuntimeShader)?.setFloatUniform("resolution", w.toFloat(), h.toFloat())
        }
        invalidateOutline()
    }

    private fun updateSquirclePath(w: Int, h: Int) {
        squirclePath.reset()
        tempRect.set(0f, 0f, w.toFloat(), h.toFloat())
        val r = h / 2f // Real pill shape
        squirclePath.addRoundRect(tempRect, r, r, Path.Direction.CW)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Backdrop capture is heavy GPU work — only run in glass mode.
        // In normal mode: draw solid tint + children, no offscreen layers.
        if (!glassMode) {
            val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#CC0A0A0A")
            }
            canvas.drawPath(squirclePath, tint)
            drawGlassEffects(canvas)
            super.dispatchDraw(canvas)
            return
        }

        if (isDrawingBackdrop) return

        val target = targetView
        // If no target set, fall back to non-glass solid tint so children are always visible
        if (target == null) {
            val tint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.parseColor("#CC0A0A0A")
            }
            canvas.drawPath(squirclePath, tint)
            drawGlassEffects(canvas)
            super.dispatchDraw(canvas)
            return
        }
        if (target != null && visibility == VISIBLE && width > 0 && height > 0) {
            val saveCount = canvas.save()

            // 1. Clip строго по форме пилюли — применяем до всех операций
            canvas.clipPath(squirclePath)

            // 2. Backdrop: захватываем содержимое целевого view без blur-слоя.
            //    На API 31+ blur применяется через setRenderEffect() на самом View
            //    (см. setupRenderEffect / init), поэтому здесь просто рисуем backdrop.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // saveLayer без RenderEffect — просто изолируем слой для правильного
                // Porter-Duff compositing внутри clip-области пилюли
                val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
                canvas.saveLayer(tempRect, layerPaint)
            } else {
                // Fallback: непрозрачный тёмный фон (API < 31 без blur)
                canvas.drawColor(Color.parseColor("#E6121212"))
            }

            // 3. Рисуем backdrop из target view
            getLocationInWindow(tempLoc)
            target.getLocationInWindow(tempTargetLoc)
            canvas.translate((tempTargetLoc[0] - tempLoc[0]).toFloat(), (tempTargetLoc[1] - tempLoc[1]).toFloat())

            try {
                isDrawingBackdrop = true
                target.draw(canvas)
                isDrawingBackdrop = false
            } catch (_: Exception) {
                isDrawingBackdrop = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                canvas.restore()
            }

            // 4. Полупрозрачный тёмный тонирующий слой поверх backdrop
            //    (обеспечивает читаемость текста в обоих темах и glass mode)
            val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = if (isDarkBackdrop) Color.parseColor("#33000000")
                        else Color.parseColor("#55000000")
            }
            canvas.drawPath(squirclePath, tintPaint)

            // 5. Subtle glass highlight
            drawGlassEffects(canvas)

            // 6. Draw children (текст, иконки)
            super.dispatchDraw(canvas)

            canvas.restoreToCount(saveCount)
        }
    }

    private fun drawGlassEffects(canvas: Canvas) {
        // Very subtle top light gradient
        val specular = LinearGradient(0f, 0f, 0f, height * 0.45f,
            intArrayOf(Color.parseColor("#12FFFFFF"), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP)
        glassPaint.shader = specular
        canvas.drawPath(squirclePath, glassPaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        targetView?.viewTreeObserver?.addOnPreDrawListener(preDrawListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        targetView?.viewTreeObserver?.removeOnPreDrawListener(preDrawListener)
    }
}

