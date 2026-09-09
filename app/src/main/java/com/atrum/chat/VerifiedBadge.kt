package com.atrum.chat

/**
 * Значок верификации рядом с ником (main-visible фича, см. PERSONAL_BUILD.md).
 *
 * ⭐ НЕПОДДЕЛЫВАЕМО. Галочка показывается ТОЛЬКО если выполнены ОБА условия:
 *   1) identity-ключ (Ed25519 pubkey, Base64) входит в захардкоженный список [VERIFIED];
 *   2) профиль РЕАЛЬНО подписан этим identity-ключом (ephemeralSig проверяется — та же
 *      проверка, что в ChatActivity.verifyPartnerIdentity).
 * Поэтому чужой не может нарисовать себе чужую галочку: он не подпишет профиль чужим
 * приватным ключом. Рендер живёт в ОСНОВНОМ коде, значок видят собеседники на любой версии;
 * старый клиент просто не показывает его (аддитивно, §17 CLAUDE.md).
 */
object VerifiedBadge {

    /**
     * Захардкоженные identity-ключи (Ed25519 pubkey, Base64 NO_WRAP), которым рисуется
     * галочка у ВСЕХ пользователей.
     *
     * ⚠️ Ключей у владельца НЕСКОЛЬКО, и это не дубликат по ошибке. Личная сборка ставится с
     * `applicationIdSuffix = ".debug"` (см. build.gradle.kts) — это ОТДЕЛЬНОЕ приложение со
     * своим хранилищем, а значит и со своей парой identity-ключей. Пара генерируется один раз
     * при первом запуске (Prefs.getOrCreateIdentity) и живёт до очистки данных или
     * переустановки с другой подписью; после такого сброса ключ становится НОВЫМ, и старый
     * перестаёт кого-либо описывать. Поэтому здесь перечислены все актуальные ключи владельца:
     * убрать любой из них — значит погасить себе галочку и права в соответствующей сборке.
     *
     * ⚠️ Узнать свой ключ из приложения сейчас НЕЛЬЗЯ: показ ключа по долгому тапу существовал
     * временно и был удалён по просьбе владельца. Если ключ снова понадобится (например, после
     * очередного сброса данных личной сборки) — временно вернуть тот показ или прочитать
     * KEY_IDENTITY_PUB из EncryptedSharedPreferences отладчиком.
     */
    private val VERIFIED: Set<String> = setOf(
        "va4MxU8pVxerGIfNTYGJi9zKwYiVAYR7QeNTHeW0n34=", // Sebastian (владелец) — обычный релиз
        "FItMJVZzZhOJMMzFqaDLdrxUqTBJ/AnGyIp0VKL/J7g=", // Sebastian (владелец) — личная сборка
        "3PwdaFGTvZpFv+/JIelt2UeDcwKJLlWXCVEwH0Eit+4=", // Sebastian (владелец) — ключ после сброса данных (08.2026)
        // ⚠️ Ключ 0p/tHO9Id3… удалён из списка: владелец отозвал его через раздел «Владелец».
        // Держать отозванный ключ в списке смысла нет — на устройствах, куда доехал отзыв, он
        // и так гасится RevokedKeys, а там, куда не доехал, он был бы действующим зря.
        "Qb6ya2amykJhPWwTHU4sCDDpAs5rquq+Iw2EAdFXuzY=", // Sebastian (владелец) — после отзыва предыдущего (08.2026)
    )

    /**
     * Ключ в списке верифицированных? (только членство, без проверки подлинности).
     *
     * ⚠️ Отозванный ключ не считается верифицированным, даже если он есть в [VERIFIED]:
     * список вкомпилирован в APK и вычеркнуть из него утёкший ключ можно только новым
     * релизом, поэтому поверх него работает отзыв (см. [RevokedKeys]).
     */
    fun isKeyVerified(identityKeyB64: String?): Boolean =
        !identityKeyB64.isNullOrBlank() &&
            identityKeyB64 in VERIFIED &&
            !RevokedKeys.isRevoked(identityKeyB64)

