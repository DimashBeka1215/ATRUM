package com.atrum.chat

import com.atrum.chat.transport.ChatTransport
import com.atrum.chat.transport.ProfileSlotSigned
import java.util.concurrent.ConcurrentHashMap

/**
 * ОТВЕТНАЯ СТОРОНА обмена профилями.
 *
 * ⚠️ Зачем это появилось (репорт: «профиль ищет безумно долго», «аватарка у создателя не
 * приходит»). Обмен профилями в ATRUM устроен так: каждый участник публикует СВОЙ слот
 * profiles.txt, и в этот слот попадает КАРТА участников целиком — то есть моя копия
 * собеседника тоже (см. ProfileSync.pushMyProfile: read-modify-write). Значит доказательство
 * «он меня получил» — это чужой слот, в котором лежит мой актуальный профиль.
 *
 * Проблема была в том, что переопубликовать свой слот умел ТОЛЬКО открытый экран чата
 * (ChatActivity). Экран списка чатов профили лишь читает. Поэтому создатель, который отдал
 * ATR-код и не сидит в чате, ответной публикации не делал НИКОГДА — и взаимный обмен,
 * которого ждёт экран присоединения, был недостижим в самом частом сценарии: «я скинул код,
 * ты вводи, я подожду в списке».
 *
 * Этот объект закрывает дыру: любой экран, который и так прочитал канал, может дёшево
 * проверить «а несёт ли мой слот актуальные копии тех, кого я вижу?» и, если нет, ровно один
 * раз переопубликоваться. Свойства:
 *
 *  - САМООГРАНИЧЕНИЕ: как только мой слот покрыл всех видимых участников, публикаций больше
 *    нет — условие перестаёт выполняться само, без флагов и таймеров.
 *  - БЕЗ ЛИШНЕЙ КРИПТОГРАФИИ: мой слот разбирается тем же [ProfileSync.parseProfiles], что
 *    и остальные слоты этого же чтения, поэтому попадает в кэш разбора слотов и почти всегда
 *    достаётся из памяти.
 *  - БЕЗ НОВОГО ПОЛЛИНГА (§1): своих сетевых циклов не заводит, работает на уже готовых
 *    данных вызывающего. Единственный сетевой вызов — сама публикация, и только по делу.
 */
object ProfileExchange {

    /**
     * Минимальная пауза между ответными публикациями ОДНОГО чата. Страховка от «шторма»:
     * если публикация не проходит (реле не собрали кворум), условие «слот не покрывает
     * участников» останется истинным и на следующем тике опроса — без паузы это означало бы
     * попытку записи каждые несколько секунд.
     */
    private const val ACK_THROTTLE_MS = 12_000L

    private val lastAckAttemptMs = ConcurrentHashMap<String, Long>()

    /**
     * Несёт ли МОЙ слот актуальные копии профилей [peers]?
     *
     * Чистая функция (вход → выход) — вынесена ради юнит-тестов, см. ProfileExchangeTest.
     * Критерий «актуальная копия»: участник вообще присутствует, его ник совпадает, аватар
     * совпадает, и метка updatedAt не старше той, что я вижу в канале. Сравнивать только
     * присутствие нельзя: слот мог остаться с прошлой сессии, когда у собеседника были другие
     * ник и аватар, и тогда он увидел бы в моём слоте себя-устаревшего и не засчитал бы обмен.
     */
    internal fun mySlotCoversPeers(
        mySlot: Map<String, Profile>?,
        peers: Map<String, Profile>
    ): Boolean {
        if (peers.isEmpty()) return true          // подтверждать нечего
        if (mySlot == null) return false          // моего слота на реле нет вовсе
        for ((uid, peer) in peers) {
            val copy = mySlot[uid] ?: return false
            if (copy.name != peer.name) return false
            if (copy.avatarBase64.orEmpty() != peer.avatarBase64.orEmpty()) return false
            if (copy.updatedAt < peer.updatedAt) return false
        }
        return true
    }

    /**
     * Несёт ли слот [slot] мой АКТУАЛЬНЫЙ профиль. Чистая функция, см. ProfileExchangeTest.
     *
     * Применяется к слотам обеих сторон, и смысл в каждом случае свой:
     *  • ЧУЖОЙ слот с моим профилем = собеседник меня прочитал и переопубликовал (взаимность);
     *  • МОЙ слот с моим профилем = мои данные реально лежат на реле и читаются.
     *
     * Аватар сверяем строго: собеседник мог прочитать меня, когда аватара ещё не было, и такой
     * слот не доказывает, что он увидит нынешнюю картинку. Имя сверяем, только если оно у меня
     * непустое (пустое имя сравнивать не с чем).
     */
    internal fun slotCarriesMyProfile(
        slot: Map<String, Profile>,
        myUserId: String,
        myName: String,
        myAvatarBase64: String?
    ): Boolean {
        val me = slot[myUserId] ?: return false
        if (myName.isNotBlank() && me.name != myName) return false
        return me.avatarBase64.orEmpty() == myAvatarBase64.orEmpty()
    }

