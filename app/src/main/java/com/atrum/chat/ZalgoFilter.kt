package com.atrum.chat

import java.text.Normalizer

/**
 * Детектор «Zalgo»-текста — строк с переизбытком Unicode combining-знаков (Mn/Me), которые
 * наслаиваются по вертикали и ломают вёрстку. Используется, чтобы ЗАПРЕТИТЬ отправку таких
 * сообщений и сохранение таких ников (кнопка/отправка блокируются).
 *
 * Критерий (сверен в песочнице): после NFC-нормализации — 2+ комбинирующих знака ПОДРЯД на
 * одном базовом символе. NFC схлопывает легитимные акценты (café, Việt, кириллица) в готовые
 * символы → у них 0 combining, они НЕ триггерят. Служебные знаки эмодзи (вариационные
 * селекторы U+FE00–U+FE0F, keycap U+20E3) исключены — эмодзи (❤️, 1️⃣, флаги, ZWJ-семьи) не блокируются.
 *
 * Итерация по кодовым точкам (codePointAt) — корректно для surrogate-пар (эмодзи).
 */
object ZalgoFilter {

    /** 2+ комбинирующих подряд (после NFC) на базовый символ ⇒ Zalgo. */
    private const val ZALGO_RUN = 2

    private fun isZalgoMark(cp: Int): Boolean {
        // Служебные знаки эмодзи — не Zalgo.
        if (cp in 0xFE00..0xFE0F || cp == 0x20E3) return false
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
    }

    /** true, если в тексте есть [ZALGO_RUN]+ комбинирующих знака подряд (после NFC). */
    fun containsZalgo(text: String): Boolean {
        val s = Normalizer.normalize(text, Normalizer.Form.NFC)
        var run = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (isZalgoMark(cp)) {
                run++
                if (run >= ZALGO_RUN) return true
            } else {
                run = 0
            }
            i += Character.charCount(cp)
        }
        return false
    }
}