    /**
     * Данные, которые подписывает identity-ключ, чтобы отозвать САМ СЕБЯ. Подпись делается
     * тем же ключом, который гасится, — и это безопасно: отзыв способен только убавить
     * доверие, поэтому вору публиковать его невыгодно (см. докстринг [RevokedKeys]).
     */
    fun revokeSigData(identityKeyB64: String): ByteArray =
        "atrum_idrevoke_v1_$identityKeyB64".toByteArray(Charsets.UTF_8)

    /**
     * Показывать ли галочку рядом с ником профиля [p] в чате [chatId]. Неподделываемо:
     * ключ в списке И профиль реально подписан этим identity-ключом.
     */
    fun isVerifiedProfile(p: Profile?, chatId: String): Boolean {
        if (p == null) return false
        val idk = p.identityPubKey ?: return false
        // Отзыв применяется ДО проверки членства: он приезжает в том же профиле, и если его
        // не разобрать здесь, отозванный ключ продолжал бы считаться живым. Единая точка на
        // все экраны — отдельных мест разбора заводить не нужно. Дёшево: уже известный ключ
        // и профиль без подписи отзыва выходят из apply() сразу.
        RevokedKeys.apply(p.revokedKey, p.revocationSig)
        if (!isKeyVerified(idk)) return false
        // 1) identitySig — доказательство личности, публикуется ВСЕГДА (в т.ч. в беседах).
        val isig = p.identitySig
        if (isig != null && runCatching {
                CryptoHelper.verifyIdentitySignature(idk, identitySigData(chatId), isig)
            }.getOrDefault(false)
        ) return true
        // 2) ephemeralSig — 1:1 (в т.ч. старые профили без identitySig, совместимость §17).
        val eph = p.ephemeralPubKey
        val sig = p.ephemeralSig
        if (eph != null && sig != null && runCatching {
                val data = android.util.Base64.decode(eph, android.util.Base64.NO_WRAP) +
                    chatId.toByteArray(Charsets.UTF_8)
                CryptoHelper.verifyIdentitySignature(idk, data, sig)
            }.getOrDefault(false)
        ) return true
        return false
    }

