package com.atrum.chat

import android.os.Bundle
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Полноэкранный переход при отзыве/возврате прав создателя (мокап одобрен). Показывается ВМЕСТО
 * резкого [ChatActivity.recreate] на устройстве, где применился отзыв/возврат: описание что
 * происходит + кто вправе это делать (verified root), и полоса загрузки ЧУТЬ ДОЛЬШЕ реального
 * применения — чтобы человек успел прочитать. По окончании полосы чат тихо перезагружается под
 * экраном (тот же механизм, что у приёма передачи владения — [ChatActivity.pendingOwnerReloadChatId]).
 */
class RevokeTransitionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHAT_ID = "chat_id"
        /** true — отзыв (аннулирование), false — возврат (восстановление). */
        const val EXTRA_REVOKE = "revoke"
        /** Длительность полосы — заметно дольше реального применения (успеть прочитать). */
        private const val DURATION_MS = 4500L
        private const val FINISH_DELAY_MS = 4800L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_revoke_transition)
        val revoke = intent.getBooleanExtra(EXTRA_REVOKE, true)

        val icon = findViewById<ImageView>(R.id.iv_icon)
        val title = findViewById<TextView>(R.id.tv_title)
        val desc = findViewById<TextView>(R.id.tv_desc)
        if (revoke) {
            icon.setImageResource(R.drawable.ic_shield_x)
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.error)
            title.setText(R.string.revoke_transition_revoke_title)
            desc.setText(R.string.revoke_transition_revoke_desc)
        } else {
            icon.setImageResource(R.drawable.ic_shield_check)
            icon.imageTintList = ContextCompat.getColorStateList(this, R.color.accent)
            title.setText(R.string.revoke_transition_restore_title)
            desc.setText(R.string.revoke_transition_restore_desc)
        }

        // Плавная полоса (та же, что при скачивании стикеров, DESIGN.md §4.9). Идёт 0→100 за
        // DURATION_MS — дольше реального применения (оно уже произошло в БД), чтобы успеть прочитать.
        val pb = findViewById<ProgressBar>(R.id.pb_revoke)
        android.animation.ValueAnimator.ofInt(0, 100).apply {
            duration = DURATION_MS
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { pb.progress = it.animatedValue as Int }
            start()
        }

        // Перезагрузку чата НЕ дёргаем отсюда: ChatActivity уже вызвал recreate() при запуске
        // этого окна, и беседа грузится ПОД ним, пока идёт анимация. По окончании просто
        // закрываемся — человек попадает в уже прогруженный чат, без отдельного экрана загрузки.
        pb.postDelayed({ finish() }, FINISH_DELAY_MS)
    }

    /** Назад во время перехода не закрываем «в пустоту» — ждём завершения полосы. */
    override fun onBackPressed() { /* заблокировано на время перехода */ }
}
