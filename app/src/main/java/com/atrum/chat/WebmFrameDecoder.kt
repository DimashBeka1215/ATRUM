package com.atrum.chat

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Настоящий покадровый декод webm (VP9) через MediaCodec + MediaExtractor.
 *
 * Зачем: MediaMetadataRetriever на ряде устройств (Samsung) для VP9 не умеет отдавать
 * РАЗНЫЕ кадры — getFrameAtTime снапается к единственному keyframe, METADATA_KEY_VIDEO_FRAME_COUNT
 * пуст, getFrameAtIndex недоступен. Поэтому декодим поток сами: MediaCodec пишет кадры в
 * offscreen-Surface (SurfaceTexture), GL рисует их в pbuffer нужного размера, glReadPixels →
 * Bitmap. Кадры прорежаются до maxFrames равномерно по всему ролику.
 *
 * Альфы у VP9 в обычном декодере нет — кадры RGB. Прозрачность накладывается снаружи (keyOut).
 */
object WebmFrameDecoder {
    private const val TAG = "WEBMSTK"
    private const val TIMEOUT_US = 10_000L

    // Колбэки готовности кадра (SurfaceTexture.onFrameAvailable) доставляем на ВЫДЕЛЕННЫЙ
    // поток, а не на главный. Иначе при быстром скролле занятый main-Looper задерживает
    // колбэки, и потоки декода надолго зависают в awaitNewImage (баг «декод встал на минуту»).
    private val callbackThread = HandlerThread("WebmFrameCb").also { it.start() }
    private val callbackHandler = Handler(callbackThread.looper)

