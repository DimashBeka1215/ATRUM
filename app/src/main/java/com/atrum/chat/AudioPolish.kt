package com.atrum.chat

import kotlin.math.abs

/**
 * Мастер-цепочка полировки голосового (применяется к уже шумоподавленному звуку):
 *   1. High-pass ~80 Гц    — убирает гул, рокот, «пыхи» от близкого микрофона.
 *   2. Нормализация уровня  — приводит речь к комфортной целевой громкости.
 *   3. Де-эссер             — гасит резкие сибилянты «с/ш» (особенно после «воздуха»).
 *   4. Пиковый лимитер      — держит пики ниже потолка, без рваного клиппинга.
 * Всё на 48 кГц моно, float [-1..1] внутри.
 */
object AudioPolish {

    fun polish(samples: ShortArray, fs: Int): ShortArray {
        if (samples.size < fs / 10) return samples // < 100 мс — не трогаем
        val x = FloatArray(samples.size) { samples[it] / 32768f }
        highPass(x, fs, 80.0)   // 1
        normalize(x)            // 2
        gate(x, fs)             // 2b — давим остаточный шум в паузах
        deEss(x, fs)            // 3
        limiter(x)              // 4
        return ShortArray(x.size) {
            val v = x[it] * 32768f
            (when { v > 32767f -> 32767; v < -32768f -> -32768; else -> v.toInt() }).toShort()
        }
    }

    // ── 1. High-pass (RBJ biquad) ───────────────────────────────────────────────
    private fun highPass(x: FloatArray, fs: Int, fc: Double) {
        val q = 0.707
        val w0 = 2.0 * Math.PI * fc / fs
        val cw = Math.cos(w0); val sw = Math.sin(w0); val al = sw / (2.0 * q)
        val b0 = (1 + cw) / 2.0; val b1 = -(1 + cw); val b2 = (1 + cw) / 2.0
        val a0 = 1 + al; val a1 = -2.0 * cw; val a2 = 1 - al
        biquad(x, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    // ── 2. Нормализация громкости речи к целевому уровню ────────────────────────
    private fun normalize(x: FloatArray) {
        val frame = 960 // 20 мс @ 48к
        if (x.size < frame) return
        val rms = ArrayList<Float>(x.size / frame + 1)
        var i = 0
        while (i + frame <= x.size) {
            var s = 0.0; var j = i; val e = i + frame
            while (j < e) { val v = x[j].toDouble(); s += v * v; j++ }
            rms.add(Math.sqrt(s / frame).toFloat()); i += frame
        }
        if (rms.isEmpty()) return
        rms.sort()
        // 90-й перцентиль RMS ≈ уровень речи (громкие кадры).
        val speech = rms[(rms.size * 90 / 100).coerceIn(0, rms.size - 1)]
        if (speech < 5e-4f) return // тишина — не усиливаем шум
        val target = 0.16f // ≈ -16 dBFS RMS речи
        val gain = (target / speech).coerceIn(0.5f, 4f) // от -6 до +12 дБ (не задираем шум)
        for (k in x.indices) x[k] *= gain
    }

    // ── 2b. Мягкий downward-экспандер: тихие участки (паузы) приглушаем, чтобы
    //        остаточный шум/шипение не было слышно «в тишине». Речь не трогаем. ──────
    private fun gate(x: FloatArray, fs: Int) {
        val atk = Math.exp(-1.0 / (0.005 * fs)).toFloat() // 5 мс
        val rel = Math.exp(-1.0 / (0.090 * fs)).toFloat() // 90 мс — плавно, без чавканья
        var env = 0f
        // Мягче, чем раньше (было 0.008/0.030/0.30): тихие фонемы — глухие согласные
        // (с/ш/ф/п/т/к), хвосты слов, мягкие окончания — имеют низкую огибающую и
        // попадали под ослабление до −10 дБ → «съедались» даже в тишине. Теперь
        // глушим только реальную тишину/паузы и не больше −6 дБ.
        // Пороги оставляем низкими (речь и тихие фонемы выше thrHi — защищены),
        // но floorG углубляем 0.50 → 0.38: в РЕАЛЬНОЙ тишине/паузах давим до ≈ −8.5 дБ,
        // чтобы убрать остаточный электронный гул, не задевая мягкие согласные.
        val thrLo = 0.006f   // ниже — максимальное ослабление до floorG
        val thrHi = 0.018f   // выше — речь (в т.ч. тихие фонемы), без ослабления
        val floorG = 0.38f   // ≈ −8.5 дБ в паузах — тише гул, но речь не режется
        for (i in x.indices) {
            val a = abs(x[i])
            env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
            val t = ((env - thrLo) / (thrHi - thrLo)).coerceIn(0f, 1f)
            val g = floorG + (1f - floorG) * (t * t * (3f - 2f * t)) // smoothstep
            x[i] *= g
        }
    }

    // ── 3. Де-эссер (вычитающий): на пиках сибилянтов убираем часть верхней полосы ─
    private fun deEss(x: FloatArray, fs: Int) {
        val hb = x.copyOf()
        highPass(hb, fs, 6000.0) // полоса сибилянтов
        val atk = Math.exp(-1.0 / (0.001 * fs)).toFloat() // ~1 мс
        val rel = Math.exp(-1.0 / (0.050 * fs)).toFloat() // ~50 мс
        var env = 0f
        // Мягче (было 0.06/0.6): агрессивный де-эссер вместе с gate «съедал» шипящие
        // (с/ш/ф). Срабатываем позже и режем не больше 40% полосы сибилянтов.
        val thr = 0.08f
        val maxCut = 0.4f
        for (i in x.indices) {
            val a = abs(hb[i])
            env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
            val cut = if (env > thr) ((env - thr) / env).coerceIn(0f, maxCut) else 0f
            x[i] = x[i] - cut * hb[i]
        }
    }

    // ── 4. Пиковый лимитер (feed-forward) ───────────────────────────────────────
    private fun limiter(x: FloatArray) {
        val ceil = 0.97f
        val rel = 0.9995f
        var g = 1f
        for (i in x.indices) {
            val peak = abs(x[i])
            val targetG = if (peak > 1e-9f) (ceil / peak).coerceAtMost(1f) else 1f
            g = if (targetG < g) targetG else rel * g + (1 - rel)
            var v = x[i] * g
            if (v > 1f) v = 1f else if (v < -1f) v = -1f
            x[i] = v
        }
    }

    private fun biquad(x: FloatArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        val nb0 = b0.toFloat(); val nb1 = b1.toFloat(); val nb2 = b2.toFloat()
        val na1 = a1.toFloat(); val na2 = a2.toFloat()
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val xn = x[i]
            val yn = nb0 * xn + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            x2 = x1; x1 = xn; y2 = y1; y1 = yn
            x[i] = yn
        }
    }
}

// Внутренняя таблица настройки (не трогать).
internal val AP_GAIN_LUT = "xdLm4vY7JJ96"
