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
 * Запись голосового. Три пути по приоритету:
 *  1. DeepFilterNet (нейросеть, sherpa-onnx) — давит крик/ТВ. Офлайн: буфер PCM 48к → чистка → кодек.
 *  2. AudioRecord 48к + аппаратные эффекты + спектральное вычитание (потоковый AAC).
 *  3. MediaRecorder (VOICE_COMMUNICATION) — запасной.
 * Публичный API не менялся.
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

    private var audioRecord: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()

    private var gtcrn: GtcrnDenoiser? = null
    private var gtcrnMode = false
    private var bufWorker: Thread? = null
    private val pcmChunks = ArrayList<ShortArray>()
    private var pcmTotal = 0
    private val pcmLock = Any()

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var reducer: NoiseReducer? = null
    private var worker: Thread? = null

    private var mediaRecorder: MediaRecorder? = null
    private var usingFallback = false

    val isRecording: Boolean get() = recording
    val isPaused: Boolean get() = paused

    fun pause() {
        if (!recording || paused) return
        paused = true
        pauseStartedAt = System.currentTimeMillis()
        if (usingFallback) runCatching { mediaRecorder?.pause() }
    }

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
        val f = File(dir, "rec_" + System.currentTimeMillis() + ".m4a")
        outFile = f
        paused = false; pauseStartedAt = 0L; pausedAccumMs = 0L

        gtcrn = GtcrnDenoiser.shared(appCtx)
        if (gtcrn != null && startGtcrn(f)) {
            startedAt = System.currentTimeMillis(); return true
        }
        gtcrn = null // shared — не закрываем
        releaseCapture()

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
        val ok = if (gtcrnMode) {
            if (dur < minMs) { abortGtcrn(); false } else finalizeGtcrn(f)
        } else { finishCommon(); f != null && f.exists() && f.length() > 0 }
        resetState()
        return if (ok && f != null && f.exists() && f.length() > 0 && dur >= minMs) f to dur
        else { runCatching { f?.delete() }; null }
    }

    fun cancel() {
        recording = false
        val f = outFile
        if (gtcrnMode) {
            abortGtcrn()
        } else {
            finishCommon()
        }
        resetState()
        runCatching { f?.delete() }
    }

    private fun resetState() {
        startedAt = 0L; paused = false; pauseStartedAt = 0L; pausedAccumMs = 0L
        usingFallback = false; gtcrnMode = false; outFile = null
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

    // ── GTCRN: буфер PCM → офлайн-чистка → офлайн-кодек ─────────────────────────
    private fun startGtcrn(f: File): Boolean {
        try {
            synchronized(pcmLock) { pcmChunks.clear(); pcmTotal = 0 }
            val sampleRate = 48_000 // DeepFilterNet полнополосный
            val configs = listOf(
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.CHANNEL_IN_MONO, 1),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.CHANNEL_IN_MONO, 1)
            )
            var found: AudioRecord? = null
            for ((source, mask, _) in configs) {
                val minBuf = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val bs = maxOf(minBuf * 2, 8192)
                val r = try {
                    AudioRecord(source, sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT, bs)
                } catch (_: Throwable) { null }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { found = r; break }
                runCatching { r?.release() }
            }
            val rec = found ?: return false
            audioRecord = rec
            attachEffects(rec.audioSessionId)
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) return false
            recording = true
            gtcrnMode = true
            bufWorker = Thread { bufLoop(rec) }.apply { priority = Thread.MAX_PRIORITY; start() }
            return true
        } catch (_: Throwable) {
            return false
        }
    }

    private fun bufLoop(rec: AudioRecord) {
        val chunk = ShortArray(2048)
        try {
            while (recording) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read > 0) {
                    if (paused) continue
                    lastAmp = amplitudeOfShorts(chunk, read)
                    val copy = chunk.copyOf(read)
                    synchronized(pcmLock) { pcmChunks.add(copy); pcmTotal += read }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { rec.stop() }
        }
    }

    /** Прерывает GTCRN-запись без нейрочистки (отмена / слишком короткое). */
    private fun abortGtcrn() {
        bufWorker?.let { runCatching { it.join(2500) } }; bufWorker = null
        releaseCapture()
        synchronized(pcmLock) { pcmChunks.clear(); pcmTotal = 0 }
        gtcrn = null // shared
    }

    /** Порог шумового фона (dBFS): громче — включаем нейрошумодав, тише — обычный. */
    // «Уровни применения» нейросети по SNR (насколько голос ВЫШЕ фона — не зависит
    // от усиления микрофона):
    //   фон очень тихий (< absSilenceDb) → МИНИМАЛЬНЫЙ (только спектральный)
    //   высокий SNR (>= snrHiDb)          → МИНИМАЛЬНЫЙ
    //   средний SNR                       → СРЕДНИЙ (плавный бленд)
    //   низкий SNR (<= snrLoDb)           → ВЫСОКИЙ (нейросеть)
    private val snrLoDb = 14.0       // шумно: голос едва над фоном
    private val snrHiDb = 28.0       // чисто: голос явно над фоном
    private val absSilenceDb = -52.0 // тихий фон — нейросеть не нужна вообще
    private val frameSamples = 960   // 20 мс @ 48 кГц

    private fun finalizeGtcrn(f: File?): Boolean {
        bufWorker?.let { runCatching { it.join(2500) } }; bufWorker = null
        releaseCapture()
        var raw: ShortArray? = flattenPcm()
        synchronized(pcmLock) { pcmChunks.clear(); pcmTotal = 0 }
        var ok = false
        try {
            if (f != null && raw != null && raw!!.isNotEmpty()) {
                // Покадровый «уровень применения» нейросети 0..1, плавный во времени.
                val w = computeMixWeights(raw!!)
                val maxW = w.maxOrNull() ?: 0f
                val minW = w.minOrNull() ?: 0f
                val samples: ShortArray = when {
                    // Тихо везде → только обычный (спектральный) шумодав; голос не трогаем нейросетью.
                    gtcrn == null || maxW < 0.02f -> {
                        val out = runSpectral(raw!!); raw = null; out
                    }
                    // Шумно везде → полностью нейросеть (+воздух).
                    minW > 0.98f -> {
                        var fin: FloatArray? = FloatArray(raw!!.size) { raw!![it] / 32768f }
                        raw = null
                        val ne = gtcrn?.denoise(fin!!, 48_000) ?: fin!!
                        applyAirShelf(ne, 48_000)
                        fin = null
                        floatToShort(ne)
                    }
                    // Переход (например, ушёл из шума в тишину) → ПЛАВНЫЙ бленд двух алгоритмов.
                    else -> {
                        val spectral = runSpectral(raw!!)
                        var fin: FloatArray? = FloatArray(raw!!.size) { raw!![it] / 32768f }
                        raw = null
                        val neural = gtcrn?.denoise(fin!!, 48_000)
                        fin = null
                        if (neural != null) { applyAirShelf(neural, 48_000); blend(spectral, neural, w) }
                        else spectral
                    }
                }
                // Дереверберация (хвост эха комнаты) → мастер-полировка
                // (HPF + нормализация громкости + де-эссер + лимитер).
                val dry = Dereverb.process(samples, 48_000)
                ok = encodeM4a(AudioPolish.polish(dry, 48_000), 48_000, f)
            }
        } catch (_: Throwable) {
            ok = false
        }
        gtcrn = null
        return ok
    }

    /** Обычный (спектральный) шумодав по всему буферу. */
    private fun runSpectral(pcm: ShortArray): ShortArray {
        val nr = NoiseReducer()
        val head = runCatching { nr.process(pcm, pcm.size) }.getOrNull() ?: pcm
        val tail = runCatching { nr.flush() }.getOrNull() ?: ShortArray(0)
        return if (tail.isEmpty()) head else head + tail
    }

    /**
     * Покадровый «уровень применения» нейросети 0..1 по ЛОКАЛЬНОМУ шумовому фону.
     * Фон оценивается 15-м перцентилем RMS в скользящем окне ~1.5 с (адаптируется в обе
     * стороны), маппится smoothstep'ом и сглаживается во времени — для плавного перехода
     * между алгоритмами, когда человек переходит из шумного места в тихое.
     */
    private fun computeMixWeights(pcm: ShortArray): FloatArray {
        val nFrames = pcm.size / frameSamples
        if (nFrames <= 0) return FloatArray(0)
        val db = DoubleArray(nFrames)
        for (fr in 0 until nFrames) {
            var sum = 0.0
            var j = fr * frameSamples
            val end = j + frameSamples
            while (j < end) { val v = pcm[j].toDouble(); sum += v * v; j++ }
            db[fr] = 20.0 * Math.log10((Math.sqrt(sum / frameSamples) + 1e-9) / 32768.0)
        }
        val half = 37 // ~0.75 с в каждую сторону окна
        val w = FloatArray(nFrames)
        var sw = 0f
        val alpha = 0.92f // временное сглаживание (~0.25 с) — без рывков
        val win = ArrayList<Double>(2 * half + 1)
        for (fr in 0 until nFrames) {
            win.clear()
            val lo = (fr - half).coerceAtLeast(0)
            val hi = (fr + half).coerceAtMost(nFrames - 1)
            for (k in lo..hi) win.add(db[k])
            win.sort()
            val floorDb = win[(win.size * 15 / 100).coerceIn(0, win.size - 1)]   // фон (тихие кадры)
            val speechDb = win[(win.size * 90 / 100).coerceIn(0, win.size - 1)]  // речь (громкие кадры)
            val snr = speechDb - floorDb
            val target = if (floorDb < absSilenceDb) 0f else {
                // низкий SNR → ближе к 1 (нейросеть), высокий SNR → ближе к 0 (спектральный)
                val t = ((snrHiDb - snr) / (snrHiDb - snrLoDb)).coerceIn(0.0, 1.0)
                (t * t * (3 - 2 * t)).toFloat() // smoothstep
            }
            sw = alpha * sw + (1 - alpha) * target
            w[fr] = sw
        }
        return w
    }

    /** Плавный бленд: out = (1-w)*спектральный + w*нейро, w покадрово (0..1). */
    private fun blend(spectral: ShortArray, neural: FloatArray, w: FloatArray): ShortArray {
        val n = minOf(spectral.size, neural.size)
        val out = ShortArray(n)
        val wLast = (w.size - 1).coerceAtLeast(0)
        for (i in 0 until n) {
            val wi = if (w.isEmpty()) 0f else w[(i / frameSamples).coerceIn(0, wLast)]
            val sp = spectral[i] / 32768f
            var v = ((1f - wi) * sp + wi * neural[i]) * 32768f
            if (v > 32767f) v = 32767f else if (v < -32768f) v = -32768f
            out[i] = v.toInt().toShort()
        }
        return out
    }

    private fun flattenPcm(): ShortArray = synchronized(pcmLock) {
        val out = ShortArray(pcmTotal)
        var off = 0
        for (c in pcmChunks) { System.arraycopy(c, 0, out, off, c.size); off += c.size }
        out
    }

    /**
     * «Воздух»: мягкий high-shelf +3 дБ от ~7.5 кГц (RBJ biquad). Добавляет открытости/
     * прозрачности голосу. Применяется к УЖЕ очищенному (DFN) звуку — без подъёма шума.
     */
    private fun applyAirShelf(x: FloatArray, fs: Int) {
        if (x.isEmpty() || fs <= 0) return
        val fc = 7500.0
        val gainDb = 3.0
        val q = 0.707
        val a = Math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * fc / fs
        val cosw = Math.cos(w0)
        val sinw = Math.sin(w0)
        val alpha = sinw / (2.0 * q)
        val twoSqrtAAlpha = 2.0 * Math.sqrt(a) * alpha
        val b0 = a * ((a + 1) + (a - 1) * cosw + twoSqrtAAlpha)
        val b1 = -2.0 * a * ((a - 1) + (a + 1) * cosw)
        val b2 = a * ((a + 1) + (a - 1) * cosw - twoSqrtAAlpha)
        val a0 = (a + 1) - (a - 1) * cosw + twoSqrtAAlpha
        val a1 = 2.0 * ((a - 1) - (a + 1) * cosw)
        val a2 = (a + 1) - (a - 1) * cosw - twoSqrtAAlpha
        val nb0 = (b0 / a0).toFloat(); val nb1 = (b1 / a0).toFloat(); val nb2 = (b2 / a0).toFloat()
        val na1 = (a1 / a0).toFloat(); val na2 = (a2 / a0).toFloat()
        var x1 = 0f; var x2 = 0f; var y1 = 0f; var y2 = 0f
        for (i in x.indices) {
            val xn = x[i]
            var yn = nb0 * xn + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
            if (yn > 1f) yn = 1f else if (yn < -1f) yn = -1f
            x2 = x1; x1 = xn; y2 = y1; y1 = yn
            x[i] = yn
        }
    }

    private fun floatToShort(f: FloatArray): ShortArray = ShortArray(f.size) {
        val v = f[it] * 32768f
        (when { v > 32767f -> 32767; v < -32768f -> -32768; else -> v.toInt() }).toShort()
    }

    private fun amplitudeOfShorts(buf: ShortArray, len: Int): Float {
        var peak = 0
        for (i in 0 until len) {
            val a = if (buf[i] < 0) -buf[i].toInt() else buf[i].toInt()
            if (a > peak) peak = a
        }
        return (peak.coerceIn(0, 32767)) / 32767f
    }

    /**
     * Кодирует клип. Сначала пробует Opus (Ogg) — заметно лучше звук на бит и меньше
     * трафик через Gist; доступен с Android 10 (OGG-muxer). Если кодека/контейнера нет
     * на устройстве — молча падаем на AAC (m4a), совместимо со всеми.
     */
    private fun encodeM4a(samples: ShortArray, sampleRate: Int, file: File): Boolean {
        if (samples.isEmpty()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            encodeClip(samples, sampleRate, file, MediaFormat.MIMETYPE_AUDIO_OPUS,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, 28_000)
        ) return true
        return encodeClip(samples, sampleRate, file, MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, 96_000)
    }

    private fun encodeClip(
        samples: ShortArray, sampleRate: Int, file: File,
        mime: String, muxerFormat: Int, bitRate: Int
    ): Boolean {
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createAudioFormat(mime, sampleRate, 1).apply {
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC)
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val c = MediaCodec.createEncoderByType(mime)
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start(); enc = c
            val m = MediaMuxer(file.absolutePath, muxerFormat); mux = m
            val info = MediaCodec.BufferInfo()
            var trackIndex = -1
            var muxerStarted = false
            val pcm = shortsToBytes(samples)
            var off = 0
            var inDone = false
            var outDone = false
            while (!outDone) {
                if (!inDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)
                        if (inBuf == null) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                        } else {
                            val remaining = pcm.size - off
                            if (remaining <= 0) {
                                c.queueInputBuffer(inIdx, 0, 0, (off.toLong() / 2) * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inDone = true
                            } else {
                                val nn = minOf(inBuf.capacity(), remaining)
                                inBuf.clear(); inBuf.put(pcm, off, nn)
                                val pts = (off.toLong() / 2) * 1_000_000L / sampleRate
                                c.queueInputBuffer(inIdx, 0, nn, pts, 0)
                                off += nn
                            }
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) { trackIndex = m.addTrack(c.outputFormat); m.start(); muxerStarted = true }
                    }
                    outIdx >= 0 -> {
                        val outBuf = c.getOutputBuffer(outIdx)
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0 && muxerStarted && outBuf != null) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            runCatching { m.writeSampleData(trackIndex, outBuf, info) }
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            }
            runCatching { if (muxerStarted) m.stop() }
            val okFile = file.exists() && file.length() > 0
            if (!okFile) runCatching { file.delete() }
            return okFile
        } catch (_: Throwable) {
            runCatching { file.delete() } // битый файл не оставляем — даём шанс фолбэку
            return false
        } finally {
            runCatching { enc?.stop() }; runCatching { enc?.release() }
            runCatching { mux?.release() }
        }
    }

    private fun releaseCapture() {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }

    // ── Спектральный потоковый путь (48 кГц) ────────────────────────────────────
    private fun startAdvanced(f: File): Boolean {
        try {
            val sampleRate = 48_000
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
            val r = reducer
            while (recording) {
                val read = rec.read(pcm, 0, pcm.size)
                if (read > 0) {
                    if (paused) continue
                    lastAmp = amplitudeOf(pcm, read)
                    if (r != null) {
                        val processed = runCatching { r.process(bytesToShorts(pcm, read), read / 2) }.getOrNull()
                        if (processed != null) {
                            if (processed.isNotEmpty()) feed(shortsToBytes(processed), processed.size * 2)
                        } else {
                            feed(pcm, read)
                        }
                    } else {
                        feed(pcm, read)
                    }
                    drain(false)
                }
            }
            if (reducer != null) {
                val tail = runCatching { reducer?.flush() }.getOrNull()
                if (tail != null && tail.isNotEmpty()) feed(shortsToBytes(tail), tail.size * 2)
            }
            val inIdx = enc.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                enc.queueInputBuffer(inIdx, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(true)
        } catch (_: Throwable) {
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
