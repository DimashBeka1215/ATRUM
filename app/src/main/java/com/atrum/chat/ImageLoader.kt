package com.atrum.chat

import android.graphics.Bitmap
import com.atrum.chat.transport.ChatTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Загружает картинку чата через ChatTransport (Channel или Nostr).
 *
 * Поддерживаемые форматы imageFileName:
 *
 *   "gist:GIST_ID"   — новый формат: изображение в отдельном источнике.
 *   "img_xxx.txt"    — старый формат: файл в основном контенте чата.
 *
 * Дедупликация одновременных загрузок:
 *   [inFlight] хранит CompletableDeferred для каждого ref, по которому
 *   идёт загрузка. Если второй вызов loadBase64(ref) придёт пока первый
 *   ещё выполняется — он просто await'ит тот же Deferred.
 *   Результат: один сетевой запрос на ref, сколько бы корутин ни просило.
 *   Это устраняет race condition при быстрых прокрутках (30 изображений
 *   в трёх коллажах → только 30 уникальных запросов, не 30×N).
 */
private const val MAX_PARALLEL_CHUNKS = 6

/**
 * Сколько НЕ повторять загрузку файла после неудачи. Битое/недоступное фото (например со
 * старыми слишком большими чанками, которые реле отвергли) иначе грузится заново на КАЖДЫЙ
 * bind/тик — тяжёлый декод/дешифр в бесконечном цикле → GC-шторм и ANR. По истечении окна
 * пробуем снова (вдруг реле уже отдало недостающие чанки).
 */
private const val FAILED_RETRY_MS = 60_000L

