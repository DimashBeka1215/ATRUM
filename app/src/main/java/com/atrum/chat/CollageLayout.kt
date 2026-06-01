package com.atrum.chat

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Telegram-style image collage ViewGroup.
 *
 * Дочерние View добавляются динамически из MessageAdapter.
 * Аспект-ратио задаётся через [aspectRatios] до addView.
 *
 * Алгоритм раскладки:
 *   - N изображений разбиваются на строки (computeRows)
 *   - Высота строки: rowHeight = (ширина - gaps) / сумма_AR_в_строке
 *     с ограничением [MIN_ROW_HEIGHT_DP, MAX_ROW_HEIGHT_DP]
 *   - Последний child в строке растягивается до правого края (компенсируем
 *     накопленную погрешность округления)
 */
class CollageLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    companion object {
        private const val GAP_DP         = 2f
        private const val MIN_ROW_HEIGHT_DP = 72f
        private const val MAX_ROW_HEIGHT_DP = 300f
    }

    private val gap          = dp(GAP_DP)
    private val minRowHeight = dp(MIN_ROW_HEIGHT_DP)
    private val maxRowHeight = dp(MAX_ROW_HEIGHT_DP)

    /** Соотношения сторон (ширина/высота) для каждого дочернего View. */
    var aspectRatios: List<Float> = emptyList()
        set(value) { field = value; requestLayout() }

    /** Строки индексов — вычисляется в onMeasure, используется в onLayout. */
    private var rows: List<List<Int>> = emptyList()

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val n = minOf(childCount, aspectRatios.size)

        if (n == 0) {
            setMeasuredDimension(w, 0)
            return
        }

        rows = computeRows(n)

        var totalH = 0
        for (rowIdx in rows.indices) {
            val row = rows[rowIdx]
            val rowH = rowHeight(row, w)
            // Замеряем каждый child
            for (colIdx in row.indices) {
                val idx = row[colIdx]
                val child = getChildAt(idx) ?: continue
                val ar = safeAr(idx)
                val cw = if (colIdx == row.lastIndex) {
                    // Последний в строке — остаток ширины
                    w - columnLeft(row, colIdx, rowH)
                } else {
                    (ar * rowH).toInt()
                }
                child.measure(
                    MeasureSpec.makeMeasureSpec(cw.coerceAtLeast(1), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(rowH,                 MeasureSpec.EXACTLY)
                )
            }
            totalH += rowH
            if (rowIdx < rows.lastIndex) totalH += gap
        }

        setMeasuredDimension(w, totalH)
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        if (childCount == 0 || rows.isEmpty()) return

        var top = 0
        for (rowIdx in rows.indices) {
            val row  = rows[rowIdx]
            val rowH = rowHeight(row, w)

            for (colIdx in row.indices) {
                val idx   = row[colIdx]
                val child = getChildAt(idx) ?: continue
                val left  = columnLeft(row, colIdx, rowH)
                val right = if (colIdx == row.lastIndex) w else left + (safeAr(idx) * rowH).toInt()
                child.layout(left, top, right, top + rowH)
            }
            top += rowH
            if (rowIdx < rows.lastIndex) top += gap
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Высота строки: (доступная ширина) / (сумма AR) с min/max зажимом. */
    private fun rowHeight(row: List<Int>, availW: Int): Int {
        if (row.isEmpty()) return 0
        val totalAr  = row.sumOf { safeAr(it).toDouble() }.toFloat()
        val usableW  = availW - gap * (row.size - 1)
        return (usableW / totalAr).toInt().coerceIn(minRowHeight, maxRowHeight)
    }

    /** X-координата левого края child с индексом colIdx внутри строки. */
    private fun columnLeft(row: List<Int>, colIdx: Int, rowH: Int): Int {
        var x = 0
        for (i in 0 until colIdx) {
            x += (safeAr(row[i]) * rowH).toInt() + gap
        }
        return x
    }

    private fun safeAr(idx: Int): Float =
        aspectRatios.getOrElse(idx) { 1f }.coerceAtLeast(0.1f)

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    // ── Row grouping ──────────────────────────────────────────────────────────

    /**
     * Разбивает N изображений на строки.
     * Алгоритм аналогичен Telegram:
     *   1 → [1]; 2 → [2]; 3 → [1,2] или [2,1]; 4 → [2,2]; ...
     */
    private fun computeRows(n: Int): List<List<Int>> = when (n) {
        1 -> listOf(listOf(0))
        2 -> listOf(listOf(0, 1))
        3 -> {
            // Если первое изображение пейзажное — оно одно на первой строке
            if (safeAr(0) >= 1.2f) listOf(listOf(0), listOf(1, 2))
            else                    listOf(listOf(0, 1), listOf(2))
        }
        4 -> listOf(listOf(0, 1), listOf(2, 3))
        5 -> listOf(listOf(0, 1), listOf(2, 3, 4))
        6 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5))
        7 -> listOf(listOf(0, 1, 2), listOf(3, 4), listOf(5, 6))
        8 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7))
        9 -> listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7, 8))
        else -> buildRows(n)
    }

    /** Для N > 9: жадно набираем строки по 3. */
    private fun buildRows(n: Int): List<List<Int>> {
        val result = mutableListOf<List<Int>>()
        var i = 0
        while (i < n) {
            val remaining = n - i
            val size = when {
                remaining <= 3  -> remaining
                remaining == 4  -> 2      // лучше 2+2, чем 3+1
                else            -> 3
            }
            result.add((i until i + size).toList())
            i += size
        }
        return result
    }
}
