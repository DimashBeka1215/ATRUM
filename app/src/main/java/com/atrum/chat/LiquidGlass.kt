package com.atrum.chat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlin.math.max

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  LIQUID GLASS — материал «атмосферного стекла» ATRUM
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Реализация по документации Apple (Technology Overviews → Liquid Glass /
 * Adopting Liquid Glass, HIG → Materials, WWDC25 «Meet Liquid Glass»).
 *
 * Ключевая мысль документации: Liquid Glass — НЕ «полупрозрачный фон с блюром».
 * Это материал из ТРЁХ оптических слоёв, которые ведут себя как настоящее стекло:
 *
 *   1) HIGHLIGHT   — блик, который живёт и смещается, а не нарисован статично;
 *   2) SHADOW      — кромка-тень, отделяющая стекло от контента под ним (глубина);
 *   3) ILLUMINATION— свечение изнутри в ответ на касание («illuminates from underneath»).
 *
 * Плюс два системных правила:
 *
 *   • LENSING — свет преломляется НА КРОМКЕ. Именно кромка отличает стекло от
 *     матовой плёнки: в центре материал почти прозрачен, по краю — гнёт свет.
 *     Здесь это даёт [LiquidGlassOverlay] (кромка на всех устройствах) и
 *     [LiquidGlassRefraction] (настоящее искажение обоев, Android 13+).
 *
 *   • CONCENTRICITY — радиусы вложенных форм не выдумываются, а вычисляются:
 *     внутренний = внешний − отступ. См. [concentricRadiusDp].
 *
 * Доктрина применения (Apple, «Avoid overusing Liquid Glass effects»): материал —
 * только для ВЕРХНЕГО функционального слоя (шапка чата, панель ввода, их кнопки).
 * На пузырьки сообщений, карточки и прочий контент стекло НЕ вешаем: оно обязано
 * подчёркивать контент, а не соревноваться с ним. Стекло на стекле — запрещено там же.
 *
 * Доступность (Apple: Reduce Transparency / Reduce Motion / Increase Contrast):
 * прямых аналогов первого и третьего в Android нет, но системное отключение анимаций
 * есть — см. [isMotionReduced]. При нём материал остаётся, «жизнь» гаснет.
 *
 * Связано: §0 (три режима чата), §1.5 (всё мгновенно и на месте), §4/§5 (токены
 * @color/glass_*, без hex в коде), §9 (иконки не трогаем).
 */
object LiquidGlass {

    // ── Геометрия ────────────────────────────────────────────────────────────

    /**
     * CONCENTRICITY по Apple: радиус вложенной формы = радиус контейнера − отступ
     * до его края. Тогда контуры на всех уровнях вложенности читаются как ОДНА
     * непрерывная кривая, а не как разные фигуры друг на друге.
     *
     * Пример из нашей шапки: капсула r=26dp, кнопка утоплена на 5dp →
     * concentricRadiusDp(26, 5) = 21dp — ровно половина кнопки 42dp, то есть
     * идеальный круг. Совпадение не случайно: так и проверяется, что геометрия
     * собрана правильно, а не подогнана на глаз.
     */
    fun concentricRadiusDp(outerRadiusDp: Float, insetDp: Float): Float =
        max(0f, outerRadiusDp - insetDp)

    /** Радиус истинной «капсулы» (pill) для формы заданной высоты. */
    fun capsuleRadiusDp(heightDp: Float): Float = heightDp / 2f

    fun dpToPx(context: Context, dp: Float): Float =
        dp * context.resources.displayMetrics.density

    // ── Возможности устройства ───────────────────────────────────────────────

