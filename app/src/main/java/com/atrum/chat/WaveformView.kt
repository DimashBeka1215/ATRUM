package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Дорожка громкости голосового («спектрограмма» по амплитуде — видно громкие пики).
 *
 * Режимы:
 *  • статичный — [setSamples] рисует огибающую записанного голосового; [setProgress]
 *    подкрашивает «прослушанную» часть (для индикатора воспроизведения);
 *  • живой     — [pushLevel] добавляет столбики в реальном времени при записи.
 */
class WaveformView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val barW = 2.5f * density
    private val gap = 2f * density
    private val radius = 1.5f * density

    private var samples: IntArray = IntArray(0)   // 0..100
    private var progress: Float = 0f              // 0..1
    private val live = ArrayList<Int>()
    private var liveMode = false

    private var playedColor = 0xFF9D4EDD.toInt()
    private var unplayedColor = 0xFF8E8E96.toInt()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setColors(played: Int, unplayed: Int) {
        playedColor = played; unplayedColor = unplayed; invalidate()
    }

    /** Статичная огибающая (значения 0..100). */
    fun setSamples(arr: IntArray) {
        liveMode = false
        samples = arr
        invalidate()
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        invalidate()
    }

    /** Живой режим: добавить уровень (0..100), старые столбики уезжают влево. */
    fun pushLevel(level: Int) {
        liveMode = true
        val cap = maxBarsForWidth()
        live.add(level.coerceIn(0, 100))
        while (live.size > cap) live.removeAt(0)
        invalidate()
    }

    fun reset() {
        live.clear(); samples = IntArray(0); progress = 0f; liveMode = false; invalidate()
    }

    private fun maxBarsForWidth(): Int {
        val w = width.takeIf { it > 0 } ?: return 48
        return max(1, (w / (barW + gap)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val data = if (liveMode) live.toIntArray() else samples
        if (data.isEmpty()) return
        val h = height.toFloat()
        val midY = h / 2f
        val maxH = h - 2f * density
        val minH = 2f * density
        val step = barW + gap
        val totalW = width.toFloat()
        // Живой режим — прижимаем вправо; статичный — заполняем по всей ширине.
        val count = data.size
        val playedCount = (progress * count).toInt()

        for (i in 0 until count) {
            val x = if (liveMode) totalW - (count - i) * step
                    else i * (totalW / count) + (totalW / count - barW) / 2f
            if (x < -barW) continue
            val lvl = data[i] / 100f
            val barH = max(minH, lvl * maxH)
            rect.set(x, midY - barH / 2f, x + barW, midY + barH / 2f)
            paint.color = if (!liveMode && i < playedCount) playedColor else if (liveMode) playedColor else unplayedColor
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }
}
