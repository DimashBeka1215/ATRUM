package com.atrum.chat

import android.content.Context
import com.atrum.chat.data.AppDatabase
import com.atrum.chat.transport.TransportFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Планировщик публикаций ATRUM (по запросу пользователя: «нужен планировщик событий,
 * чтобы ставил действия в очередь»). Все админ-публикации метаданных группы —
 * members.txt (мут/бан/энролл/имя/описание) и groupprofile.txt (имя/ава/описание) —
 * идут ТОЛЬКО через эту очередь.
 *
 * Зачем (два бага одной природы):
 *  1. «Админ изменил аву и имя — ничего не изменилось»: смена имени и смена авы — два
 *     параллельных lifecycleScope.launch; оба читали ОДНУ версию members.txt и
 *     публиковали одинаковый номер — у получателей вторая правка отбрасывалась
 *     анти-откатом навсегда. А сорвавшаяся публикация профиля беседы нигде не
 *     запоминалась как «недоделанная» и не повторялась.
 *  2. «Мут лагает, если слишком быстро мутить»: та же гонка версий между быстрыми
 *     публикациями.
 *
 * Устройство:
 *  • Вместо очереди СОБЫТИЙ — очередь СОСТОЯНИЙ (dirty-флаги на чат): Room всегда
 *    обновляется мгновенно самим действием (§1.5), а флаг говорит «состояние чата X
 *    не опубликовано». Пять быстрых мутов = пять мгновенных правок Room и ОДНА
 *    публикация со всеми пятью (коалесценция) — быстрее и не теряется ничего.
 *  • Один воркер, строгая сериализация (mutex): версии members.txt монотонны по
 *    построению, гонка невозможна.
 *  • Флаги персистентны (Prefs): процесс умер до публикации — дочистится при
 *    следующем старте ([resume] из App.onCreate).
 *  • БЕЗ вечных запросов (требование пользователя): воркер живёт только пока есть
 *    грязные чаты; на сбоях — ограниченный бэкофф (до ~1 минуты на проход), затем
 *    воркер останавливается, флаги остаются — хвост дочистят самопочинка админа
 *    (maybeAdminRepairMembersFile → mark*Dirty) или следующий запуск приложения.
 */
object PublishScheduler {

    private const val KIND_MEMBERS = "members"
    private const val KIND_PROFILE = "profile"

    /** Бэкофф между проходами по грязным чатам, когда публикация не удалась. */
    private val RETRY_DELAYS_MS = longArrayOf(0L, 2_000L, 5_000L, 15_000L, 30_000L)

    private val workerMutex = Mutex()
    private val workerActive = AtomicBoolean(false)

    /** Атомарность read-modify-write dirty-наборов: пометки летят из UI/тиков/фона. */
    private val dirtyLock = Any()

    private fun addDirty(prefs: Prefs, kind: String, id: String) = synchronized(dirtyLock) {
        prefs.setPublishDirtySet(kind, prefs.getPublishDirtySet(kind) + id)
    }

    private fun removeDirty(prefs: Prefs, kind: String, id: String) = synchronized(dirtyLock) {
        prefs.setPublishDirtySet(kind, prefs.getPublishDirtySet(kind) - id)
    }

    /** Членство изменилось (мут/бан/энролл/имя/описание) — опубликовать members.txt чата. */
    fun markMembersDirty(context: Context, networkChatId: String) = mark(context, KIND_MEMBERS, networkChatId)

    /** Имя/ава/описание изменились — опубликовать groupprofile.txt чата. */
    fun markProfileDirty(context: Context, networkChatId: String) = mark(context, KIND_PROFILE, networkChatId)

    /** Дочистка недоставленного после перезапуска процесса — вызывается из App.onCreate. */
    fun resume(context: Context) {
        val prefs = Prefs(context)
        if (prefs.getPublishDirtySet(KIND_MEMBERS).isNotEmpty() ||
            prefs.getPublishDirtySet(KIND_PROFILE).isNotEmpty()
        ) ensureWorker(context.applicationContext)
    }

    private fun mark(context: Context, kind: String, networkChatId: String) {
        addDirty(Prefs(context), kind, networkChatId)
        ensureWorker(context.applicationContext)
    }