    /** Настоящее преломление обоев (AGSL RuntimeShader) — только Android 13+. */
    fun supportsRefraction(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Пользователь отключил анимации в системе (Настройки → Для разработчиков →
     * Animator duration scale = off, либо режим энергосбережения). Ближайший
     * доступный на Android аналог Apple Reduce Motion: материал остаётся,
     * но «дыхание» блика и свечение от касания не запускаем.
     */
    fun isMotionReduced(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    } catch (_: Throwable) {
        false
    }

    // ── Сборка материала на конкретной View ──────────────────────────────────

    /**
     * Навешивает оптический слой Liquid Glass поверх содержимого view.
     *
     * Используется [View.setForeground] — слой рисуется ПОВЕРХ детей (иконки,
     * текст), но под ничем не перехватывает касания, поэтому существующие
     * обработчики и вёрстка остаются нетронутыми: ни один layout не меняется.
     *
     * @param cornerRadiusDp радиус скругления самой view (для клиппинга кромки)
     * @param interactive    вешать ли отклик на касание. ВАЖНО: false для тех view,
     *                       у кого УЖЕ есть свой OnTouchListener (у нас это btnVoice
     *                       с жестом записи голоса) — иначе мы его затрём.
     */
    fun attach(
        view: View,
        cornerRadiusDp: Float,
        interactive: Boolean,
        style: LiquidGlassOverlay.Style = LiquidGlassOverlay.Style.SURFACE
    ): LiquidGlassOverlay {
        val ctx = view.context
        // Гасим предыдущий слой, если он уже висел на этой view. applyWallpaper() (а с ней и
        // applyGlassStyle) вызывается повторно — при смене обоев, повороте, возврате на экран.
        // Без этого каждый вызов плодил бы новый бесконечный аниматор блика, а старый продолжал
        // бы тикать в никуда: утечка и лишний расход батареи.
        (view.foreground as? LiquidGlassOverlay)?.release()

        val overlay = LiquidGlassOverlay(ctx, dpToPx(ctx, cornerRadiusDp), style)
        overlay.motionEnabled = !isMotionReduced(ctx)
        view.foreground = overlay
        if (interactive && overlay.motionEnabled) {
            // Слушатель НЕ потребляет событие (возвращает false): клики, долгие
            // нажатия и прочая логика продолжают работать ровно как раньше.
            view.setOnTouchListener { v, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    (v.foreground as? LiquidGlassOverlay)?.illuminate(ev.x, ev.y)
                }
                false
            }
        }
        overlay.startSpecular()
        return overlay
    }

    /** Полностью снимает материал: гасит аниматоры, убирает слой и слушателя. */
    fun detach(view: View, hadOwnTouchListener: Boolean = false) {
        (view.foreground as? LiquidGlassOverlay)?.release()
        view.foreground = null
        if (!hadOwnTouchListener) view.setOnTouchListener(null)
    }
}

/**
 * Оптический слой материала: LENSING-кромка + «дышащий» highlight + illumination
 * от касания. Рисуется как foreground, поэтому не влияет на вёрстку и не требует
 * ни одного нового View в layout.
 */
