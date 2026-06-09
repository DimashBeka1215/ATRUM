package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Тонкая обёртка над системным BiometricPrompt.
 *
 * ВАЖНО (требования безопасности — высший приоритет):
 *  - Приложение НЕ хранит и НЕ регистрирует биометрию. Оно лишь спрашивает у
 *    системной подсистемы телефона «это владелец?» и получает да/нет.
 *  - На Samsung вся биометрия проходит через Knox/TEE — мы туда не лезем.
 *  - Регистрация отпечатка возможна только в системных настройках телефона,
 *    не внутри Atrum.
 *  - Отпечаток ОДНОРАЗОВЫЙ: каждый вход требует свежего системного запроса.
 *    Авторизация нигде не кешируется. Это обеспечено криптогейтом ниже —
 *    Keystore-ключ с авторизацией «на одну операцию» (auth-per-use). После
 *    единственной крипто-операции авторизация «сгорает» и для следующего входа
 *    нужен новый отпечаток.
 *  - Используется только сильная биометрия (BIOMETRIC_STRONG, class 3).
 *  - Если набор биометрии в телефоне изменился (добавили/удалили отпечаток),
 *    ключ инвалидируется системой → вход по отпечатку временно недоступен,
 *    пользователь входит по PIN. Чужой только что добавленный отпечаток не
 *    откроет приложение.
 */
object BiometricHelper {

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_NAME = "atrum_biometric_gate_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** Сырой статус от системы. */
    fun status(context: Context): Int =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)

    /**
     * Есть ли в телефоне физический сканер биометрии.
     * Если железа нет — строку «Вход по отпечатку» вообще не показываем.
     */
    fun hasHardware(context: Context): Boolean {
        return when (status(context)) {
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> false
            else -> true
        }
    }

    /** Готова ли биометрия к использованию прямо сейчас (есть железо и есть отпечаток). */
    fun canUse(context: Context): Boolean =
        status(context) == BiometricManager.BIOMETRIC_SUCCESS

    /** Железо есть, но пользователь ещё не добавил отпечаток в системе. */
    fun isNoneEnrolled(context: Context): Boolean =
        status(context) == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

    /**
     * Системный экран регистрации отпечатка. На Android 11+ есть прямой intent,
     * на старых версиях открываем общие настройки безопасности.
     */
    fun enrollIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED, AUTHENTICATORS)
            }
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
    }

    // ─── Криптогейт: гарантия свежей одноразовой биометрии ──────────────────────

    /**
     * Создаёт (или возвращает существующий) Keystore-ключ, который можно
     * использовать ТОЛЬКО сразу после успешной биометрической аутентификации,
     * и ровно на одну операцию (auth-per-use). Ключ нельзя экспортировать —
     * он живёт в защищённом аппаратном хранилище (на Samsung — Knox/TEE).
     */
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_NAME, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            KEY_NAME,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            // Новый/удалённый отпечаток в системе → ключ становится недействительным.
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 секунд = авторизация требуется на КАЖДУЮ операцию (одноразово),
            // только сильной биометрией.
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        generator.init(builder.build())
        return generator.generateKey()
    }

    /** Удалить ключ криптогейта (например после инвалидации новым отпечатком). */
    private fun deleteKey() {
        try {
            KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(KEY_NAME)
        } catch (_: Exception) {
        }
    }

    /**
     * Готовит CryptoObject для одной аутентификации.
     * Возвращает null, если ключ инвалидирован (сменился набор биометрии) или
     * криптогейт по какой-то причине недоступен — тогда вход по отпечатку
     * НЕ выполняется, пользователь входит по PIN (без тихого ослабления защиты).
     */
    private fun buildCryptoObject(): BiometricPrompt.CryptoObject? {
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            BiometricPrompt.CryptoObject(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            // Набор биометрии изменился — пересоздадим ключ при следующем запуске,
            // сейчас вход только по PIN.
            deleteKey()
            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Показать системный диалог отпечатка.
     * Окно рисует сама система (Android / Samsung Knox), не приложение.
     *
     * Успех подтверждается РЕАЛЬНОЙ крипто-операцией ключом, который авторизуется
     * только свежим отпечатком — поэтому подделать колбэк onSuccess невозможно.
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        negativeButton: String,
        onSuccess: () -> Unit,
        onError: (code: Int, message: CharSequence) -> Unit,
        onFailed: () -> Unit = {}
    ) {
        val crypto = buildCryptoObject()
        if (crypto == null) {
            onError(BiometricPrompt.ERROR_NO_BIOMETRICS, "biometric crypto gate unavailable")
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Доказательство подлинности: выполняем одну крипто-операцию.
                    // Если ключ не был авторизован свежим отпечатком — doFinal бросит
                    // исключение, и мы НЕ пустим в приложение.
                    val cipher = result.cryptoObject?.cipher
                    val proven = try {
                        cipher?.doFinal("atrum".toByteArray(Charsets.UTF_8))
                        cipher != null
                    } catch (_: Exception) {
                        false
                    }
                    if (proven) onSuccess()
                    else onError(BiometricPrompt.ERROR_VENDOR, "biometric crypto op failed")
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errorCode, errString)
                }

                override fun onAuthenticationFailed() {
                    onFailed()
                }
            })

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negativeButton)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .setConfirmationRequired(false)
            .build()

        prompt.authenticate(info, crypto)
    }
}
