package com.atrum.chat

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas

/**
 * Снимок текущего экрана, размытый по Гауссу (итерированный box blur) — фон контекстных меню/
 * диалогов в стиле Apple. Мягкие дефолты (radius=5): очертания сзади читаются, но фон приглушён.
 *
 * Алгоритм: снимок decorView при [scale] → [passes] проходов 1D box blur (H+V), по ЦПТ ≈ Гаусс,
 * без блочности → upscale обратно (bilinear). Дёшево (~<15мс на среднем CPU при 40% масштабе).
 */
object ScreenBlur {

    /** @return размытый снимок текущего окна [ctx] или null при неудаче (не Activity/нулевой размер). */
    fun capture(ctx: Context, scale: Float = 0.40f, radius: Int = 5, passes: Int = 3): Bitmap? {
        val activity = ctx as? Activity ?: return null
        return try {
            val decor = activity.window.decorView
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return null
            // Убираем pressed-подсветку/ripple зажатого элемента из снимка: меню часто вызывается
            // долгим тапом, и без этого в размытый фон «впечатывался» хитбокс нажатого чата/сообщения.
            clearPressed(decor)
            val sw = (w * scale).toInt().coerceAtLeast(2)
            val sh = (h * scale).toInt().coerceAtLeast(2)
            val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
            Canvas(small).also { c -> c.scale(scale, scale); decor.draw(c) }
            val blurred = boxBlur(small, radius, passes)
            small.recycle()
            val result = Bitmap.createScaledBitmap(blurred, w, h, true)
            blurred.recycle()
            result
        } catch (_: Exception) {
            null
        }
    }

    /** Рекурсивно снимает pressed-состояние и «допрыгивает» drawable до конечного кадра (без ripple). */
    private fun clearPressed(v: android.view.View) {
        if (v.isPressed) v.isPressed = false
        v.jumpDrawablesToCurrentState()
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) clearPressed(v.getChildAt(i))
    }

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

    private fun boxBlurH(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            var rS = 0; var gS = 0; var bS = 0
            for (k in -r..r) {
                val p = px[base + k.coerceIn(0, w - 1)]
                rS += (p ushr 16) and 0xFF
                gS += (p ushr 8) and 0xFF
                bS += p and 0xFF
            }
            for (x in 0 until w) {
                row[x] = (0xFF shl 24) or ((rS / div) shl 16) or ((gS / div) shl 8) or (bS / div)
                val xl = (x - r).coerceAtLeast(0)
                val xr = (x + r + 1).coerceAtMost(w - 1)
                val pl = px[base + xl]; val pr = px[base + xr]
                rS += ((pr ushr 16) and 0xFF) - ((pl ushr 16) and 0xFF)
                gS += ((pr ushr 8) and 0xFF) - ((pl ushr 8) and 0xFF)
                bS += (pr and 0xFF) - (pl and 0xFF)
            }
            row.copyInto(px, base, 0, w)
        }
    }

    private fun boxBlurV(px: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        val col = IntArray(h)
        for (x in 0 until w) {
            var rS = 0; var gS = 0; var bS = 0
            for (k in -r..r) {
                val p = px[k.coerceIn(0, h - 1) * w + x]
                rS += (p ushr 16) and 0xFF
                gS += (p ushr 8) and 0xFF
                bS += p and 0xFF
            }
            for (y in 0 until h) {
                col[y] = (0xFF shl 24) or ((rS / div) shl 16) or ((gS / div) shl 8) or (bS / div)
                val yt = (y - r).coerceAtLeast(0)
                val yb = (y + r + 1).coerceAtMost(h - 1)
                val pt = px[yt * w + x]; val pb = px[yb * w + x]
                rS += ((pb ushr 16) and 0xFF) - ((pt ushr 16) and 0xFF)
                gS += ((pb ushr 8) and 0xFF) - ((pt ushr 8) and 0xFF)
                bS += (pb and 0xFF) - (pt and 0xFF)
            }
            for (y in 0 until h) px[y * w + x] = col[y]
        }
    }
}
