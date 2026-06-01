package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Legacy stub. С Device Flow deep link не нужен, эта Activity больше
 * не используется. Оставлена пустой чтобы старые ссылки (если есть) не
 * вызывали падение.
 */
class OAuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, ChatsListActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
