package com.atrum.chat

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Telegram-style context menu.
 *
 *  – Настоящее Gaussian-размытие фона (скриншот + агрессивное масштабирование)
 *  – Ширина карточки подстраивается под ширину сообщения (якоря)
 *  – Выравнивание по горизонтали совпадает с пузырьком (левый / правый)
 *  – Позиционирование через translationX/Y — без requestLayout, без мигания
 *  – True-black + фиолетовая рамка, как в NeonDialog
 */
object TelegramMenu {

    data class Item(
        val label: String,
        val iconRes: Int,
        val isDestructive: Boolean = false,
        val action: () -> Unit = {}
    )

    private val REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")

    private fun resolveColor(ctx: Context, resId: Int): Int {
        val typedValue = android.util.TypedValue()
        return if (ctx.theme.resolveAttribute(resId, typedValue, true)) {
            typedValue.data
        } else {
            // Fallback colors if attributes are not found
            when(resId) {
                android.R.attr.colorBackground -> 0xFF000000.toInt()
                android.R.attr.textColorPrimary -> 0xFFFFFFFF.toInt()
                else -> Color.MAGENTA
            }
        }
    }

    private fun Context.dp(v: Float) =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    // ─────────────────────────────────────────────────────────────────────────

    fun show(
        ctx: Context,
        anchor: View,
        items: List<Item>,
        onReaction: ((String) -> Unit)? = null
    ): Dialog {
        val screenW = ctx.resources.displayMetrics.widthPixels
        val screenH = ctx.resources.displayMetrics.heightPixels

        // Dynamic colors based on theme
        val bgColor = resolveColor(ctx, android.R.attr.colorBackground)
        val textColor = resolveColor(ctx, android.R.attr.textColorPrimary)
        val accentColor = resolveColor(ctx, androidx.appcompat.R.attr.colorAccent)
        val dividerColor = (textColor and 0x00FFFFFF) or 0x18000000 // 10% opacity of text color approx or just fixed low alpha
        val destructiveColor = accentColor // Using accent for destructive as in original BORDER/DESTR

        val borderColor = accentColor
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorLeft   = anchorLoc[0]
        val anchorTop    = anchorLoc[1]
        val anchorRight  = anchorLeft + anchor.width
        val anchorCenterX = anchorLeft + anchor.width / 2

        // Выше якоря или ниже?
        val showBelow = (anchorTop + anchor.height / 2) < screenH / 2

        // ── 2. Снимок экрана + Gaussian blur ─────────────────────────────────
        //    Делаем ПЕРЕД показом диалога, пока виден chat UI.
        val blurBitmap = captureBlurredScreen(ctx)

        // ── 3. Адаптивная ширина карточки ─────────────────────────────────────
        //    Минимум 220dp; ширина якоря если больше; максимум 85% экрана.
        val minW    = ctx.dp(220f)
        val cardWidth = anchor.width.coerceAtLeast(minW)
            .coerceAtMost((screenW * 0.85f).toInt())

        // ── 4. Горизонтальное выравнивание ─────────────────────────────────────
        //    Своё сообщение (якорь правее центра) → прижимаем к правому краю якоря.
        //    Чужое (якорь левее центра) → прижимаем к левому краю якоря.
        val margin8 = ctx.dp(8f)
        val cardLeft = if (anchorCenterX > screenW / 2) {
            // Right-align: правый край карточки = правый край якоря
            (anchorRight - cardWidth).coerceIn(margin8, screenW - cardWidth - margin8)
        } else {
            // Left-align: левый край карточки = левый край якоря
            anchorLeft.coerceIn(margin8, screenW - cardWidth - margin8)
        }

        // ── 5. Диалог (fullscreen, без системного dim — мы сами делаем фон) ─────
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Убираем системный dim и blur — мы рендерим собственный blur-фон
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // ── 6. Корневой FrameLayout ───────────────────────────────────────────
        val root = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setOnClickListener { dialog.dismiss() }
        }

