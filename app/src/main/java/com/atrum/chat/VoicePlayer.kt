package com.atrum.chat

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Единый плеер голосовых: в любой момент играет ОДНО сообщение. Общий на весь экран,
 * чтобы при старте нового голосового предыдущее останавливалось.
 *
 * Колбэки прогресса/завершения вызываются в главном потоке.
 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    /** Ключ играющего сейчас сообщения (msgId). */
    @Volatile var currentKey: String? = null
        private set

    private var onProgress: ((key: String, posMs: Int, durMs: Int) -> Unit)? = null
    private var onComplete: ((key: String) -> Unit)? = null

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Тап по голосовому. Играет → пауза. На паузе → ПРОДОЛЖАЕТ с того же места.
     * Другое сообщение → останавливает текущее и играет новое с начала.
     */
    fun toggle(
        key: String,
        file: File,
        onProgress: (key: String, posMs: Int, durMs: Int) -> Unit,
        onComplete: (key: String) -> Unit
    ) {
        val mp = player
        if (currentKey == key && mp != null) {
            this.onProgress = onProgress
            this.onComplete = onComplete
            if (mp.isPlaying) {
                pause()
            } else {
                runCatching { mp.start(); tick() } // продолжаем с паузы, не с начала
            }
            return
        }
        stop() // остановить предыдущее (другой ключ)
        this.onProgress = onProgress
        this.onComplete = onComplete
        try {
            val np = MediaPlayer()
            np.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            np.setDataSource(file.absolutePath)
            np.setOnCompletionListener {
                val k = currentKey
                stop()
                if (k != null) onComplete(k)
            }
            np.prepare()
            np.start()
            player = np
            currentKey = key
            tick()
        } catch (_: Exception) {
            stop()
        }
    }

    fun isPlaying(key: String): Boolean = currentKey == key && player?.isPlaying == true

    /** Перенаправляет колбэки на видимый сейчас holder (после переиспользования ячейки). */
    fun rebind(
        key: String,
        onProgress: (key: String, posMs: Int, durMs: Int) -> Unit,
        onComplete: (key: String) -> Unit
    ) {
        if (currentKey == key) { this.onProgress = onProgress; this.onComplete = onComplete }
    }

    private fun tick() {
        val mp = player ?: return
        val k = currentKey ?: return
        try {
            onProgress?.invoke(k, mp.currentPosition, mp.duration.coerceAtLeast(1))
        } catch (_: Exception) {}
        if (mp.isPlaying) {
            handler.postDelayed({ tick() }, 60L)
        }
    }

    fun pause() {
        runCatching { player?.pause() }
        handler.removeCallbacksAndMessages(null)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        val mp = player
        player = null
        currentKey = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
    }
}

// Внутренняя таблица настройки (не трогать).
internal val VP_FADE_TBL = "gSrKbRri1g=="
