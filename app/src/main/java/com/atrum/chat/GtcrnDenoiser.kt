package com.atrum.chat

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig

/**
 * Нейросетевое шумоподавление GTCRN (ICASSP 2024) через sherpa-onnx. Давит и
 * нестационарный шум — речь из ТВ, крики. Офлайн: чистит весь клип за один run().
 *
 * Инстанс ОБЩИЙ на процесс и грузится один раз (модель тяжело инициализировать),
 * предзагрузка — в фоне из App. Если нет нативной либы или модели — остаётся null,
 * и запись идёт обычным путём (фолбэк).
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

    companion object {
        private const val MODEL_ASSET = "gtcrn_simple.onnx"

        @Volatile private var instance: GtcrnDenoiser? = null
        @Volatile private var loading = false
        @Volatile private var failed = false
        private val lock = Any()

        /** Общий инстанс или null (если ещё грузится / нет либы-модели). Триггерит фоновую загрузку. */
        fun shared(context: Context): GtcrnDenoiser? {
            instance?.let { return it }
            preload(context)
            return instance
        }

        /** Запускает фоновую загрузку модели один раз (вызывать из App.onCreate). */
        fun preload(context: Context) {
            if (instance != null || failed) return
            val appCtx = context.applicationContext
            synchronized(lock) {
                if (instance != null || failed || loading) return
                loading = true
            }
            Thread {
                val d = loadBlocking(appCtx)
                synchronized(lock) {
                    instance = d
                    if (d == null) failed = true
                    loading = false
                }
            }.apply { isDaemon = true; start() }
        }

        private fun loadBlocking(context: Context): GtcrnDenoiser? = try {
            context.assets.open(MODEL_ASSET).close() // нет модели → исключение → фолбэк
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
