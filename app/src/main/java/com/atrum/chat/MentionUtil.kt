package com.atrum.chat

import java.util.regex.Pattern

/**
 * Утилита распознавания @упоминаний в тексте сообщения (фича «вызов пользователя»).
 * Упоминание в сообщении — это токен «@<handle>», где handle — тег из профиля
 * (Profile.tag) или, если тега нет, имя (одно слово). Матчинг по тегу — основной;
 * для многословных имён без тега упоминание не распознаётся (известное ограничение —
 * см. ChatActivity.insertMention).
 */
object MentionUtil {

    /**
     * ⛔ ОБРАТИМЫЙ ВЫКЛЮЧАТЕЛЬ (запрос пользователя: «только обратимо»). true — фича
     * упоминаний активна (детект/бейдж/уведомление/кнопка). Поставить false — и всё
     * поведение откатывается к базовому: фон не считает упоминания, не пишет
     * Chat.mentionMsgIds, кнопка @ не показывается. Автодополнение по @ и подсветка в
     * тексте — отдельные, дешёвые, здесь не гейтятся. Детект уже идёт на БЫСТРОМ пути
     * (recomputeAndNotify триггерится стримом реле ~400мс, см. MessageWatchService),
     * т.е. на уровне мута/сообщения — отдельный «перенос» не нужен.
     */
    const val ENABLED = true

    private val MENTION = Pattern.compile("@([\\p{L}0-9_]+)")

    /** Упомянут ли Я: содержит ли [text] «@<tag>» или «@<name>» (без учёта регистра). */
    fun mentionsMe(text: String, myTag: String?, myName: String?): Boolean {
        if (text.isEmpty() || text.indexOf('@') < 0) return false
        val handles = buildList {
            myTag?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            myName?.trim()?.takeIf { it.isNotEmpty() && ' ' !in it }?.let { add(it.lowercase()) }
        }
        if (handles.isEmpty()) return false
        val m = MENTION.matcher(text)
        while (m.find()) {
            if ((m.group(1) ?: "").lowercase() in handles) return true
        }
        return false
    }
}
