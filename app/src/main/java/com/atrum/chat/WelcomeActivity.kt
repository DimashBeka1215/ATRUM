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
            val next = if (prefs.hasLocalPassword()) LockActivity::class.java
            else ChatsListActivity::class.java
            startActivity(Intent(this, next))
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