class ImageLoader(
    private val api: ChatTransport,
    private val password: String,
    // Крипто-домен для расшифровки контента. ДОЛЖЕН совпадать с тем, под которым
    // устанавливается forward-secrecy сессия (chat.chatId) — иначе медиа не попадёт
    // в сессионный ключ (как текст) и будет зависеть от пароля. По умолчанию —
    // сетевой chatId (channelId) для обратной совместимости вызовов.
    private val cryptoChatId: String = api.cryptoChatId
) {

    /**
     * In-flight запросы: ref → Deferred<base64?>.
     * putIfAbsent атомарен в ConcurrentHashMap → безопасно без внешней синхронизации.
     */
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Негативный кэш: ref → время последней неудачной загрузки (мс). */
    private val failedLoads = ConcurrentHashMap<String, Long>()

    /**
     * Сбрасывает негативный кэш для [fileName] — следующая загрузка пойдёт по сети
     * немедленно, не дожидаясь [FAILED_RETRY_MS]. Нужно для ЯВНЫХ повторов по тапу
     * пользователя (например тап по play голосового): пользователь хочет попробовать
     * сейчас, а не через минуту.
     */
    fun forget(fileName: String) {
        failedLoads.remove(fileName)
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Загружает сырые байты файла (для TGS-стикеров).
     * Контент хранится как base64 — декодируем обратно в байты.
     */
    suspend fun loadRawBytes(fileName: String, onChunkProgress: ((current: Int, total: Int) -> Unit)? = null): ByteArray? {
        val base64 = loadBase64(fileName, onChunkProgress) ?: return null
        return try {
            android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        } catch (_: Exception) { null }
    }

    /**
     * Диагностика: где именно рвётся загрузка медиа-ссылки (фото/голос/стикер).
     * Возвращает человекочитаемую причину: не найден на реле / не расшифровался /
     * манифест без чанков / битый base64 / файл цел (сбой воспроизведения).
     */
    suspend fun diagnoseMedia(ref: String): String = try {
        // ⚠️ ВРЕМЕННАЯ ДИАГНОСТИКА (не для релиза, см. TODO_REMOVE_EMPTY_MEDIA_CRASH):
        // раньше здесь был api.loadFileOrNull(ref), который ГЛОТАЕТ сообщение исключения
        // (любая причина схлопывалась в "файл не найден на реле"). Ловим loadFile()
        // напрямую, чтобы показать РЕАЛЬНУЮ причину из NostrTransport.loadFile(): не
        // ответило ни одно реле (сеть/Tor) / реле ответили но канала нет / реле ответили
        // но именно этого файла нет (не долетел при отправке).
        var loadError: String? = null
        val raw = try {
            withContext(Dispatchers.IO) { api.loadFile(ref) }
        } catch (e: Exception) {
            loadError = e.message ?: e.toString()
            null
        }
        when {
            raw == null -> loadError ?: "файл не найден на реле (причина неизвестна)"
            else -> {
                val dec = CryptoHelper.decrypt(raw, password, cryptoChatId)
                when {
                    dec == null -> "найден, не расшифр (len=${raw.length}, fmt=${raw.take(5)}, " +
                        "diag=[${if (raw.trim().startsWith("\$G4\$")) CryptoHelper.decryptV4Diag(raw.trim(), password, cryptoChatId) else "notV4"}])"
                    dec.startsWith(ImageChunker.CHUNKED_MARKER) -> {
                        val names = ImageChunker.parseManifest(dec)
                        if (names.isNullOrEmpty()) "манифест пуст/битый"
                        else {
                            // Реально грузим каждый чанк, смотрим его длину, склеиваем и
                            // пробуем расшифровать — чтобы понять, рвётся ли это на обрезке
                            // чанка реле, порядке или формате.
                            val parts = names.map { runCatching { api.loadFile(it) }.getOrNull() }
                            val lens = parts.map { it?.length ?: -1 }
                            val joined = parts.joinToString("") { it ?: "" }
                            val redec = CryptoHelper.decrypt(joined, password, cryptoChatId)
                            // Проверяем, валиден ли сам base64 склейки (после префикса $G4$):
                            // если нет — данные искажены в передаче; если да — целы, значит
                            // проблема в ключе/nonce (Argon2/chatId), а не в чанках.
                            val body = joined.removePrefix("\$G4\$")
                            val b64ok = runCatching {
                                android.util.Base64.decode(body, android.util.Base64.NO_WRAP).isNotEmpty()
                            }.getOrDefault(false)
                            // Хвосты крайних чанков — увидеть склейку на границах.
                            val edges = parts.mapIndexed { i, p ->
                                "c$i:${p?.take(3) ?: "NUL"}/${p?.takeLast(3) ?: ""}"
                            }
                            "чанки=${names.size} длины=$lens сумма=${joined.length} " +
                                if (redec == null) "НЕ расшифр fmt=${joined.take(5)} b64ok=$b64ok " +
                                    "diag=[${CryptoHelper.decryptV4Diag(joined, password, cryptoChatId)}] edges=$edges"
                                else "OK declen=${redec.length}"
                        }
                    }
                    else -> {
                        val bytes = runCatching {
                            android.util.Base64.decode(dec, android.util.Base64.NO_WRAP)
                        }.getOrNull()
                        if (bytes == null) "одиночный: base64 битый (declen=${dec.length})"
                        else "одиночный ок (байт=${bytes.size}) — сбой воспроизведения"
                    }
                }
            }
        }
    } catch (e: Exception) {
        "ошибка: ${e.message?.take(100)}"
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
    suspend fun loadBase64(fileName: String, onChunkProgress: ((current: Int, total: Int) -> Unit)? = null): String? {
        // Быстрый путь: уже в кеше
        ImageCache.getBase64(fileName)?.let { return it }

        // Негативный кэш: недавно упавшую загрузку НЕ повторяем (иначе битые фото грузятся
        // на каждый bind/тик → тяжёлый декод/дешифр в цикле → GC-шторм и ANR). Раз в
        // FAILED_RETRY_MS пробуем снова.
        failedLoads[fileName]?.let { ts ->
            if (System.currentTimeMillis() - ts < FAILED_RETRY_MS) return null
            failedLoads.remove(fileName)
        }

        // Дедупликация: пытаемся зарегистрироваться первыми
        val ours = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(fileName, ours)
        if (existing != null) {
            // Кто-то уже загружает — ждём его результата
            return existing.await()
        }

        // Мы первые — выполняем загрузку
        return try {
            val base64 = when {
                fileName.startsWith("gist:") -> loadFromImageSource(fileName)
                fileName.startsWith("http://", true) || fileName.startsWith("https://", true) -> {
                    loadFromExternalUrl(fileName)
                }
                else -> loadFromChatContent(fileName, onChunkProgress)
            }
            if (base64 != null) {
                ImageCache.put(fileName, base64, null)
                failedLoads.remove(fileName)
            } else {
                failedLoads[fileName] = System.currentTimeMillis()
            }
            ours.complete(base64)
            base64
        } catch (e: Exception) {
            failedLoads[fileName] = System.currentTimeMillis()
            ours.complete(null)
            null
        } finally {
            inFlight.remove(fileName)
        }
    }

    // ── Private loaders ────────────────────────────────────────────────────────

    /**
     * Загружает изображение по внешней ссылке http/https.
     * Использует Tor, если транспорт настроен на работу через него.
     */
    private suspend fun loadFromExternalUrl(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val client = createHttpClient(api.useTor)
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (compatible; Atrum/1.0)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val bytes = response.body?.bytes() ?: return@withContext null
                // Возвращаем как base64, чтобы ImageCache и ImageUtils.fromBase64 работали единообразно
                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun createHttpClient(useTor: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(if (useTor) 30 else 10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)

        if (useTor) {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", TorManager.SOCKS_PORT)))
        }
        return builder.build()
    }

    /**
     * Загружает изображение из отдельного источника (формат "gist:GIST_ID").
     * ChatTransport.loadImageByRef() склеивает чанки и возвращает зашифрованный контент.
     */
    private suspend fun loadFromImageSource(ref: String): String? {
        val encryptedContent = withContext(Dispatchers.IO) { api.loadImageByRef(ref) }
        return CryptoHelper.decrypt(encryptedContent, password, cryptoChatId)
    }

    /**
     * Загружает изображение из основного контента чата (старый формат).
     * Обрабатывает plain base64, CHUNKED-манифест и любые другие форматы.
     */
    private suspend fun loadFromChatContent(
        fileName: String,
        onChunkProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String? {
        val mainContent = loadFileRetry(fileName)
        val decrypted = CryptoHelper.decrypt(mainContent, password, cryptoChatId) ?: return null
        return if (decrypted.startsWith(ImageChunker.CHUNKED_MARKER)) {
            assembleChunkedImage(fileName, decrypted, onChunkProgress)
        } else {
            onChunkProgress?.invoke(1, 1)
            decrypted
        }
    }

    /**
     * Собирает CHUNKED-изображение из отдельных файлов основного контента чата.
     *
     * Проверка целостности (best-effort): если отправитель опубликовал файл с SHA-256
     * каждого чанка (см. ImageChunker.CHUNK_HASHES_MARKER), после скачивания сверяем
     * хеши и для НЕСОВПАВШИХ чанков делаем ОДИН точечный повторный запрос (сбросив
     * закэшированную копию, если транспорт — NostrTransport). Если хешей нет (старая
     * версия собеседника/старое фото) или их число не совпало — просто не проверяем,
     * как и раньше. Финальную сборку это НИКОГДА не блокирует сильнее, чем раньше —
     * даже если что-то в самой проверке пошло не так, попытка расшифровки всё равно
     * происходит (сама AES-GCM уже даёт финальную гарантию целостности).
     */
    private suspend fun assembleChunkedImage(
        mainFileName: String,
        manifest: String,
        onChunkProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String? {
        val chunkNames = ImageChunker.parseManifest(manifest) ?: return null
        if (chunkNames.isEmpty()) return null
        return try {
            // Чанки качаем ПАРАЛЛЕЛЬНО (с ограничением одновременных запросов), порядок
            // сохраняем по индексу — это резко ускоряет длинные медиа (голос 10+ мин =
            // сотни чанков): вместо последовательного цикла через Tor — пачками.
            // onChunkProgress репортит по мере ЗАВЕРШЕНИЯ каждого скачивания (не по
            // порядку индекса — чанки параллельны) — для UI этого достаточно, это
            // визуальная оценка «сколько уже скачано», как буфер-бар у YouTube, а не
            // точная позиция воспроизведения.
            val sem = Semaphore(MAX_PARALLEL_CHUNKS)
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val parts = coroutineScope {
                chunkNames.map { name ->
                    async(Dispatchers.IO) {
                        sem.withPermit { loadFileRetry(name) }.also {
                            onChunkProgress?.invoke(done.incrementAndGet(), chunkNames.size)
                        }
                    }
                }.awaitAll()
            }.toMutableList()

            verifyAndFixChunks(mainFileName, chunkNames, parts)

            CryptoHelper.decrypt(parts.joinToString(""), password, cryptoChatId)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Best-effort проверка SHA-256 каждого скачанного чанка против списка, который мог
     * опубликовать отправитель (см. NostrTransport.saveFileChunked). Битые чанки
     * перезапрашиваются РОВНО ОДИН раз (со сбросом кэша у NostrTransport — иначе
     * повторный loadFile() тихо вернёт ту же испорченную копию). [parts] правится на месте.
     */
    private suspend fun verifyAndFixChunks(
        mainFileName: String,
        chunkNames: List<String>,
        parts: MutableList<String>
    ) {
        val hashesName = ImageChunker.chunkHashesFileName(mainFileName)
        val hashesRaw = try {
            withContext(Dispatchers.IO) { api.loadFileOrNull(hashesName) }
        } catch (_: Exception) { null } ?: return
        val hashesDecrypted = CryptoHelper.decrypt(hashesRaw, password, cryptoChatId) ?: return
        val expectedHashes = ImageChunker.parseChunkHashes(hashesDecrypted, chunkNames.size) ?: return

        for (i in chunkNames.indices) {
            if (ImageChunker.sha256Hex(parts[i]) == expectedHashes[i]) continue
            android.util.Log.w("AtrumImageLoader",
                "Чанк ${chunkNames[i]} не прошёл проверку целостности — точечный повтор")
            (api as? com.atrum.chat.transport.NostrTransport)?.evictCachedFile(chunkNames[i])
            val refetched = try {
                withContext(Dispatchers.IO) { loadFileRetry(chunkNames[i], attempts = 3) }
            } catch (_: Exception) { null } ?: continue
            if (ImageChunker.sha256Hex(refetched) == expectedHashes[i]) {
                parts[i] = refetched
            } else {
                android.util.Log.w("AtrumImageLoader",
                    "Чанк ${chunkNames[i]} всё ещё не сходится после повтора — продолжаем как есть")
            }
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
