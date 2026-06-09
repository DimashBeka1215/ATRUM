package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Базовый класс для защищённых экранов приложения.
 *
 *  - FLAG_SECURE: запрет скриншотов и превью в списке недавних приложений.
 *  - Повторная блокировка: при возврате из фона, если приложение было заблокировано
 *    (AppLock.locked) и задан PIN — показываем LockActivity заново.
 */
abstract class SecureActivity : AppCompatActivity() {

    /** Должен ли этот экран повторно блокироваться. LockActivity переопределяет в false. */
    protected open val lockProtected: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Prefs(this).debugDisableSecureFlags) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (lockProtected && AppLock.locked && Prefs(this).hasLocalPassword()) {
            startActivity(Intent(this, LockActivity::class.java))
        }
    }
}