    private fun ensureWorker(appContext: Context) {
        if (!workerActive.compareAndSet(false, true)) return
        AppScope.launch(Dispatchers.IO) {
            try {
                workerMutex.withLock { drain(appContext) }
            } finally {
                workerActive.set(false)
                // Пока воркер завершался, могли пометить новый чат — перезапускаемся,
                // чтобы не потерять сигнал (классическое окно compareAndSet).
                val prefs = Prefs(appContext)
                if (prefs.getPublishDirtySet(KIND_MEMBERS).isNotEmpty() ||
                    prefs.getPublishDirtySet(KIND_PROFILE).isNotEmpty()
                ) {
                    if (workerActive.compareAndSet(false, true)) {
                        AppScope.launch(Dispatchers.IO) {
                            try { workerMutex.withLock { drain(appContext) } }
                            finally { workerActive.set(false) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun drain(appContext: Context) {
        val prefs = Prefs(appContext)
        for (attempt in RETRY_DELAYS_MS.indices) {
            if (RETRY_DELAYS_MS[attempt] > 0) kotlinx.coroutines.delay(RETRY_DELAYS_MS[attempt])
            var anyLeft = false

            for (chatId in prefs.getPublishDirtySet(KIND_MEMBERS)) {
                val ok = runCatching { publishMembers(appContext, chatId) }.getOrDefault(false)
                if (ok) removeDirty(prefs, KIND_MEMBERS, chatId) else anyLeft = true
            }
            for (chatId in prefs.getPublishDirtySet(KIND_PROFILE)) {
                val ok = runCatching { publishProfile(appContext, chatId) }.getOrDefault(false)
                if (ok) removeDirty(prefs, KIND_PROFILE, chatId) else anyLeft = true
            }
            if (!anyLeft) return
        }
        // Проходы исчерпаны — флаги остаются в Prefs, дочистят repair/следующий старт.
    }

    /**
     * Публикация ТЕКУЩЕГО состояния членства из Room (снимок строится в момент
     * выполнения — поэтому коалесценция бесплатна). true — успех или «нечего делать».
     */
    private suspend fun publishMembers(appContext: Context, networkChatId: String): Boolean {
        val db = AppDatabase.get(appContext)
        val prefs = Prefs(appContext)
        val chat = db.chatDao().getByChatId(networkChatId) ?: return true // чат удалён — снять флаг
        val adminUserId = chat.adminUserId ?: return true
        if (!chat.isGroup) return true
        // Мультиподпись (Этап 2): публиковать members.txt может ГЛАВНЫЙ админ ИЛИ делегат
        // с правом MODERATE. Делегат публикует СВОЙ слот (подписан его ключом) — он несёт
        // только его муты/баны; ростер/роли приёмник берёт из слота главного (верховенство,
        // см. MembersSync.mergeSlots). Роль-версию (chat.membersVersion) двигает ТОЛЬКО
        // главный — делегатский слот её не трогает (иначе фиктивный прогресс ростера).
        val isPrimary = adminUserId == prefs.myUserId
        val me = db.chatParticipantDao().getOne(chat.id, prefs.myUserId)
        val isModerator = me != null && AdminPermissions.has(me.permissions, AdminPermissions.MODERATE)
        val isPinner = me != null && AdminPermissions.has(me.permissions, AdminPermissions.PIN)
        // ⭐ Верифицированный разработчик (VerifiedBadge, PERSONAL_BUILD.md §Часть 2/3): публикует
        //    свой members.txt-слот в ЛЮБОЙ беседе — приёмники признают его полноправным админом по
        //    identity-подписи (MembersSync.isVerifiedAdminSlot), вне ростера главного и без участия
        //    настоящего админа. Гейт по isKeyVerified (а НЕ PersonalFeatures): слот полезен, только
        //    если ключ реально в захардкоженном списке — иначе его отвергнут. Поэтому работает и в
        //    обычном release-APK, где присутствует мой identity-ключ. Раньше здесь был тихий выход,
        //    из-за которого моя модерация применялась локально, но НИКОГДА не уходила в сеть.
        val isVerifiedAdmin = VerifiedBadge.isKeyVerified(prefs.myIdentityPubKey)
        if (!isPrimary && !isModerator && !isPinner && !isVerifiedAdmin) return true
        val participants = db.chatParticipantDao().getForChat(chat.id)
        if (participants.isEmpty()) return true // энролл-самолечение заведёт и пометит заново
        val password = prefs.getChatPassword(networkChatId).ifEmpty {
            @Suppress("DEPRECATION") chat.chatPassword
        }
        val token = prefs.getChatToken(networkChatId).ifEmpty {
            @Suppress("DEPRECATION") chat.transportToken
        }
        val transport = TransportFactory.forChat(
            appContext, networkChatId, token, password, prefs.myUserId, adminUserId = adminUserId
        )
        val newVersion = chat.membersVersion + 1
        val entries = participants.map {
            MembersSync.Entry(
                it.userId, it.banned, it.mutedUntilMs, it.mutedReason,
                MembersSync.evidenceIdsFromStore(it.mutedEvidenceIds), it.permissions
            )
        }
        // Закрепления (Этап 3): публикуем МОИ вклады (myPinnedMsgIds), а не показываемое
        // слияние — иначе я бы «залипал» чужие пины и мешал их откреплению.
        val pinnedList = chat.myPinnedMsgIds?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        // Подписываем СТАБИЛЬНОЕ состояние (версия+ростер+муты/баны+пины, привязанное к chatId)
        // приватным identity-ключом. priv затирается сразу (§1). Подписывают:
        //  • ГЛАВНЫЙ админ (Фаза 3, ADR §10) — чтобы приёмник проверил подлинность против
        //    ЗАКРЕПЛЁННОГО admin-ключа (неподделываемо; знание пароля не даёт identity-priv).
        //    Аддитивно: старые клиенты поле игнорируют, новые пока НЕ enforce'ят (см. applyIncoming).
        //  • верифиц-дев не-главный — прежняя заявка на ALL (isVerifiedAdminSlot).
        var identityKey: String? = null
        var identitySig: String? = null
        if (isPrimary || (isVerifiedAdmin && !isPrimary)) {
            val fileForSig = MembersSync.MembersFile(
                version = newVersion, adminUserId = adminUserId, participants = entries,
                ts = 0L, groupName = chat.groupName, groupDescription = chat.groupDescription,
                pinned = pinnedList
            )
            val (priv, _) = prefs.getOrCreateIdentity()
            try {
                identitySig = CryptoHelper.signWithIdentity(priv, MembersSync.verifiedAdminSigData(networkChatId, fileForSig))
                if (identitySig != null) identityKey = prefs.myIdentityPubKey
            } finally {
                priv.fill(0)
            }
        }
        MembersSync.publish(
            transport = transport,
            password = password,
            chatId = networkChatId,
            adminUserId = adminUserId, // всегда id ГЛАВНОГО — идентичность ростера
            newVersion = newVersion,
            participants = entries,
            groupName = chat.groupName,
            groupDescription = chat.groupDescription,
            pinned = pinnedList,
            identityKey = identityKey,
            identitySig = identitySig
        )
        // Роль-версию продвигает только главный; делегатский слот реле хранят по его pubkey
        // отдельно и обновляют по created_at, поэтому его «версия» приёмнику не важна.
        if (isPrimary) db.chatDao().updateMembersVersionIfNewer(chat.id, newVersion)
        return true
    }

    /** Публикация текущего профиля беседы (имя/ава/описание) из Room. */
    private suspend fun publishProfile(appContext: Context, networkChatId: String): Boolean {
        val db = AppDatabase.get(appContext)
        val prefs = Prefs(appContext)
        val chat = db.chatDao().getByChatId(networkChatId) ?: return true
        val adminUserId = chat.adminUserId ?: return true
        if (!chat.isGroup || adminUserId != prefs.myUserId) return true
        if (chat.groupName == null && chat.groupAvatarBase64 == null && chat.groupDescription == null) return true
        val password = prefs.getChatPassword(networkChatId).ifEmpty {
            @Suppress("DEPRECATION") chat.chatPassword
        }
        val token = prefs.getChatToken(networkChatId).ifEmpty {
            @Suppress("DEPRECATION") chat.transportToken
        }
        val transport = TransportFactory.forChat(
            appContext, networkChatId, token, password, prefs.myUserId, adminUserId = adminUserId
        )
        val ts = System.currentTimeMillis()
        GroupProfileSync.publish(
            transport = transport,
            password = password,
            chatId = networkChatId,
            groupName = chat.groupName,
            groupAvatarBase64 = chat.groupAvatarBase64,
            groupDescription = chat.groupDescription,
            ts = ts
        )
        prefs.setGroupProfileTs(networkChatId, ts)
        return true
    }
}
