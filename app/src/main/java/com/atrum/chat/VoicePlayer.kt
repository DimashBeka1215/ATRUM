package com.atrum.chat

import android.media.MediaPlayer
import java.io.File

/**
 * Единый плеер голосовых: в любой момент играет ОДНО сообщение. Общий на весь экран,
 * чтобы при старте нового голосового предыдущее останавливалось.
 *
 * Колбэки прогресса/завершения вызываются в главном потоке (MediaPlayer шлёт их туда).
 */
object VoicePlayer {

    private var player: MediaPlayer? = null
    /** Ключ играющего сейчас сообщения (msgId) — чтобы адаптер знал, какую ячейку анимировать. */
    @Volatile var currentKey: String? = null
        private set

    private var onProgress: ((key: String, posMs: Int, durMs: Int) -> Unit)? = null
    private var onComplete: ((key: String) -> Unit)? = null

    /** Играет файл. Если этот же ключ уже играет — ставит на паузу (toggle). */
    fun toggle(
        key: String,
        file: File,
        onProgress: (key: String, posMs: Int, durMs: Int) -> Unit,
        onComplete: (key: String) -> Unit
    ) {
        if (currentKey == key && player?.isPlaying == true) {
            pause()
            return
        }
        stop() // остановить предыдущее
        this.onProgress = onProgress
        this.onComplete = onComplete
        try {
            val mp = MediaPlayer()
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                val k = currentKey
                stop()
                if (k != null) onComplete(k)
            }
            mp.prepare()
            mp.start()
            player = mp
            currentKey = key
            tick()
        } catch (_: Exception) {
            stop()
        }
    }

    fun isPlaying(key: String): Boolean = currentKey == key && player?.isPlaying == true

    /** Перенаправляет колбэки прогресса на видимый сейчас holder (после переиспользования ячейки). */
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
        mp.setOnSeekCompleteListener(null)
        if (mp.isPlaying) {
            handler.postDelayed({ tick() }, 60L)
        }
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    fun pause() {
        runCatching { player?.pause() }
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
