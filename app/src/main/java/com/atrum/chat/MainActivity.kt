package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Устарел: теперь точка входа — WelcomeActivity, а старая логика логина переехала
 * в OnboardingActivity (ник/пароль) и CreateChatActivity (токен/gist/пароль чата).
 * Этот класс остаётся как редирект на случай если его случайно запустят извне.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }
}
