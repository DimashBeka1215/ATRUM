package com.atrum.chat

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.io.File

/**
 * Запись голосового сообщения в AAC/M4A с акцентом на качество и чистоту голоса.
 *
 * Основной путь — AudioRecord (48 кГц, стерео если поддерживается железом) + аппаратные
 * аудиоэффекты на сессии записи:
 *   • NoiseSuppressor      — подавление фонового шума (шумодав);
 *   • AutomaticGainControl — авто-усиление тихого голоса;
 *   • AcousticEchoCanceler — устранение эха.
 * Кодирование AAC-LC 128 кбит/с через MediaCodec + MediaMuxer.
 *
 * Запасной путь — MediaRecorder с источником VOICE_COMMUNICATION (встроенная голосовая
 * пред-обработка с шумоподавлением), если продвинутый путь не поднялся на устройстве.
 *
 * Публичный API (start/stop/cancel/elapsedMs/amplitude/isRecording) не менялся.
 */
class VoiceRecorder(context: Context) {

    private val appCtx = context.applicationContext

    @Volatile private var recording = false
    @Volatile private var paused = false
    @Volatile private var startedAt = 0L
    private var pauseStartedAt = 0L
    private var pausedAccumMs = 0L
    @Volatile private var lastAmp = 0f
    private var outFile: File? = null

    // Продвинутый путь.
    private var audioRecord: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var reducer: NoiseReducer? = null
    private var worker: Thread? = null

    // Запасной путь.
    private var mediaRecorder: MediaRecorder? = null
    private var usingFallback = false

    val isRecording: Boolean get() = recording
    val isPaused: Boolean get() = paused

    /** Ставит запись на паузу: захват звука замораживается, файл остаётся цельным. */
    fun pause() {
        if (!recording || paused) return
        paused = true
        pauseStartedAt = System.currentTimeMillis()
        if (usingFallback) runCatching { mediaRecorder?.pause() }
    }

    /** Продолжает запись с того же места. */
    fun resume() {
        if (!recording || !paused) return
        if (pauseStartedAt > 0L) pausedAccumMs += System.currentTimeMillis() - pauseStartedAt
        pauseStartedAt = 0L
        paused = false
        if (usingFallback) runCatching { mediaRecorder?.resume() }
    }

    fun start(): Boolean {
        if (recording) return false
        val dir = File(appCtx.cacheDir, "voice_rec").apply { mkdirs() }
        val f = File(dir, "rec_${System.currentTimeMillis()}.m4a")
        outFile = f
        paused = false; pauseStartedAt = 0L; pausedAccumMs = 0L
        if (startAdvanced(f)) {
            recording = true; startedAt = System.currentTimeMillis(); return true
        }
        cleanupAdvanced()
        if (startFallback(f)) {
            usingFallback = true; recording = true; startedAt = System.currentTimeMillis(); return true
        }
        outFile = null
        return false
    }

