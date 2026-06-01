package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityLockBinding

/**
 * Экран блокировки. Показывается если у пользователя установлен локальный пароль.
 */
class LockActivity : SecureActivity() {

    private lateinit var binding: ActivityLockBinding
    private lateinit var prefs: Prefs
    private var failCount = 0

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
        binding.etPwd.requestFocus()
    }

    private fun tryUnlock() {
        val pwd = binding.etPwd.text.toString()
        if (prefs.checkLocalPassword(pwd)) {
            failCount = 0
            startActivity(Intent(this, ChatsListActivity::class.java))
            finish()
        } else {
            failCount++
            val delayMs = when {
                failCount >= 10 -> 30_000L
                failCount >= 5  -> 5_000L
                failCount >= 3  -> 2_000L
                else            -> 0L
            }
            Toast.makeText(this, R.string.lock_wrong, Toast.LENGTH_SHORT).show()
            binding.etPwd.setText("")
            if (delayMs > 0) {
                binding.btnUnlock.isEnabled = false
                binding.btnUnlock.postDelayed({ binding.btnUnlock.isEnabled = true }, delayMs)
            }
        }
    }

    override fun onBackPressed() {
        // Не даём выйти из приложения через back — пусть пользователь введёт пароль
        moveTaskToBack(true)
    }
}
