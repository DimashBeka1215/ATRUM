package com.atrum.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Превью ссылок (Open Graph).
 *
 * Приватность: og-данные тянет ОТПРАВИТЕЛЬ через Tor и заливает в ОТДЕЛЬНЫЙ файл на реле
 * с детерминированным именем lp_<hash(url)>. Получатель грузит этот файл с реле (как
 * картинку) и НЕ ходит на сам сайт — нет утечки активности получателя. Формат сообщения
 * не меняется: превью живёт как отдельный файл, рендерится по URL из текста.
 */
data class LinkPreviewData(
    val url: String,
    val title: String,
    val description: String,
    val site: String,
    val thumbBase64: String?
) {
    fun toJson(): String = JSONObject().apply {
        put("u", url); put("t", title); put("d", description); put("s", site)
        if (thumbBase64 != null) put("i", thumbBase64)
    }.toString()

    companion object {
        fun fromJson(s: String): LinkPreviewData? = try {
            val j = JSONObject(s)
            LinkPreviewData(
                url = j.optString("u"),
                title = j.optString("t"),
                description = j.optString("d"),
                site = j.optString("s"),
                thumbBase64 = j.optString("i", null)?.takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) { null }
    }
}

object LinkPreview {

    /** Детерминированное имя файла-превью по URL — одинаково у всех клиентов. */
    fun fileName(url: String): String = "lp_" + sha256hex(url.trim()).take(24)

    /** Первый http(s)-URL в тексте (как Linkify), или null. */
    fun firstUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = android.util.Patterns.WEB_URL.matcher(text)
        return if (m.find()) m.group() else null
    }

    private fun sha256hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun client(useTor: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(if (useTor) 40 else 12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
        if (useTor) b.proxy(
            Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved("127.0.0.1", TorManager.SOCKS_PORT))
        )
        return b.build()
    }

    /** Тянет og-превью. Возвращает null при любой ошибке (fail-safe). */
    fun fetch(url: String, useTor: Boolean): LinkPreviewData? = try {
        val normalized = if (url.startsWith("http", true)) url else "https://$url"
        val cl = client(useTor)
        val req = Request.Builder().url(normalized)
            .header("User-Agent", "Mozilla/5.0 (compatible; AtrumBot/1.0)")
            .header("Accept", "text/html")
            .build()
        cl.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.peekBody(300_000L).string()
            val title = meta(body, "og:title") ?: meta(body, "twitter:title") ?: htmlTitle(body) ?: return null
            val desc = meta(body, "og:description") ?: meta(body, "twitter:description") ?: ""
            val site = meta(body, "og:site_name") ?: host(normalized)
            val imgUrl = meta(body, "og:image") ?: meta(body, "twitter:image")
            val thumb = imgUrl?.let { runCatching { fetchThumb(absolutize(it, normalized), cl) }.getOrNull() }
            LinkPreviewData(
                normalized,
                title.trim().take(200),
                desc.trim().take(300),
                site.trim().take(60),
                thumb
            )
        }
    } catch (_: Throwable) { null }

    private fun meta(html: String, prop: String): String? {
        val esc = Regex.escape(prop)
        Regex("""<meta[^>]+(?:property|name)=["']$esc["'][^>]+content=["']([^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return decodeEntities(it) }
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']$esc["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return decodeEntities(it) }
        return null
    }

    private fun htmlTitle(html: String): String? =
        Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { decodeEntities(it.trim()) }?.takeIf { it.isNotBlank() }

    private fun decodeEntities(s: String): String = s
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")

    private fun host(url: String): String = try { java.net.URI(url).host ?: url } catch (_: Exception) { url }

    private fun absolutize(img: String, base: String): String = try {
        if (img.startsWith("http", true)) img else java.net.URI(base).resolve(img).toString()
    } catch (_: Exception) { img }

    private fun fetchThumb(imgUrl: String, cl: OkHttpClient): String? {
        val req = Request.Builder().url(imgUrl).build()
        cl.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val bytes = resp.body?.bytes() ?: return null
            if (bytes.size > 5_000_000) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val maxDim = 256
            var sample = 1
            while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
            val bmp = BitmapFactory.decodeByteArray(
                bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
            bmp.recycle()
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }
}
