package com.atrum.chat

/**
 * ЛИЧНАЯ СБОРКА (ATRUM Personal) — единая точка для «фишек для себя», которых НЕТ в обычном
 * публичном релизе.
 *
 * Как это устроено:
 *   • Флаг [BuildConfig.PERSONAL] задаётся в app/build.gradle.kts: в build-типе `debug` = true,
 *     в `release` = false. Личная сборка = build-вариант `debug` (`./gradlew assembleDebug`),
 *     ставится РЯДОМ с обычным релизом (applicationId с суффиксом `.debug`), со своими данными.
 *   • [enabled] — единственный переключатель. Весь личный код прячется за ним, поэтому в
 *     публичный релиз он не попадает и не может задеть обычных пользователей/старые чаты (§17).
 *
 * Как добавить свою фишку:
 *   1. Пиши код как обычно, в подходящем месте.
 *   2. Оборачивай ВХОД в фишку в `if (PersonalFeatures.enabled) { … }` (или ранний
 *      `if (!PersonalFeatures.enabled) return`). Так фишка живёт только в личной сборке.
 *   3. Если фишка с UI — сначала мокап и одобрение (§0), но видимая только при [enabled].
 *   4. Держи здесь общие личные хелперы/константы, чтобы всё личное было в одном месте.
 */
object PersonalFeatures {

    /** true только в личной (debug) сборке — см. buildConfigField PERSONAL в build.gradle.kts. */
    val enabled: Boolean get() = BuildConfig.PERSONAL

    // ── Сюда добавляются личные фишки. Пример-заглушка (в release не выполняется): ──
    //
    // fun greetOnStart(context: android.content.Context) {
    //     if (!enabled) return
    //     android.widget.Toast.makeText(context, "Личная сборка", android.widget.Toast.LENGTH_SHORT).show()
    // }
}
