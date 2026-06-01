package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.atrum.chat.databinding.ActivityEulaBinding

/**
 * Экран пользовательского соглашения (EULA).
 *
 * Показывается ОДИН РАЗ при первом запуске — до интро, онбординга и всего остального.
 * Проверяется в IntroActivity до любой другой логики.
 *
 * Принять  → записывает Prefs.eulaAccepted = true, продолжает нормальный запуск.
 * Отказаться → finishAffinity() — выходит на рабочий стол без сохранения флага.
 *              При следующем запуске соглашение покажется снова.
 *
 * Текст соглашения хранится в res/raw/eula.txt (RU + EN).
 */
class EulaActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEulaBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEulaBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Загружаем текст из raw-ресурса
        binding.tvEulaText.text = loadEulaText()

        binding.btnAccept.setOnClickListener {
            Prefs(this).eulaAccepted = true
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
        }

        binding.btnDecline.setOnClickListener {
            // Выход на рабочий стол, флаг не сохраняется
            finishAffinity()
        }
    }

    /**
     * Выбирает eula_ru.txt или eula_en.txt по языку системы.
     * Русский — для "ru", для всех остальных — английский.
     */
    private fun loadEulaText(): String {
        val isRussian = java.util.Locale.getDefault().language == "ru"
        val resId = if (isRussian) R.raw.eula_ru else R.raw.eula_en
        return try {
            resources.openRawResource(resId).bufferedReader().readText()
        } catch (_: Exception) {
            "Failed to load the agreement text."
        }
    }
}
