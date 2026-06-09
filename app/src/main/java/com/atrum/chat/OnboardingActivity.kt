package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityOnboardingBinding

/**
 * Onboarding в 2 шага:
 *  1) Ввод ника
 *  2) Локальный пароль (опционально — можно пропустить)
 *
 * После завершения: помечает isOnboarded=true и открывает ChatsListActivity.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        showStep1()
        binding.btnNext.setOnClickListener { nextFromNameStep() }
        binding.btnFinish.setOnClickListener { finishOnboarding(setPassword = true) }
        binding.btnSkipPwd.setOnClickListener { finishOnboarding(setPassword = false) }
    }

    override fun onBackPressed() {
        // Если на шаге пароля — назад возвращает на шаг ника. Иначе обычное поведение.
        if (binding.stepPassword.visibility == View.VISIBLE) {
            showStep1()
        } else {
            super.onBackPressed()
        }
    }

    private fun showStep1() {
        binding.stepName.visibility = View.VISIBLE
        binding.stepPassword.visibility = View.GONE
        binding.etName.requestFocus()
    }

    private fun showStep2() {
        binding.stepName.visibility = View.GONE
        binding.stepPassword.visibility = View.VISIBLE
        binding.etPwd.requestFocus()
    }

    private fun nextFromNameStep() {
        val name = binding.etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_name, Toast.LENGTH_SHORT).show()
            return
        }
        val tag = binding.etTag.text.toString().trim()
        if (tag.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_tag, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.myName = name
        prefs.myTag = tag
        prefs.myUserId
        showStep2()
    }

    private fun finishOnboarding(setPassword: Boolean) {
        if (setPassword) {
            val pwd = binding.etPwd.text.toString()
            val pwdRepeat = binding.etPwdRepeat.text.toString()
            if (pwd.length < 4) {
                Toast.makeText(this, R.string.error_pwd_short, Toast.LENGTH_SHORT).show()
                return
            }
            if (pwd != pwdRepeat) {
                Toast.makeText(this, R.string.error_pwd_mismatch, Toast.LENGTH_SHORT).show()
                return
            }
            prefs.setLocalPassword(pwd)
            prefs.localPasswordPlaintext = pwd
        } else {
            prefs.setLocalPassword(null)
        }
        prefs.isOnboarded = true
        startActivity(Intent(this, ChatsListActivity::class.java))
        finish()
    }
}
