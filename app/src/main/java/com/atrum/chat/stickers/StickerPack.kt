package com.atrum.chat.stickers

/**
 * Тип стикера — определяет как его рендерить.
 */
enum class StickerType {
    STATIC,     // .webp — обычный ImageView
    ANIMATED,   // .tgs  — Lottie (сжатый JSON)
    VIDEO       // .webm — покадровый движок WebmStickerView (MediaCodec → кеш кадров → ImageView)
}

/**
 * Один стикер внутри пака.
 *
 * @param fileId      file_id из Telegram API
 * @param localPath   абсолютный путь к файлу на диске (null = не скачан)
 * @param type        тип: статичный / анимированный / видео
 * @param emoji       эмодзи-ассоциация из Telegram (опционально)
 */
data class Sticker(
    val fileId: String,
    val localPath: String?,
    val type: StickerType,
    val emoji: String = ""
)

/**
 * Стикер-пак — коллекция стикеров из Telegram.
 *
 * @param name        техническое имя пака (PackName из ссылки)
 * @param title       отображаемый заголовок
 * @param stickers    список стикеров
 * @param thumbPath   путь к миниатюре первого стикера (для таба)
 * @param addedAt     timestamp добавления (для сортировки)
 */
data class StickerPack(
    val name: String,
    val title: String,
    val stickers: List<Sticker>,
    val thumbPath: String?,
    val addedAt: Long = System.currentTimeMillis()
)
