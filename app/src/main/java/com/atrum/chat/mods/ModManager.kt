package com.atrum.chat.mods

import com.atrum.chat.nostr.Schnorr
import com.atrum.chat.nostr.hexToBytes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Менеджер модов — Фаза 1: только каталог + проверка подписи + список.
 * Загрузка .dex (DexClassLoader) и установка — Фаза 2.
 *
 * Доверие: подпись каталога издательским Ed25519-ключом ([PUBLISHER_PUBKEY_B64]).
 * Пока ключ не вшит — каталог показывается с явной пометкой «подпись не проверена»
 * (dev-режим Фазы 1). В релизе с настроенным издателем непроверенный каталог
 * отклоняется (см. [fetchCatalog]).
 */
object ModManager {

    /** raw-URL подписанного каталога. */
    const val CATALOG_URL = "https://source.atrum.chat/mods/main/catalog.json"

    /**
     * Издательский публичный ключ (Schnorr/secp256k1, hex 64 символа) — якорь доверия,
     * как PUBLISHER_PUBKEY_HEX в RelayListStore. Приватная часть — у издателя
     * (экран «Издатель» / офлайн), в приложение для проверки не нужна.
     */
    const val PUBLISHER_PUBKEY_HEX =
        "babb366434dc20108f63d699a04a3f302105c786919e2a5983d98070d4a5a8fe"

    fun publisherConfigured(): Boolean = PUBLISHER_PUBKEY_HEX.length == 64

    /** Проверка подписи издателя над каноничной строкой: Schnorr над SHA-256(canonical). */
    fun verifyPublisher(canonical: String, sigHex: String): Boolean = try {
        if (!publisherConfigured() || sigHex.isBlank()) {
            false
        } else {
            val msg = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            Schnorr.verify(PUBLISHER_PUBKEY_HEX.hexToBytes(), msg, sigHex.hexToBytes())
        }
    } catch (_: Exception) {
        false
    }

    sealed class Result {
        /** [verified] = подпись каталога валидна (false в dev-режиме без издателя). */
        data class Ok(val mods: List<ModInfo>, val verified: Boolean) : Result()
        data class Err(val message: String) : Result()
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /** Тянет каталог, проверяет подпись (если издатель настроен), возвращает список модов. */
    suspend fun fetchCatalog(): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(CATALOG_URL).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.Err("HTTP ${resp.code}")
                val body = resp.body?.string()
                    ?: return@withContext Result.Err("пустой ответ")
                val cat = ModCatalogParser.parse(body)
                    ?: return@withContext Result.Err("каталог не разобран")

                val verified = verifyPublisher(cat.canonical, cat.signature)
                // В релизе (издатель настроен) непроверенный каталог НЕ показываем.
                if (publisherConfigured() && !verified) {
                    return@withContext Result.Err("подпись каталога не прошла проверку")
                }
                Result.Ok(cat.mods, verified)
            }
        } catch (e: Exception) {
            Result.Err(e.message ?: "ошибка сети")
        }
    }
}
