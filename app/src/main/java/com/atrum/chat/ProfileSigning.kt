package com.atrum.chat

import android.util.Base64

/**
 * Подписи, которыми снабжается МОЙ профиль в profiles.txt.
 *
 * Вынесено из ChatActivity, потому что публиковать свой профиль умеет уже не только экран чата:
 * [PublishScheduler] добивает недоставленную публикацию в фоне и после перезапуска приложения,
 * и обязан класть В ТОЧНОСТИ тот же набор подписей. Иначе фоновая публикация переписала бы мой
 * слот версией БЕЗ подписи — а у собеседника подлинность (галочка, VerifiedBadge) считается
 * именно по ним, и она бы погасла. Дублировать крипто-код ради этого нельзя (§1), поэтому —
 * одна общая точка.
 *
 * Обе функции затирают приватный ключ сразу после подписи (§1) и никогда не бросают наружу:
 * подпись — усиление, а не условие работы, её отсутствие не должно ронять публикацию.
 */
object ProfileSigning {

    /**
     * Подпись эфемерного ключа сессии (ключ + chatId) моим identity-ключом.
     * Даёт собеседнику доказательство, что ECDH-ключ опубликовал именно я (1:1-галочка).
     * null — если эфемерного ключа нет (беседы им не пользуются) или подпись не удалась.
     */
    fun ephemeralSig(prefs: Prefs, ephPubB64: String?, chatId: String): String? {
        if (ephPubB64 == null) return null
        val (priv, _) = prefs.getOrCreateIdentity()
        return try {
            val data = Base64.decode(ephPubB64, Base64.NO_WRAP) + chatId.toByteArray(Charsets.UTF_8)
            CryptoHelper.signWithIdentity(priv, data)
        } catch (_: Exception) {
            null
        } finally {
            priv.fill(0)
        }
    }

    /**
     * Подпись «доказательство identity» (домен + chatId моим identity-ключом). В отличие от
     * [ephemeralSig] работает и в БЕСЕДАХ, где эфемерного ключа нет, — публикуется как
     * Profile.identitySig и даёт неподделываемую галочку в группах.
     */
    fun identitySig(prefs: Prefs, chatId: String): String? {
        val (priv, _) = prefs.getOrCreateIdentity()
        return try {
            CryptoHelper.signWithIdentity(priv, VerifiedBadge.identitySigData(chatId))
        } catch (_: Exception) {
            null
        } finally {
            priv.fill(0)
        }
    }
}
