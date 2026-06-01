package com.atrum.chat

import android.graphics.Bitmap
import android.util.LruCache
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory кеш картинок из gist.
 *
 * Храним Bitmap (для отображения в адаптере) и сырой base64
 * (для fullscreen-просмотрщика — открывается через [ImageViewActivity]).
 *
 * Размер bitmap-кеша ограничен 50 МБ. При вытеснении bitmap из LruCache
 * base64 остаётся в [base64s] — повторный декод дешевле нового сетевого запроса.
 *
 * [shownConfirmations] — per-session множество ref'ов, для которых уже
 * была показана анимация подтверждения загрузки (зелёный кружок).
 * Позволяет [CollageCell] отличить «первую загрузку» от «повторного декода
 * после вытеснения из LruCache» и не показывать анимацию лишний раз.
 */
object ImageCache {

    private const val MAX_BITMAP_BYTES = 50 * 1024 * 1024

    private val bitmaps = object : LruCache<String, Bitmap>(MAX_BITMAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val base64s = HashMap<String, String>()

    /**
     * Refs, для которых анимация подтверждения уже была показана в этой сессии.
     * ConcurrentHashMap-backed Set — безопасен для вызовов из разных корутин.
     */
    private val shownConfirmations: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    // ── Bitmap ─────────────────────────────────────────────────────────────────

    fun getBitmap(key: String): Bitmap? = bitmaps.get(key)

    // ── Base64 ────────────────────────────────────────────────────────────────

    fun getBase64(key: String): String? = synchronized(base64s) { base64s[key] }

    // ── Составной статус ──────────────────────────────────────────────────────

    /**
     * true если данные изображения известны (bitmap **или** base64 в кеше).
     *
     * Используется в click-listener'е ячейки коллажа: открывать viewer
     * разрешено только когда данные доступны — иначе тап игнорируется
     * (изображение ещё загружается, показан спиннер).
     */
    fun isKnown(key: String): Boolean =
        bitmaps.get(key) != null || synchronized(base64s) { key in base64s }

    // ── Put ───────────────────────────────────────────────────────────────────

    fun put(key: String, base64: String, bitmap: Bitmap?) {
        synchronized(base64s) { base64s[key] = base64 }
        if (bitmap != null) bitmaps.put(key, bitmap)
    }

    // ── Confirmation tracking ─────────────────────────────────────────────────

    /**
     * Была ли уже показана анимация подтверждения для этого ref.
     * При true — [CollageCell.showBitmapImmediate] вместо [CollageCell.showBitmap].
     */
    fun wasShownConfirmation(key: String): Boolean = key in shownConfirmations

    /** Запомнить что анимация подтверждения была показана. */
    fun markShownConfirmation(key: String) { shownConfirmations.add(key) }

    // ── Clear ─────────────────────────────────────────────────────────────────

    fun clear() {
        bitmaps.evictAll()
        synchronized(base64s) { base64s.clear() }
        shownConfirmations.clear()
    }
}
