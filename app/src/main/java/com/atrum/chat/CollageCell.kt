package com.atrum.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.OvershootInterpolator
import android.graphics.Typeface
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel

/**
 * Ячейка коллажа с индикатором загрузки.
 *
 * Состояния:
 *   Loading         — серый фон + белый вращающийся спиннер по центру
 *   Loaded (fresh)  — изображение проявляется (fade-in 200 мс), затем
 *                     зелёный кружок с галочкой pop-in на 300 мс → пауза 600 мс → fade-out
 *   Loaded (cached) — изображение ставится мгновенно, без анимации
 *   Error           — спиннер скрывается, ячейка остаётся серой
 *
 * Используется как дочерний View в [CollageLayout].
 */
class CollageCell @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    /** Само изображение. tag = ref строка (для защиты от RecyclerView recycling). */
    val imageView: ShapeableImageView

    /** Крутящийся индикатор загрузки. */
    private val progressBar: ProgressBar

    /** Зелёный круг с галочкой — анимация подтверждения загрузки. */
    private val doneCircle: FrameLayout

    /** Слой прогресса заливки ПОВЕРХ фото ячейки (тёмная плёнка + кольцо + проценты). */
    private val uploadOverlay: FrameLayout
    private val uploadRing: ProgressBar
    private val uploadText: TextView

    init {
        val dp = context.resources.displayMetrics.density

        // ── Изображение ────────────────────────────────────────────────────────
        imageView = ShapeableImageView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0f      // невидимо до завершения загрузки
        }
        addView(imageView)

        // ── Спиннер ────────────────────────────────────────────────────────────
        val spinnerSize = (36 * dp).toInt()
        progressBar = ProgressBar(context).apply {
            layoutParams = LayoutParams(spinnerSize, spinnerSize).also { lp ->
                lp.gravity = Gravity.CENTER
            }
            isIndeterminate = true
            // Белый полупрозрачный (хорошо виден на любом цвете фона)
            indeterminateTintList =
                android.content.res.ColorStateList.valueOf(0xDDFFFFFF.toInt())
        }
        addView(progressBar)

        // ── Done-кружок с галочкой ─────────────────────────────────────────────
        val circleSize = (42 * dp).toInt()
        val iconSize   = (22 * dp).toInt()

        doneCircle = FrameLayout(context).apply {
            layoutParams = LayoutParams(circleSize, circleSize).also { lp ->
                lp.gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC34C759.toInt())   // тот же зелёный, что в iOS confirm
            }
            alpha  = 0f
            scaleX = 0f
            scaleY = 0f
        }
        doneCircle.addView(
            ImageView(context).apply {
                layoutParams = LayoutParams(iconSize, iconSize).also { lp ->
                    lp.gravity = Gravity.CENTER
                }
                setImageResource(R.drawable.ic_check)
                imageTintList =
                    android.content.res.ColorStateList.valueOf(Color.WHITE)
            }
        )
        addView(doneCircle)

        // ── Оверлей прогресса заливки (своё фото при отправке) ──────────────────
        // ⚠️ Кольцо ВСЕГДА строго по центру ячейки — без логики позиционирования по
        // углам (см. CLAUDE.md §13). Затемняется только маленький круг ПОД кольцом,
        // а не вся ячейка целиком — фото видно сразу, индикатор лёгкий и ненавязчивый.
        val ringSize = (36 * dp).toInt()
        val backdropSize = (52 * dp).toInt()
        uploadRing = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LayoutParams(ringSize, ringSize).also { it.gravity = Gravity.CENTER }
            isIndeterminate = false
            max = 100
            progress = 0
            progressDrawable = ContextCompat.getDrawable(context, R.drawable.bg_voice_ring)
            background = null
        }
        uploadText = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                .also { it.gravity = Gravity.CENTER }
            setTextColor(Color.WHITE)
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
        }
        val uploadRingBackdrop = FrameLayout(context).apply {
            layoutParams = LayoutParams(backdropSize, backdropSize).also { it.gravity = Gravity.CENTER }
            background = ContextCompat.getDrawable(context, R.drawable.bg_upload_ring_backdrop)
            addView(uploadRing)
            addView(uploadText)
        }
        uploadOverlay = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            background = null
            visibility = GONE
            addView(uploadRingBackdrop)
        }
        addView(uploadOverlay)

        // Начальный фон — «поверхность»
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface_elevated))
    }

    /**
     * Прогресс заливки именно ЭТОЙ ячейки (0..99 → кольцо с процентами поверх фото;
     * иначе оверлей скрыт). Фото под оверлеем видно сразу (своё, из кэша).
     */
    fun setUploadProgress(pct: Int) {
        if (pct in 0..99) {
            uploadOverlay.visibility = VISIBLE
            uploadRing.progress = pct
            uploadText.text = "$pct%"
        } else {
            uploadOverlay.visibility = GONE
        }
    }

    // ── Внешний API ────────────────────────────────────────────────────────────

    /** Задаёт радиус скруглений углов изображения. */
    fun setCornerRadius(radius: Float) {
        imageView.shapeAppearanceModel = ShapeAppearanceModel.builder()
            .setAllCornerSizes(radius)
            .build()
    }

    /**
     * Переводит ячейку в состояние «загружается».
     * Вызывается перед стартом фоновой загрузки или при переиспользовании view.
     */
    fun showLoading() {
        imageView.animate().cancel()
        imageView.alpha = 0f
        imageView.setImageBitmap(null)
        progressBar.visibility = VISIBLE
        doneCircle.alpha  = 0f
        doneCircle.scaleX = 0f
        doneCircle.scaleY = 0f
        setBackgroundColor(ContextCompat.getColor(context, R.color.surface_elevated))
    }

    /**
     * Мгновенно показывает изображение без анимации.
     * Используется для уже закешированных картинок, чтобы не раздражать анимацией
     * при скролле через уже загруженные сообщения.
     */
    fun showBitmapImmediate(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        imageView.alpha = 1f
        progressBar.visibility = GONE
        doneCircle.alpha = 0f
        background = null
    }

    /**
     * Проявляет свежезагруженное изображение с анимацией подтверждения.
     *
     * Анимация:
     *   1. Fade-in изображения (200 мс)
     *   2. Pop-in зелёного done-кружка (300 мс, OvershootInterpolator)
     *   3. Пауза 600 мс
     *   4. Fade-out done-кружка (350 мс)
     */
    fun showBitmap(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        progressBar.visibility = GONE

        // Fade-in изображения
        imageView.animate()
            .alpha(1f)
            .setDuration(200)
            .withEndAction { background = null }
            .start()

        // Pop-in done-кружка
        doneCircle.alpha  = 1f
        doneCircle.scaleX = 0f
        doneCircle.scaleY = 0f

        val popIn = AnimatorSet()