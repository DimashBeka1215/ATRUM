package com.atrum.chat

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Игровые подсказки-указатели (coach marks) — как в туториалах игр.
 *
 * Не полноэкранная модалка: маленькая карточка (чёрная поверхность + фиолетовая обводка) со
 * стрелкой на конкретный элемент интерфейса; сам элемент подсвечен «дыркой» в затемнении, всё
 * остальное приглушено. Кнопки «Пропустить» / «Далее», точки-шаги внизу.
 *
 * Использование из любого экрана (в onResume, после верстки):
 *
 *   CoachMark.show(this, "chats_list", listOf(
 *       CoachMark.Step(R.id.fab_new_chat, getString(R.string.coach_create_t),
 *                      getString(R.string.coach_create_b), circle = true, iconRes = R.drawable.ic_plus)
 *   ))
 *
 * Тур с ключом [tourKey] показывается один раз (флаг в [Prefs]); повторный показ — [force] = true
 * (например из настройки «показать обучение заново»). Экраны сами вызывают show() — так подсказки
 * «пронизывают» весь ATRUM: каждый экран заводит свой короткий тур при первом заходе.
 *
 * Три темы: карточка — @color/surface + фиолетовая обводка (одинакова в тёмной/светлой/glass),
 * текст — токены text_primary/secondary. Никакого hardcode фона.
 */
object CoachMark {

    /** Один шаг тура. [targetId] = 0 → без цели (карточка по центру, обзорный шаг). */
    data class Step(
        val targetId: Int,
        val title: String,
        val body: String,
        val circle: Boolean = false,
        val iconRes: Int = 0
    )

    private const val BORDER = 0xFFA855F7.toInt()   // фиолетовая обводка (инвариант темы)
    private const val RING    = 0xFF9D4EDD.toInt()  // кольцо вокруг подсвеченного элемента

