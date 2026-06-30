package com.atrum.chat.mods

import android.content.Context
import dalvik.system.DexClassLoader
import dalvik.system.InMemoryDexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Загрузчик модов (Фаза 2). САМЫЙ чувствительный код проекта — исполняет внешний код.
 *
 * Инвариант безопасности: код мода исполняется ТОЛЬКО после успешной проверки:
 *   1. размер .dex в пределах [MAX_DEX_BYTES] (анти-DoS),
 *   2. SHA-256 .dex == заявленному в каталоге,
 *   3. подпись издателя над каноничной строкой "id|version|entryClass|sha256" валидна.
 * Любая осечка → Err, ничего не грузится. Без настроенного издателя загрузка запрещена.
 *
 * Загрузка через InMemoryDexClassLoader (API 26+) — НЕ пишем исполняемый файл на диск
 * (нет проблемы W^X и подмены файла). На API 24–25 — read-only файл в codeCacheDir.
 */
object ModLoader {

    private const val MAX_DEX_BYTES = 8 * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    sealed class Result {
        data class Ok(val mod: AtrumMod) : Result()
        data class Err(val message: String) : Result()
    }

    suspend fun downloadAndLoad(context: Context, info: ModInfo, host: ModHost): Result =
        withContext(Dispatchers.IO) {
            try {
                if (!ModManager.publisherConfigured())
                    return@withContext Result.Err("издатель не настроен — загрузка модов отключена")
                if (info.entryClass.isBlank())
                    return@withContext Result.Err("в каталоге не указан entryClass")

                // 1. скачать .dex
                val dexBytes = fetchDex(info.dexUrl)
                    ?: return@withContext Result.Err("не удалось скачать мод")
                if (dexBytes.size > MAX_DEX_BYTES)
                    return@withContext Result.Err("мод слишком большой")

                // 2. SHA-256
                val sha = sha256Hex(dexBytes)
                if (!sha.equals(info.dexSha256, ignoreCase = true))
                    return@withContext Result.Err("хеш мода не совпал")

                // 3. подпись издателя (Schnorr над SHA-256 каноничной строки)
                val canonical = "${info.id}|${info.version}|${info.entryClass}|$sha"
                if (!ModManager.verifyPublisher(canonical, info.signature))
                    return@withContext Result.Err("подпись мода неверна")

                // 4. загрузка кода — ТОЛЬКО после всех проверок
                val loader = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    InMemoryDexClassLoader(ByteBuffer.wrap(dexBytes), javaClass.classLoader)
                } else {
                    val f = File(context.codeCacheDir, "mod_${sanitize(info.id)}.dex")
                    f.writeBytes(dexBytes)
                    f.setReadOnly()
                    DexClassLoader(f.absolutePath, context.codeCacheDir.absolutePath, null, javaClass.classLoader)
                }
                val cls = loader.loadClass(info.entryClass)
                val instance = cls.getDeclaredConstructor().newInstance()
                val mod = instance as? AtrumMod
                    ?: return@withContext Result.Err("entryClass не реализует AtrumMod")
                if (mod.id != info.id)
                    return@withContext Result.Err("id мода не совпал с каталогом")

                mod.onLoad(host)
                Result.Ok(mod)
            } catch (e: Throwable) {
                Result.Err(e.message ?: "ошибка загрузки мода")
            }
        }

    private fun fetchDex(url: String): ByteArray? = try {
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.bytes()
        }
    } catch (_: Exception) {
        null
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }

    private fun sanitize(id: String): String =
        id.substringAfterLast('/').substringAfterLast('\\').ifBlank { "mod" }
}