    fun elapsedMs(): Long {
        if (startedAt == 0L) return 0L
        val extra = if (paused && pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
        return System.currentTimeMillis() - startedAt - pausedAccumMs - extra
    }

    fun amplitude(): Float {
        if (usingFallback) {
            val r = mediaRecorder ?: return 0f
            return try { (r.maxAmplitude.coerceIn(0, 32767)) / 32767f } catch (_: Exception) { 0f }
        }
        return lastAmp
    }

    fun stop(minMs: Long = 700L): Pair<File, Long>? {
        if (!recording) return null
        val dur = elapsedMs()
        recording = false
        val f = outFile
        finishCommon()
        startedAt = 0L; paused = false; pauseStartedAt = 0L; pausedAccumMs = 0L; usingFallback = false; outFile = null
        return if (f != null && f.exists() && f.length() > 0 && dur >= minMs) f to dur
        else { runCatching { f?.delete() }; null }
    }

    fun cancel() {
        recording = false
        val f = outFile
        finishCommon()
        startedAt = 0L; paused = false; pauseStartedAt = 0L; pausedAccumMs = 0L; usingFallback = false; outFile = null
        runCatching { f?.delete() }
    }

    private fun finishCommon() {
        mediaRecorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        mediaRecorder = null
        worker?.let { t -> runCatching { t.join(2500) } }
        worker = null
        cleanupAdvanced()
    }

    // ── Продвинутый путь: AudioRecord + эффекты + AAC ───────────────────────────
    private fun startAdvanced(f: File): Boolean {
        try {
            val sampleRate = 48_000
            // VOICE_COMMUNICATION = аппаратный голосовой тракт связи с агрессивным
            // шумоподавлением и AEC/AGC — давит фон СИЛЬНО и независимо от того, доступен
            // ли отдельный эффект NoiseSuppressor (на части устройств его нет вообще).
            // Тракт моно: стерео сознательно уступаем ради максимального шумодава.
            val configs = listOf(
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.CHANNEL_IN_MONO, 1),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.CHANNEL_IN_MONO, 1)
            )
            var found: AudioRecord? = null
            var channelCount = 1
            var bufSize = 0
            for ((source, mask, ch) in configs) {
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val bs = maxOf(minBuf * 2, 8192)
                val r = try {
                    AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bs)
                } catch (_: Throwable) { null }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                    found = r; channelCount = ch; bufSize = bs; break
                }
                runCatching { r?.release() }
            }
            val rec = found ?: return false
            audioRecord = rec
            attachEffects(rec.audioSessionId)
            // Программное доп. шумоподавление (спектральное вычитание) только для моно.
            reducer = if (channelCount == 1) NoiseReducer() else null

            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufSize)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            codec = enc
            muxer = MediaMuxer(f.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false

            recording = true
            worker = Thread { encodeLoop(rec, enc, sampleRate, channelCount, bufSize) }.apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun attachEffects(sessionId: Int) {
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.also { it.enabled = true; effects.add(it) } }
        runCatching { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.also { it.enabled = true; effects.add(it) } }
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; effects.add(it) } }
    }

    private fun encodeLoop(rec: AudioRecord, enc: MediaCodec, sampleRate: Int, channelCount: Int, bufSize: Int) {
        val info = MediaCodec.BufferInfo()
        var trackIndex = -1
        var muxerStarted = false
        var totalBytes = 0L
        val pcm = ByteArray(bufSize)
        val bytesPerFrame = 2 * channelCount

        fun ptsUs(): Long = totalBytes * 1_000_000L / (sampleRate.toLong() * bytesPerFrame)

        fun drain(endOfStream: Boolean) {
            val m = muxer ?: return
            while (true) {
                val outIdx = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!endOfStream) return }
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = m.addTrack(enc.outputFormat)
                            m.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = enc.getOutputBuffer(outIdx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            runCatching { m.writeSampleData(trackIndex, outBuf, info) }
                        }
                        enc.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        fun feed(data: ByteArray, len: Int) {
            var off = 0
            while (off < len) {
                val inIdx = enc.dequeueInputBuffer(10_000)
                if (inIdx < 0) { drain(false); continue }
                val inBuf = enc.getInputBuffer(inIdx)
                if (inBuf == null) { enc.queueInputBuffer(inIdx, 0, 0, ptsUs(), 0); continue }
                val cap = inBuf.remaining()
                val nn = minOf(len - off, cap)
                inBuf.clear(); inBuf.put(data, off, nn)
                enc.queueInputBuffer(inIdx, 0, nn, ptsUs(), 0)
                totalBytes += nn
                off += nn
            }
        }

        try {
            val nr = reducer
            while (recording) {
                val read = rec.read(pcm, 0, pcm.size)
                if (read > 0) {
                    if (paused) continue // звук на паузе отбрасываем — файл без «дыры»
                    lastAmp = amplitudeOf(pcm, read)
                    if (nr != null) {
                        val processed = runCatching { nr.process(bytesToShorts(pcm, read), read / 2) }.getOrNull()
                        if (processed != null) {
                            if (processed.isNotEmpty()) feed(shortsToBytes(processed), processed.size * 2)
                        } else {
                            feed(pcm, read) // сбой шумодава — пишем сырой звук
                        }
                    } else {
                        feed(pcm, read)
                    }
                    drain(false)
                }
            }
            if (nr != null) {
                val tail = runCatching { nr.flush() }.getOrNull()
                if (tail != null && tail.isNotEmpty()) feed(shortsToBytes(tail), tail.size * 2)
            }
            val inIdx = enc.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                enc.queueInputBuffer(inIdx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (_: Throwable) {
            // best-effort: файл всё равно финализируем ниже
        } finally {
            runCatching { rec.stop() }
            runCatching { if (muxerStarted) muxer?.stop() }
        }
    }

    private fun bytesToShorts(b: ByteArray, len: Int): ShortArray {
        val out = ShortArray(len / 2)
        var i = 0
        while (i + 1 < len) {
            out[i / 2] = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort()
            i += 2
        }
        return out
    }

    private fun shortsToBytes(s: ShortArray): ByteArray {
        val out = ByteArray(s.size * 2)
        for (i in s.indices) {
            val v = s[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    private fun amplitudeOf(buf: ByteArray, len: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < len) {
            val sample = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        return (peak.coerceIn(0, 32767)) / 32767f
    }

    private fun cleanupAdvanced() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { muxer?.release() }
        muxer = null
        reducer = null
    }

    // ── Запасной путь: MediaRecorder с голосовым источником ─────────────────────
    private fun startFallback(f: File): Boolean {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(appCtx)
                      else @Suppress("DEPRECATION") MediaRecorder()
            try {
                rec.setAudioSource(source)
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioChannels(1)
                rec.setAudioSamplingRate(48_000)
                rec.setAudioEncodingBitRate(96_000)
                rec.setOutputFile(f.absolutePath)
                rec.prepare()
                rec.start()
                mediaRecorder = rec
                return true
            } catch (_: Exception) {
                runCatching { rec.release() }
            }
        }
        return false
    }
}
