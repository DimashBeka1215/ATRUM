package com.atrum.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Отполированный Callback для ответа на сообщение (справа налево).
 * Реализовано: Snap-back, блокировка мульти-свайпа, эффект сопротивления и вибрация.
 */
class SwipeToReplyCallback(
    context: Context,
    private val onReply: (position: Int) -> Unit
) : ItemTouchHelper.Callback() {

    private val density = context.resources.displayMetrics.density
    private val replyIcon = ContextCompat.getDrawable(context, R.drawable.ic_reply_menu)!!
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x4DA855F7.toInt()
    }

    private var hasTriggered = false
    private var activeViewHolder: RecyclerView.ViewHolder? = null

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        // Если уже свайпаем один айтем, другие не трогаем
        if (activeViewHolder != null && activeViewHolder != viewHolder) return makeMovementFlags(0, 0)
        return makeMovementFlags(0, ItemTouchHelper.LEFT)
    }

    override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean = false

    // Ставим порог, который нельзя достичь, чтобы айтем всегда возвращался (snap-back)
    override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 10f
    override fun getSwipeEscapeVelocity(defaultValue: Float) = defaultValue * 20f

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            val itemView = viewHolder.itemView
            val maxTranslation = 80f * density
            
            // Вычисляем смещение вручную для максимальной плавности
            val clampedDx = if (abs(dX) > maxTranslation) {
                -(maxTranslation + (abs(dX) - maxTranslation) * 0.2f)
            } else {
                dX
            }.coerceAtMost(0f)

            // Устанавливаем смещение напрямую
            itemView.translationX = clampedDx

            // Логика триггера (вибрация + колбэк)
            if (isCurrentlyActive && abs(clampedDx) >= maxTranslation * 0.8f && !hasTriggered) {
                hasTriggered = true
                itemView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val pos = viewHolder.adapterPosition
                if (pos != RecyclerView.NO_POSITION) onReply(pos)
            }

            // Отрисовка иконки
            if (abs(clampedDx) > 0) {
                val progress = (abs(clampedDx) / maxTranslation).coerceIn(0f, 1f)
                drawReplyUI(c, itemView, progress)
            }
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    private fun drawReplyUI(c: Canvas, itemView: View, progress: Float) {
        val iconSize = (24f * density).toInt()
        val margin = (20f * density).toInt()
        val cx = itemView.right - margin - iconSize / 2f
        val cy = itemView.top + itemView.height / 2f

        val scale = 0.5f + 0.5f * progress
        val alpha = (255 * progress).toInt().coerceIn(0, 255)

        circlePaint.alpha = (alpha * 0.4f).toInt()
        c.drawCircle(cx, cy, (iconSize / 2f + 5 * density) * scale, circlePaint)

        val halfSize = (iconSize / 2f * scale).toInt()
        replyIcon.setBounds(
            (cx - halfSize).toInt(), (cy - halfSize).toInt(),
            (cx + halfSize).toInt(), (cy + halfSize).toInt()
        )
        replyIcon.alpha = alpha
        replyIcon.draw(c)
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
            activeViewHolder = viewHolder
            hasTriggered = false
        }
        super.onSelectedChanged(viewHolder, actionState)
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        // Принудительно обнуляем смещение при завершении
        viewHolder.itemView.translationX = 0f
        super.clearView(recyclerView, viewHolder)
        if (activeViewHolder == viewHolder) {
            activeViewHolder = null
            hasTriggered = false
        }
    }
}
