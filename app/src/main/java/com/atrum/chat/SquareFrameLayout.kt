package com.atrum.chat

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * FrameLayout, высота которого всегда равна его ширине (квадрат). Ширину задаёт
 * родитель (у ячеек сетки галереи — GridLayoutManager по колонке), а высота
 * подгоняется под неё. Так ячейки ровно тайлятся по ширине без серых зазоров и
 * без ручного вычисления размера.
 */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Высота = ширина: меряем оба измерения по width-спеке.
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
