package com.atrum.chat

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope
import java.security.MessageDigest

/**
 * Точка входа восстановления/сохранения ключа владельца (личная сборка).
 *
 * В `src/release` лежит одноимённая заглушка, которая ничего не делает, — поэтому обычный
 * APK не тянет ни Play Services, ни Drive, а код вызова в общем коде остаётся один и тот же.
 * Причина разделения простая: ATRUM работает через Tor и ставится в том числе на прошивки
 * без сервисов Google — класть их в публичную сборку ради удобства одного человека нельзя.
 */
object OwnerCloud {

    /**
     * Окно за запуск показывается ровно одно.
     *
     * ⚠️ Без этого флага получался бы цикл: [maybePrompt] зовётся из onResume списка чатов,
     * окно — отдельная Activity, и её закрытие снова приводит к onResume. Любой исход, кроме
     * успеха (отмена, «копии нет», ошибка сети), мгновенно открывал бы окно заново, и выйти
     * из него было бы нельзя. Флаг в памяти, а не в Prefs: при следующем запуске приложение
     * обязано напомнить снова, иначе один случайный тап навсегда закрыл бы дорогу к правам.
     */
    @Volatile
    private var promptedThisRun = false

    /** Решает, надо ли что-то предложить. Зовётся при выходе на список чатов. */
    fun maybePrompt(activity: Activity) {
        // ⚠️ ownerTools, а не enabled: личную сборку может собрать любой, и у него ключ тоже
        // «не опознан» — без этого гейта постороннему вылезало бы окно с предложением войти
        // в Google. Подробнее — в PersonalFeatures.ownerTools.
        if (!PersonalFeatures.ownerTools || promptedThisRun) return
        val prefs = Prefs(activity)

        if (VerifiedBadge.isKeyVerified(prefs.myIdentityPubKey)) {
            // Ключ на месте. Один раз предлагаем положить копию в облако — именно этого шага
            // не хватило в прошлый раз, когда данные стёрли раньше, чем сделали копию.
            if (prefs.ownerCloudSaved || prefs.ownerCloudDeclined) return
            promptedThisRun = true
            OwnerCloudActivity.start(activity, OwnerCloudActivity.MODE_SAVE)
        } else {
            // Ключ не опознан — предлагаем достать копию.
            promptedThisRun = true
            OwnerCloudActivity.start(activity, OwnerCloudActivity.MODE_RESTORE)
        }
    }

    /** Отпечаток аккаунта, которому принадлежат инструменты владельца. */
    private const val ACCOUNT_FP =
        "404eb8e97d09544af3b85457829c9462878df1c07fb7bb1f6ce216b2639dfc90"

    /**
     * Владелец ли это — то есть выполнен ли вход в Google под ТЕМ САМЫМ аккаунтом.
     *
     * Зачем поверх [PersonalFeatures.ownerTools]: флаг сборки — это строка в файле, её может
     * дописать себе кто угодно, и раздел появился бы у любого, кто собрал личную сборку из
     * исходников. Вход под конкретным аккаунтом подделать так же дёшево не выйдет.
     *
     * В коде лежит не почта, а её отпечаток: по нему аккаунт не восстановить, а сверить —
     * можно. Проверяется ещё и выданный доступ к скрытой папке: без него раздел всё равно
     * бесполезен, а «вошёл куда-то» без разрешения ничего не значит.
     *
     * ⚠️ По-прежнему НЕ граница безопасности: проверку в своей сборке можно вырезать. Она
     * убирает раздел с глаз постороннего, а права держатся на ключе и подписи (VerifiedBadge).
     */
    fun isOwnerAccount(context: Context): Boolean {
        if (!PersonalFeatures.ownerTools) return false
        val acc = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        if (!GoogleSignIn.hasPermissions(acc, Scope(OwnerDrive.SCOPE))) return false
        val email = acc.email?.trim()?.lowercase() ?: return false
        return fingerprint(email) == ACCOUNT_FP
    }

    private fun fingerprint(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest("atrum_owner_acct_v1:$value".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    /**
     * Открывает экран «Владелец». Вызов идёт из общего кода настроек, поэтому и живёт здесь:
     * в src/release у этой функции пустая заглушка, и публичная сборка про экран не знает.
     */
    fun openOwner(activity: Activity) {
        if (!isOwnerAccount(activity)) return
        OwnerActivity.start(activity)
    }

    /** Отложить до следующего запуска (кнопка «Позже»). */
    fun postpone() {
        promptedThisRun = true
    }
}
