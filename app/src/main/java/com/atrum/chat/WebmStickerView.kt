package com.atrum.chat

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.AttributeSet
import android.util.LruCache
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Анимированный webm-стикер БЕЗ видео-декодеров в рантайме (по-кадровый движок).
 *
 * Каждый webm декодируется ОДИН раз в набор лёгких ARGB-кадров с впечатанной
 * прозрачностью (luma-key: тёмные пиксели -> прозрачные), кадры кешируются
 * (StickerFrameCache) и проигрываются простой сменой Bitmap в ImageView.
 *
 * Важно для производительности открытия чата:
 *  - декод стикеров идёт на ограниченном пуле (2 потока, decodeDispatcher) — не
 *    за раз, иначе при открытии чата десятки параллельных декодов забивали кучу (256 МБ)
 *    огромными bitmap-ами -> блокирующий GC -> фризы и долгое открытие;
 *  - кадры берём СРАЗУ уменьшенными (getScaledFrameAtTime, API 27+), без 512px-транзиентов.
 *
 * Снаружи API совместимо: play / pause / resume / release / setFallbackBitmap.
 */
class WebmStickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val imageView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    private var currentKey: String? = null
    private var frames: List<Bitmap>? = null
    private var frameIndex = 0
    private var ticking = false
    private var frameDelay = DEFAULT_FRAME_DELAY_MS

    private val ticker = object : Runnable {
        override fun run() {
            val f = frames
            if (f == null || f.isEmpty()) return
            imageView.setImageBitmap(f[frameIndex % f.size])
            frameIndex++
            if (ticking) postDelayed(this, frameDelay)
        }
    }

    /** Применить декодированный набор: кадры + родная задержка кадра. */
    private fun applyFrames(sf: StickerFrames) {
        frames = sf.frames
        frameDelay = sf.delayMs
        frameIndex = 0
        startTicker()
    }

    init {
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
    }

    /** Статичный кадр-заглушка (первый кадр) — пока кадры декодируются. */
    fun setFallbackBitmap(bmp: Bitmap?) {
        if (bmp != null && frames == null) imageView.setImageBitmap(bmp)
    }

    /** Привязать webm-файл и запустить покадровую анимацию (зацикленно). */
    fun play(file: File, key: String) {
        if (currentKey == key && frames != null) { startTicker(); return }
        release()
        currentKey = key
        visibility = VISIBLE

        StickerFrameCache.get(key)?.let { applyFrames(it); return }
        if (!file.exists()) return

        val cacheDir = context.cacheDir
        scope.launch {
            // Дедуплицированный декод: результат кладётся в кеш кадров внутри decodeShared,
            // поэтому он сохраняется даже если этот холдер уже переиспользован под другой стикер.
            val decoded = decodeShared(file, key, cacheDir).await()
            if (currentKey != key) return@launch
            if (decoded != null) applyFrames(decoded)
        }
    }

    /** Этот стикер уже проигрывается в этом вью — повторный бинд можно пропустить (без мигания). */
    fun isPlaying(key: String): Boolean = currentKey == key && frames != null

    /** Кадры уже есть (в памяти или на диске) — можно играть без исходного webm-файла. */
    fun hasFrames(key: String): Boolean =
        StickerFrameCache.get(key) != null || StickerDiskCache.has(context.cacheDir, key)

    /**
     * Играть, когда кадры уже закешированы (память/диск) — без base64/декода/temp-файла.
     * Быстрый путь для повторного открытия чата с кучей webm.
     */
    fun playCached(key: String) {
        if (currentKey == key && frames != null) { startTicker(); return }
        release()
        currentKey = key
        visibility = VISIBLE

        StickerFrameCache.get(key)?.let { applyFrames(it); return }

        val cacheDir = context.cacheDir
        scope.launch {
            val decoded = withContext(decodeDispatcher) {
                StickerFrameCache.get(key) ?: StickerDiskCache.load(cacheDir, key)
            }
            if (decoded != null) StickerFrameCache.put(key, decoded)
            if (currentKey != key) return@launch
            if (decoded != null) applyFrames(decoded)
        }
    }

    fun pause() {
        ticking = false
        removeCallbacks(ticker)
    }

    fun resume() {
        if (frames != null) startTicker()
    }

    /** Останавливает анимацию. Кадры остаются в общем кеше для повторного показа. */
    fun release() {
        ticking = false
        removeCallbacks(ticker)
        frames = null
        currentKey = null
        frameIndex = 0
        imageView.setImageDrawable(null)
    }

    private fun startTicker() {
        if (ticking) return
        ticking = true
        post(ticker)
    }

    companion object {
        private const val DEFAULT_FRAME_DELAY_MS = 64L  // запас, если длительность неизвестна
        private const val MAX_FRAMES = 28
        private const val FPS = 14.0
        private const val TARGET_SIZE = 192
        // Порог ключа по max-каналу: ниже KEY_LOW — прозрачно (чёрный фон),
        // выше KEY_HIGH — полностью непрозрачно, между — мягкий край (анти-алиасинг).
        private const val KEY_LOW = 10
        private const val KEY_HIGH = 30
        private const val FALLBACK_DUR_US = 3_000_000L  // 3с — если webm без duration

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        // Пул на 3 потока для декода стикеров: видимый экран (несколько webm) прогревается
        // быстрее, при этом параллелизм ограничен — память под контролем (кадры маленькие).
        private val decodeDispatcher =
            Executors.newFixedThreadPool(3).asCoroutineDispatcher()
        private val decodeScope = CoroutineScope(decodeDispatcher + SupervisorJob())

        // Дедуп декода: если один и тот же стикер запросили сразу несколько холдеров до того,
        // как кадры закешированы — выполняется ОДИН MediaCodec-декод, остальные ждут его результат.
        private val inFlightDecodes = ConcurrentHashMap<String, Deferred<StickerFrames?>>()

        /** Общий (дедуплицированный) декод стикера по ключу. Кладёт результат в кеш кадров. */
        private fun decodeShared(file: File, key: String, cacheDir: File): Deferred<StickerFrames?> =
            inFlightDecodes.computeIfAbsent(key) {
                decodeScope.async {
                    try {
                        val r = StickerFrameCache.get(key) ?: decodeFrames(file, key, cacheDir)
                        if (r != null) StickerFrameCache.put(key, r)
                        r
                    } finally {
                        inFlightDecodes.remove(key)
                    }
                }
            }

        private fun decodeFrames(file: File, key: String, cacheDir: File): StickerFrames? {
            // 0. Диск-кеш готовых кадров — без MediaCodec/EGL, мгновенно (как в Telegram).
            StickerDiskCache.load(cacheDir, key)?.let { return it }

            // 1. Настоящий MediaCodec-декод — кадры + родная задержка (длительность/кадры).
            val raw = WebmFrameDecoder.decode(file, TARGET_SIZE, MAX_FRAMES)
            if (raw != null && raw.first.isNotEmpty()) {
                val out = ArrayList<Bitmap>(raw.first.size)
                for (b in raw.first) out.add(keyOut(b))
                // Срезаем почти-прозрачные кадры по краям — это они давали вспышку на стыке петли.
                val frames = trimBlankEnds(out)
                val sf = StickerFrames(frames, raw.second)
                // Сохраняем на диск, чтобы следующее открытие чата не декодировало заново.
                StickerDiskCache.save(cacheDir, key, sf)
                return sf
            }

            // 2. Фолбэк: MediaMetadataRetriever (если кодек не справился).
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val durUs = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L) * 1000L).let { if (it > 0L) it else FALLBACK_DUR_US }
                val out = ArrayList<Bitmap>(MAX_FRAMES)
                val count = ((durUs / 1_000_000.0) * FPS).toInt().coerceIn(1, MAX_FRAMES)
                val stepUs = durUs / count
                var t = 0L; var i = 0
                while (i < count) {
                    val frame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)?.let { scaleDown(it) }
                    if (frame != null) out.add(keyOut(frame))
                    t += stepUs; i++
                }
                if (out.isEmpty()) null
                else {
                    val frames = trimBlankEnds(out)
                    StickerFrames(frames, ((durUs / 1000.0) / frames.size).toLong().coerceIn(20L, 400L))
                }
            } catch (e: Exception) {
                null
            } finally {
                try { retriever.release() } catch (_: Exception) {}
            }
        }

        private fun scaleDown(src: Bitmap): Bitmap {
            val w = src.width; val h = src.height
            if (w <= 0 || h <= 0) return src
            val m = maxOf(w, h)
            if (m <= TARGET_SIZE) return src
            val scale = TARGET_SIZE.toFloat() / m
            val nw = (w * scale).toInt().coerceAtLeast(1)
            val nh = (h * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(src, nw, nh, true)
            if (scaled !== src) src.recycle()
            return scaled
        }

        /** Кадр почти полностью прозрачный (видимых пикселей < ~2%) — фоновый «пустой» кадр. */
        private fun isNearlyBlank(b: Bitmap): Boolean {
            val w = b.width; val h = b.height
            if (w <= 0 || h <= 0) return true
            val px = IntArray(w * h)
            b.getPixels(px, 0, w, 0, 0, w, h)
            var opaque = 0; var sampled = 0; var i = 0
            while (i < px.size) {            // выборка 1/4 пикселей — быстро
                if (((px[i] ushr 24) and 0xFF) > 16) opaque++
                sampled++
                i += 4
            }
            return opaque < sampled / 50
        }

        /**
         * Убирает почти-прозрачные кадры по КРАЯМ набора. Именно крайний пустой кадр давал
         * мигание-вспышку на стыке петли. Середину не трогаем; всегда оставляем ≥2 кадров.
         */
        private fun trimBlankEnds(frames: ArrayList<Bitmap>): List<Bitmap> {
            if (frames.size <= 2) return frames
            var start = 0
            var end = frames.size - 1
            while (start < end && isNearlyBlank(frames[start])) start++
            while (end > start && isNearlyBlank(frames[end])) end--
            if (start == 0 && end == frames.size - 1) return frames   // нечего резать
            if (end - start + 1 < 2) return frames                    // не оставляем <2 кадров
            for (idx in frames.indices) if (idx < start || idx > end) {
                try { frames[idx].recycle() } catch (_: Exception) {}
            }
            return ArrayList(frames.subList(start, end + 1))
        }

        private fun keyOut(src: Bitmap): Bitmap {
            val w = src.width; val h = src.height
            val out = if (src.config == Bitmap.Config.ARGB_8888 && src.isMutable) src
                      else src.copy(Bitmap.Config.ARGB_8888, true)
            val pixels = IntArray(w * h)
            out.getPixels(pixels, 0, w, 0, 0, w, h)
            for (idx in pixels.indices) {
                val c = pixels[idx]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                // Прозрачность ключуем по МАКСИМАЛЬНОМУ каналу (HSV-value), а не по luma.
                // Иначе тёмно-ЦВЕТНЫЕ части стикера (тёмно-красный/синий) имеют низкую luma и
                // дырявились → сквозь них просвечивали обои. По max-каналу прозрачным становится
                // только истинно ЧЁРНЫЙ фон webm; цветные тёмные участки остаются непрозрачными.
                val v = maxOf(r, maxOf(g, b))
                val a = when {
                    v <= KEY_LOW -> 0
                    v >= KEY_HIGH -> 255
                    else -> ((v - KEY_LOW) * 255) / (KEY_HIGH - KEY_LOW)
                }
                pixels[idx] = (a shl 24) or (c and 0x00FFFFFF)
            }
            out.setPixels(pixels, 0, w, 0, 0, w, h)
            if (out !== src) src.recycle()
            return out
        }
    }
}

/** Декодированный стикер: кадры (с прозрачностью) + задержка между кадрами для родной скорости. */
class StickerFrames(val frames: List<Bitmap>, val delayMs: Long)

/**
 * Кеш декодированных кадров стикеров (общий, ограниченный по памяти).
 * При вытеснении кадры перекодируются при следующем показе.
 */
object StickerFrameCache {
    private const val MAX_BYTES = 24 * 1024 * 1024
    private val cache = object : LruCache<String, StickerFrames>(MAX_BYTES) {
        override fun sizeOf(key: String, value: StickerFrames): Int =
            value.frames.sumOf { it.byteCount }
    }

    fun get(key: String): StickerFrames? = cache.get(key)
    fun put(key: String, value: StickerFrames) { cache.put(key, value) }
    fun clear() { cache.evictAll() }
}
