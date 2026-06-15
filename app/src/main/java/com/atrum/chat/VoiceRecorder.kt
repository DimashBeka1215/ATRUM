package com.atrum.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

/**
 * Запись голосового сообщения в компактный AAC/M4A (моно, ~24 кбит/с).
 * Один экземпляр — одна запись. Потокобезопасно для UI-потока (start/stop там).
 */
class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var startedAt: Long = 0L

    /** Идёт ли запись прямо сейчас. */
    val isRecording: Boolean get() = recorder != null

    /**
     * Начинает запись во временный файл. Возвращает true при успехе.
     * Бросать исключения наружу не будем — звук может быть занят/нет разрешения.
     */
    fun start(): Boolean {
        if (recorder != null) return false
        val dir = File(context.cacheDir, "voice_rec").apply { mkdirs() }
        val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioChannels(1)
            rec.setAudioSamplingRate(44100)
            rec.setAudioEncodingBitRate(24000)
            rec.setOutputFile(f.absolutePath)
            rec.prepare()
            rec.start()
            recorder = rec
            outFile = f
            startedAt = System.currentTimeMillis()
            true
        } catch (_: Exception) {
            runCatching { rec.release() }
            runCatching { f.delete() }
            recorder = null
            outFile = null
            false
        }
    }

    /** Текущая длительность записи в миллисекундах. */
    fun elapsedMs(): Long = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt

    /** Текущая амплитуда (0..1) для индикатора. 0 если не пишем. */
    fun amplitude(): Float {
        val r = recorder ?: return 0f
        return try { (r.maxAmplitude.coerceIn(0, 32767)) / 32767f } catch (_: Exception) { 0f }
    }

    /**
     * Останавливает запись и возвращает готовый файл (или null при ошибке/слишком короткой).
     * @param minMs минимальная длительность; короче — считаем случайным нажатием и отбрасываем.
     */
    fun stop(minMs: Long = 700L): Pair<File, Long>? {
        val rec = recorder ?: return null
        val f = outFile
        val dur = elapsedMs()
        recorder = null
        outFile = null
        startedAt = 0L
        return try {
            rec.stop()
            rec.release()
            if (f != null && f.exists() && dur >= minMs) f to dur
            else { runCatching { f?.delete() }; null }
        } catch (_: Exception) {
            runCatching { rec.release() }
            runCatching { f?.delete() }
            null
        }
    }

    /** Прерывает запись и удаляет файл (отмена). */
    fun cancel() {
        val rec = recorder
        val f = outFile
        recorder = null
        outFile = null
        startedAt = 0L
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        runCatching { f?.delete() }
    }
}
