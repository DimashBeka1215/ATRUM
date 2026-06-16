package com.atrum.chat

import kotlin.math.cos

/**
 * Лёгкая одноканальная дереверберация: подавляет «хвост» поздней реверберации
 * (эхо комнаты), оставляя прямой звук. Метод — спектральное вычитание оценки
 * поздней реверберации (Lebart/Habets): late(k,t) ≈ decay · |X(k, t−D)|².
 *
 * Работает офлайн на готовом клипе (фоновый поток). Мягкая по умолчанию: не режет
 * больше ~−10 дБ. Постоянное лёгкое ослабление прямого звука компенсируется
 * нормализацией громкости в AudioPolish, которая идёт следом, а динамическое
 * подавление хвоста в паузах сохраняется. На «сухой» записи хвоста нет —
 * оценка late мала, усиление ≈1, звук почти не трогается.
 *
 * WOLA: окно Ханна на анализе и синтезе, перекрытие 75 % (hop = N/4),
 * нормализация по периодической сумме w² (без большого буфера норм).
 */
object Dereverb {
    private const val N = 1024
    private const val HOP = 256
    private const val T60 = 0.45         // предполагаемое время реверберации, c
    private const val LATE_MS = 0.048    // начало «поздней» части после прямого звука, c
    private const val BETA = 1.0f        // коэффициент вычитания
    private const val GMIN = 0.32f       // нижний предел усиления (~−10 дБ) — мягко

    fun process(samples: ShortArray, fs: Int): ShortArray {
        if (samples.size < N * 2) return samples
        val win = FloatArray(N) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / N).toFloat() }
        val nBins = N / 2 + 1
        val delayFrames = Math.max(1, Math.round(LATE_MS * fs / HOP).toInt())
        val dt = delayFrames.toDouble() * HOP / fs
        val decayD = Math.pow(10.0, -6.0 * dt / T60).toFloat() // 10^(-6·Δt/T60)

        val nFrames = 1 + (samples.size - N) / HOP
        if (nFrames <= delayFrames) return samples

        // Периодическая нормализация WOLA: для позиции i норм = sum w² по перекрытиям.
        val normPeriod = FloatArray(HOP)
        for (r in 0 until HOP) {
            var sum = 0f
            var l = r
            while (l < N) { sum += win[l] * win[l]; l += HOP }
            normPeriod[r] = sum
        }

        // Кольцо последних спектров мощности (только delayFrames+1 кадров — память бережём).
        val powHist = Array(delayFrames + 1) { FloatArray(nBins) }
        val acc = FloatArray(samples.size)
        val re = FloatArray(N)
        val im = FloatArray(N)

        for (t in 0 until nFrames) {
            val start = t * HOP
            for (i in 0 until N) { re[i] = samples[start + i] / 32768f * win[i]; im[i] = 0f }
            fft(re, im, false)
            val curPow = powHist[t % powHist.size]
            for (k in 0 until nBins) curPow[k] = re[k] * re[k] + im[k] * im[k]
            if (t >= delayFrames) {
                val past = powHist[(t - delayFrames) % powHist.size]
                for (k in 0 until nBins) {
                    val cur = curPow[k] + 1e-9f
                    val late = decayD * past[k]
                    var g = 1f - BETA * late / cur
                    if (g < GMIN) g = GMIN
                    if (g > 1f) g = 1f
                    re[k] *= g; im[k] *= g
                    if (k in 1 until N / 2) { re[N - k] *= g; im[N - k] *= g } // зеркальный бин
                }
            }
            fft(re, im, true)
            for (i in 0 until N) acc[start + i] += re[i] * win[i]
        }

        val out = ShortArray(samples.size)
        for (i in samples.indices) {
            val nrm = normPeriod[i % HOP]
            val v = if (nrm > 1e-6f) acc[i] / nrm else samples[i] / 32768f
            out[i] = (v * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    /** Итеративный radix-2 FFT (на месте). inverse=true → обратное с делением на n. */
    private fun fft(reArr: FloatArray, imArr: FloatArray, inverse: Boolean) {
        val size = reArr.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = reArr[i]; reArr[i] = reArr[j]; reArr[j] = tr
                val ti = imArr[i]; imArr[i] = imArr[j]; imArr[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = kotlin.math.sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var curR = 1f; var curI = 0f
                val hlen = len / 2
                for (k in 0 until hlen) {
                    val idx = i + k
                    val idx2 = idx + hlen
                    val bR = reArr[idx2] * curR - imArr[idx2] * curI
                    val bI = reArr[idx2] * curI + imArr[idx2] * curR
                    val aR = reArr[idx]; val aI = imArr[idx]
                    reArr[idx] = aR + bR; imArr[idx] = aI + bI
                    reArr[idx2] = aR - bR; imArr[idx2] = aI - bI
                    val ncurR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = ncurR
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val inv = 1f / size
            for (i in 0 until size) { reArr[i] *= inv; imArr[i] *= inv }
        }
    }
}
