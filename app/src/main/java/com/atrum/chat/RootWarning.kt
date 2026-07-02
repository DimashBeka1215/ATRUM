package com.atrum.chat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import androidx.core.content.ContextCompat

/**
 * Одноразовое (при первом заходе в чаты) окно-напоминание о наличии root-прав.
 * Показывается ТОЛЬКО если root обнаружен и окно ещё не показывали. Ни на что не влияет.
 */
object RootWarning {

    private const val URL_XDA = "https://www.xda-developers.com"
    private const val URL_4PDA = "https://4pda.to"

    fun maybeShow(activity: Activity) {
        val prefs = Prefs(activity)
        if (prefs.rootWarningShown) return
        if (!RootDetector.isRooted(activity)) return
        prefs.rootWarningShown = true
        NeonDialog.showInfoIcon(
            ctx = activity,
            iconRes = R.drawable.ic_magisk,
            title = activity.getString(R.string.root_warn_title),
            message = buildMessage(activity),
            buttonText = activity.getString(R.string.root_warn_ok)
        )
    }

    private fun buildMessage(ctx: Context): CharSequence {
        val xda = ctx.getString(R.string.root_warn_xda)
        val forpda = ctx.getString(R.string.root_warn_4pda)
        val sb = SpannableStringBuilder(ctx.getString(R.string.root_warn_body, xda, forpda))
        linkify(ctx, sb, xda, URL_XDA)
        linkify(ctx, sb, forpda, URL_4PDA)
        return sb
    }

    private fun linkify(ctx: Context, sb: SpannableStringBuilder, label: String, url: String) {
        val start = sb.toString().indexOf(label)
        if (start < 0) return
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            override fun updateDrawState(ds: TextPaint) {
                ds.color = ContextCompat.getColor(ctx, R.color.accent_light)
                ds.isUnderlineText = false
            }
        }
        sb.setSpan(span, start, start + label.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}
