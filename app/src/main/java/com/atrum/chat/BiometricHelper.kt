package com.atrum.chat

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Тонкая обёртка над системным BiometricPrompt.
 *
 * ВАЖНО (требование безопасности):
 *  - Приложение НЕ хранит и НЕ регистрирует биометрию. Оно лишь спрашивает у
 *    системной подсистемы телефона «это владелец?» и получает да/нет.
 *  - На Samsung вся биометрия проходит через Knox/TEE — мы туда не лезем.
 *  - Регистрация отпечатка возможна только в системных настройках телефона,
 *    не внутри Atrum.
 *
 * Используется только сильная биометрия (BIOMETRIC_STRONG, class 3).
 */
object BiometricHelper {

    private const val AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG

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

    /**
     * Показать системный диалог отпечатка.
     * Окно рисует сама система (Android / Samsung Knox), не приложение.
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
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
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

        prompt.authenticate(info)
    }
}
