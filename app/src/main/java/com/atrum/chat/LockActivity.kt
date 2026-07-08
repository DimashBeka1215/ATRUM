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
        // Если функция была удалена навсегда (отказ при первом включении) — никогда.
        biometricEnabled = !prefs.biometricRemoved &&
            prefs.biometricEnabled && BiometricHelper.canUse(this)
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
        // БАГ (исправлено): раньше здесь БЕЗУСЛОВНО открывался ChatsListActivity —
        // это ломало любой сценарий, где LockActivity вызван как "повторная блокировка"
        // из SecureActivity.onStart() поверх УЖЕ существующего защищённого экрана
        // (например JoinChatActivity, открытый по deep-link atrum://join из внешнего
        // QR-сканера/камеры). Такой экран НЕ делает finish() перед показом LockActivity —
        // он остаётся в стеке под ней. Принудительный переход в ChatsListActivity после
        // разблокировки выбрасывал этот экран из стека, и вместе с ним — необработанное
        // приглашение (JoinChatActivity так и не успевал показать свой диалог ввода PIN
        // инвайта и вызвать runConnect()). Пользователь просто попадал в список чатов
        // без единого намёка, что он вообще сканировал приглашение.
        //
        // ChatsListActivity нужен явно ТОЛЬКО при холодном старте (WelcomeActivity уже
        // сделал finish() перед запуском LockActivity — под ней в стеке пусто, "просто
        // finish()" привёл бы на рабочий стол). Это единственный вызывающий, который
        // передаёт EXTRA_COLD_START. Во всех остальных случаях (повторная блокировка)
        // достаточно закрыть LockActivity — система сама покажет то, что было под ней,
        // со всем его состоянием (включая ещё не обработанный deep-link).
        if (intent.getBooleanExtra(EXTRA_COLD_START, false)) {
            startActivity(Intent(this, ChatsListActivity::class.java))
        }
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

    companion object {
        /**
         * Передаётся ТОЛЬКО из WelcomeActivity (холодный старт) — единственный вызывающий,
         * который сам делает finish() ДО запуска LockActivity, так что под ней в стеке
         * пусто. Только в этом случае unlock() обязан явно открыть ChatsListActivity.
         * SecureActivity.onStart() (повторная блокировка поверх любого защищённого экрана)
         * эту extra НЕ передаёт — там под LockActivity в стеке уже есть нужный экран.
         */
        const val EXTRA_COLD_START = "cold_start"
    }
}
