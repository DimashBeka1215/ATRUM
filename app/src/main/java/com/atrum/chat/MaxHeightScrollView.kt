package com.atrum.chat

import android.content.Context
import android.util.AttributeSet
import android.widget.ScrollView

/**
 * ScrollView с ограничением высоты: пока контент ниже [maxHeightPx] — тянется по контенту
 * (wrap), выше — упирается в потолок и прокручивается внутри. Нужен для выпадающего списка
 * альбомов галереи, чтобы он не занимал слишком большую область при многих альбомах.
 */
class MaxHeightScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ScrollView(context, attrs, defStyleAttr) {

    var maxHeightPx: Int = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val hs = if (maxHeightPx > 0)
            MeasureSpec.makeMeasureSpec(maxHeightPx, MeasureSpec.AT_MOST)
        else heightMeasureSpec
        super.onMeasure(widthMeasureSpec, hs)
    }
}
