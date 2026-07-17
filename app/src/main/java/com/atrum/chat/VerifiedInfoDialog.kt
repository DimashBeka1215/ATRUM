package com.atrum.chat

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

/**
 * Окно-объяснение при тапе по значку верификации: «Разработчик ATRUM». Кастомный диалог в
 * стиле ATRUM (прозрачное окно + карточка-surface, §10 DESIGN — не системный AlertDialog).
 * Текст всегда один: галочка показывается только для ключа владельца, значит любое её
 * нажатие ведёт сюда.
 */
object VerifiedInfoDialog {

    fun show(ctx: Context) {
        val dialog = Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_verified_info, null)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val w = (ctx.resources.displayMetrics.widthPixels * 0.86f).toInt()
            setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        view.findViewById<View>(R.id.btn_verified_ok).setOnClickListener { dialog.dismiss() }

        // Значок-«герой» появляется с анимацией (pop + блик) при открытии окна. Клик по нему
        // отключаем — иначе тап открыл бы это же окно повторно.
        view.findViewById<VerifiedBadgeView>(R.id.verified_hero).apply {
            isClickable = false
            setOnClickListener(null)
            setVerified(true, animate = true)
        }

        dialog.show()
    }
}