class LiquidGlassOverlay(
    context: Context,
    private var radiusPx: Float,
    private val glassStyle: Style = Style.SURFACE
) : Drawable() {

    /**
     * Роль элемента в иерархии материала.
     *
     * Apple прямо запрещает класть стекло на стекло («avoid overcrowding or layering
     * Liquid Glass elements on top of each other»). Поэтому кромку и блик получает
     * только САМА стеклянная поверхность, а вложенные в неё кнопки — лишь свечение
     * от касания: иначе внутри капсулы получается пять маленьких стёкол на большом,
     * и шапка визуально рассыпается.
     */
    enum class Style {
        /** Стеклянная поверхность: кромка-линза + блик + свечение. Шапка, панель ввода. */
        SURFACE,

        /** Элемент ВНУТРИ стекла: только свечение от касания. Кнопки в капсуле/пилюле. */
        NESTED
    }

    private val rimLight = ContextCompat.getColor(context, R.color.glass_rim_light)
    private val rimDark = ContextCompat.getColor(context, R.color.glass_rim_dark)
    private val specularColor = ContextCompat.getColor(context, R.color.glass_specular)
    private val illuminationColor = ContextCompat.getColor(context, R.color.glass_illumination)

    /** Выключается при системном отключении анимаций (аналог Apple Reduce Motion). */
    var motionEnabled: Boolean = true

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val glassBounds = RectF()

    /** Контур клиппинга. Держим полем — в draw() выделять Path нельзя (GC на каждый кадр). */
    private val clipPath = android.graphics.Path()
    private val rimRect = RectF()

    /** Фаза «дыхания» блика, 0..1. Ведётся аниматором, гонит highlight по кромке. */
    private var specularPhase = 0f
    private var specularAnim: ValueAnimator? = null

    /** Состояние свечения от касания. */
    private var glowX = 0f
    private var glowY = 0f
    private var glowProgress = 0f          // 0 = погасло, 1 = максимум
    private var glowAnim: ValueAnimator? = null

    // ── Публичное управление ─────────────────────────────────────────────────

    /** Запускает медленное «дыхание» блика (Apple: highlight движется, а не статичен). */
    fun startSpecular() {
        if (glassStyle != Style.SURFACE) return   // блик — только у самой поверхности
        if (!motionEnabled || specularAnim != null) return
        specularAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SPECULAR_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                specularPhase = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    /**
     * Свечение изнутри из точки касания — Apple «the material illuminates from
     * underneath as feedback». Одноразовый импульс, гаснет сам.
     */
    fun illuminate(x: Float, y: Float) {
        if (!motionEnabled) return
        glowX = x
        glowY = y
        glowAnim?.cancel()
        glowAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = ILLUMINATION_MS
            addUpdateListener {
                glowProgress = it.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    /** Останавливает всё живое — обязательно при уходе из glass-режима. */
    fun release() {
        specularAnim?.cancel(); specularAnim = null
        glowAnim?.cancel(); glowAnim = null
        glowProgress = 0f
    }

    fun setCornerRadiusPx(px: Float) {
        radiusPx = px
        invalidateSelf()
    }

    // ── Отрисовка ────────────────────────────────────────────────────────────

    override fun onBoundsChange(b: Rect) {
        glassBounds.set(b)
        rebuildShaders()
    }

    private fun rebuildShaders() {
        if (glassBounds.width() <= 0f || glassBounds.height() <= 0f) return

        // LENSING: кромка сверху ловит свет, снизу уходит в тень. Именно этот
        // перепад читается глазом как «толщина» и кривизна стекла — без него
        // поверхность выглядит плёнкой, а не линзой.
        rimPaint.shader = LinearGradient(
            0f, glassBounds.top, 0f, glassBounds.bottom,
            intArrayOf(rimLight, Color.TRANSPARENT, rimDark),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        rimPaint.strokeWidth = max(1f, glassBounds.height() * RIM_WIDTH_RATIO)
    }

    override fun draw(canvas: Canvas) {
        if (glassBounds.width() <= 0f || glassBounds.height() <= 0f) return

        val save = canvas.save()
        // Всё клиппим по форме самой view — материал не вылезает за капсулу.
        clipPath.reset()
        clipPath.addRoundRect(glassBounds, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
        canvas.clipPath(clipPath)

        if (glassStyle == Style.SURFACE) drawSpecular(canvas)
        drawIllumination(canvas)

        canvas.restoreToCount(save)

        // Вложенные элементы кромку не получают — см. Style.NESTED.
        if (glassStyle != Style.SURFACE) return

        // Кромку рисуем ПОСЛЕ восстановления клиппинга, но по тому же контуру,
        // с поправкой на толщину — иначе половина обводки срезается клиппингом.
        val inset = rimPaint.strokeWidth / 2f
        rimRect.set(
            glassBounds.left + inset, glassBounds.top + inset,
            glassBounds.right - inset, glassBounds.bottom - inset
        )
        val rimRadius = max(0f, radiusPx - inset)
        canvas.drawRoundRect(rimRect, rimRadius, rimRadius, rimPaint)
    }

    /** HIGHLIGHT: мягкая диагональная полоса света, медленно смещающаяся по стеклу. */
    private fun drawSpecular(canvas: Canvas) {
        val w = glassBounds.width()
        // Фаза гонит центр блика вдоль поверхности; амплитуда небольшая, чтобы
        // эффект читался как «свет играет», а не как бегущая полоса.
        val shift = (specularPhase - 0.5f) * w * SPECULAR_TRAVEL
        val cx = glassBounds.centerX() + shift

        val specularFade = specularColor and 0x00FFFFFF   // тот же цвет, alpha = 0
        specularPaint.shader = LinearGradient(
            cx - w * 0.5f, glassBounds.top,
            cx + w * 0.5f, glassBounds.bottom,
            intArrayOf(specularFade, specularColor, specularFade),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(glassBounds, specularPaint)
    }

    /** ILLUMINATION: радиальное свечение из точки касания, гаснущее за ~450мс. */
    private fun drawIllumination(canvas: Canvas) {
        if (glowProgress <= 0.01f) return

        // Радиус растёт по мере угасания — свет «расходится» от пальца.
        val maxRadius = max(glassBounds.width(), glassBounds.height()) * ILLUMINATION_SPREAD
        val radius = maxRadius * (1f - glowProgress * 0.55f)
        if (radius <= 0f) return

        val alpha = (Color.alpha(illuminationColor) * glowProgress).toInt().coerceIn(0, 255)
        val core = Color.argb(
            alpha,
            Color.red(illuminationColor),
            Color.green(illuminationColor),
            Color.blue(illuminationColor)
        )
        glowPaint.shader = RadialGradient(
            glowX, glowY, radius,
            intArrayOf(core, core and 0x00FFFFFF),   // гаснем в свой же цвет, не в чёрный
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(glowX, glowY, radius, glowPaint)
    }

    override fun setAlpha(alpha: Int) { /* прозрачность задаётся токенами слоёв */ }
    override fun setColorFilter(colorFilter: ColorFilter?) { /* не тонируем материал */ }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        /** Период «дыхания» блика. Намеренно медленный — материал живой, но не мигает. */
        const val SPECULAR_PERIOD_MS = 5200L
        /** Длительность вспышки от касания. */
        const val ILLUMINATION_MS = 450L
        /** Доля ширины, на которую ездит блик (0.35 = ±17% от центра). */
        const val SPECULAR_TRAVEL = 0.35f
        /** Толщина кромки как доля высоты элемента. */
        const val RIM_WIDTH_RATIO = 0.035f
        /** Во сколько раз пятно свечения крупнее элемента. */
        const val ILLUMINATION_SPREAD = 0.9f
    }
}

/**
 * НАСТОЯЩЕЕ ПРЕЛОМЛЕНИЕ (lensing) обоев под стеклом — Android 13+ (API 33).
 *
 * Apple описывает Liquid Glass как материал, который «dynamically bends and shapes
 * light»: фон под элементом не просто размывается, он ГНЁТСЯ у кромки, как под
 * линзой. В Android публичного API «взять то, что под вью, и исказить» нет, и это
 * принципиальное ограничение платформы. Единственная честная точка приложения —
 * сами обои: они лежат отдельным ImageView под всем интерфейсом, поэтому
 * [RenderEffect] с AGSL-шейдером можно повесить прямо на них и искажать картинку
 * ровно в тех полосах, где сверху лежат капсула шапки и пилюля ввода.
 *
 * Что это даёт: обои под стеклом реально преломляются у кромки. Чего не даёт:
 * сообщения, проезжающие под шапкой, не гнутся (они не часть этого слоя). Это
 * осознанный компромисс, а не недоделка — и он совпадает с принципом Apple
 * «content-first»: гнём фон, не трогаем контент.
 *
 * На API < 33 не вызывается вовсе; там материал держится на кромке из
 * [LiquidGlassOverlay] — деградация мягкая, ровно как у backdrop-блюра (API 31+).
 */
object LiquidGlassRefraction {

    /**
     * AGSL-шейдер. Для каждой из двух стеклянных областей считает знаковое
     * расстояние до её скруглённого прямоугольника (SDF) и у самой кромки
     * сдвигает координату выборки наружу от центра — это и есть преломление.
     * В середине стекла смещение равно нулю: линза гнёт свет по краю, а не везде.
     */
    private const val AGSL = """
        uniform shader content;
        uniform float4 rectA;
        uniform float4 rectB;
        uniform float radiusA;
        uniform float radiusB;
        uniform float amount;
        uniform float band;

        float2 lens(float2 coord, float4 r, float rad, float amt, float bandPx) {
            float2 c = float2((r.x + r.z) * 0.5, (r.y + r.w) * 0.5);
            float2 h = float2((r.z - r.x) * 0.5, (r.w - r.y) * 0.5);
            if (h.x <= 0.0 || h.y <= 0.0) { return float2(0.0); }
            float2 q = abs(coord - c) - (h - float2(rad));
            float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rad;
            if (d > 0.0 || d < -bandPx) { return float2(0.0); }
            float edge = smoothstep(-bandPx, 0.0, d);
            float2 v = coord - c;
            float len = length(v);
            if (len < 0.001) { return float2(0.0); }
            return (v / len) * edge * amt;
        }

        half4 main(float2 coord) {
            float2 d = lens(coord, rectA, radiusA, amount, band)
                     + lens(coord, rectB, radiusB, amount, band);
            return content.eval(coord + d);
        }
    """

    /**
     * Вешает преломление на [wallpaper] в полосах [a] и [b] (координаты — в
     * системе самого wallpaper, в пикселях).
     *
     * @return true если эффект применён; false — если устройство/драйвер его не
     *         потянул. Вызывающая сторона в этом случае просто остаётся на кромке.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun install(
        wallpaper: View,
        a: RectF,
        b: RectF,
        radiusAPx: Float,
        radiusBPx: Float,
        amountPx: Float,
        bandPx: Float
    ): Boolean = try {
        val shader = RuntimeShader(AGSL)
        shader.setFloatUniform("rectA", a.left, a.top, a.right, a.bottom)
        shader.setFloatUniform("rectB", b.left, b.top, b.right, b.bottom)
        shader.setFloatUniform("radiusA", radiusAPx)
        shader.setFloatUniform("radiusB", radiusBPx)
        shader.setFloatUniform("amount", amountPx)
        shader.setFloatUniform("band", bandPx)
        wallpaper.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
        true
    } catch (_: Throwable) {
        // Компиляция шейдера или RenderEffect недоступны на этом устройстве —
        // молча откатываемся, эффект необязательный (§16: это не баг, а фолбэк).
        clear(wallpaper)
        false
    }

    /** Снимает эффект. Безопасно вызывать на любом API. */
    fun clear(wallpaper: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                wallpaper.setRenderEffect(null)
            } catch (_: Throwable) { /* ignore */ }
        }
    }
}