        // ── 7. Blur-фон: размытый скриншот + затемнение ───────────────────────
        if (blurBitmap != null) {
            root.addView(ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = ImageView.ScaleType.FIT_XY
                setImageBitmap(blurBitmap)
                // Тёмный оверлей поверх размытия (65% непрозрачности)
                setColorFilter(Color.argb(165, 0, 0, 0), PorterDuff.Mode.SRC_ATOP)
                isClickable = false
            })
        } else {
            // Fallback: просто тёмный оверлей если скриншот не удался
            root.addView(View(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.argb(180, 0, 0, 0))
                isClickable = false
            })
        }

        // ── 8. Контейнер (emoji + карточка) ─────────────────────────────────
        //    Начинает в (0,0), invisible — позиционируется через translationX/Y
        //    в post{} после layout. Нет requestLayout → нет мигания.
        val container = LinearLayout(ctx).apply {
            orientation  = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility   = View.INVISIBLE
            setOnClickListener { /* поглотить клик */ }
        }

        val emojiRow = buildEmojiRow(ctx, dialog, onReaction, bgColor, borderColor)
        val menuCard = buildMenuCard(ctx, dialog, items, bgColor, borderColor, textColor, destructiveColor, dividerColor)
        val gapPx    = ctx.dp(8f)

        if (showBelow) {
            container.addView(emojiRow)
            container.addView(spacer(ctx, gapPx))
            container.addView(menuCard)
        } else {
            container.addView(menuCard)
            container.addView(spacer(ctx, gapPx))
            container.addView(emojiRow)
        }

        root.addView(container)
        dialog.setContentView(root)
        dialog.show()

        // ── 9. Точное позиционирование после layout ────────────────────────────
        container.post {
            val decorLoc = IntArray(2)
            dialog.window?.decorView?.getLocationOnScreen(decorLoc)

            // Якорь относительно диалогового окна
            val relAnchorTop    = anchorTop    - decorLoc[1]
            val relAnchorBottom = anchorTop + anchor.height - decorLoc[1]
            val dialogH = dialog.window?.decorView?.height ?: screenH

            val containerH = container.measuredHeight.takeIf { it > 0 } ?: container.height

            val yPos = if (showBelow) {
                (relAnchorBottom + gapPx).coerceIn(gapPx, dialogH - containerH - gapPx)
            } else {
                (relAnchorTop - gapPx - containerH).coerceIn(gapPx, dialogH - containerH - gapPx)
            }

            // translationX/Y применяются мгновенно без layout-прохода → нет мигания
            container.translationX = (cardLeft - decorLoc[0]).toFloat()
            container.translationY = yPos.toFloat()
            container.visibility   = View.VISIBLE

            animateIn(emojiRow, menuCard, showBelow)
        }

        return dialog
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gaussian blur через даунскейл
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Настоящий Gaussian blur через итерированный box blur.
     *
     * Алгоритм:
     *  1. Снимаем decorView при 35% — достаточно деталей, достаточно мало для скорости.
     *  2. Применяем [BOX_PASSES] проходов 1D box blur (горизонталь + вертикаль).
     *     Box blur = скользящее среднее по окну 2R+1. По ЦПТ N проходов → Гаусс.
     *     Никакого downscale/upscale внутри blur — нет блочности, нет пикселизации.
     *  3. Масштабируем результат обратно. При 3× upscale bilinear абсолютно гладкий.
     *
     * Производительность: 378×840 пикселей (35% от 1080×2400),
     * 3 прохода × 2 оси × ~317k пикселей ≈ 2M операций ≈ <10ms на современном CPU.
     */
    private fun captureBlurredScreen(ctx: Context): Bitmap? {
        val activity = ctx as? Activity ?: return null
        return try {
            val decor = activity.window.decorView
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return null

            // Шаг 1: Захват при 35% масштабе
            val scale = 0.35f
            val sw = (w * scale).toInt().coerceAtLeast(2)
            val sh = (h * scale).toInt().coerceAtLeast(2)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            Canvas(small).also { c -> c.scale(scale, scale); decor.draw(c) }

            // Шаг 2: Iterated box blur (3 прохода ≈ Гаусс, без пикселизации)
            val blurred = boxBlur(small, radius = 9, passes = 3)
            small.recycle()

            // Шаг 3: upscale обратно (~3×, bilinear — гладко при таком соотношении)
            val result = Bitmap.createScaledBitmap(blurred, w, h, true)
            blurred.recycle()
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Применяет [passes] проходов горизонтального + вертикального box blur.
     * Box blur — скользящее среднее: O(w×h) на проход независимо от радиуса.
     * Возвращает новый bitmap; исходный не рециклируется.
     */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)

        repeat(passes) {
            boxBlurH(pixels, w, h, radius)
            boxBlurV(pixels, w, h, radius)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Горизонтальный проход box blur (скользящее среднее по строкам). */
    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            var rS = 0; var gS = 0; var bS = 0
            // Начальная сумма окна [-r..r] с edge-clamp
            for (k in -r..r) {
                val p = px[base + k.coerceIn(0, w - 1)]
                rS += (p ushr 16) and 0xFF
                gS += (p ushr 8)  and 0xFF
                bS +=  p          and 0xFF
            }
            for (x in 0 until w) {
                row[x] = (0xFF shl 24) or
                          ((rS / div) shl 16) or
                          ((gS / div) shl 8)  or
                          (bS / div)
                // Сдвигаем окно: убираем крайний левый, добавляем крайний правый
                val xl = (x - r).coerceAtLeast(0)
                val xr = (x + r + 1).coerceAtMost(w - 1)
                val pl = px[base + xl]; val pr = px[base + xr]
                rS += ((pr ushr 16) and 0xFF) - ((pl ushr 16) and 0xFF)
                gS += ((pr ushr 8)  and 0xFF) - ((pl ushr 8)  and 0xFF)
                bS += (pr and 0xFF) - (pl and 0xFF)
            }
            row.copyInto(px, base, 0, w)
        }
    }

    /** Вертикальный проход box blur (скользящее среднее по столбцам). */
    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val col = IntArray(h)
        for (x in 0 until w) {
            var rS = 0; var gS = 0; var bS = 0
            for (k in -r..r) {
                val p = px[k.coerceIn(0, h - 1) * w + x]
                rS += (p ushr 16) and 0xFF
                gS += (p ushr 8)  and 0xFF
                bS +=  p          and 0xFF
            }
            for (y in 0 until h) {
                col[y] = (0xFF shl 24) or
                          ((rS / div) shl 16) or
                          ((gS / div) shl 8)  or
                          (bS / div)
                val yt = (y - r).coerceAtLeast(0)
                val yb = (y + r + 1).coerceAtMost(h - 1)
                val pt = px[yt * w + x]; val pb = px[yb * w + x]
                rS += ((pb ushr 16) and 0xFF) - ((pt ushr 16) and 0xFF)
                gS += ((pb ushr 8)  and 0xFF) - ((pt ushr 8)  and 0xFF)
                bS += (pb and 0xFF) - (pt and 0xFF)
            }
            for (y in 0 until h) px[y * w + x] = col[y]
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun buildEmojiRow(
        ctx: Context,
        dialog: Dialog,
        onReaction: ((String) -> Unit)?,
        bgColor: Int,
        borderColor: Int
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity     = Gravity.CENTER_VERTICAL
        background  = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = ctx.dp(26f).toFloat()
            setStroke(ctx.dp(1f), borderColor)
        }
        setPadding(ctx.dp(4f), ctx.dp(6f), ctx.dp(4f), ctx.dp(6f))
        elevation = ctx.dp(8f).toFloat()
        scaleX = 0f; scaleY = 0f; alpha = 0f

        REACTIONS.forEach { emoji ->
            addView(buildEmojiButton(ctx, emoji, textColor = null) { dialog.dismiss(); onReaction?.invoke(emoji) })
        }
        addView(buildEmojiButton(ctx, "＋", textColor = (0xB3 shl 24) or (resolveColor(ctx, android.R.attr.textColorPrimary) and 0x00FFFFFF)) { dialog.dismiss() })
    }

    private fun buildEmojiButton(
        ctx: Context,
        text: String,
        textColor: Int? = null,
        onClick: () -> Unit
    ): TextView {
        val size = ctx.dp(42f)
        val defaultTextColor = resolveColor(ctx, android.R.attr.textColorPrimary)
        return TextView(ctx).apply {
            this.text = text
            textSize  = if (textColor != null) 17f else 23f
            gravity   = Gravity.CENTER
            setTextColor(textColor ?: defaultTextColor)
            layoutParams = LinearLayout.LayoutParams(size, size)
            isClickable = true; isFocusable = true
            background  = rippleOval(ctx)
            setOnClickListener { onClick() }
        }
    }

    private fun buildMenuCard(
        ctx: Context,
        dialog: Dialog,
        items: List<Item>,
        bgColor: Int,
        borderColor: Int,
        textColor: Int,
        destructiveColor: Int,
        dividerColor: Int
    ): LinearLayout = LinearLayout(ctx).apply {
        orientation   = LinearLayout.VERTICAL
        clipToOutline = true
        background    = GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = ctx.dp(20f).toFloat()
            setStroke(ctx.dp(1f), borderColor)
        }
        elevation = ctx.dp(12f).toFloat()
        scaleX = 0.85f; scaleY = 0.85f; alpha = 0f

        items.forEachIndexed { i, item ->
            addView(buildMenuItem(ctx, dialog, item, textColor, destructiveColor))
            if (i < items.lastIndex) addView(hDivider(ctx, dividerColor))
        }
    }

    private fun buildMenuItem(ctx: Context, dialog: Dialog, item: Item, textColor: Int, destructiveColor: Int): LinearLayout {
        val color = if (item.isDestructive) destructiveColor else textColor
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(20f), ctx.dp(15f), ctx.dp(20f), ctx.dp(15f))
            isClickable = true; isFocusable = true
            background  = rippleRect(ctx)

            addView(ImageView(ctx).apply {
                setImageResource(item.iconRes)
                val s = ctx.dp(20f)
                layoutParams = LinearLayout.LayoutParams(s, s).also { it.marginEnd = ctx.dp(14f) }
                imageTintList = ColorStateList.valueOf(color)
                scaleType     = ImageView.ScaleType.FIT_CENTER
            })
            addView(TextView(ctx).apply {
                text      = item.label
                textSize  = 15f
                setTextColor(color)
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            setOnClickListener { dialog.dismiss(); item.action() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun animateIn(emojiRow: View, menuCard: View, emojiAbove: Boolean) {
        val overshoot = OvershootInterpolator(1.8f)
        val spring    = OvershootInterpolator(3.2f)
        val delay     = if (emojiAbove) 0L else 60L

        AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(menuCard, View.SCALE_X, 0.85f, 1f).apply { duration = 260; interpolator = overshoot },
                ObjectAnimator.ofFloat(menuCard, View.SCALE_Y, 0.85f, 1f).apply { duration = 260; interpolator = overshoot },
                ObjectAnimator.ofFloat(menuCard, View.ALPHA,   0f,    1f).apply { duration = 160 },
                ObjectAnimator.ofFloat(emojiRow, View.SCALE_X, 0f,    1f).apply { duration = 340; interpolator = spring; startDelay = delay },
                ObjectAnimator.ofFloat(emojiRow, View.SCALE_Y, 0f,    1f).apply { duration = 340; interpolator = spring; startDelay = delay },
                ObjectAnimator.ofFloat(emojiRow, View.ALPHA,   0f,    1f).apply { duration = 200; startDelay = delay }
            )
            start()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun hDivider(ctx: Context, color: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        setBackgroundColor(color)
    }

    private fun rippleOval(ctx: Context): RippleDrawable {
        val textColor = resolveColor(ctx, android.R.attr.textColorPrimary)
        val rippleColor = (0x28 shl 24) or (textColor and 0x00FFFFFF)
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor), null,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        )
    }

    private fun rippleRect(ctx: Context): RippleDrawable {
        val textColor = resolveColor(ctx, android.R.attr.textColorPrimary)
        val rippleColor = (0x28 shl 24) or (textColor and 0x00FFFFFF)
        return RippleDrawable(
            ColorStateList.valueOf(rippleColor), null,
            GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.WHITE) }
        )
    }

    private fun spacer(ctx: Context, h: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }
}
