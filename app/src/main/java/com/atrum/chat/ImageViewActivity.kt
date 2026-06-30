package com.atrum.chat

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.atrum.chat.databinding.ActivityImageViewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Полноэкранный просмотрщик изображений.
 *
 * Режим 1 — одна картинка: EXTRA_BASE64 (обратная совместимость).
 * Режим 2 — коллаж: EXTRA_REFS (ArrayList<String>) + EXTRA_START_INDEX.
 *   ViewPager2 с горизонтальным свайпом, точки-индикатор внизу.
 *
 * Zoom на каждой странице:
 *   - Pinch-to-zoom (плавный, без рывков)
 *   - Double-tap: анимированный zoom 2× / возврат к fit (ValueAnimator, 280 мс)
 */
class ImageViewActivity : SecureActivity() {

    private lateinit var binding: ActivityImageViewBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImageViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnClose.setOnClickListener { finish() }

        val refs = intent.getStringArrayListExtra(EXTRA_REFS)
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

        if (!refs.isNullOrEmpty()) {
            // ── Multi-image mode ──────────────────────────────────────────────
            binding.ivFull.visibility = View.GONE
            binding.vpImages.visibility = View.VISIBLE

            val loader = buildImageLoader()

            val adapter = ImagePagerAdapter(refs, loader) { _, _ -> }
            binding.vpImages.adapter = adapter
            binding.vpImages.setCurrentItem(startIndex, false)

            // Dots indicator
            if (refs.size > 1) {
                binding.llDots.visibility = View.VISIBLE
                setupDots(refs.size, startIndex)
                binding.vpImages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) = updateDots(position)
                })
            }
        } else {
            // ── Single-image mode ─────────────────────────────────────────────
            // pendingBase64 — статический холдер для обхода лимита Binder-транзакции (~1 МБ).
            // ChatActivity кладёт сюда base64 перед startActivity(); мы читаем и сразу сбрасываем.
            val base64 = pendingBase64?.also { pendingBase64 = null }
                ?: intent.getStringExtra(EXTRA_BASE64)
            val bitmap = ImageUtils.fromBase64(base64)
            if (bitmap == null) { finish(); return }

            binding.ivFull.visibility = View.VISIBLE
            binding.vpImages.visibility = View.GONE

            setupZoomableImage(binding.ivFull, bitmap)
        }
    }

    // ── Dots ──────────────────────────────────────────────────────────────────

    private val dotViews = mutableListOf<View>()

    private fun setupDots(count: Int, selected: Int) {
        dotViews.clear()
        binding.llDots.removeAllViews()
        val dp = resources.displayMetrics.density
        val size = (6 * dp).toInt()
        val margin = (4 * dp).toInt()
        repeat(count) { i ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.leftMargin = margin; it.rightMargin = margin
                }
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@ImageViewActivity, R.drawable.bg_dot
                )
                alpha = if (i == selected) 1f else 0.35f
            }
            binding.llDots.addView(v)
            dotViews.add(v)
        }
    }

    private fun updateDots(selected: Int) {
        dotViews.forEachIndexed { i, v -> v.alpha = if (i == selected) 1f else 0.35f }
    }

    // ── Build loader ──────────────────────────────────────────────────────────

    private fun buildImageLoader(): ImageLoader? {
        // ImageViewActivity doesn't carry a transport; images should already be
        // cached by ChatActivity before we open the viewer.  If they aren't
        // (edge case), return null — the page adapter will show the bitmap from
        // ImageCache only and skip network loading.
        return null
    }

    // ── Zoomable single ImageView setup ──────────────────────────────────────

    fun setupZoomableImage(imageView: ImageView, bitmap: Bitmap) {
        // FIT_CENTER центрирует и масштабирует картинку силами фреймворка СРАЗУ при показе —
        // без ручной матрицы, поэтому нет кадра, где картинка прижата к углу нативным размером.
        // На MATRIX переключаемся только в момент применения фит-матрицы; визуально она
        // идентична FIT_CENTER, поэтому переход бесшовный, а зум/пан дальше работают как прежде.
        imageView.scaleType = ImageView.ScaleType.FIT_CENTER
        imageView.imageMatrix = Matrix()
        imageView.setImageBitmap(bitmap)

        val state = ZoomState(bitmap.width, bitmap.height)
        attachZoomTouchListener(imageView, state)

        val applyFit = {
            state.viewW = imageView.width
            state.viewH = imageView.height
            imageView.scaleType = ImageView.ScaleType.MATRIX
            state.fitMatrix(imageView)
        }

        if (imageView.width > 0 && imageView.height > 0) {
            applyFit()
        } else {
            imageView.viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (imageView.width > 0 && imageView.height > 0) {
                            imageView.viewTreeObserver.removeOnGlobalLayoutListener(this)
                            applyFit()
                        }
                    }
                }
            )
        }
    }

    /**
     * Заполняет фон страницы тем же фото (centerCrop) с размытием по Гауссу — чтобы
     * чёрные letterbox-полосы у непропорциональных фото сменились мягким размытым фоном.
     * API 31+ — аппаратный RenderEffect; ниже — фолбэк: сильно уменьшенная копия,
     * которую centerCrop-ImageView растягивает с фильтрацией (мягкое размытие).
     */
    private fun applyBlurBackground(iv: BlurFillView, bmp: Bitmap) {
        // ВСЕГДА кормим фон уменьшенной копией: шейдер растянет её с фильтрацией → фон
        // мягкий сам по себе, без внутреннего clamp-шва, даже если RenderEffect на кадр
        // не применился (бывает при свайпе ViewPager). RenderEffect на 31+ добавляет
        // дополнительное гауссово сглаживание поверх. Масштаб/позиция fit сохраняются.
        iv.setBitmap(downscaleBlur(bmp))
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            try {
                iv.setRenderEffect(
                    android.graphics.RenderEffect.createBlurEffect(
                        40f, 40f, android.graphics.Shader.TileMode.CLAMP
                    )
                )
            } catch (_: Throwable) {
                iv.setRenderEffect(null)
            }
        }
    }

    private fun downscaleBlur(bmp: Bitmap): Bitmap {
        val w = maxOf(1, bmp.width / 10)
        val h = maxOf(1, bmp.height / 10)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    // ── Touch / zoom logic ────────────────────────────────────────────────────

    private fun attachZoomTouchListener(iv: ImageView, state: ZoomState) {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (state.viewW == 0) return true
                val cur = state.currentScale()
                var factor = detector.scaleFactor
                val proposed = cur * factor
                factor = when {
                    proposed < state.minScale -> state.minScale / cur
                    proposed > state.maxScale -> state.maxScale / cur
                    else -> factor
                }
                state.matrix.postScale(factor, factor, detector.focusX, detector.focusY)
                state.constrain()
                iv.imageMatrix = state.matrix
                return true
            }
        })

        val doubleTapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (state.viewW == 0) return true
                val cur = state.currentScale()
                if (cur > state.minScale * 1.1f) {
                    // Animate back to fit
                    state.animateTo(iv, state.fitMatrixValues())
                } else {
                    // Animate to 2× centred on tap point
                    state.animateTo(iv, state.zoomMatrixValues(e.x, e.y, state.minScale * 2f))
                }
                return true
            }
        })

        val savedMatrix = Matrix()
        val startPoint = PointF()
        var mode = NONE

        iv.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            doubleTapDetector.onTouchEvent(event)
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN -> {
                    savedMatrix.set(state.matrix)
                    startPoint.set(event.x, event.y)
                    mode = DRAG
                }
                MotionEvent.ACTION_POINTER_DOWN -> mode = ZOOM
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> mode = NONE
                MotionEvent.ACTION_MOVE -> {
                    if (mode == DRAG && !scaleDetector.isInProgress) {
                        state.matrix.set(savedMatrix)
                        state.matrix.postTranslate(event.x - startPoint.x, event.y - startPoint.y)
                        state.constrain()
                        iv.imageMatrix = state.matrix
                    }
                }
            }
            true
        }
    }

    // ── ZoomState ─────────────────────────────────────────────────────────────

    inner class ZoomState(val bitmapW: Int, val bitmapH: Int) {
        val matrix = Matrix()
        private val tmp = FloatArray(9)
        var viewW = 0; var viewH = 0
        var minScale = 1f; var maxScale = 5f
        private var zoomAnimator: ValueAnimator? = null

        fun currentScale(): Float {
            matrix.getValues(tmp); return tmp[Matrix.MSCALE_X]
        }

        fun fitMatrix(iv: ImageView?) {
            if (viewW == 0 || viewH == 0 || bitmapW == 0 || bitmapH == 0) return
            val s = minOf(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
            minScale = s; maxScale = s * 5f
            matrix.reset()
            matrix.postScale(s, s)
            matrix.postTranslate((viewW - bitmapW * s) / 2f, (viewH - bitmapH * s) / 2f)
            iv?.imageMatrix = matrix
        }

        fun fitMatrixValues(): FloatArray {
            val s = minOf(viewW.toFloat() / bitmapW, viewH.toFloat() / bitmapH)
            val m = Matrix()
            m.postScale(s, s)
            m.postTranslate((viewW - bitmapW * s) / 2f, (viewH - bitmapH * s) / 2f)
            return FloatArray(9).also { m.getValues(it) }
        }

        fun zoomMatrixValues(pivotX: Float, pivotY: Float, targetScale: Float): FloatArray {
            val cur = currentScale()
            val factor = targetScale / cur
            val m = Matrix(matrix)
            m.postScale(factor, factor, pivotX, pivotY)
            val v = FloatArray(9).also { m.getValues(it) }
            // constrain translation in the zoomed state
            val s = v[Matrix.MSCALE_X]
            val scaledW = bitmapW * s; val scaledH = bitmapH * s
            v[Matrix.MTRANS_X] = if (scaledW <= viewW) (viewW - scaledW) / 2f
                                  else v[Matrix.MTRANS_X].coerceIn(viewW - scaledW, 0f)
            v[Matrix.MTRANS_Y] = if (scaledH <= viewH) (viewH - scaledH) / 2f
                                  else v[Matrix.MTRANS_Y].coerceIn(viewH - scaledH, 0f)
            return v
        }

        fun constrain() {
            matrix.getValues(tmp)
            val s = tmp[Matrix.MSCALE_X]
            val scaledW = bitmapW * s; val scaledH = bitmapH * s
            tmp[Matrix.MTRANS_X] = if (scaledW <= viewW) (viewW - scaledW) / 2f
                                    else tmp[Matrix.MTRANS_X].coerceIn(viewW - scaledW, 0f)
            tmp[Matrix.MTRANS_Y] = if (scaledH <= viewH) (viewH - scaledH) / 2f
                                    else tmp[Matrix.MTRANS_Y].coerceIn(viewH - scaledH, 0f)
            matrix.setValues(tmp)
        }

        /** Smoothly animates matrix from current values to [target] over 280 ms. */
        fun animateTo(iv: ImageView, target: FloatArray) {
            zoomAnimator?.cancel()
            val from = FloatArray(9).also { matrix.getValues(it) }
            zoomAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { anim ->
                    val t = anim.animatedFraction
                    val interp = FloatArray(9) { i -> from[i] + (target[i] - from[i]) * t }
                    matrix.setValues(interp)
                    iv.imageMatrix = matrix
                }
                start()
            }
        }
    }

    // ── ViewPager2 adapter ────────────────────────────────────────────────────

    inner class ImagePagerAdapter(
        private val refs: List<String>,
        private val loader: ImageLoader?,
        @Suppress("unused") private val onReady: (Int, Bitmap) -> Unit
    ) : RecyclerView.Adapter<ImagePagerAdapter.PageVH>() {

        inner class PageVH(view: View) : RecyclerView.ViewHolder(view) {
            val iv: ImageView = view.findViewById(R.id.iv_page)
            val ivBlur: BlurFillView = view.findViewById(R.id.iv_page_blur)
            val pb: ProgressBar = view.findViewById(R.id.pb_page)
            var state: ZoomState? = null

            fun bind(ref: String) {
                // Reset zoom
                iv.imageMatrix = Matrix()
                state = null
                pb.visibility = View.VISIBLE
                iv.setImageDrawable(null)
                ivBlur.setBitmap(null)

                // 1. Bitmap в LruCache — показываем мгновенно
                val cached = ImageCache.getBitmap(ref)
                if (cached != null) {
                    pb.visibility = View.GONE
                    onBitmap(cached)
                    return
                }

                // 2. Bitmap вытеснен из LruCache, но base64 остался — декодируем в фоне.
                //    Это дешевле нового сетевого запроса и не требует loader.
                val cachedBase64 = ImageCache.getBase64(ref)
                if (cachedBase64 != null) {
                    lifecycleScope.launch {
                        val bitmap = withContext(Dispatchers.Default) {
                            ImageUtils.fromBase64(cachedBase64)
                        }
                        pb.visibility = View.GONE
                        if (bitmap != null) {
                            ImageCache.put(ref, cachedBase64, bitmap)
                            onBitmap(bitmap)
                        }
                    }
                    return
                }

                // 3. Данных нет совсем — грузим через loader (если доступен)
                val l = loader ?: run { pb.visibility = View.GONE; return }
                lifecycleScope.launch {
                    val bitmap = withContext(Dispatchers.IO) { l.loadBitmap(ref) }
                    pb.visibility = View.GONE
                    if (bitmap != null) onBitmap(bitmap)
                }
            }

            private fun onBitmap(bitmap: Bitmap) {
                val s = ZoomState(bitmap.width, bitmap.height)
                state = s
                applyBlurBackground(ivBlur, bitmap)
                setupZoomableImage(iv, bitmap)
            }
        }

        override fun getItemCount() = refs.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageVH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_image_page, parent, false)
            return PageVH(v)
        }
        override fun onBindViewHolder(holder: PageVH, position: Int) {
            holder.bind(refs[position])
        }
    }

    companion object {
        const val EXTRA_BASE64 = "image_base64"
        /** ArrayList<String> of image refs for collage swiper mode */
        const val EXTRA_REFS = "image_refs"
        /** Index of the image that was tapped (default 0) */
        const val EXTRA_START_INDEX = "start_index"

        /**
         * Статический холдер для inline base64 (старый формат).
         * Android ограничивает Binder-транзакцию ~1 МБ, поэтому передавать
         * многомегабайтный base64 через Intent.putExtra() → TransactionTooLargeException.
         * ChatActivity кладёт сюда строку прямо перед startActivity();
         * onCreate() читает и сразу сбрасывает в null.
         */
        @Volatile var pendingBase64: String? = null

        private const val NONE = 0
        private const val DRAG = 1
        private const val ZOOM = 2
    }
}
