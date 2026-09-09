package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.provider.Settings
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  ATMOSPHERIC GLASS — материал «атмосферного стекла» ATRUM
 * ════════════════════════════════════════════════════════════════════════════
 *
 * ── ЧТО ЭТО СЕЙЧАС И ПОЧЕМУ ──────────────────────────────────────────────────
 *
 * 25.08.2026 — ОТКАТ: настоящее AGSL-преломление живого кадра на GPU убрано целиком
 * по прямому запросу («полностью убери весь эффект жидкого стекла и оставь просто
 * прозрачность»). Поверхность больше не гнёт то, что под ней, не размывает и не
 * светится от касания — это плоская полупрозрачная заливка (`@color/glass_tint`)
 * внутри скруглённого контура, ничего больше.
 *
 * История подходов (чтобы не наступить второй раз — ни в одну, ни в другую сторону):
 *
 *   1) View.setBackgroundBlurRadius через рефлексию — метода нет в публичном API,
 *      вызов всегда падал в catch. Размытия не было НИКОГДА, ни на одном устройстве.
 *   2) Размытая копия обоев + та же копия, увеличенная по кромке. Работало везде, но
 *      преломлением не являлось: сквозь стекло не было видно сообщений, а «линза» была
 *      масштабированным гауссом.
 *   3) Настоящее преломление: [AtmosphericGlassBackdrop] писал то, что лежит под стеклом,
 *      в RenderNode раз в кадр, а каждая поверхность гнула его AGSL-шейдером
 *      (`res/raw/atrum_glass_refraction.agsl`, MIT, из AndroidLiquidGlassView) —
 *      технически рабочая схема, что и было целью. Прожила несколько раундов тюнинга
 *      мокапа (см. git-историю этого файла и `DESIGN.md` §1.3 на момент отката), но в
 *      итоге откатана целиком — решение принято на уровне мокапа, не в этом файле.
 *   4) (сейчас) Плоская заливка ниже — то, что было деградацией для до-API-33 устройств
 *      в подходе (3), теперь единственный режим на всех версиях Android. Код специально
 *      оставлен маленьким: если понадобится вернуть преломление, шаг (3) в git-истории
 *      этого файла — рабочая, уже когда-то откомментированная реализация, ре-имплементить
 *      с нуля не придётся.
 *
 * ── Доктрина применения (не изменилась) ─────────────────────────────────────
 * Материал — только для верхнего функционального слоя. Стекло на стекле запрещено:
 * вложенные элементы ([Style.NESTED]) не рисуют вообще ничего — раньше там было
 * свечение от касания, сейчас и оно убрано вместе с остальным «живым» слоем.
 */
object AtmosphericGlass {

    /**
     * CONCENTRICITY по Apple: радиус вложенной формы = радиус контейнера − отступ.
     * Тогда контуры всех уровней читаются как одна непрерывная кривая. Актуально и для
     * плоской заливки — геометрия капсул не связана с тем, как их красят.
     */
    fun concentricRadiusDp(outerRadiusDp: Float, insetDp: Float): Float =
        max(0f, outerRadiusDp - insetDp)

    fun dpToPx(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density

    /**
     * Пользователь отключил анимации в системе — ближайший доступный на Android аналог
     * Apple Reduce Motion. Больше ни на что не влияет (анимированной «жизни» в материале
     * не осталось), но используется [BackdropBlurView] — оставлено ради него.
     */
    fun isMotionReduced(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) == 0f
    } catch (_: Throwable) {
        false
    }

    /**
     * Навешивает материал на view.
     *
     * SURFACE идёт в BACKGROUND: поверхность рисует плоскую заливку ПОД содержимым.
     * NESTED идёт в FOREGROUND и теперь не рисует ничего — оставлен как стиль, а не
     * удалён совсем, чтобы вызовы attach() в ChatActivity не пришлось переписывать,
     * если материал когда-нибудь снова станет «живым».
     *
     * @param cornerRadiusDp null = честная капсула: радиус равен половине фактической
     *                       высоты, какой бы она ни оказалась.
     * @param interactive    больше ни на что не влияет (раньше включал свечение от
     *                       касания) — параметр оставлен, чтобы не трогать вызовы.
     */
    fun attach(
        view: View,
        style: AtmosphericGlassSurface.Style,
        interactive: Boolean,
        cornerRadiusDp: Float? = null
    ): AtmosphericGlassSurface {
        // Гасим предыдущий слой на этой view (applyGlassStyle() зовётся повторно —
        // смена обоев, поворот, возврат на экран).
        (view.background as? AtmosphericGlassSurface)?.release()
        (view.foreground as? AtmosphericGlassSurface)?.release()

        val surface = AtmosphericGlassSurface(
            view.context, style, cornerRadiusDp?.let { dpToPx(view.context, it) }
        )
        surface.host = view

        if (style == AtmosphericGlassSurface.Style.SURFACE) {
            view.background = surface
        } else {
            view.foreground = surface
        }
        return surface
    }

