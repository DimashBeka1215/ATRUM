package com.atrum.chat

/**
 * Нормализация и проверка пользовательского тега.
 *
 * Канон: "@core", где core — только латинские буквы, цифры и подчёркивание.
 * Русский, пробелы-в-середине как есть, знаки препинания и прочее не допускаются.
 */
object TagUtils {

    private const val MAX_LEN = 32

    /**
     * Приводит ввод к канону "@core". Пробелы → подчёркивания, ведущие «@» убираются.
     * Возвращает null, если после очистки ядро пустое, слишком длинное ИЛИ содержит
     * недопустимые символы (чтобы не «манглить» молча — лучше отказать с сообщением).
     */
    fun normalize(input: String): String? {
        val core = input.trim().trimStart('@').trim().replace(' ', '_')
        if (core.isEmpty() || core.length > MAX_LEN) return null
        val ok = core.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }
        return if (ok) "@$core" else null
    }
}