    /** Кадры (RGB, без альфы) + задержка между кадрами (мс) для проигрывания на РОДНОЙ скорости. */
    fun decode(file: File, outSize: Int, maxFrames: Int): Pair<List<Bitmap>, Long>? {
        if (!file.exists()) return null
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        var surf: CodecOutputSurface? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)

            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) { track = i; format = f; break }
            }
            if (track < 0 || format == null) return null
            extractor.selectTrack(track)

            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var durUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            // У части webm длительности нет в формате трека → тогда выборка кадров и задержка
            // ломались (брались первые N + фикс 64мс → «сверхскорость»). Берём длительность из
            // контейнера через MediaMetadataRetriever как надёжный фолбэк.
            if (durUs <= 0L) {
                durUs = try {
                    val mmr = android.media.MediaMetadataRetriever()
                    mmr.setDataSource(file.absolutePath)
                    val ms = mmr.extractMetadata(
                        android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    try { mmr.release() } catch (_: Exception) {}
                    ms * 1000L
                } catch (_: Exception) { 0L }
            }
            // САМЫЙ надёжный источник длительности — таймстемпы сэмплов (presentationTime).
            // KEY_DURATION и MMR на части webm пусты, и тогда задержка падала на фикс 64мс →
            // стикер играл на ~2x. Сканируем сэмплы БЕЗ декода, берём последний PTS + один кадр
            // и используем как длительность (если она надёжнее/больше). Затем перематываем в начало.
            run {
                var maxPts = 0L
                var prevPts = 0L
                var frameStepUs = 0L
                while (true) {
                    val t = extractor.sampleTime
                    if (t < 0L) break
                    if (t > maxPts) maxPts = t
                    if (t > prevPts) { val d = t - prevPts; if (d in 1..200_000L) frameStepUs = d }
                    prevPts = t
                    if (!extractor.advance()) break
                }
                extractor.seekTo(0L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                // последний кадр стартует в maxPts; полная петля ≈ maxPts + длительность кадра.
                val scanned = if (maxPts > 0L) maxPts + (if (frameStepUs > 0L) frameStepUs else 0L) else 0L
                if (scanned > 0L && (durUs <= 0L || scanned > durUs)) durUs = scanned
            }
            // Кадры берём РАВНОМЕРНО по всей длительности (по времени presentationTime), а не
            // первые N: иначе длинный стикер обрезается и при фиксированной задержке играет быстрее.
            val stepUs = if (durUs > 0L) durUs / maxFrames else 0L

            surf = CodecOutputSurface(outSize, outSize)
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, surf.surface, null, 0)
            codec.start()

            val frames = ArrayList<Bitmap>(maxFrames)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var nextTargetUs = 0L
            var guard = 0
            while (!outputDone && guard++ < 100_000) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sampleSize = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    val hasImage = info.size != 0
                    // Берём кадр, если его время дошло до следующей равномерной цели по ролику.
                    val keep = hasImage && frames.size < maxFrames &&
                        (stepUs <= 0L || info.presentationTimeUs >= nextTargetUs)
                    codec.releaseOutputBuffer(outIdx, keep)
                    if (keep) {
                        try {
                            surf.awaitNewImage()
                            surf.drawImage()
                            frames.add(surf.readBitmap())
                            nextTargetUs += stepUs
                        } catch (e: Exception) {
                            Log.w(TAG, "WebmFrameDecoder readFrame EXC: ${e.message}")
                        }
                    }
                    if (frames.size >= maxFrames) outputDone = true
                }
            }
            if (frames.isEmpty()) return null
            // Задержка кадра = длительность / число кадров → петля совпадает с оригиналом.
            // Верхний предел 400мс (а не 250): иначе длинные стикеры (>7с) играли заметно
            // быстрее оригинала из-за слишком жёсткого клэмпа.
            val delayMs = if (durUs > 0L)
                ((durUs / 1000.0) / frames.size).toLong().coerceIn(20L, 400L)
            else 64L
            return frames to delayMs
        } catch (e: Exception) {
            Log.w(TAG, "WebmFrameDecoder EXC: ${e.message}")
            return null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { surf?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    /**
     * Offscreen EGL pbuffer (outW x outH) + SurfaceTexture, в который MediaCodec пишет кадры.
     * drawImage() рисует внешнюю текстуру в pbuffer, readBitmap() забирает пиксели.
     */
    private class CodecOutputSurface(private val outW: Int, private val outH: Int) :
        SurfaceTexture.OnFrameAvailableListener {

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val texRender: STextureRender
        private val surfaceTexture: SurfaceTexture
        val surface: Surface
        private val lock = Object()
        private var frameAvailable = false
        private val pixelBuf: ByteBuffer =
            ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.LITTLE_ENDIAN)

        init {
            eglSetup()
            makeCurrent()
            texRender = STextureRender()
            surfaceTexture = SurfaceTexture(texRender.textureId)
            // Колбэк на выделенном потоке (callbackHandler), не на главном — иначе занятый
            // скроллом main-Looper стопорит awaitNewImage и декод «зависает».
            surfaceTexture.setOnFrameAvailableListener(this, callbackHandler)
            surface = Surface(surfaceTexture)
        }

        private fun eglSetup() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay === EGL14.EGL_NO_DISPLAY) throw RuntimeException("no EGL display")
            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) throw RuntimeException("eglInitialize failed")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfig = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfig, 0))
                throw RuntimeException("eglChooseConfig failed")
            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            val surfAttr = intArrayOf(EGL14.EGL_WIDTH, outW, EGL14.EGL_HEIGHT, outH, EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], surfAttr, 0)
            if (eglSurface == null || eglSurface === EGL14.EGL_NO_SURFACE)
                throw RuntimeException("eglCreatePbufferSurface failed")
        }

        private fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
                throw RuntimeException("eglMakeCurrent failed")
        }

        override fun onFrameAvailable(st: SurfaceTexture) {
            synchronized(lock) { frameAvailable = true; lock.notifyAll() }
        }

        fun awaitNewImage() {
            synchronized(lock) {
                val start = System.currentTimeMillis()
                while (!frameAvailable) {
                    lock.wait(2500)
                    if (!frameAvailable && System.currentTimeMillis() - start > 2500)
                        throw RuntimeException("frame wait timeout")
                }
                frameAvailable = false
            }
            surfaceTexture.updateTexImage()
        }

        fun drawImage() = texRender.drawFrame(surfaceTexture)

        fun readBitmap(): Bitmap {
            pixelBuf.rewind()
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf)
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            pixelBuf.rewind()
            bmp.copyPixelsFromBuffer(pixelBuf)
            return bmp
        }

        fun release() {
            if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
            try { surface.release() } catch (_: Exception) {}
            try { surfaceTexture.release() } catch (_: Exception) {}
        }
    }

    /** Рисует внешнюю OES-текстуру (кадр декодера) полноэкранным квадом. */
    private class STextureRender {
        val textureId: Int
        private val program: Int
        private val mvp = FloatArray(16)
        private val st = FloatArray(16)
        private val vertices: FloatBuffer
        private val aPosition: Int
        private val aTextureCoord: Int
        private val uMVP: Int
        private val uST: Int

        init {
            val data = floatArrayOf(
                // X, Y, Z,  U, V
                -1f, -1f, 0f, 0f, 0f,
                 1f, -1f, 0f, 1f, 0f,
                -1f,  1f, 0f, 0f, 1f,
                 1f,  1f, 0f, 1f, 1f
            )
            vertices = ByteBuffer.allocateDirect(data.size * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            vertices.put(data).position(0)
            Matrix.setIdentityM(mvp, 0)
            // Переворот по вертикали: glReadPixels читает снизу-вверх, иначе кадр вверх ногами.
            Matrix.scaleM(mvp, 0, 1f, -1f, 1f)
            Matrix.setIdentityM(st, 0)

            program = buildProgram(VERTEX, FRAGMENT)
            aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            aTextureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
            uMVP = GLES20.glGetUniformLocation(program, "uMVPMatrix")
            uST = GLES20.glGetUniformLocation(program, "uSTMatrix")

            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            textureId = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }

        fun drawFrame(stx: SurfaceTexture) {
            stx.getTransformMatrix(st)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            vertices.position(0)
            GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 20, vertices)
            GLES20.glEnableVertexAttribArray(aPosition)
            vertices.position(3)
            GLES20.glVertexAttribPointer(aTextureCoord, 2, GLES20.GL_FLOAT, false, 20, vertices)
            GLES20.glEnableVertexAttribArray(aTextureCoord)

            GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uST, 1, false, st, 0)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glFinish()
        }

        private fun buildProgram(vsrc: String, fsrc: String): Int {
            val vs = loadShader(GLES20.GL_VERTEX_SHADER, vsrc)
            val fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fsrc)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val ok = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] != GLES20.GL_TRUE) throw RuntimeException("link: " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        private fun loadShader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1)
            GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) throw RuntimeException("compile: " + GLES20.glGetShaderInfoLog(s))
            return s
        }

        companion object {
            private const val VERTEX =
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uSTMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec4 aTextureCoord;\n" +
                "varying vec2 vTextureCoord;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = (uSTMatrix * aTextureCoord).xy;\n" +
                "}\n"
            private const val FRAGMENT =
                "#extension GL_OES_EGL_image_external : require\n" +
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "uniform samplerExternalOES sTexture;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
                "}\n"
        }
    }
}