    /** Снимает материал: убирает слой с view. */
    fun detach(view: View, hadOwnTouchListener: Boolean = false) {
        (view.background as? AtmosphericGlassSurface)?.let {
            it.release(); view.background = null
        }
        (view.foreground as? AtmosphericGlassSurface)?.let {
            it.release(); view.foreground = null
        }
        if (!hadOwnTouchListener) view.setOnTouchListener(null)
    }
}

/**
 * 25.08.2026 — ОТКАТ: раньше здесь раз в кадр записывался в RenderNode тот контент, что
 * лежит под стеклом (обои, скримы, лента сообщений), чтобы шейдер преломления мог его
 * исказить (см. историю в шапке [AtmosphericGlass]). Материал целиком убран — искажать
 * больше нечего, общий задник для плоской заливки не нужен.
 *
 * Класс оставлен пустой заглушкой с той же сигнатурой: в ChatActivity.kt десяток мест
 * вызывают attach()/detach()/invalidate() (привязка к жизненному циклу экрана, слушатель
 * скролла ленты) — переписывать их ради этого отката было бы лишним риском для кода,
 * который к самому материалу не относится.
 */
object AtmosphericGlassBackdrop {
    fun attach(rootView: View, sourceViews: List<View>) { /* заглушка, см. комментарий выше */ }
    fun detach() { /* заглушка */ }
    fun invalidate() { /* заглушка */ }
}

/**
 * Стеклянная поверхность. Раньше — преломление задника + свет по кромке + свечение от
 * касания (RenderNode/RuntimeShader/RenderEffect, см. историю в [AtmosphericGlass]).
 * Сейчас — плоская полупрозрачная заливка внутри скруглённого контура, ничего больше:
 * ни блюра, ни подстройки под то, что физически находится позади.
 */
class AtmosphericGlassSurface(
    context: Context,
    private val glassStyle: Style,
    /** null = капсула: радиус всегда равен половине высоты, какой бы она ни была. */
    private val fixedRadiusPx: Float?
) : Drawable() {

    enum class Style {
        /** Самостоятельное стекло: плоская заливка. */
        SURFACE,
        /** Элемент ВНУТРИ стекла: не рисует ничего (стекло на стекле запрещено). */
        NESTED
    }

    var host: View? = null
    /** Больше ни на что не влияет (раньше включал/выключал «живые» слои) — оставлено
     *  публичным полем, т.к. его выставляет [BackdropBlurView]. */
    var motionEnabled: Boolean = true

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.glass_tint)
    }

    private val area = RectF()
    private val clipPath = Path()

    private val radius: Float
        get() = fixedRadiusPx ?: (area.height() / 2f)

    /** Ничего больше не держит (не осталось ни аниматоров, ни RenderNode) — метод
     *  оставлен, чтобы attach()/detach()/[BackdropBlurView] не пришлось переписывать. */
    fun release() { /* нечего освобождать */ }

    override fun onBoundsChange(b: Rect) {
        area.set(b)
    }

    override fun draw(canvas: Canvas) {
        // NESTED больше не рисует ничего — раньше здесь было свечение от касания,
        // теперь у вложенных элементов вообще нет собственного слоя.
        if (glassStyle != Style.SURFACE) return
        if (area.width() <= 0f || area.height() <= 0f) return

        val r = radius
        clipPath.reset()
        clipPath.addRoundRect(area, r, r, Path.Direction.CW)

        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(area, tintPaint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) { /* прозрачность задаётся токеном заливки */ }
    override fun setColorFilter(colorFilter: ColorFilter?) { /* не тонируем материал */ }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
