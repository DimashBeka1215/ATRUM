package com.atrum.chat

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * Базовый класс для защищённых экранов приложения.
 *
 *  - FLAG_SECURE: запрет скриншотов и превью в списке недавних приложений — но ТОЛЬКО на
 *    экранах самой переписки ([chatContentScreen]), остальные экраны скриншотить можно
 *    (по запросу пользователя: «разреши скрины везде кроме самих переписок»).
 *  - Повторная блокировка: при возврате из фона, если приложение было заблокировано
 *    (AppLock.locked) и задан PIN — показываем LockActivity заново.
 */
abstract class SecureActivity : AppCompatActivity() {

    /** Должен ли этот экран повторно блокироваться. LockActivity переопределяет в false. */
    protected open val lockProtected: Boolean = true

    /**
     * Экран показывает контент самой переписки (сообщения чата, полноэкранный просмотр
     * фото/голосовых из чата, галерея медиа чата) — скриншоты здесь запрещены ВСЕГДА, если
     * тестер явно не снял блокировку в настройках. Остальные экраны (списки чатов, профиль,
     * настройки и т.п.) по умолчанию скриншотить можно. Переопределяется в ChatActivity,
     * ImageViewActivity, MediaListActivity.
     */
    protected open val chatContentScreen: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Скриншоты запрещены только на экранах самой переписки — везде остальное разрешено.
        // Тестер может снять блокировку и с переписки в настройках (напр. для промо-скриншотов).
        if (chatContentScreen && !Prefs(this).isScreenshotsAllowed) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        val delay = (4000L..11000L).random()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing && !isDestroyed) {
                if (RuntimeInfo.compromised(this) || NativeCodec.mismatched(this)) {
                    UpdateRequiredActivity.launch(this)
                    finishAffinity()
                } else {
                    PackIndex.enforce(this)
                }
            }
        }, delay)
    }

    override fun onStart() {
        super.onStart()
        if (BuildInfo.isTampered(this) || RuntimeInfo.compromised(this) || NativeCodec.mismatched(this)) {
            UpdateRequiredActivity.launch(this)
            finish()
            return
        }
        if (lockProtected && AppLock.locked && Prefs(this).hasLocalPassword()) {
            if (AppLock.withinAutoLockGrace()) {
                // Краткая отлучка (вернулись в пределах льготного периода) — не перепрашиваем
                // пароль и снимаем блокировку, чтобы и следующие переходы не запрашивали его.
                // Это убирает жалобу «просит пароль при каждом действии».
                AppLock.locked = false
            } else {
                startActivity(Intent(this, LockActivity::class.java))
            }
        }
    }
}