    /**
     * Ответная публикация, если мой слот ещё не подтверждает кого-то из видимых участников.
     *
     * @param signedSlots слоты profiles.txt ЭТОГО ЖЕ чтения (нужны, чтобы найти среди них мой
     *        собственный по подписи — чужая копия меня доказательством не является).
     * @param knownProfiles уже разобранная карта участников этого же чтения.
     * @return true, если публикация была выполнена и удалась.
     */
    suspend fun ackPeersIfNeeded(
        api: ChatTransport,
        password: String,
        myUserId: String,
        signedSlots: List<ProfileSlotSigned>,
        knownProfiles: Map<String, Profile>,
        myName: String,
        myTag: String?,
        myAvatarBase64: String?,
        myIdentityPubKey: String?,
        protocolVersion: Int,
        identityPriv: ByteArray? = null
    ): Boolean {
        try {
            // Вышедших и удаливших профиль подтверждать не нужно — они уже не ждут моих данных.
            // Участник без имени — обычно presence-заготовка, его профиль ещё не приехал.
            val peers = knownProfiles
                .filterKeys { it != myUserId }
                .filterValues { !it.deleted && !it.left && it.name.isNotBlank() }
            if (peers.isEmpty()) return false
            // Транспорт без подписи событий (локальный чат, Bluetooth): свой слот от чужого не
            // отличить, а значит и доказывать нечего — там писатель один (§17).
            val myPubkey = api.myWirePubkey ?: return false
            val mySlotEncrypted = signedSlots
                .firstOrNull { it.signerPubkey.equals(myPubkey, ignoreCase = true) }
                ?.content
            val mySlot = mySlotEncrypted?.let { ProfileSync.parseProfiles(it, password, api.chatId) }
            if (mySlotCoversPeers(mySlot, peers)) return false
            val now = System.currentTimeMillis()
            if (now - (lastAckAttemptMs[api.chatId] ?: 0L) < ACK_THROTTLE_MS) return false
            lastAckAttemptMs[api.chatId] = now

            // ⛔ КРИТИЧНО: pushMyProfile делает ПОЛНУЮ замену моего слота тем Profile, который
            // ему передали (см. ProfileSync.pushMyProfile: existing[userId] = profileToPush).
            // Опубликовать отсюда «слим»-профиль из одних имени и аватара значило бы стереть с
            // реле мой эфемерный ключ, подписи и статус — то есть сломать собеседнику forward
            // secrecy и галочку верификации ради ответной публикации. Поэтому берём СВОЮ
            // последнюю опубликованную запись как основу и обновляем в ней только то, что
            // экран списка чатов знает достоверно: ник, тег, аватар, identity-ключ, версию
            // протокола. Всё остальное переносится как есть (Profile.copy).
            // ⛔ БАЗА — ТОЛЬКО МОЙ СОБСТВЕННЫЙ СЛОТ, никогда не чужая копия меня.
            // Раньше был фолбэк на knownProfiles[myUserId] — то есть на карту из ЧУЖОГО слота.
            // Чужая копия меня может быть какой угодно устаревшей: без identity-подписи, со
            // старым эфемерным ключом, без статуса. Опубликовав её как «себя», я бы своими
            // руками стёр с реле собственную галочку и сломал forward secrecy. Если своего
            // слота сейчас не видно — просто не подтверждаем в этот раз: публикацией себя с
            // нуля занимаются ChatActivity и PublishScheduler, у них есть все поля.
            val base = mySlot?.get(myUserId) ?: return false
            val updated = base.copy(
                name = myName.ifBlank { base.name },
                tag = myTag?.takeIf { it.isNotBlank() } ?: base.tag,
                avatarBase64 = myAvatarBase64?.takeIf { it.isNotBlank() } ?: base.avatarBase64,
                identityPubKey = myIdentityPubKey ?: base.identityPubKey,
                pv = maxOf(protocolVersion, base.pv),
                // ⛔ IDENTITY-ПОДПИСЬ ПЕРЕСЧИТЫВАЕТСЯ ЛОКАЛЬНО, А НЕ НАСЛЕДУЕТСЯ.
                // Именно по ней рисуется галочка и выдаётся иммунитет (VerifiedBadge.
                // isVerifiedProfile), а pushMyProfile ЗАМЕНЯЕТ мой слот целиком — значит
                // профиль без подписи, опубликованный отсюда, гасит галочку у всех
                // собеседников в этом чате. Наследовать её из base мало: base — это то, что
                // лежит на реле, а туда подпись могла не попасть (старый слот, слот, собранный
                // другим путём). Считаем её здесь из личного ключа: подпись над доменом
                // atrum_idsig_v1_<chatId> детерминирована, стоит копейки и подделке не
                // подлежит. Если ключа под рукой нет — оставляем то, что было (не хуже).
                identitySig = identityPriv
                    ?.let {
                        runCatching {
                            CryptoHelper.signWithIdentity(it, VerifiedBadge.identitySigData(api.chatId))
                        }.getOrNull()
                    }
                    ?: base.identitySig
            )
            val contentChanged = updated.name != base.name ||
                updated.tag != base.tag ||
                updated.avatarBase64 != base.avatarBase64
            // Подпись содержимого перевыпускаем ТОЛЬКО если содержимое изменилось: иначе
            // получатель сверял бы новую подпись со старыми полями. null → pushMyProfile
            // подпишет заново переданным приватником (см. там же).
            val toPublish =
                if (contentChanged) updated.copy(updatedAt = now, contentSig = null) else updated
            return ProfileSync.pushMyProfile(api, password, toPublish, identityPriv)
        } finally {
            // Приватник затираем в любом исходе, включая ранние return (§1: не оставлять
            // копию ключа в памяти). pushMyProfile затирает свою копию сам — повторный
            // fill(0) по тому же массиву безвреден.
            identityPriv?.fill(0)
        }
    }
}
