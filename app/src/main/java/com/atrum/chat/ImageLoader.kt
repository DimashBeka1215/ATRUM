package com.atrum.chat

import android.graphics.Bitmap
import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Загружает картинку чата через ChatTransport (Gist или Nostr).
 *
 * Поддерживаемые форматы imageFileName:
 *
 *   "gist:GIST_ID"   — новый формат: изображение в отдельном gist.
 *   "img_xxx.txt"    — старый формат: файл в основном чат-gist.
 *
 * Дедупликация одновременных загрузок:
 *   [inFlight] хранит CompletableDeferred для каждого ref, по которому
 *   идёт загрузка. Если второй вызов loadBase64(ref) придёт пока первый
 *   ещё выполняется — он просто await'ит тот же Deferred.
 *   Результат: один сетевой запрос на ref, сколько бы корутин ни просило.
 *   Это устраняет race condition при быстрых прокрутках (30 изображений
 *   в трёх коллажах → только 30 уникальных запросов, не 30×N).
 */
class ImageLoader(private val api: ChatTransport, private val password: String) {

    /**
     * In-flight запросы: ref → Deferred<base64?>.
     * putIfAbsent атомарен в ConcurrentHashMap → безопасно без внешней синхронизации.
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Загружает сырые байты файла (для TGS-стикеров).
     * Контент хранится как base64 в gist — декодируем обратно в байты.
     */
    suspend fun loadRawBytes(fileName: String): ByteArray? {
        val base64 = loadBase64(fileName) ?: return null
        return try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    /** Загружает Bitmap. Возвращает null если что-то пошло не так. */
    suspend fun loadBitmap(fileName: String): Bitmap? {
        ImageCache.getBitmap(fileName)?.let { return it }
        val base64 = loadBase64(fileName) ?: return null
        val bitmap = withContext(Dispatchers.Default) { ImageUtils.fromBase64(base64) } ?: return null
        ImageCache.put(fileName, base64, bitmap)
        return bitmap
    }

    /**
     * Загружает base64-строку (для fullscreen через [ImageViewActivity]).
     *
     * Дедупликация: если загрузка уже идёт, await'ит существующий Deferred
     * вместо нового сетевого запроса.
     */
    suspend fun loadBase64(fileName: String): String? {
        // Быстрый путь: уже в кеше
        ImageCache.getBase64(fileName)?.let { return it }

        // Дедупликация: пытаемся зарегистрироваться первыми
        val ours = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(fileName, ours)
        if (existing != null) {
            // Кто-то уже загружает — ждём его результата
            return existing.await()
        }

        // Мы первые — выполняем загрузку
        return try {
            val base64 = if (fileName.startsWith("gist:")) {
                loadFromImageGist(fileName)
            } else {
                loadFromChatGist(fileName)
            }
            if (base64 != null) ImageCache.put(fileName, base64, null)
            ours.complete(base64)
            base64
        } catch (e: Exception) {
            ours.complete(null)
            null
        } finally {
            inFlight.remove(fileName)
        }
    }

    // ── Private loaders ────────────────────────────────────────────────────────

    /**
     * Загружает изображение из отдельного gist (формат "gist:GIST_ID").
     * GistTransport.loadImageByRef() склеивает чанки и возвращает зашифрованный контент.
     */
    private suspend fun loadFromImageGist(ref: String): String? {
        val encryptedContent = withContext(Dispatchers.IO) { api.loadImageByRef(ref) }
        return CryptoHelper.decrypt(encryptedContent, password, api.chatId)
    }

    /**
     * Загружает изображение из основного чат-gist (старый формат).
     * Обрабатывает plain base64, CHUNKED-манифест и любые другие форматы.
     */
    private suspend fun loadFromChatGist(fileName: String): String? {
        val mainContent = loadFileRetry(fileName)
        val decrypted = CryptoHelper.decrypt(mainContent, password, api.chatId) ?: return null
        return if (decrypted.startsWith(ImageChunker.CHUNKED_MARKER)) {
            assembleChunkedImage(decrypted)
        } else {
            decrypted
        }
    }

    /**
     * Собирает CHUNKED-изображение из отдельных файлов основного чат-gist.
     */
    private suspend fun assembleChunkedImage(manifest: String): String? {
        val chunkNames = ImageChunker.parseManifest(manifest) ?: return null
        if (chunkNames.isEmpty()) return null
        return try {
            val sb = StringBuilder()
            for (chunkName in chunkNames) {
                sb.append(loadFileRetry(chunkName))
            }
            CryptoHelper.decrypt(sb.toString(), password, api.chatId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Загружает файл с ретраями — через Tor чтение нестабильно, и один транзиентный
     * промах (реле не ответило за дедлайн) не должен рушить всю картинку/чанк.
     */
    private suspend fun loadFileRetry(name: String, attempts: Int = 5): String {
        var last: Exception? = null
        repeat(attempts) { i ->
            try {
                return withContext(Dispatchers.IO) { api.loadFile(name) }
            } catch (e: Exception) {
                last = e
                if (i < attempts - 1) delay(900L * (i + 1))
            }
        }
        throw last ?: RuntimeException("loadFile failed: $name")
    }
}
