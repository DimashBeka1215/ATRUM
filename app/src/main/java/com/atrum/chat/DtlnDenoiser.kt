package com.atrum.chat

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Нейросетевое шумоподавление DTLN (Dual-signal Transformation LSTM Network,
 * breizhn/DTLN, MIT). В отличие от спектрального вычитания, давит и НЕстационарный
 * шум — речь из телевизора, крики, удары. Две TFLite-модели с внешним состоянием
 * LSTM: model_1 строит спектральную маску, model_2 до-чищает во времени.
 *
 * Архитектурные константы фиксированы обучением: 16 кГц, блок 512, сдвиг 128.
 * Опционально: если модели не положены в assets (или нет TFLite) — [load] вернёт
 * null, и запись пойдёт по обычному пути (спектральное вычитание на 48 кГц).
 *
 * Файлы моделей: assets/model_1.tflite и assets/model_2.tflite
 * (из репозитория breizhn/DTLN, папка pretrained_model — можно quant-версии).
 */
class DtlnDenoiser private constructor(
    private val interp1: Interpreter,
    private val interp2: Interpreter
) {
    private val blockLen = 512
    private val blockShift = 128
    private val bins = blockLen / 2 + 1   // 257

    private val inBuffer = FloatArray(blockLen)
    private val outBuffer = FloatArray(blockLen)
    private val hopBuf = FloatArray(blockShift)
    private var hopFill = 0

    private val re = FloatArray(blockLen)
    private val im = FloatArray(blockLen)
    private val mag = FloatArray(bins)
    private val phase = FloatArray(bins)

    private val inMagBuf = direct(bins * 4)
    private val maskBuf = direct(bins * 4)
    private val estBuf = direct(blockLen * 4)
    private val outBlockBuf = direct(blockLen * 4)
    private val states1In = direct(interp1.getInputTensor(1).numBytes())
    private val states1Out = direct(interp1.getOutputTensor(1).numBytes())
    private val states2In = direct(interp2.getInputTensor(1).numBytes())
    private val states2Out = direct(interp2.getOutputTensor(1).numBytes())

    init { zero(states1In); zero(states2In) }

    /** 16 кГц — обязательная частота модели. Подавать сюда нужно моно 16 кГц PCM. */
    fun process(input: ShortArray, len: Int): ShortArray {
        val out = ArrayList<Short>(len)
        var i = 0
        while (i < len) {
            hopBuf[hopFill++] = input[i] / 32768f
            i++
            if (hopFill == blockShift) { processBlock(out); hopFill = 0 }
        }
        return ShortArray(out.size) { out[it] }
    }

    fun flush(): ShortArray {
        val out = ArrayList<Short>()
        if (hopFill > 0) {
            for (k in hopFill until blockShift) hopBuf[k] = 0f
            processBlock(out); hopFill = 0
        }
        repeat((blockLen - blockShift) / blockShift) {
            for (k in 0 until blockShift) hopBuf[k] = 0f
            processBlock(out)
        }
        return ShortArray(out.size) { out[it] }
    }

    fun close() { runCatching { interp1.close() }; runCatching { interp2.close() } }

    private fun processBlock(out: ArrayList<Short>) {
        System.arraycopy(inBuffer, blockShift, inBuffer, 0, blockLen - blockShift)
        System.arraycopy(hopBuf, 0, inBuffer, blockLen - blockShift, blockShift)

        for (k in 0 until blockLen) { re[k] = inBuffer[k]; im[k] = 0f }
        fft(re, im, false)
        for (k in 0 until bins) { mag[k] = hypot(re[k], im[k]); phase[k] = atan2(im[k], re[k]) }

        inMagBuf.rewind(); for (k in 0 until bins) inMagBuf.putFloat(mag[k]); inMagBuf.rewind()
        states1In.rewind(); maskBuf.rewind(); states1Out.rewind()
        interp1.runForMultipleInputsOutputs(arrayOf(inMagBuf, states1In), mapOf(0 to maskBuf, 1 to states1Out))
        copyBuf(states1Out, states1In)

        maskBuf.rewind()
        for (k in 0 until bins) {
            val m = mag[k] * maskBuf.getFloat()
            re[k] = m * cos(phase[k]); im[k] = m * sin(phase[k])
        }
        for (k in 1 until bins - 1) { re[blockLen - k] = re[k]; im[blockLen - k] = -im[k] }
        fft(re, im, true)

        estBuf.rewind(); for (k in 0 until blockLen) estBuf.putFloat(re[k]); estBuf.rewind()
        states2In.rewind(); outBlockBuf.rewind(); states2Out.rewind()
        interp2.runForMultipleInputsOutputs(arrayOf(estBuf, states2In), mapOf(0 to outBlockBuf, 1 to states2Out))
        copyBuf(states2Out, states2In)

        System.arraycopy(outBuffer, blockShift, outBuffer, 0, blockLen - blockShift)
        for (k in blockLen - blockShift until blockLen) outBuffer[k] = 0f
        outBlockBuf.rewind()
        for (k in 0 until blockLen) outBuffer[k] += outBlockBuf.getFloat()

        for (k in 0 until blockShift) {
            val v = outBuffer[k] * 32768f
            val s = when { v > 32767f -> 32767; v < -32768f -> -32768; else -> v.toInt() }
            out.add(s.toShort())
        }
    }

    private fun copyBuf(src: ByteBuffer, dst: ByteBuffer) {
        src.rewind(); dst.rewind(); dst.put(src); dst.rewind(); src.rewind()
    }

    private fun zero(b: ByteBuffer) { b.rewind(); while (b.hasRemaining()) b.put(0); b.rewind() }

    private fun fft(reA: FloatArray, imA: FloatArray, inverse: Boolean) {
        val size = reA.size
        var j = 0
        for (i in 1 until size) {
            var bit = size shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = reA[i]; reA[i] = reA[j]; reA[j] = tr
                val ti = imA[i]; imA[i] = imA[j]; imA[j] = ti
            }
        }
        var len = 2
        while (len <= size) {
            val ang = (if (inverse) 2.0 else -2.0) * Math.PI / len
            val wr = cos(ang).toFloat(); val wi = sin(ang).toFloat()
            var i = 0
            while (i < size) {
                var curR = 1f; var curI = 0f
                val hlen = len / 2
                for (k in 0 until hlen) {
                    val a = i + k; val b = a + hlen
                    val bR = reA[b] * curR - imA[b] * curI
                    val bI = reA[b] * curI + imA[b] * curR
                    val aR = reA[a]; val aI = imA[a]
                    reA[a] = aR + bR; imA[a] = aI + bI
                    reA[b] = aR - bR; imA[b] = aI - bI
                    val nR = curR * wr - curI * wi
                    curI = curR * wi + curI * wr
                    curR = nR
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) { val inv = 1f / size; for (i in 0 until size) { reA[i] *= inv; imA[i] *= inv } }
    }

    companion object {
        private fun direct(bytes: Int): ByteBuffer =
            ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        /** Загружает модели из assets. null — если их нет или TFLite недоступен (→ фолбэк). */
        fun load(context: Context): DtlnDenoiser? = try {
            val o = Interpreter.Options()
            val i1 = Interpreter(mapAsset(context, "model_1.tflite"), o)
            val i2 = Interpreter(mapAsset(context, "model_2.tflite"), o)
            DtlnDenoiser(i1, i2)
        } catch (_: Throwable) {
            null
        }

        private fun mapAsset(context: Context, name: String): ByteBuffer {
            context.assets.openFd(name).use { afd ->
                FileInputStream(afd.fileDescriptor).channel.use { ch ->
                    return ch.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
                }
            }
        }
    }
}
