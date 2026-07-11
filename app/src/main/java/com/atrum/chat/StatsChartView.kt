package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Компактный кастомный график для раздела «Беседа» статистики (мокап одобрен). Один класс,
 * два режима:
 *  • [MODE_LINE]  — линия «участники со временем» с фиолетовой area-заливкой;
 *  • [MODE_BARS]  — сгруппированные столбики «пришло/ушло» по бакетам.
 * Кликабелен: тап по бакету → [onBucketClick]. Активный бакет подсвечивается.
 * Цвета — только токены палитры (§4), никакого hardcode кроме прозрачностей поверх accent.
 */
class StatsChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bucket(val label: String, val members: Int, val joins: Int, val leaves: Int)

    companion object { const val MODE_LINE = 0; const val MODE_BARS = 1 }

    var mode: Int = MODE_LINE
    var onBucketClick: ((Int) -> Unit)? = null

    private var buckets: List<Bucket> = emptyList()
    private var selected: Int = -1

    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val accentLight = ContextCompat.getColor(context, R.color.accent_light)
    private val deep = ContextCompat.getColor(context, R.color.msg_self)
    private val grid = ContextCompat.getColor(context, R.color.border)

    private val dp = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * dp; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = accentLight }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = grid }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    fun setData(data: List<Bucket>, mode: Int) {
        this.buckets = data
        this.mode = mode
        selected = -1
        invalidate()
    }

    fun setSelected(index: Int) { selected = index; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = buckets.size
        if (n == 0) return
        val padB = 4f * dp
        val h = height - padB
        val w = width.toFloat()

        // Сетка (3 линии).
        for (k in 0..2) { val y = h * k / 2f + 2f * dp; canvas.drawLine(0f, y, w, y, gridP) }

        if (mode == MODE_LINE) {
            val maxV = (buckets.maxOf { it.members }).coerceAtLeast(1)
            fun x(i: Int) = if (n == 1) w / 2f else 6f * dp + i * (w - 12f * dp) / (n - 1)
            fun y(v: Int) = (h - 4f * dp) - (v.toFloat() / maxV) * (h - 12f * dp)
            val path = Path(); val area = Path()
            buckets.forEachIndexed { i, b -> val px = x(i); val py = y(b.members); if (i == 0) { path.moveTo(px, py); area.moveTo(px, h) } ; path.lineTo(px, py); area.lineTo(px, py) }
            area.lineTo(x(n - 1), h); area.close()
            val top = (accent and 0x00FFFFFF) or 0x66000000  // accent @ ~40%
            val bot = accent and 0x00FFFFFF                    // accent @ 0%
            fill.shader = android.graphics.LinearGradient(0f, 0f, 0f, h, top, bot, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawPath(area, fill)
            fill.shader = null
            canvas.drawPath(path, line)
            buckets.forEachIndexed { i, b -> dot.color = if (i == selected) accentLight else ContextCompat.getColor(context, R.color.bg); canvas.drawCircle(x(i), y(b.members), 4.5f * dp, dot); dot.color = accentLight; dot.style = Paint.Style.STROKE; dot.strokeWidth = 2f * dp; canvas.drawCircle(x(i), y(b.members), 4.5f * dp, dot); dot.style = Paint.Style.FILL }
        } else {
            val maxV = buckets.maxOf { maxOf(it.joins, it.leaves) }.coerceAtLeast(1)
            val slot = w / n
            val bw = (slot * 0.28f).coerceAtMost(9f * dp)
            buckets.forEachIndexed { i, b ->
                val cx = slot * i + slot / 2f
                val jh = b.joins.toFloat() / maxV * (h - 6f * dp)
                val lh = b.leaves.toFloat() / maxV * (h - 6f * dp)
                bar.color = accentLight; bar.alpha = if (selected == -1 || selected == i) 255 else 90
                canvas.drawRoundRect(cx - bw - 1f * dp, h - jh.coerceAtLeast(1.5f * dp), cx - 1f * dp, h, 2f * dp, 2f * dp, bar)
                bar.color = deep; bar.alpha = if (selected == -1 || selected == i) 255 else 90
                canvas.drawRoundRect(cx + 1f * dp, h - lh.coerceAtLeast(1.5f * dp), cx + bw + 1f * dp, h, 2f * dp, 2f * dp, bar)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            val n = buckets.size
            if (n > 0) {
                val idx = (event.x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                selected = idx
                invalidate()
                onBucketClick?.invoke(idx)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}
