package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран-гейт перед издателем: жёлтое предупреждение «для разработчика», поле пароля под
 * стиль приложения и кнопка «Удалить это окно» (локально скрыть раздел «Сеть»).
 *
 * Открывается из настроек после 7 нажатий по «Реле сообщений». Правильный пароль ведёт на
 * экран издателя; «Удалить это окно» прячет раздел на этом телефоне (на работу не влияет).
 */
class PublisherGateActivity : AppCompatActivity() {

    private val prefs by lazy { Prefs(this) }
    private lateinit var et: EditText
    private var pwdVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_publisher_gate)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        et = findViewById(R.id.etGatePwd)

        findViewById<ImageView>(R.id.btnTogglePwd).setOnClickListener {
            pwdVisible = !pwdVisible
            et.inputType = if (pwdVisible)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            et.setSelection(et.text.length)
        }

        findViewById<View>(R.id.btnEnter).setOnClickListener { onEnter() }
        findViewById<View>(R.id.btnDeleteWindow).setOnClickListener { onDeleteWindow() }
    }

    private fun onEnter() {
        if (PublisherGate.verify(et.text.toString())) {
            startActivity(Intent(this, PublisherActivity::class.java))
            finish()
        } else {
            Toast.makeText(this, R.string.pub_gate_wrong, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onDeleteWindow() {
        NeonDialog.showConfirm(
            ctx = this,
            title = getString(R.string.pub_gate_delete),
            message = getString(R.string.pub_gate_delete_confirm),
            positiveText = getString(R.string.action_delete),
            positiveIsDestructive = true,
            negativeText = getString(R.string.btn_cancel)
        ) {
            prefs.relaySectionHidden = true
            Toast.makeText(this, R.string.pub_gate_deleted, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
