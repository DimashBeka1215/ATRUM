package com.atrum.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Реализует GitHub OAuth Device Flow.
 *
 *   1. requestDeviceCode() — получаем device_code + user_code у GitHub.
 *      user_code — это 8 символов вида "WDJB-MJHT" который мы показываем
 *      пользователю и просим ввести на github.com/login/device.
 *
 *   2. pollForToken(deviceCode, interval) — в фоне опрашиваем GitHub
 *      каждые `interval` секунд пока пользователь не подтвердит. Когда
 *      подтвердил — возвращается access_token.
 *
 *   Архитектурно: токен — это только transport-credential для GitHub API.
 *   Криптография сообщений на него никак не завязана.
 */
object OAuthManager {

    /** Результат начального запроса device_code. */
    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSec: Int,
        val pollIntervalSec: Int
    )

    sealed class TokenResult {
        data class Success(val accessToken: String) : TokenResult()
        object Pending : TokenResult()
        object SlowDown : TokenResult()
        object Expired : TokenResult()
        object AccessDenied : TokenResult()
        data class Error(val message: String) : TokenResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "GitHub-CLI/2.0.0")
                .build()
            chain.proceed(request)
        }
        .build()

    /**
     * Шаг 1: запрашиваем device_code у GitHub.
     */
    suspend fun requestDeviceCode(): DeviceCodeResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", OAuthConfig.CLIENT_ID)
            .add("scope", OAuthConfig.SCOPE)
            .build()

        val req = Request.Builder()
            .url(OAuthConfig.DEVICE_CODE_URL)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = resp.body?.string()?.take(200).orEmpty()
                throw RuntimeException("HTTP ${resp.code}: $err")
            }
            val body = resp.body?.string() ?: throw RuntimeException("Пустой ответ")
            val json = JSONObject(body)

            val userCode = json.getString("user_code")
            val verifyUri = json.optString("verification_uri", OAuthConfig.VERIFICATION_URL)
            // verification_uri_complete уже содержит user_code — открыть его в браузере
            // и пользователь увидит экран сразу с подтверждением.
            val verifyComplete = json.optString("verification_uri_complete",
                "$verifyUri?user_code=$userCode")

            DeviceCodeResponse(
                deviceCode = json.getString("device_code"),
                userCode = userCode,
                verificationUri = verifyUri,
                verificationUriComplete = verifyComplete,
                expiresInSec = json.optInt("expires_in", 900),
                pollIntervalSec = json.optInt("interval", 5)
            )
        }
    }

    /**
     * Шаг 2: один запрос на получение access_token.
     * Возвращает Pending если пользователь ещё не подтвердил, иначе токен или ошибку.
     */
    suspend fun pollForToken(deviceCode: String): TokenResult = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("client_id", OAuthConfig.CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()

        val req = Request.Builder()
            .url(OAuthConfig.TOKEN_URL)
            .header("Accept", "application/json")
            .post(formBody)
            .build()

        try {
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: return@withContext TokenResult.Error("Empty body")
                val json = JSONObject(body)

                val accessToken = json.optString("access_token", null)
                if (!accessToken.isNullOrBlank()) {
                    return@withContext TokenResult.Success(accessToken)
                }

                val error = json.optString("error", "")
                when (error) {
                    "authorization_pending" -> TokenResult.Pending
                    "slow_down" -> TokenResult.SlowDown
                    "expired_token" -> TokenResult.Expired
                    "access_denied" -> TokenResult.AccessDenied
                    else -> TokenResult.Error(error.ifBlank { "HTTP ${resp.code}" })
                }
            }
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: "network error")
        }
    }

    /**
     * Удобный helper: запустить poll-цикл до получения токена или ошибки.
     * Уважает interval / slow_down / expired сигналы от GitHub.
     */
    suspend fun pollUntilDone(
        deviceCode: String,
        initialIntervalSec: Int,
        expiresInSec: Int,
        onStatus: (String) -> Unit = {}
    ): TokenResult {
        var interval = initialIntervalSec.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + expiresInSec * 1000L

        while (System.currentTimeMillis() < deadline) {
            delay(interval * 1000L)
            when (val r = pollForToken(deviceCode)) {
                is TokenResult.Success -> return r
                TokenResult.Pending -> { /* продолжаем ждать */ }
                TokenResult.SlowDown -> {
                    interval += 5
                    onStatus("Замедляем опрос…")
                }
                TokenResult.Expired -> return r
                TokenResult.AccessDenied -> return r
                is TokenResult.Error -> {
                    // Любые сетевые ошибки ретраим — мы ограничены дедлайном device_code.
                    // Прерываем только при явном отказе доступа или истечении кода
                    // (они возвращаются как AccessDenied / Expired, а не Error).
                    continue
                }
            }
        }
        return TokenResult.Expired
    }
}
