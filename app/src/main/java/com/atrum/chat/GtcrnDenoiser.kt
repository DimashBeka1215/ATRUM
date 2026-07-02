package com.atrum.chat

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig

/**
 * Нейросетевое шумоподавление через sherpa-onnx. Модель — DeepFilterNet (DPDFNet2,
 * полнополосный 48 кГц): давит и нестационарный шум (речь из ТВ, крики), бережно
 * сохраняя голос за счёт «глубокой фильтрации» гармоник. Офлайн: чистит весь клип.
 *
 * Имя класса историческое (раньше был GTCRN) — теперь это DeepFilterNet.
 * Инстанс ОБЩИЙ на процесс, грузится один раз в фоне (App.preload). Нет нативной
 * либы или модели в assets → остаётся null, запись идёт обычным путём (фолбэк).
 */
class GtcrnDenoiser private constructor(private val impl: OfflineSpeechDenoiser) {

    /** Частота на выходе модели (DeepFilterNet — 48 кГц). */
    var outputRate: Int = 48000
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
        /** DeepFilterNet2, 48 кГц (из релиза sherpa-onnx «speech-enhancement-models»). */
        private const val MODEL_ASSET = "dpdfnet2_48khz_hr.onnx"

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

        /**
         * Как [shared], но ЖДЁТ окончания фоновой загрузки до [timeoutMs] (модель ~10 МБ,
         * грузится секунды). Вызывать ТОЛЬКО из фонового потока (например при финализации
         * записи голоса) — там блокировка допустима. Возвращает инстанс или null, если
         * загрузка не удалась (failed) либо не успела за таймаут.
         */
        fun awaitShared(context: Context, timeoutMs: Long = 6000L): GtcrnDenoiser? {
            instance?.let { return it }
            if (failed) return null
            preload(context)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                instance?.let { return it }
                if (failed) return null
                try { Thread.sleep(50) } catch (_: InterruptedException) { return instance }
            }
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
                    dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig(model = MODEL_ASSET),
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