    /** Тур поверх основного экрана активити. */
    fun show(activity: Activity, tourKey: String, steps: List<Step>, force: Boolean = false) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        show(activity, root, tourKey, steps, force)
    }

    /**
     * Тур поверх произвольного контейнера [container] — например decorView окна диалога
     * (`dialog.window?.decorView as ViewGroup`), чтобы подсказки рисовались НАД диалогом, а
     * цели искались внутри него. Оверлей добавляется в [container] и там же ищет вьюхи по id.
     */
    fun show(activity: Activity, container: ViewGroup, tourKey: String, steps: List<Step>, force: Boolean = false) {
        if (steps.isEmpty()) return
        val prefs = Prefs(activity)
        if (!force && prefs.isCoachShown(tourKey)) return
        // Ждём, пока целевые вьюхи получат размеры/позицию, иначе спотлайт встанет не туда.
        container.post {
            if (activity.isFinishing) return@post
            // Не дублируем оверлей, если уже висит.
            for (i in 0 until container.childCount) if (container.getChildAt(i) is Overlay) return@post
            val overlay = Overlay(activity, container, steps) { prefs.setCoachShown(tourKey) }
            container.addView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // Флаг «просмотрено» ставим сразу при первом ПОКАЗЕ, а не только по завершению тура.
            // Иначе, если пользователь ушёл с экрана (Back/навигация) не долистав до конца,
            // onDone не вызывался, флаг не сохранялся — и плашки появлялись повторно.
            prefs.setCoachShown(tourKey)
        }
    }

    // ── Оверлей ───────────────────────────────────────────────────────────────

    private class Overlay(
        val activity: Activity,
        val container: ViewGroup,
        val steps: List<Step>,
        val onDone: () -> Unit
    ) : FrameLayout(activity) {

        private var index = 0
        private val card: LinearLayout
        private val tvTitle: TextView
        private val tvBody: TextView
        private val ivIcon: ImageView
        private val dotsBox: LinearLayout
        private val btnNext: TextView
        private val btnSkip: TextView

        // Текущая геометрия (в координатах оверлея)
        private var cardBelow = true   // карточка ниже цели (стрелка сверху) или выше (стрелка снизу)

        // Перепозиционируем карточку при КАЖДОЙ раскладке дерева: цель может появиться/сместиться
        // позже (insets, входная анимация, первый заход) — иначе карточка «слетает» от подсветки.
        private val relayoutListener = ViewTreeObserver.OnGlobalLayoutListener { positionCard() }

        /** Прямоугольник подсветки цели в координатах оверлея — вычисляется по требованию
         *  (после layout), чтобы getLocationInWindow вернул верные координаты. */
        private fun currentSpot(): RectF? {
            val step = steps.getOrNull(index) ?: return null
            if (step.targetId == 0) return null
            val target = container.findViewById<View>(step.targetId) ?: return null
            if (target.width <= 0 || !target.isShown) return null
            val loc = IntArray(2); target.getLocationInWindow(loc)
            val my = IntArray(2); getLocationInWindow(my)
            val x = (loc[0] - my[0]).toFloat()
            val y = (loc[1] - my[1]).toFloat()
            val pad = dp(8f)
            return RectF(x - pad, y - pad, x + target.width + pad, y + target.height + pad)
        }

        private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA6000000.toInt() }
        private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(2f); color = RING
        }
        private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(8f); color = 0x559D4EDD
        }
        private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = ContextCompat.getColor(activity, R.color.surface)
        }
        private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1f); color = BORDER
        }

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)  // нужно для PorterDuff.CLEAR (дырка в затемнении)
            setWillNotDraw(false)
            isClickable = true                       // перехватываем тапы (фон-скрим)

            // ── Карточка-подсказка ──
            card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(activity, R.color.surface))
                    setStroke(dp(1f).toInt(), BORDER)
                    cornerRadius = dp(14f)
                }
                setPadding(dp(14f).toInt(), dp(12f).toInt(), dp(14f).toInt(), dp(12f).toInt())
                isClickable = true  // клик по карточке не проваливается на скрим
            }

            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            ivIcon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16f).toInt(), dp(16f).toInt()).apply {
                    marginEnd = dp(7f).toInt()
                }
                setColorFilter(ContextCompat.getColor(activity, R.color.accent_light))
            }
            tvTitle = TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
            }
            titleRow.addView(ivIcon)
            titleRow.addView(tvTitle)
            card.addView(titleRow)

            tvBody = TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(dp(2f), 1f)
                setTextColor(ContextCompat.getColor(activity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(5f).toInt() }
            }
            card.addView(tvBody)

            val footer = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(11f).toInt() }
            }
            dotsBox = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            btnSkip = TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(ContextCompat.getColor(activity, R.color.text_tertiary))
                text = activity.getString(R.string.coach_skip)
                setPadding(dp(6f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(4f).toInt())
                setOnClickListener { finish() }
            }
            btnNext = TextView(activity).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
                setTextColor(ContextCompat.getColor(activity, R.color.white))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(ContextCompat.getColor(activity, R.color.accent))
                    cornerRadius = dp(9f)
                }
                setPadding(dp(14f).toInt(), dp(5f).toInt(), dp(14f).toInt(), dp(5f).toInt())
                setOnClickListener { next() }
            }
            footer.addView(dotsBox)
            footer.addView(btnSkip)
            footer.addView(btnNext)
            card.addView(footer)

            addView(card, LayoutParams(dp(258f).toInt(), LayoutParams.WRAP_CONTENT))

            // Тап по фону (вне карточки) — просто следующий шаг, чтобы не «застрять».
            setOnClickListener { next() }

            bindStep()
            viewTreeObserver.addOnGlobalLayoutListener(relayoutListener)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnGlobalLayoutListener(relayoutListener)
            super.onDetachedFromWindow()
        }

        private fun next() {
            if (index >= steps.size - 1) finish() else { index++; bindStep() }
        }

        private fun finish() {
            onDone()
            (parent as? ViewGroup)?.removeView(this)
        }

        private fun bindStep() {
            val step = steps[index]
            // Иконка
            if (step.iconRes != 0) { ivIcon.setImageResource(step.iconRes); ivIcon.visibility = View.VISIBLE }
            else ivIcon.visibility = View.GONE
            tvTitle.text = step.title
            tvBody.text = step.body
            btnNext.text = activity.getString(
                if (index >= steps.size - 1) R.string.coach_done else R.string.coach_next
            )
            btnSkip.visibility = if (steps.size > 1 && index < steps.size - 1) View.VISIBLE else View.GONE
            rebuildDots()
            // Позиционируем карточку и рисуем после того, как оверлей/цель разложены.
            post { positionCard(); invalidate() }
            invalidate()
        }

        private fun rebuildDots() {
            dotsBox.removeAllViews()
            if (steps.size < 2) return
            for (i in steps.indices) {
                val active = i == index
                dotsBox.addView(View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        if (active) dp(16f).toInt() else dp(5f).toInt(), dp(5f).toInt()
                    ).apply { marginEnd = dp(5f).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(3f)
                        setColor(ContextCompat.getColor(
                            activity, if (active) R.color.accent_light else R.color.border
                        ))
                    }
                })
            }
        }

        /** Ставит карточку у цели (ниже/выше) или по центру; учитывает края экрана. */
        private fun positionCard() {
            val margin = dp(16f)
            val gap = dp(12f)          // зазор карточка↔цель (место под стрелку)
            val cardW = dp(258f)
            // Меряем высоту карточки под фикс-ширину.
            card.measure(
                MeasureSpec.makeMeasureSpec(cardW.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val cardH = card.measuredHeight
            val vw = width.toFloat().let { if (it <= 0) resources.displayMetrics.widthPixels.toFloat() else it }
            val vh = height.toFloat().let { if (it <= 0) resources.displayMetrics.heightPixels.toFloat() else it }

            val lp = card.layoutParams as LayoutParams
            val s = currentSpot()
            val newLeft: Int
            val newTop: Int
            if (s == null) {
                // Обзорный шаг — карточка по центру.
                newLeft = ((vw - cardW) / 2f).toInt()
                newTop = ((vh - cardH) / 2f).toInt()
                cardBelow = true
            } else {
                // По горизонтали центрируем над целью, но держим в пределах экрана.
                val cx = s.centerX()
                var left = cx - cardW / 2f
                left = left.coerceIn(margin, vw - margin - cardW)
                // Ниже цели, если сверху не влезает; иначе выше.
                val below = s.bottom + gap + cardH + margin <= vh
                cardBelow = below
                val top = if (below) s.bottom + gap else s.top - gap - cardH
                newLeft = left.toInt()
                newTop = top.coerceIn(margin, vh - margin - cardH).toInt()
            }
            // Применяем только при изменении — иначе setLayoutParams зациклит OnGlobalLayout.
            if (lp.leftMargin != newLeft || lp.topMargin != newTop) {
                lp.leftMargin = newLeft
                lp.topMargin = newTop
                card.layoutParams = lp
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrimPaint)
            val s = currentSpot()
            if (s != null) {
                if (steps[index].circle) {
                    val r = maxOf(s.width(), s.height()) / 2f
                    canvas.drawCircle(s.centerX(), s.centerY(), r, clearPaint)
                    canvas.drawCircle(s.centerX(), s.centerY(), r, ringGlow)
                    canvas.drawCircle(s.centerX(), s.centerY(), r, ringPaint)
                } else {
                    val rad = dp(14f)
                    canvas.drawRoundRect(s, rad, rad, clearPaint)
                    canvas.drawRoundRect(s, rad, rad, ringGlow)
                    canvas.drawRoundRect(s, rad, rad, ringPaint)
                }
            }
            canvas.restoreToCount(sc)
            // Стрелка от карточки к цели (после того как карточка спозиционирована).
            if (s != null && card.width > 0) drawArrow(canvas, s)
        }

        private fun drawArrow(canvas: Canvas, s: RectF) {
            val half = dp(7f)
            val cx = s.centerX().coerceIn(card.left + dp(20f), card.right - dp(20f))
            val path = android.graphics.Path()
            if (cardBelow) {
                val baseY = card.top.toFloat()
                path.moveTo(cx - half, baseY + 1)
                path.lineTo(cx + half, baseY + 1)
                path.lineTo(cx, baseY - half)
            } else {
                val baseY = card.bottom.toFloat()
                path.moveTo(cx - half, baseY - 1)
                path.lineTo(cx + half, baseY - 1)
                path.lineTo(cx, baseY + half)
            }
            path.close()
            canvas.drawPath(path, arrowFill)
            canvas.drawPath(path, arrowStroke)
        }

        private fun dp(v: Float) = v * resources.displayMetrics.density
    }
}
