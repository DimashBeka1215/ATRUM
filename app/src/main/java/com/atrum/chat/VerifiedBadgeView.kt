package com.atrum.chat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat

/**
 * Значок верификации рядом с ником — вариант 5 «Заливка + блик» (выбран пользователем).
 * Акцентный круг + белая галочка; при появлении один раз проигрывается pop (лёгкий
 * overshoot) и бегущий блик по кругу. Цвет — из токена @color/accent (тёмная/светлая/glass).
 *
 * Использование: положить в layout рядом с ником (обычно 16–18dp) и вызвать
 * [setVerified] (true → показать, animate=true при ПЕРВОМ появлении). Кто верифицирован —
 * решает [VerifiedBadge] (неподделываемо, по подписи identity-ключа).
 */
class VerifiedBadgeView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val sweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF }

    private val checkPath = Path()
    private val clipPath = Path()

    /** <0 — блик не рисуется; 0..1 — позиция бегущего блика. */
    private var sweepFrac = -1f
    private var introAnim: ValueAnimator? = null

    init {
        // Тап по значку → окно «Разработчик ATRUM». В диалоге-герое клик отключается
        // (VerifiedInfoDialog), чтобы не открывать окно повторно.
        isClickable = true
        isFocusable = true
        setOnClickListener { VerifiedInfoDialog.show(context) }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val s = minOf(w, h).toFloat()
        checkPaint.strokeWidth = s * 0.12f
        // Галочка в долях размера (как в мокапе, вариант 5).
        checkPath.reset()
        checkPath.moveTo(s * 0.27f, s * 0.52f)
        checkPath.lineTo(s * 0.43f, s * 0.70f)
        checkPath.lineTo(s * 0.75f, s * 0.34f)
    }

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val r = s / 2f
        canvas.drawCircle(r, r, r, circlePaint)

        if (sweepFrac in 0f..1f) {
            canvas.save()
            clipPath.reset()
            clipPath.addCircle(r, r, r, Path.Direction.CW)
            canvas.clipPath(clipPath)
            val bw = s * 0.5f
            val x = -bw + sweepFrac * (s + bw * 2f)
            canvas.drawRect(x, 0f, x + bw, s, sweepPaint)
            canvas.restore()
        }

        canvas.drawPath(checkPath, checkPaint)
    }

    /** Показать/скрыть значок. [animate] = true — проиграть pop+блик один раз (первый показ). */
    fun setVerified(verified: Boolean, animate: Boolean) {
        if (!verified) {
            introAnim?.cancel()
            visibility = GONE
            return
        }
        val was = visibility == VISIBLE
        visibility = VISIBLE
        if (animate && !was) playIntro() else {
            scaleX = 1f; scaleY = 1f; alpha = 1f; sweepFrac = -1f; invalidate()
        }
    }

    private fun playIntro() {
        scaleX = 0.3f; scaleY = 0.3f; alpha = 0f
        animate().scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(260).setInterpolator(OvershootInterpolator(2f)).start()

        introAnim?.cancel()
        introAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 170
            duration = 520
            addUpdateListener { sweepFrac = it.animatedValue as Float; invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { sweepFrac = -1f; invalidate() }
            })
            start()
        }
    }
}
