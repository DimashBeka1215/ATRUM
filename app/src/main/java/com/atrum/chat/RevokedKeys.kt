package com.atrum.chat

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Отозванные identity-ключи владельца.
 *
 * ЗАЧЕМ. Список [VerifiedBadge.VERIFIED] вкомпилирован в APK: чтобы вычеркнуть оттуда
 * утёкший ключ, нужен новый релиз и обновление У ВСЕХ. Отзыв закрывает эту дыру, не требуя
 * ни релиза, ни сервера: владелец подписывает своим же ключом строку «этот ключ отозван»,
 * она едет в профиле по обычному транспорту, и каждый клиент, увидев её, гасит ключ у себя.
 *
 * ПОЧЕМУ ЭТО БЕЗОПАСНО, хотя подпись делается самим отзываемым ключом:
 *  • отзыв может только УБАВИТЬ доверие. Вор, укравший ключ, опубликовав отзыв, убьёт
 *    собственный краденый доступ — выгоды нет;
 *  • отзыв НЕОБРАТИМ. Иначе вор мог бы отзывать и возвращать ключ, играя с доверием;
 *  • подделать нельзя: подпись проверяется тем самым ключом, который гасится.
 *
 * ГРАНИЦА ЧЕСТНОСТИ. Отзыв доезжает не мгновенно и не до всех: только до тех, кто прочитает
 * профиль в общей беседе. Мгновенного глобального «выключить» в системе без сервера не
 * существует. Но именно эти люди и видят галочку — остальным она и так не показывается.
 */
object RevokedKeys {

    private val revoked = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var prefs: Prefs? = null

    /**
     * Отозванный МОЙ ключ и подпись его отзыва. Пара, а не одна подпись: отозвав ключ,
     * владелец переходит на новый, и профиль дальше публикуется уже с новым identityPubKey —
     * проверять подпись против него было бы бессмысленно (она относится к старому).
     */
    @Volatile
    private var mine: Pair<String, String>? = null

    /** Поднимает список из хранилища. Вызывать из App.onCreate до первой отрисовки. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = runCatching { Prefs(context.applicationContext) }.getOrNull() ?: return
        prefs = p
        revoked.addAll(p.revokedIdentityKeys)
        val k = p.myRevokedKey
        val sg = p.myRevocationSig
        if (!k.isNullOrBlank() && !sg.isNullOrBlank()) mine = k to sg
    }

    /**
     * Отзыв собственного ключа. Держится отдельно от [revoked], потому что его нужно не только
     * помнить, но и РАССЫЛАТЬ: [ProfileSync.pushMyProfile] прикладывает его к каждой публикации
     * профиля, и отзыв сам расползается по всем беседам без отдельного канала.
     */
    fun myRevocation(): Pair<String, String>? = mine

    /** Запоминает отзыв своего ключа и сразу гасит его локально. Необратимо. */
    fun revokeMine(pubB64: String, revocationSigB64: String): Boolean {
        if (!apply(pubB64, revocationSigB64)) return false
        mine = pubB64 to revocationSigB64
        prefs?.let { p ->
            runCatching {
                p.myRevocationSig = revocationSigB64
                p.myRevokedKey = pubB64
            }
        }
        return true
    }

    /** Отозван ли ключ. Пустой/неизвестный — нет. */
    fun isRevoked(pubB64: String?): Boolean =
        !pubB64.isNullOrBlank() && revoked.contains(pubB64)

    /**
     * Применяет пришедший отзыв: проверяет подпись ТЕМ ЖЕ ключом и, если сходится, гасит его
     * навсегда. Возвращает true, если ключ отозван (в том числе если был отозван раньше).
     *
     * Дёшево при повторных вызовах: уже известный ключ не проверяется заново, а без подписи
     * функция сразу выходит — поэтому её можно звать на каждой отрисовке профиля.
     */
    fun apply(pubB64: String?, revocationSigB64: String?): Boolean {
        if (pubB64.isNullOrBlank()) return false
        if (revoked.contains(pubB64)) return true
        if (revocationSigB64.isNullOrBlank()) return false

        val ok = runCatching {
            CryptoHelper.verifyIdentitySignature(
                pubB64, VerifiedBadge.revokeSigData(pubB64), revocationSigB64
            )
        }.getOrDefault(false)
        if (!ok) return false

        revoked.add(pubB64)
        prefs?.let { p -> runCatching { p.revokedIdentityKeys = revoked.toSet() } }
        return true
    }

    /** Только для тестов/диагностики: снимок текущего набора. */
    fun snapshot(): Set<String> = revoked.toSet()
}