    /**
     * ⭐ ЕДИНАЯ ТОЧКА ПРАВДЫ распознавания верифицированного разработчика в беседе.
     *
     * Профили на реле дозаполняются по тикам: в списке участников профиль может на миг
     * прийти без подписи, тогда как в чате он уже полный (или наоборот). Раньше каждый
     * экран проверял подпись сам и получал разный результат — отсюда «в чате галочка есть,
     * в списке нет, иммунитет игнорится». Теперь: как только подпись проверена ХОТЬ ГДЕ-ТО,
     * userId запоминается для этого chatId НАВСЕГДА (в рамках сессии), и ВСЕ экраны берут
     * статус отсюда — согласованно.
     *
     * Безопасно и неподделываемо: в набор попадают ТОЛЬКО те, у кого реально сошлась
     * identity-подпись ([isVerifiedProfile]); знание пароля чата этого не даёт. Набор только
     * пополняется (проверенное не «протухает» от одного пустого чтения), но не даёт ложных
     * срабатываний — там лишь криптографически доказанные ключи.
     */
    private val confirmedDevs = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * true — [userId] верифицированный разработчик в чате [chatId]. Сначала пробует свежую
     * проверку профиля (и, если сошлось, запоминает), иначе — по ранее подтверждённой памяти.
     * Использовать ВЕЗДЕ, где решается «рисовать галочку / применять иммунитет / скрывать
     * кнопки модерации» — вместо прямого isVerifiedProfile, чтобы статус был единым.
     */
    fun isVerifiedDev(chatId: String, userId: String, profile: Profile?): Boolean {
        if (isVerifiedProfile(profile, chatId)) {
            confirmedDevs.getOrPut(chatId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(userId)
            return true
        }
        return confirmedDevs[chatId]?.contains(userId) == true
    }

    /** Только по подтверждённой памяти (без свежего профиля) — для мест, где профиля нет под рукой. */
    fun isConfirmedDev(chatId: String, userId: String): Boolean =
        confirmedDevs[chatId]?.contains(userId) == true

    /**
     * Данные, которые подписывает identity-ключ для доказательства личности
     * ([Profile.identitySig]). Домен + chatId — привязка к конкретному чату (анти-replay).
     */
    fun identitySigData(chatId: String): ByteArray =
        "atrum_idsig_v1_$chatId".toByteArray(Charsets.UTF_8)

    /**
     * Своя ГАЛОЧКА — строго по ключу, как и у всех остальных.
     *
     * ⚠️ РАНЬШЕ здесь был фолбэк `|| PersonalFeatures.enabled`, то есть в ЛЮБОЙ debug-сборке
     * функция возвращала true. Считалось, что это безопасная косметика «только на своём
     * экране», но фолбэк протёк в права: PartnerProfileActivity считает
     * `memberVerified = … || (isMe && isVerifiedSelf(…))`, а из него берётся `effBanned` —
     * то есть любой, кто собрал debug, видел себя НЕ забаненным в списке участников.
     *
     * Заодно фолбэк маскировал реальную поломку: при потерянном ключе галочка в настройках
     * продолжала гореть, хотя собеседники её уже не видели, — и расхождение выглядело как
     * баг синхронизации, а не как «ключ пропал».
     *
     * ⛔ Для ПРАВ и ИММУНИТЕТА (chatIsAdmin, myGroupPermissions, canStats, checkSelfBanned,
     * мут-иммунитет и т.п.) по-прежнему только [isKeyVerified] — строго по ключу.
     */
    fun isVerifiedSelf(myIdentityKeyB64: String?): Boolean =
        isKeyVerified(myIdentityKeyB64)

    /**
     * Возвращает имя с ПРИКЛЕЕННЫМ inline-значком (ImageSpan) — для центрированных/возможно
     * многострочных имён (профиль партнёра), где отдельная вью рядом ломала бы центрирование.
     * Значок статичный (ic_verified_badge), кликабельность не нужна — рядом уже есть щит.
     */
    fun nameWithBadge(ctx: android.content.Context, name: String, sizeDp: Int = 18): CharSequence {
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_verified_badge)
            ?: return name
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, px, px)
        val sb = android.text.SpannableStringBuilder(name).append("  ")
        sb.setSpan(
            android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_BASELINE),
            sb.length - 1, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    /**
     * Универсально ставит имя с КЛИКАБЕЛЬНОЙ галочкой в любой [tv] (если [verified]) — чтобы
     * галочка была ВЕЗДЕ (статистика, список админов и т.п.) и везде по тапу показывала окно
     * «кто я» ([VerifiedInfoDialog]). Кликабелен ТОЛЬКО значок (ClickableSpan), поэтому клик
     * по строке-родителю (если у неё свой обработчик) не перехватывается. Если не [verified] —
     * просто имя без галочки/клика (и снимаем возможный прежний movementMethod).
     */
    fun applyNameBadge(tv: android.widget.TextView, name: String, verified: Boolean, sizeDp: Int = 15) {
        if (!verified) {
            tv.text = name
            tv.movementMethod = null
            return
        }
        val ctx = tv.context
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_verified_badge)
        if (d == null) { tv.text = name; return }
        val px = (sizeDp * ctx.resources.displayMetrics.density).toInt()
        d.setBounds(0, 0, px, px)
        val sb = android.text.SpannableStringBuilder(name).append("  ")
        val start = sb.length - 1
        sb.setSpan(
            android.text.style.ImageSpan(d, android.text.style.ImageSpan.ALIGN_BASELINE),
            start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        sb.setSpan(object : android.text.style.ClickableSpan() {
            override fun onClick(widget: android.view.View) { VerifiedInfoDialog.show(widget.context) }
            // Без подчёркивания/смены цвета — это значок, а не ссылка.
            override fun updateDrawState(ds: android.text.TextPaint) {}
        }, start, sb.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        tv.text = sb
        tv.movementMethod = android.text.method.LinkMovementMethod.getInstance()
        tv.highlightColor = android.graphics.Color.TRANSPARENT
    }
}
