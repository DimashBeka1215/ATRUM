package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import com.atrum.chat.databinding.ActivityWelcomeBinding

/**
 * Точка входа. Решает куда отправить пользователя:
 *  - не онбордился → OnboardingActivity (ник + опц. пароль)
 *  - онбордился, есть локальный пароль → LockActivity
 *  - онбордился, без пароля → ChatsListActivity
 *
 * Кнопка "Начать" на welcome-заставке показывается только при первом запуске.
 */
class WelcomeActivity : SecureActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        // Уже онбордился — пропускаем заставку
        if (prefs.isOnboarded) {
            val locked = prefs.hasLocalPassword()
            val next = if (locked) LockActivity::class.java else ChatsListActivity::class.java
            startActivity(Intent(this, next).apply {
                // Холодный старт: мы сейчас сами сделаем finish() — под LockActivity в
                // стеке будет пусто, поэтому она обязана явно открыть ChatsListActivity
                // после разблокировки (см. LockActivity.EXTRA_COLD_START).
                if (locked) putExtra(LockActivity.EXTRA_COLD_START, true)
            })
            finish()
            return
        }

        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnStart.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
        }
    }
}
