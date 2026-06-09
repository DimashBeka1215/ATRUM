package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.atrum.chat.databinding.ActivityLockBinding

/**
 * Экран блокировки. Показывается если у пользователя установлен локальный пароль (PIN),
 * а также при каждом возврате приложения из фона (см. SecureActivity / AppLock).
 *
 * Если включён вход по отпечатку — на экране есть выбор: ввести PIN или приложить
 * палец. Отпечаток проверяется системным BiometricPrompt (на Samsung — через Knox/TEE);
 * приложение саму биометрию не хранит и не регистрирует. Отпечаток одноразовый:
 * каждый показ экрана запрашивает свежую аутентификацию.
 */
class LockActivity : SecureActivity() {

    // Сам экран блокировки не должен повторно блокировать себя.
    override val lockProtected: Boolean = false

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: Prefs
    private var biometricEnabled = false
    private var scanAnimator: android.animation.ObjectAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        binding.btnUnlock.setOnClickListener { tryUnlock() }
        binding.etPwd.setOnEditorActionListener { _, _, _ ->
            tryUnlock()
            true
        }

        // Применяем сохранённый троттлинг неверного PIN (не сбрасывается рестартом).
        enforceLockout()

        // Вход по отпечатку доступен, только если пользователь его включил
        // и система телефона готова (есть железо + добавлен отпечаток).
        biometricEnabled = prefs.biometricEnabled && BiometricHelper.canUse(this)
        if (biometricEnabled) {
            binding.orDivider.visibility = View.VISIBLE
            binding.btnBiometric.visibility = View.VISIBLE
            binding.btnBiometric.setOnClickListener { showBiometricPrompt() }
            binding.btnFpHelp.visibility = View.VISIBLE
            binding.btnFpHelp.setOnClickListener { toggleFpHelp() }
            startScanAnimation()
            // Сразу предлагаем системный диалог отпечатка.
            showBiometricPrompt()
        } else {
            binding.orDivider.visibility = View.GONE
            binding.btnBiometric.visibility = View.GONE
            binding.etPwd.requestFocus()
        }
    }

    private fun showBiometricPrompt() {
        BiometricHelper.authenticate(
            activity = this,
            title = getString(R.string.biometric_prompt_title),
            subtitle = getString(R.string.biometric_prompt_subtitle),
            negativeButton = getString(R.string.biometric_prompt_cancel),
            onSuccess = { unlock() },
            onError = { _, _ ->
                // Пользователь отменил или система недоступна — остаётся вход по PIN.
                binding.etPwd.requestFocus()
            }
        )
    }

    private fun unlock() {
        // Сбрасываем троттлинг и снимаем глобальную блокировку.
        prefs.pinFailCount = 0
        prefs.pinLockoutUntil = 0L
        AppLock.locked = false
        startActivity(Intent(this, ChatsListActivity::class.java))
        finish()
    }

    /** Блокирует кнопку входа, если ещё действует штрафная задержка. */
    private fun enforceLockout() {
        val remaining = prefs.pinLockoutUntil - System.currentTimeMillis()
        if (remaining > 0) {
            binding.btnUnlock.isEnabled = false
            binding.btnUnlock.postDelayed({ binding.btnUnlock.isEnabled = true }, remaining)
        }
    }

    private fun tryUnlock() {
        // Не проверяем PIN пока действует штрафная задержка.
        if (prefs.pinLockoutUntil - System.currentTimeMillis() > 0) {
            Toast.makeText(this, R.string.lock_wrong, Toast.LENGTH_SHORT).show()
            return
        }
        val pwd = binding.etPwd.text.toString()
        if (prefs.checkLocalPassword(pwd)) {
            prefs.localPasswordPlaintext = pwd
            unlock()
        } else {
            val fails = prefs.pinFailCount + 1
            prefs.pinFailCount = fails
            val delayMs = when {
                fails >= 10 -> 30_000L
                fails >= 5  -> 5_000L
                fails >= 3  -> 2_000L
                else        -> 0L
            }
            if (delayMs > 0) {
                prefs.pinLockoutUntil = System.currentTimeMillis() + delayMs
            }
            Toast.makeText(this, R.string.lock_wrong, Toast.LENGTH_SHORT).show()
            binding.etPwd.setText("")
            if (delayMs > 0) {
                binding.btnUnlock.isEnabled = false
                binding.btnUnlock.postDelayed({ binding.btnUnlock.isEnabled = true }, delayMs)
            }
        }
    }

    private var fpHelpShown = false

    /** Показ/скрытие плашки-объяснения про вход по отпечатку с плавной анимацией. */
    private fun toggleFpHelp() {
        val card = binding.cardFpHelp
        val dy = 16f * resources.displayMetrics.density
        fpHelpShown = !fpHelpShown
        if (fpHelpShown) {
            card.visibility = View.VISIBLE
            card.alpha = 0f
            card.translationY = dy
            card.animate().alpha(1f).translationY(0f).setDuration(260).start()
            binding.btnFpHelp.setImageResource(R.drawable.ic_close)
        } else {
            card.animate().alpha(0f).translationY(dy).setDuration(220)
                .withEndAction { card.visibility = View.GONE }.start()
            binding.btnFpHelp.setImageResource(R.drawable.ic_question)
        }
    }

    /**
     * Анимация ожидания сканера: луч (v_scan_line) ходит вверх-вниз по иконке
     * отпечатка, пока экран блокировки открыт. Обрезка по кругу — clipToOutline у fp_circle.
     */
    private fun startScanAnimation() {
        val circle = binding.fpCircle
        val line = binding.vScanLine
        circle.post {
            val travel = (circle.height - line.height).toFloat().coerceAtLeast(0f)
            line.translationY = 0f
            scanAnimator = android.animation.ObjectAnimator.ofFloat(
                line, View.TRANSLATION_Y, 0f, travel
            ).apply {
                duration = 1100
                repeatCount = android.animation.ObjectAnimator.INFINITE
                repeatMode = android.animation.ObjectAnimator.REVERSE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    override fun onDestroy() {
        scanAnimator?.cancel()
        scanAnimator = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // Не даём выйти из приложения через back — пусть пользователь введёт пароль
        moveTaskToBack(true)
    }
}
