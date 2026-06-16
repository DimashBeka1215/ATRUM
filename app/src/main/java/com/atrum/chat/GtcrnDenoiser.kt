package com.atrum.chat

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig

/**
 * Нейросетевое шумоподавление GTCRN (ICASSP 2024, ультра-лёгкая модель ~48K параметров)
 * через sherpa-onnx (k2-fsa). Давит и НЕстационарный шум — речь из ТВ, крики.
 *
 * Это ОФЛАЙН-денойзер: обрабатывает весь клип целиком (run(samples, rate)). Поэтому
 * запись копится в буфер, а чистка идёт один раз в конце.
 *
 * Требует:
 *   • нативные либы sherpa-onnx-jni (jniLibs) — иначе [load] вернёт null (фолбэк);
 *   • модель assets/gtcrn_simple.onnx.
 * Модель из релиза sherpa-onnx «speech-enhancement-models».
 */
class GtcrnDenoiser private constructor(private val impl: OfflineSpeechDenoiser) {

    /** Частота на выходе модели (GTCRN — 16 кГц). */
    var outputRate: Int = 16000
        private set

    /** Чистит весь клип. Вход — моно float [-1..1] на [inputRate]. Возвращает чистые сэмплы или null. */
    fun denoise(samples: FloatArray, inputRate: Int): FloatArray? = try {
        val r = impl.run(samples, inputRate)
        outputRate = r.sampleRate
        r.samples
    } catch (_: Throwable) {
        null
    }

    fun close() { runCatching { impl.release() } }

    companion object {
        private const val MODEL_ASSET = "gtcrn_simple.onnx"

        /** null — если нет нативной либы (UnsatisfiedLinkError) или модели в assets → фолбэк. */
        fun load(context: Context): GtcrnDenoiser? = try {
            // Быстрая проверка наличия модели — иначе не дёргаем нативный код зря.
            context.assets.open(MODEL_ASSET).close()
            val cfg = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = MODEL_ASSET),
                    numThreads = 1,
                    provider = "cpu"
                )
            )
            GtcrnDenoiser(OfflineSpeechDenoiser(assetManager = context.assets, config = cfg))
        } catch (_: Throwable) {
            null
        }
    }
}
