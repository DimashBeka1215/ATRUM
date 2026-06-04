package com.atrum.chat.image

import com.atrum.chat.CryptoHelper
import com.atrum.chat.ImageLoader
import com.atrum.chat.ImageUploadQueue
import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Центральный модуль управления изображениями.
 *
 * АРХИТЕКТУРА:
 *   ChatActivity ──► ImageModule.upload() ──► transport.uploadImage()
 *                                                  └─► GistApi.createImageGist()
 *                                                           └─► POST /gists  (новый gist)
 *
 *   ChatActivity ──► ImageModule.loader ──► ImageLoader
 *                                               └─► transport.loadImageByRef("gist:ID")
 *
 * КЛЮЧЕВОЕ ПРАВИЛО:
 *   Изображения НИКОГДА не попадают в extraFiles при appendLine.
 *   Каждое изображение — отдельный POST (не PATCH основного chat-gist).
 *   Сообщение с ref "gist:ID" — маленький PATCH только с зашифрованной строкой.
 *
 * СЛЕДСТВИЕ:
 *   - Нет гигантских PATCH → нет secondary rate limit от изображений
 *   - Изображения в отдельных gist'ах → truncation невозможен → нет пустых пузырьков
 *   - writeMutex основного gist свободен → реакции/heartbeat не блокируются
 *
 * ChatActivity не создаёт ImageLoader и ImageUploadQueue напрямую — только через этот модуль.
 */
class ImageModule(
    private val transport: ChatTransport,
    private val password: String,
    private val chatId: String
) {

    /**
     * ImageLoader для MessageAdapter и fullscreen-просмотра.
     * Понимает форматы "gist:ID", "img_xxx.txt" и inline base64.
     */
    val loader: ImageLoader = ImageLoader(transport, password)

    /**
     * Очередь загрузки: ≤ 3 параллельных POST, retry с exponential backoff,
     * adaptive throttle при 429. Полностью изолирована от writeMutex основного gist.
     */
    private val queue = ImageUploadQueue()

    /**
     * Шифрует base64-изображение и загружает его в отдельный приватный gist.
     *
     * @param base64     Незашифрованный base64 изображения
     * @param onProgress Прогресс чанков (current, total); null если не нужен
     * @return           Ссылка "gist:GIST_ID" для хранения в сообщении
     *
     * Не делает PATCH основного chat-gist — только один POST к /gists.
     */
    suspend fun upload(
        base64: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        val encrypted = CryptoHelper.encrypt(base64, password, chatId)
        return queue.execute {
            transport.uploadImage(encrypted, password, onProgress)
        }
    }

    /**
     * Загружает список изображений параллельно.
     * Semaphore ImageUploadQueue ограничивает до MAX_CONCURRENT одновременных POST.
     * Возвращает refs в том же порядке что входной список.
     *
     * @param base64List  Список незашифрованных base64
     * @param onEachDone  Callback после каждой успешной загрузки (выполнено, всего)
     * @return            List<"gist:ID"> в порядке входных данных
     * @throws RuntimeException если хотя бы одна загрузка провалилась
     */
    suspend fun uploadAll(
        base64List: List<String>,
        onEachDone: ((completedCount: Int, total: Int) -> Unit)? = null
    ): List<String> {
        if (base64List.isEmpty()) return emptyList()
        val refs = arrayOfNulls<String>(base64List.size)
        val completedCount = AtomicInteger(0)
        coroutineScope {
            base64List.forEachIndexed { index, base64 ->
                launch {
                    refs[index] = upload(base64)
                    onEachDone?.invoke(completedCount.incrementAndGet(), base64List.size)
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        return (refs as Array<String>).toList()
    }

    /**
     * Создаёт новый экземпляр модуля с другим транспортом.
     * Вызывать при fallback Gist → Nostr в resolveTransport().
     */
    fun withTransport(newTransport: ChatTransport): ImageModule =
        ImageModule(newTransport, password, chatId)
}
