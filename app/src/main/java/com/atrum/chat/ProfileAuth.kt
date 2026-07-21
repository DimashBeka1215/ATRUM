package com.atrum.chat

/**
 * Проверка ПОДЛИННОСТИ СОДЕРЖИМОГО профиля (имя/тег/аватар/статус) против закреплённого
 * за пользователем identity-ключа (TOFU-пиннинг, см. GroupRosterSync / ChatParticipant.
 * pinnedIdentityPubKey). Закрывает подмену профиля инсайдером (ADR_GROUP_CHATS.md
 * §«Осталось укрепить», п.4).
 *
 * ⛔ Сверяем ТОЛЬКО против закреплённого ключа, НЕ против self-asserted [Profile.identityPubKey]:
 * инсайдер, подделавший профиль, положил бы туда СВОЙ ключ и подписал бы им — self-проверка
 * прошла бы и ничего не защитила. Защиту даёт только сверка с ключом, закреплённым при первом
 * знакомстве (TOFU).
 *
 * Не-блокирующая детекция: метод лишь сообщает статус, вызывающий сам решает как показать
 * (щит / нейтрально / предупреждение). Отсутствие подписи (старый клиент, §17) → NOT_SIGNED,
 * а не отвержение. Логика обкатана в песочнице (prof.js, 6/6).
 */
object ProfileAuth {

    enum class ContentAuth {
        /** Подпись есть и сошлась с закреплённым ключом — содержимое подлинное. */
        VERIFIED,
        /** Подписи нет (старый клиент) ИЛИ ключ ещё не закреплён — сверить не с чем. */
        NOT_SIGNED,
        /** Подпись есть, но НЕ сошлась с закреплённым ключом — возможная подмена. */
        FORGED
    }

    /**
     * @param chatId сетевой Chat.chatId (тот же домен, что у identitySig/VerifiedBadge).
     * @param profile проверяемый профиль (из profiles.txt).
     * @param pinnedIdk закреплённый за profile.userId identity-ключ (TOFU). null — ещё не закреплён.
     */
    fun contentAuth(chatId: String, profile: Profile, pinnedIdk: String?): ContentAuth {
        val sig = profile.contentSig ?: return ContentAuth.NOT_SIGNED
        val pinned = pinnedIdk ?: return ContentAuth.NOT_SIGNED
        // ⛔ Если заявленный в профиле ключ НЕ совпадает с закреплённым — это СМЕНА КЛЮЧА
        // (переустановка/ротация), а НЕ подмена содержимого. Иначе после каждой переустановки
        // собеседника (Keystore очищается → новый identity-ключ) мы бы ложно кричали FORGED.
        // Смена ключа — отдельный флоу «личность изменилась» (сверка/ре-пиннинг), здесь молчим.
        // Настоящая подмена: профиль заявляет ЗАКРЕПЛЁННЫЙ ключ, но валидную подпись под
        // содержимым дать не может (нет приватника жертвы) → sig не сходится → FORGED.
        if (profile.identityPubKey != null && profile.identityPubKey != pinned) return ContentAuth.NOT_SIGNED
        val ok = CryptoHelper.verifyProfileContent(
            pinned, chatId, profile.userId,
            profile.name, profile.tag, profile.avatarBase64, profile.status, sig
        )
        return if (ok) ContentAuth.VERIFIED else ContentAuth.FORGED
    }

    /** Удобный булев-хелпер: true только при VERIFIED. */
    fun isContentAuthentic(chatId: String, profile: Profile, pinnedIdk: String?): Boolean =
        contentAuth(chatId, profile, pinnedIdk) == ContentAuth.VERIFIED
}
