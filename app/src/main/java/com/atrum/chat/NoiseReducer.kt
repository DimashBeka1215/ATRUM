package com.atrum.chat

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Программное шумоподавление методом спектрального вычитания (spectral gating) —
 * добавочная ступень поверх аппаратного шумодава. Давит ОСТАТОЧНЫЙ стационарный
 * шум (гул/шипение/ровный фон ТВ), который железо пропускает, сохраняя голос.
 *
 * Кадр 1024 отсчёта (≈21 мс при 48 кГц), 50% перекрытие, окно Ханна (COLA), WOLA-сборка.
 * Профиль шума оценивается по первым кадрам и далее адаптивно отслеживает минимум.
 * Параметры консервативные: спектральный пол держит «хвост» сигнала, чтобы голос не
 * «робтизировался» и не появлялся musical-noise.
 *
 * Только моно (наш голосовой тракт моно). Потокобезопасность не нужна — вызывается
 * из одного потока кодирования.
 */
class NoiseReducer {

    private val n = 1024
    private val hop = n / 2
    private val half = n / 2
    private val window = FloatArray(n) { 0.5f - 0.5f * cos(2.0 * Math.PI * it / n).toFloat() }

    private val re = FloatArray(n)
    private val im = FloatArray(n)

    private val pending = FloatArray(n * 4)
    private var pendingCount = 0
    private val ola = FloatArray(n)

    private val noisePow = FloatArray(half + 1)
    private val gainSmooth = FloatArray(half + 1) { 1f }
    private val gainRaw = FloatArray(half + 1) { 1f }
    private val gainFreq = FloatArray(half + 1) { 1f }
    private var frame = 0

    // ── Параметры (консервативные — приоритет качеству голоса) ──────────────────
    private val initFrames = 8        // первые ~170 мс считаем профилем шума
    private val overSub = 1.6f        // коэффициент пере-вычитания шума
    private val floorGain = 0.18f     // спектральный пол (~ -15 дБ) — не «дырявим» голос
    private val gainTimeSmooth = 0.6f // сглаживание усиления во времени (анти musical-noise)
    private val noiseRise = 1.0015f   // медленный подъём оценки шума (следит за фоном, не за речью)

    /** Обрабатывает порцию PCM-моно (16-бит). Возвращает обработанные отсчёты (может быть меньше из-за латентности). */
    fun process(input: ShortArray, len: Int): ShortArray {
        val out = ArrayList<Short>(len)
        var off = 0
        while (off < len) {
            val space = pending.size - pendingCount
            if (space <= 0) { drainInto(out); continue }
            val take = minOf(space, len - off)
            for (i in 0 until take) pending[pendingCount + i] = input[off + i] / 32768f
            pendingCount += take
            off += take
            drainInto(out)
        }
        return ShortArray(out.size) { out[it] }
    }

    /** Досчитывает «хвост» в конце записи. */
    fun flush(): ShortArray {
        val out = ArrayList<Short>()
        // Добиваем нулями до полного кадра, чтобы вытолкнуть остаток через WOLA.
        if (pendingCount in 1 until n) {
            for (i in pendingCount until n) pending[i] = 0f
            pendingCount = n
        }
        drainInto(out)
        return ShortArray(out.size) { out[it] }
    }

    private fun drainInto(out: ArrayList<Short>) {
        while (pendingCount >= n) {
            processFrame()
            for (i in 0 until hop) {
                val v = ola[i] * 32768f
                val si = when {
                    v > 32767f -> 32767
                    v < -32768f -> -32768
                    else -> v.toInt()
                }
                out.add(si.toShort())
            }
            for (i in 0 until n - hop) ola[i] = ola[i + hop]
            for (i in n - hop until n) ola[i] = 0f
            for (i in 0 until pendingCount - hop) pending[i] = pending[i + hop]
            pendingCount -= hop
        }
    }

    private fun processFrame() {
        for (i in 0 until n) { re[i] = pending[i] * window[i]; im[i] = 0f }
        fft(re, im, false)

        // 1) Сырое усиление по бинам (спектральное вычитание со спектральным полом).
        for (k in 0..half) {
            val p = re[k] * re[k] + im[k] * im[k]
            if (frame < initFrames) {
                noisePow[k] = (noisePow[k] * frame + p) / (frame + 1)
            } else {
                noisePow[k] = if (p < noisePow[k]) p else noisePow[k] * noiseRise
            }
            val sub = p - overSub * noisePow[k]
            val cleanPow = if (sub > floorGain * floorGain * p) sub else floorGain * floorGain * p
            var g = if (p > 1e-12f) sqrt(cleanPow / p) else 1f
            if (g > 1f) g = 1f
            gainRaw[k] = g
        }
        // 2) Частотное сглаживание усиления (3-точечное) — убирает бин-к-бину
        //    дребезг, главный источник «металлического»/робот-призвука.
        gainFreq[0] = gainRaw[0]; gainFreq[half] = gainRaw[half]
        for (k in 1 until half) gainFreq[k] = 0.25f * gainRaw[k - 1] + 0.5f * gainRaw[k] + 0.25f * gainRaw[k + 1]
        // 3) Временное сглаживание + применение (+ симметрия отрицательных частот).
        for (k in 0..half) {
            val g = gainTimeSmooth * gainSmooth[k] + (1f - gainTimeSmooth) * gainFreq[k]
            gainSmooth[k] = g
            re[k] *= g; im[k] *= g
            if (k in 1 until half) { re[n - k] *= g; im[n - k] *= g }
        }

        fft(re, im, true)
        for (i in 0 until n) ola[i] += re[i]
        frame++
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
