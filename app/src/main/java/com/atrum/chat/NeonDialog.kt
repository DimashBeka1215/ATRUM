package com.atrum.chat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Фабрика диалогов в стиле Neon:
 *
 *   - Фон поверхности из темы (@color/surface)
 *   - Тонкая фиолетовая рамка (#A855F7, 1dp)
 *   - Большие скругления (20dp)
 *   - Ширина 85% экрана, высота — по контенту
 *   - Только текст, никаких иконок
 *   - Обычные действия — text_primary
 *   - Деструктивные действия — фиолетовый акцент
 *   - Disabled пункты — text_tertiary
 *
 * ⚠️  НЕ используется для: security-предупреждений, warning-диалогов
 *      о потере данных, alert-сообщений — там остаётся стандартный стиль.
 */
object NeonDialog {

    // ── Palette (theme-invariant) ─────────────────────────────────────────────

    private const val BORDER           = 0xFFA855F7.toInt()   // purple — same in both themes
    private const val TEXT_DESTRUCTIVE = 0xFFA855F7.toInt()   // purple — delete / danger

    // ── Theme-aware color helpers ─────────────────────────────────────────────

    private fun Context.bgColor()          = ContextCompat.getColor(this, R.color.surface)
    private fun Context.textPrimary()      = ContextCompat.getColor(this, R.color.text_primary)
    private fun Context.textSecondary()    = ContextCompat.getColor(this, R.color.text_secondary)
    private fun Context.textTertiary()     = ContextCompat.getColor(this, R.color.text_tertiary)
    private fun Context.borderColor()      = ContextCompat.getColor(this, R.color.border)
    private fun Context.surfaceElevated()  = ContextCompat.getColor(this, R.color.surface_elevated)

    // ── dp helpers ────────────────────────────────────────────────────────────

    private fun Context.dp(v: Float) =
        (v * resources.displayMetrics.density + 0.5f).toInt()

    // ── Reusable primitives ───────────────────────────────────────────────────

    /** Фоновый drawable: поверхность с фиолетовой рамкой и скруглениями 20dp. */
    fun Context.neonBg() = GradientDrawable().apply {
        shape      = GradientDrawable.RECTANGLE
        setColor(bgColor())
        setStroke(dp(1f), BORDER)
        cornerRadius = dp(20f).toFloat()
    }

    /** Горизонтальная разделительная полоска. */
    private fun Context.hDivider() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1
        )
        setBackgroundColor(borderColor())
    }

    /** Вертикальная разделительная полоска (между кнопками в ряд). */
    private fun Context.vDivider() = android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT)
        setBackgroundColor(borderColor())
    }

    /** Стандартный ripple-background для кликабельных элементов. */
    private fun Context.rippleBg(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        return tv.resourceId
    }

    // ── Window setup ──────────────────────────────────────────────────────────

    private fun Dialog.setupWindow(ctx: Context) {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.85f).toInt()
            setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Контекстное меню — вертикальный список текстовых пунктов.
     *
     * Использовать для: long-press на сообщении, long-press на чате.
     *
     * @param title       необязательный заголовок (имя собеседника / первая строка сообщения)
     * @param items       пункты меню
     * @param cancellable можно ли закрыть тапом вне окна
     */
    fun showMenu(
        ctx: Context,
        title: String? = null,
        items: List<Item>,
        cancellable: Boolean = true
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(cancellable)
        dialog.setCanceledOnTouchOutside(cancellable)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background  = ctx.neonBg()
            clipToOutline = true
        }

        // ── Заголовок ─────────────────────────────────────────────────────────
        if (title != null) {
            root.addView(TextView(ctx).apply {
                text = title
                setTextColor(ctx.textPrimary())
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                val h = ctx.dp(20f); val v = ctx.dp(18f)
                setPadding(h, v, h, ctx.dp(14f))
                maxLines  = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            root.addView(ctx.hDivider())
        }

        // ── Пункты ───────────────────────────────────────────────────────────
        items.forEachIndexed { i, item ->
            val color = when {
                item.isDestructive -> TEXT_DESTRUCTIVE
                item.isDisabled    -> ctx.textTertiary()
                else               -> ctx.textPrimary()
            }
            root.addView(TextView(ctx).apply {
                text = item.label
                setTextColor(color)
                textSize = 15f
                val h = ctx.dp(20f); val v = ctx.dp(15f)
                setPadding(h, v, h, v)
                // Всегда вешаем клик — визуально disabled, но action выполняется
                setBackgroundResource(ctx.rippleBg())
                setOnClickListener { dialog.dismiss(); item.action() }
            })
            if (i < items.lastIndex) root.addView(ctx.hDivider())
        }

        // нижний зазор
        root.addView(android.view.View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(8f)
            )
        })

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        dialog.show()
        return dialog
    }

    /**
     * Диалог подтверждения с двумя кнопками.
     *
     * Использовать для: удаление сообщения, удаление чата.
     */
    fun showConfirm(
        ctx: Context,
        title: String,
        message: String? = null,
        positiveText: String,
        positiveIsDestructive: Boolean = false,
        negativeText: String,
        onNegative: (() -> Unit)? = null,
        onPositive: () -> Unit
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(ctx).apply {
            orientation   = LinearLayout.VERTICAL
            background    = ctx.neonBg()
            clipToOutline = true
        }

        // Заголовок
        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(ctx.textPrimary())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(22f), h, ctx.dp(10f))
        })

        // Сообщение
        if (message != null) {
            root.addView(TextView(ctx).apply {
                text = message
                setTextColor(ctx.textSecondary())
                textSize = 14f
                val h = ctx.dp(20f)
                setPadding(h, ctx.dp(2f), h, ctx.dp(18f))
                setLineSpacing(ctx.dp(3f).toFloat(), 1f)
            })
        } else {
            root.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(12f)
                )
            })
        }

        root.addView(ctx.hDivider())

        // Кнопки
        root.addView(LinearLayout(ctx).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52f)
            )
            addView(TextView(ctx).apply {
                text = negativeText
                setTextColor(ctx.textSecondary())
                textSize = 15f
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(ctx.rippleBg())
                setOnClickListener { dialog.dismiss(); onNegative?.invoke() }
            })
            addView(ctx.vDivider())
            addView(TextView(ctx).apply {
                text = positiveText
                setTextColor(if (positiveIsDestructive) TEXT_DESTRUCTIVE else ctx.textPrimary())
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(ctx.rippleBg())
                setOnClickListener { dialog.dismiss(); onPositive() }
            })
        })

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        dialog.show()
        return dialog
    }

    /**
     * Диалог выбора с ТРЕМЯ вертикальными кнопками: иконка + заголовок + текст +
     * primary (залит акцентом) / neutral (контур) / destructive (красный текст) + сноска.
     * Отмена (тап вне/назад) — ничего не делает.
     */
    fun showThreeChoice(
        ctx: Context,
        title: String,
        message: String? = null,
        iconRes: Int = 0,
        primaryText: String,
        onPrimary: () -> Unit,
        neutralText: String,
        onNeutral: () -> Unit,
        destructiveText: String,
        onDestructive: () -> Unit,
        footnote: String? = null
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val pad = ctx.dp(20f)
        val root = LinearLayout(ctx).apply {
            orientation   = LinearLayout.VERTICAL
            background    = ctx.neonBg()
            clipToOutline = true
            setPadding(pad, ctx.dp(20f), pad, ctx.dp(18f))
        }

        if (iconRes != 0) {
            root.addView(android.widget.ImageView(ctx).apply {
                setImageResource(iconRes)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    ContextCompat.getColor(ctx, R.color.accent))
                val s = ctx.dp(40f)
                layoutParams = LinearLayout.LayoutParams(s, s).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = ctx.dp(12f)
                }
            })
        }

        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(ctx.textPrimary())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })

        if (message != null) {
            root.addView(TextView(ctx).apply {
                text = message
                setTextColor(ctx.textSecondary())
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(8f), 0, 0)
                setLineSpacing(ctx.dp(3f).toFloat(), 1f)
            })
        }

        fun btnBg(fill: Int?, stroke: Int?) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dp(11f).toFloat()
            setColor(fill ?: Color.TRANSPARENT)
            if (stroke != null) setStroke(ctx.dp(1f), stroke)
        }
        fun addButton(text: String, bg: GradientDrawable, color: Int, bold: Boolean, action: () -> Unit) {
            root.addView(TextView(ctx).apply {
                this.text = text
                setTextColor(color)
                textSize = 14.5f
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
                background = bg
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(44f)
                ).apply { topMargin = ctx.dp(if (root.childCount == 0) 14f else 9f) }
                setOnClickListener { dialog.dismiss(); action() }
            })
        }

        val accent = ContextCompat.getColor(ctx, R.color.accent)
        val white  = ContextCompat.getColor(ctx, R.color.white)
        val error  = ContextCompat.getColor(ctx, R.color.error)
        addButton(primaryText, btnBg(accent, null), white, true, onPrimary)
        addButton(neutralText, btnBg(null, ctx.borderColor()), ctx.textPrimary(), false, onNeutral)
        addButton(destructiveText, btnBg(null, null), error, false, onDestructive)

        if (footnote != null) {
            root.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1
                ).apply { topMargin = ctx.dp(14f) }
                setBackgroundColor(ctx.borderColor())
            })
            root.addView(TextView(ctx).apply {
                text = footnote
                setTextColor(ctx.textSecondary())
                textSize = 11.5f
                setPadding(0, ctx.dp(12f), 0, 0)
                setLineSpacing(ctx.dp(2f).toFloat(), 1f)
            })
        }

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        dialog.show()
        return dialog
    }

    /**
     * Диалог редактирования текста: заголовок + EditText + две кнопки.
     *
     * Использовать для: редактирование сообщения.
     */
    fun showEdit(
        ctx: Context,
        title: String,
        initialText: String,
        positiveText: String,
        negativeText: String,
        subtitle: String? = null,
        isPassword: Boolean = false,
        onPositive: (String) -> Unit
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)

        val root = LinearLayout(ctx).apply {
            orientation   = LinearLayout.VERTICAL
            background    = ctx.neonBg()
            clipToOutline = true
        }

        // Заголовок
        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(ctx.textPrimary())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(22f), h, if (subtitle != null) ctx.dp(6f) else ctx.dp(14f))
        })

        // Подзаголовок (опциональный)
        if (subtitle != null) {
            root.addView(TextView(ctx).apply {
                text = subtitle
                setTextColor(ctx.textSecondary())
                textSize = 13f
                val h = ctx.dp(20f)
                setPadding(h, 0, h, ctx.dp(14f))
                setLineSpacing(0f, 1.4f)
            })
        }

        // EditText в стиле neon
        val input = EditText(ctx).apply {
            setText(initialText)
            setSelection(initialText.length)
            setTextColor(ctx.textPrimary())
            setHintTextColor(ctx.textTertiary())
            textSize  = 15f
            if (isPassword) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                transformationMethod = PasswordTransformationMethod.getInstance()
            } else {
                setSingleLine(false)
                maxLines = 6
            }
            imeOptions = EditorInfo.IME_ACTION_DONE
            background = GradientDrawable().apply {
                shape        = GradientDrawable.RECTANGLE
                setColor(ctx.surfaceElevated())    // theme-aware input field bg
                setStroke(ctx.dp(1f), 0x44A855F7.toInt())  // полупрозрачный purple
                cornerRadius = ctx.dp(10f).toFloat()
            }
            val p = ctx.dp(12f)
            setPadding(p, p, p, p)
        }
        root.addView(FrameLayout(ctx).apply {
            val h = ctx.dp(20f)
            setPadding(h, 0, h, ctx.dp(16f))
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        })

        root.addView(ctx.hDivider())

        // Кнопки
        root.addView(LinearLayout(ctx).apply {
            orientation  = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52f)
            )
            addView(TextView(ctx).apply {
                text = negativeText
                setTextColor(ctx.textSecondary())
                textSize = 15f
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(ctx.rippleBg())
                setOnClickListener { dialog.dismiss() }
            })
            addView(ctx.vDivider())
            addView(TextView(ctx).apply {
                text = positiveText
                setTextColor(ctx.textPrimary())
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                gravity  = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(ctx.rippleBg())
                setOnClickListener {
                    val newText = input.text.toString().trim()
                    dialog.dismiss()
                    onPositive(newText)
                }
            })
        })

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        // Клавиатура появляется автоматически при открытии
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.show()
        input.requestFocus()
        return dialog
    }

    /**
     * Информационный диалог: заголовок + длинный текст + одна кнопка.
     *
     * Использовать для: changelog, about, справка.
     */
    fun showInfo(
        ctx: Context,
        title: String,
        message: String,
        buttonText: String = "OK",
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(ctx).apply {
            orientation   = LinearLayout.VERTICAL
            background    = ctx.neonBg()
            clipToOutline = true
        }

        // Заголовок
        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(ctx.textPrimary())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(22f), h, ctx.dp(10f))
        })

        // Текст (прокручиваемый если длинный)
        val scroll = android.widget.ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ).also { it.weight = 0f }
            isVerticalScrollBarEnabled = false
            val maxH = (ctx.resources.displayMetrics.heightPixels * 0.5f).toInt()
            // ограничиваем высоту через post
        }
        val msgView = TextView(ctx).apply {
            text = message
            setTextColor(ctx.textSecondary())
            textSize = 13.5f
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(4f), h, ctx.dp(20f))
            setLineSpacing(0f, 1.45f)
        }
        scroll.addView(msgView)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        root.addView(ctx.hDivider())

        // Кнопка OK
        root.addView(TextView(ctx).apply {
            text = buttonText
            setTextColor(ctx.textPrimary())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52f)
            )
            setBackgroundResource(ctx.rippleBg())
            setOnClickListener { dialog.dismiss() }
        })

        if (onDismiss != null) {
            dialog.setOnDismissListener { onDismiss() }
        }

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        dialog.show()
        return dialog
    }

    /**
     * Инфо-диалог с крупной иконкой сверху по центру. Сообщение — CharSequence, поэтому
     * поддерживает кликабельные ссылки (ClickableSpan + LinkMovementMethod).
     */
    fun showInfoIcon(
        ctx: Context,
        iconRes: Int,
        title: String,
        message: CharSequence,
        buttonText: String = "OK",
        onDismiss: (() -> Unit)? = null
    ): Dialog {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val root = LinearLayout(ctx).apply {
            orientation   = LinearLayout.VERTICAL
            background    = ctx.neonBg()
            clipToOutline = true
        }

        // Иконка сверху по центру
        root.addView(android.widget.ImageView(ctx).apply {
            setImageResource(iconRes)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(66f), ctx.dp(66f)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = ctx.dp(24f)
            }
        })

        // Заголовок (по центру)
        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(ctx.textPrimary())
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(12f), h, ctx.dp(8f))
        })

        // Текст (прокручиваемый, с кликабельными ссылками)
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(TextView(ctx).apply {
            text = message
            setTextColor(ctx.textSecondary())
            textSize = 13.5f
            val h = ctx.dp(20f)
            setPadding(h, ctx.dp(4f), h, ctx.dp(20f))
            setLineSpacing(0f, 1.45f)
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = android.graphics.Color.TRANSPARENT
        })
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Ограничиваем высоту прокрутки, чтобы кнопка всегда была видна.
        val maxH = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
        scroll.post {
            if (scroll.height > maxH) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxH }
                scroll.requestLayout()
            }
        }

        root.addView(ctx.hDivider())

        // Кнопка
        root.addView(TextView(ctx).apply {
            text = buttonText
            setTextColor(ctx.textPrimary())
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(52f))
            setBackgroundResource(ctx.rippleBg())
            setOnClickListener { dialog.dismiss() }
        })

        if (onDismiss != null) dialog.setOnDismissListener { onDismiss() }

        dialog.setContentView(root)
        dialog.setupWindow(ctx)
        dialog.show()
        return dialog
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    /**
     * Пункт контекстного меню.
     *
     * @param label         текст пункта
     * @param isDestructive если true — фиолетовый цвет текста (delete, danger)
     * @param isDisabled    если true — 30% opacity (визуально недоступен, но action всё равно вызывается)
     * @param action        действие при нажатии
     */
    data class Item(
        val label: String,
        val isDestructive: Boolean = false,
        val isDisabled: Boolean = false,
        val action: () -> Unit = {}
    )
}
