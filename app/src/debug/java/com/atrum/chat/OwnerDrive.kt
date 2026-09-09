package com.atrum.chat

import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Хранение зашифрованной копии identity-ключа в СКРЫТОЙ папке приложения на Google Drive
 * (`appDataFolder`).
 *
 * Почему именно appDataFolder: этой папки не видно в интерфейсе Drive, её содержимое не
 * индексируется и читать её может ТОЛЬКО это приложение под этим аккаунтом. Никаких других
 * файлов пользователя приложение не видит — запрошен единственный scope `drive.appdata`.
 *
 * Почему REST через OkHttp, а не google-api-services-drive: официальная библиотека тянет
 * десяток мегабайт транзитивных зависимостей ради четырёх HTTP-запросов. OkHttp в проекте
 * уже есть, а запросов ровно четыре — поиск, создание, перезапись, скачивание.
 *
 * ⚠️ Все методы БЛОКИРУЮЩИЕ (сеть) — вызывать только из фонового потока.
 */
object OwnerDrive {

    /** Имя файла копии внутри appDataFolder. */
    private const val FILE_NAME = "owner_key.atr"

    /** Единственный запрашиваемый доступ: скрытая папка ЭТОГО приложения, и ничего больше. */
    const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"

    private const val API = "https://www.googleapis.com/drive/v3/files"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** OAuth-токен доступа для [SCOPE]. Бросает, если пользователь отозвал доступ. */
    private fun token(context: Context, account: GoogleSignInAccount): String {
        val acc = account.account ?: throw IllegalStateException("нет системного аккаунта")
        return GoogleAuthUtil.getToken(context, acc, "oauth2:$SCOPE")
    }

    /** id файла копии или null, если копии ещё нет. */
    private fun findFileId(tok: String): String? {
        val q = URLEncoder.encode("name = '$FILE_NAME'", "UTF-8")
        val req = Request.Builder()
            .url("$API?spaces=appDataFolder&q=$q&fields=files(id)&pageSize=1")
            .header("Authorization", "Bearer $tok")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Drive поиск: HTTP ${r.code}")
            val files: JSONArray = JSONObject(r.body?.string().orEmpty()).optJSONArray("files")
                ?: return null
            return if (files.length() == 0) null else files.getJSONObject(0).optString("id").ifBlank { null }
        }
    }

    /**
     * Кладёт [blob] в скрытую папку, перезаписывая прежнюю копию, если она была.
     * Перезапись, а не создание второй копии: иначе после нескольких сохранений было бы
     * непонятно, какая из них актуальна.
     */
    fun save(context: Context, account: GoogleSignInAccount, blob: ByteArray) {
        val tok = token(context, account)
        val body = blob.toRequestBody("application/octet-stream".toMediaType())
        val existing = findFileId(tok)

        val req = if (existing != null) {
            Request.Builder()
                .url("$UPLOAD/$existing?uploadType=media")
                .header("Authorization", "Bearer $tok")
                .patch(body)
                .build()
        } else {
            val meta = JSONObject()
                .put("name", FILE_NAME)
                .put("parents", JSONArray().put("appDataFolder"))
                .toString()
            val multipart = MultipartBody.Builder()
                .setType("multipart/related".toMediaType())
                .addPart(meta.toRequestBody("application/json; charset=UTF-8".toMediaType()))
                .addPart(body)
                .build()
            Request.Builder()
                .url("$UPLOAD?uploadType=multipart&fields=id")
                .header("Authorization", "Bearer $tok")
                .post(multipart)
                .build()
        }

        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Drive запись: HTTP ${r.code}")
        }
    }

    /** Содержимое копии или null, если её там нет. */
    fun load(context: Context, account: GoogleSignInAccount): ByteArray? {
        val tok = token(context, account)
        val id = findFileId(tok) ?: return null
        val req = Request.Builder()
            .url("$API/$id?alt=media")
            .header("Authorization", "Bearer $tok")
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Drive чтение: HTTP ${r.code}")
            return r.body?.bytes()?.takeIf { it.isNotEmpty() }
        }
    }
}
