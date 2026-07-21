package com.atrum.chat

import android.graphics.Color
import android.view.Window
import android.view.WindowManager

/**
 * Убирает белую подложку системного навбара у окон с системной прозрачной темой
 * (`Theme_Translucent_NoTitleBar`): на планшетах с жестовой навигацией снизу вылезала белая
 * полоса (репорт). Делает навбар прозрачным — он ложится на затемнение диалога (тёмное).
 * Безопасно и идемпотентно; вызывать на `dialog.window` после создания диалога.
 */
fun Window.transparentNavBar() {
    runCatching {
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION)
        navigationBarColor = Color.TRANSPARENT
    }
}
