package com.atrum.chat.transport

/**
 * Результат совмещённой загрузки chat.txt и reactions.txt за один сетевой запрос.
 */
data class ChatAndReactions(
    val chatContent: String,
    val reactionsContent: String
)

/**
 * Результат единого poll-запроса: chat.txt + reactions.txt + profiles.txt за один GET.
 *
 * Единый polling loop читает весь канал один раз и извлекает все три файла —
 * вместо двух отдельных запросов (сообщения + профили). Экономит один полный GET/тик.
 */
data class AllChannelData(
    val chatContent: String,
    val reactionsContent: String,
    val profilesContent: String,
    /** Все слоты profiles.txt (по одному событию на участника) — для union-чтения
     *  (Фаза 1: убирает lost-update). Пусто для не-Nostr транспортов. */
    val profileSlots: List<String> = emptyList(),
    /**
     * Содержимое members.txt (ADR-001, групповые чаты) — УЖЕ проверенное по подписи
     * администратора группы (см. NostrTransport.adminUserId/splitAll). Пустая строка —
     * либо это не групповой чат, либо валидного admin-подписанного members.txt ещё нет.
     * Любые события members.txt от НЕ-администратора сюда не попадают — отфильтрованы
     * до того, как контент покинул транспортный слой.
     */
    val membersContent: String = "",
    /**
     * Мультиподпись (Этап 2 «Админы»): ВСЕ проверенные по подписи слоты members.txt —
     * по одному на подписанта (главный админ + делегированные админы). Каждый слот =
     * (pubkey подписанта, зашифрованный content). Реле хранят реплейсбл-событие по одному
     * на (pubkey, kind, d=members.txt), поэтому у каждого админа СВОЙ слот, они не
     * перетирают друг друга.
     *
     * Кому доверять из этих слотов — решает НЕ транспорт (он не расшифровывает контент),
     * а слой синхронизации: [membersContent] (слот ГЛАВНОГО админа) — единственный
     * источник истины по ростеру/ролям; из остальных слотов берутся только мут/бан от тех,
     * кто в ростере главного помечен правом MODERATE (см. MembersSync.mergeSlots,
     * верховенство главного). Пусто — не группа/нет валидных событий/не-Nostr транспорт.
     */
    val memberSlots: List<MemberSlot> = emptyList(),
    /**
     * Децентрализованный ростер (ADR-001): ВСЕ слоты profiles.txt вместе с pubkey
     * ПОДПИСАВШЕГО событие участника — по одному на pubkey. В отличие от [profileSlots]
     * (только контент) здесь сохранён pubkey, чтобы слой синхронизации мог проверить
     * привязку userId↔pubkey (событие профиля участника подписано ключом, выводимым из
     * его же userId — см. NostrTransport.privkey/pubkeyForUserId) и не дать одному
     * участнику «накрутить» счётчик чужими userId. Так членство/счётчик считаются из
     * САМООПУБЛИКОВАННЫХ профилей и НЕ зависят от присутствия админа в сети
     * (см. GroupRosterSync). Пусто — не Nostr-транспорт.
     */
    val profileSlotsSigned: List<ProfileSlotSigned> = emptyList(),
    /**
     * «Профиль беседы» groupprofile.txt (имя/аватар/описание группы) — маленькое
     * отдельное replaceable-событие, подписанное администратором (проверяется
     * транспортом тем же способом, что и members.txt). Идея пользователя: беседа
     * отдаёт свои данные сама, как профиль человека, а не заставляет клиента
     * выковыривать их из тяжёлого members.txt (ава ~25КБ base64 внутри события —
     * реле его подрезают/медленно отдают, и его перезаписывает каждый энролл
     * участника). members.txt продолжает нести имя/аву для СТАРЫХ клиентов
     * (обратная совместимость, §1 CLAUDE.md); новые клиенты предпочитают этот файл.
     * Пусто — не группа / админ ещё не публиковал (старая версия приложения у админа).
     */
    val groupProfileContent: String = "",
    /**
     * Слоты reactions.txt вместе с pubkey подписавшего — по ОДНОМУ новейшему на pubkey
     * (аналог [profileSlotsSigned]). Нужны для UNION-чтения реакций: каждый участник
     * авторитетен ТОЛЬКО за свои реакции (строку msgId|emoji|userId принимаем из слота,
     * лишь если он подписан pubkeyForUserId(userId)). Чинит «мигание/исчезновение
     * реакций»: раньше latestFile брал лишь ОДИН слот с макс. created_at, затирая реакции
     * из остальных слотов. Пусто — не Nostr-транспорт (тогда работает старый одно-слотовый
     * путь через [reactionsContent], обратная совместимость §17).
     */
    val reactionSlotsSigned: List<ProfileSlotSigned> = emptyList()
)

/**
 * Один проверенный по подписи слот members.txt (мультиподпись, Этап 2 «Админы»).
 * [signerPubkey] — hex Nostr-pubkey подписанта (сверяется с pubkeyForUserId участника,
 * чтобы понять, кто это и есть ли у него право). [content] — зашифрованный JSON.
 */
data class MemberSlot(
    val signerPubkey: String,
    val content: String
)

/**
 * Один слот profiles.txt вместе с pubkey подписавшего его участника (ADR-001,
 * децентрализованный ростер). [signerPubkey] — hex Nostr-pubkey автора события
 * profiles.txt (сверяется с pubkeyForUserId(userId) внутри слота — привязка
 * userId↔pubkey против накрутки счётчика). [content] — зашифрованный JSON профиля.
 */
data class ProfileSlotSigned(
    val signerPubkey: String,
    val content: String
)

typealias AllGistData = AllChannelData

/**
 * Абстракция над транспортным слоем чата.
 *
 * Реализации:
 *   - NostrTransport — основной, P2P через Nostr-реле (WebSocket)
 *   - LocalTransport — оффлайн-путь (чат «Избранное»)
 *
 * Интерфейс зеркалит методы Legacy API, поэтому существующий код
 * (ProfileSync, ImageLoader, ChatActivity) переключается без логических правок.
 */
/**
 * Обезвреживает имя файла, пришедшее в т.ч. из сообщения собеседника (untrusted),
 * перед построением пути File(filesDir, prefix + name). Срезает компоненты каталога и
 * нейтрализует обход ("..", абсолютные пути) — защита от path traversal (особенно BLE,
 * где пир недоверенный). Легальные плоские имена ("chat.txt", "img_…") не меняются.
 */
internal fun safeChatFileName(name: String): String {
    val base = name.substringAfterLast('/').substringAfterLast('\\')
    return if (base.isEmpty() || base == "." || base == "..") "_" else base
}

interface ChatTransport {

    /** Человекочитаемое имя для UI: "Relay Source" / "Nostr P2P" */
    val displayName: String

    /** Иконка-символ для статусной строки (☁ / ⚡) */
    val displayIcon: String

    /**
     * Использует ли транспорт Tor для сетевых запросов.
     * Если true, внешние ресурсы (например, HTTP-картинки) тоже должны грузиться через Tor.
     */
    val useTor: Boolean get() = false

    /**
     * Стабильный идентификатор чата, уникальный для каждого канала.
     *
     * Используется как входной параметр для деривации соли Argon2id в CryptoHelper:
     *   salt = SHA-256("atrum_argon2_v1:" + chatId)[0:16]
     *
     * LegacyTransport → sourceId (GUID канала в метаданных)
     * NostrTransport  → channelId (hex(SHA256("atrum_channel_v1_" + sourceId)).take(16))
     *
     * Обе стороны чата получают одинаковый chatId → одинаковую соль → одинаковый ключ
     * без явного обмена солью через канал связи.
     */
    val chatId: String

    /**
     * Крипто-домен для шифрования КОНТЕНТА медиа (фото/голос/стикеры/манифест).
     * Должен совпадать с доменом, под которым ставится forward-secrecy сессия
     * (chat.chatId), чтобы медиа шифровалось тем же сессионным ключом, что и текст,
     * и не зависело от пароля. По умолчанию = chatId (для транспортов без отдельного
     * сетевого хеша). NostrTransport переопределяет его на исходный sourceId.
     */
    val cryptoChatId: String get() = chatId

    /**
     * Детерминированный Nostr-pubkey (hex) участника по его userId — та же деривация,
     * что и у ключа подписи (chatPassword, userId). Нужна мультиподписи (Этап 2 «Админы»):
     * сопоставить подписанта слота members.txt с участником ростера. По умолчанию "" —
     * транспорты без Nostr-подписи (Local/Bluetooth) в мультиподписи не участвуют, и
     * слияние там просто выключается (memberSlots пуст). NostrTransport переопределяет.
     */
    fun pubkeyForUserId(userId: String): String = ""

    /**
     * Заранее открывает соединения к своим реле в нужном режиме (Tor/direct), чтобы первый
     * send/read сразу после открытия чата не платил за установку сокета на критическом пути
     * (репорт: «первое сообщение в беседе заедает, у других не видно, помогает перезаход»).
     * Идемпотентно и безопасно: только греет сокеты, ничего не отправляет и не меняет
     * протокол/тайминги. По умолчанию no-op (Local/Bluetooth). NostrTransport переопределяет.
     */
    fun warmUp() {}

    /** Загружает полное содержимое chat.txt (все зашифрованные строки). */
    suspend fun loadContent(): String

    /**
     * Потоковая подписка на новые сообщения: транспорт сам зовёт [onNew] при появлении
     * нового сообщения (минимальная задержка, без частого опроса реле). Возвращает
     * «стоп» — закрыть при завершении. По умолчанию заглушка (стрима нет).
     */
    fun watchMessages(onNew: () -> Unit): AutoCloseable = AutoCloseable {}

    /**
     * Тот же стрим сообщений, но с ПРИНУДИТЕЛЬНЫМ САМОЛЕЧЕНИЕМ ([fastReopen] = true):
     * подписка периодически переоткрывается с небольшим lookback, чтобы пережить «тихую
     * смерть» подписки на публичном реле (сокет жив, но реле перестаёт слать новые события,
     * а hasSub остаётся true → обычная переподписка не срабатывает). Нужно списку чатов для
     * МИНИМАЛЬНОЙ задержки на КАЖДОМ сообщении (не только первом), без частого дорогого
     * опроса реле. [fastReopen] = false — прежнее поведение (фоновый сервис пушей: экономия
     * батареи важнее, там достаточно переподписки по факту обрыва). По умолчанию делегирует
     * в обычный [watchMessages].
     */
    fun watchMessages(fastReopen: Boolean, onNew: () -> Unit): AutoCloseable = watchMessages(onNew)

    /** Потоковая подписка на изменения профиля собеседника (аватар/ник) для
     *  мгновенного обновления. По умолчанию no-op (не-Nostr транспорты). */
    fun watchProfiles(onProfile: (String) -> Unit): AutoCloseable = AutoCloseable { }

    /**
     * true — потоковая подписка на новые сообщения сейчас жива (все активные реле
     * подписаны). Фоновый сервис пушей использует это, чтобы НЕ делать дорогую сетевую
     * сверку, пока стрим гарантированно доставляет — экономия батареи. По умолчанию true
     * (транспорты без стрима не нуждаются в этом механизме).
     */
    fun isWatchHealthy(): Boolean = true

    /**
     * true (одноразово) — с последней проверки реле ПРИСЛАЛО пушем новое событие revoke.txt
     * (отзыв/возврат создателя). Позволяет читать revoke.txt строго по пушу, а не по таймеру
     * (никаких холостых чтений). Флаг сбрасывается при чтении. По умолчанию false — транспорты
     * без стрима сюда не попадают (revoke применится при переоткрытии чата).
     */
    fun consumeRevokeDirty(): Boolean = false

    /**
     * Загружает содержимое chat.txt только если оно изменилось с последнего запроса.
     * Возвращает null если контент не изменился (HTTP 304 Not Modified) — UI не нужно обновлять.
     *
     * Дефолтная реализация для транспортов без поддержки ETag — всегда возвращает свежие данные.
     * LegacyTransport переопределяет через api.loadContentIfChanged().
     */
    suspend fun loadContentIfChanged(): String? = loadContent()

    /**
     * Загружает chat.txt и reactions.txt за ОДИН сетевой запрос.
     * Позволяет сократить вдвое число GET-запросов при каждом тике polling-а.
     *
     * Дефолтная реализация: два отдельных вызова (Nostr и другие транспорты без поддержки объединённой загрузки).
     * LegacyTransport переопределяет и делает один fetchJson, извлекая оба файла из общего JSON.
     */
    suspend fun loadChatAndReactions(): ChatAndReactions =
        ChatAndReactions(loadContent(), loadFileOrNull("reactions.txt") ?: "")

    /**
     * ETag-оптимизированная версия [loadChatAndReactions].
     * Возвращает null если контент не изменился (304) — ни chat.txt, ни reactions.txt обновлять не нужно.
     *
     * Дефолтная реализация: вызывает loadContentIfChanged + loadFileOrNull.
     * LegacyTransport переопределяет на один fetchJson с ETag.
     */
    suspend fun loadChatAndReactionsIfChanged(): ChatAndReactions? {
        val chatContent = loadContentIfChanged() ?: return null
        return ChatAndReactions(chatContent, loadFileOrNull("reactions.txt") ?: "")
    }

    /**
     * Единый ETag-оптимизированный запрос: chat.txt + reactions.txt + profiles.txt.
     * Возвращает null при 304 Not Modified — ничего не изменилось, UI не трогаем.
     */
    suspend fun loadAllIfChanged(): AllChannelData? {
        val cr = loadChatAndReactionsIfChanged() ?: return null
        return AllChannelData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Полный (без ETag) единый запрос: chat.txt + reactions.txt + profiles.txt.
     */
    suspend fun loadAll(): AllChannelData {
        val cr = loadChatAndReactions()
        return AllChannelData(cr.chatContent, cr.reactionsContent, "")
    }

    /**
     * Мгновенный снимок из ДОЛГОВЕЧНОГО локального стора БЕЗ сети — для показа при открытии чата
     * (§1.5: не ждать реле, чтобы просто показать уже известное; пустой чат не должен ждать сеть
     * ради подтверждения «пусто»). Сеть обновит следом. По умолчанию null (у транспорта нет
     * локального стора). NostrTransport возвращает рендер из NostrMessageStore.
     */
    suspend fun loadLocalSnapshotOrNull(): AllChannelData? = null

    /**
     * Как [loadAll], но БЕЗ анти-пустого fallback на уже накопленные где-то ещё данные —
     * возвращает null, если этот КОНКРЕТНЫЙ транспорт не получил собственного свежего
     * ответа от сети. Нужно одноразовым admin-экранам статистики (GroupStatsActivity/
     * UserStatsActivity, см. §16 репорт «участник считается вошедшим по странному
     * паттерну — то тогда, когда обновился чат у админа»): их transport создаётся заново
     * при каждом открытии экрана и должен сам дождаться СВОЕГО ответа, а не молча
     * унаследовать состояние, накопленное чужой параллельной сессией (например открытым
     * в другом окне чатом админа). ChatActivity по-прежнему использует [loadAll] — там
     * анти-пустой fallback необходим, чтобы не стирать уже показанную историю при
     * временном сбое реле. Дефолт — просто [loadAll] (транспорты без «холодного старта»
     * вроде Local/Bluetooth не нуждаются в различии).
     */
    suspend fun loadAllFresh(): AllChannelData? = loadAll()

    /**
     * Обновляет кэш-подсказку для appendLine с последним известным содержимым chat.txt.
     * Вызывать из ChatActivity после каждого успешного чтения chatContent.
     * Дефолтная реализация — no-op (NostrTransport, LocalTransport не используют кэш).
     */
    fun updateChatContentHint(content: String) {}

    /**
     * Продлевает TTL кэш-подсказки без изменения содержимого.
     * Вызывать когда сервер вернул 304 Not Modified — сервер подтвердил что кэш актуален.
     * Дефолтная реализация — no-op.
     */
    fun touchChatContentHint() {}

    /**
     * Дозаписывает строку в конец чата.
     *
     * @param encryptedLine Новая зашифрованная строка
     * @param extraFiles Дополнительные файлы для сохранения в том же PATCH-запросе
     *                   (только для LegacyTransport, атомарно с appendLine)
     */
    suspend fun appendLine(
        encryptedLine: String,
        extraFiles: Map<String, String> = emptyMap(),
        onFileProgress: ((fileName: String, current: Int, total: Int) -> Unit)? = null
    )

    /** Перезаписывает именованный файл (profiles.txt, img_*.txt и т.д.). */
    suspend fun saveFile(name: String, content: String)

    /**
     * Атомарно перезаписывает несколько файлов за один PATCH-запрос.
     * Дефолтная реализация — последовательные saveFile (для LocalTransport/NostrTransport).
     * LegacyTransport переопределяет через api.saveFiles() — один запрос вместо N.
     */
    suspend fun saveFiles(files: Map<String, String>) {
        files.forEach { (name, content) -> saveFile(name, content) }
    }

    /**
     * Мой pubkey на проводе (hex), которым подписаны МОИ события. Нужен, чтобы отличить свой
     * слот от чужого при проверке взаимного обмена профилями: попадание моего профиля в мой же
     * слот ничего не доказывает (см. JoinChatActivity.awaitMutualProfileExchange).
     *
     * null — транспорт без подписи событий (локальный чат, Bluetooth): там слот всегда один
     * и различать нечего.
     */
    val myWirePubkey: String? get() = null

    /** Загружает файл; возвращает null если не существует или ошибка. */
    suspend fun loadFileOrNull(name: String): String?

    /**
     * Загружает ВСЕ версии файла — по одной на каждого автора («слоты»), новые первыми.
     *
     * Зачем отдельно от [loadFileOrNull]: у файлов, которые независимо переписывают НЕСКОЛЬКО
     * участников (profiles.txt), каждое устройство пишет СВОЙ слот, и «последний записавший»
     * не содержит правок остальных. [loadFileOrNull] отдаёт ровно один, самый свежий, поэтому
     * построенный на нём read-modify-write теряет чужие профили (см. ProfileSync.pullProfiles).
     *
     * Дефолтная реализация — один слот: для транспортов с ОДНИМ писателем (локальный чат
     * «Избранное», обмен по Bluetooth) уния не нужна, поведение остаётся прежним.
     */
    suspend fun loadFileSlots(name: String): List<String> =
        listOfNotNull(loadFileOrNull(name)?.takeIf { it.isNotBlank() })

    /** Загружает файл; бросает исключение если не существует. */
    suspend fun loadFile(name: String): String

    /** Заменяет строку в чате по точному совпадению. Возвращает false если не найдена. */
    suspend fun replaceLine(oldLine: String, newLine: String): Boolean

    /** Удаляет строку из чата по точному совпадению. Возвращает false если не найдена. */
    suspend fun deleteLine(line: String): Boolean

    /**
     * Полная очистка истории чата.
     * Дефолт (Local/прочие): перезаписывает chat.txt пустым манифестом.
     * NostrTransport переопределяет: публикует "маркер очистки" + NIP-09 удаление.
     */
    suspend fun clearHistory() {
        saveFile("chat.txt", "# Atrum Chat")
    }

    /**
     * Сохраняет файл с автоматическим разбиением на чанки для обхода
     * лимитов провайдера при отправке больших изображений.
     *
     * Дефолтная реализация — просто вызывает [saveFile] (подходит для
     * Nostr и других транспортов без ограничений размера).
     * LegacyTransport переопределяет этот метод с реальной чанковой логикой.
     *
     * @param name             имя файла (manifest), например img_123.txt
     * @param encryptedContent уже зашифрованный контент для сохранения
     * @param password         пароль чата (нужен для шифрования манифеста)
     * @param onProgress       callback прогресса: (current, total) чанков
     */
    suspend fun saveFileChunked(
        name: String,
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ) = saveFile(name, encryptedContent)

    // ─────────────────────────────────────────────────────────────────────────
    // Reactions — хранятся в "reactions.txt" как plaintext строки msgId|emoji|userId
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Загружает содержимое reactions.txt. Пустая строка если файла нет.
     * Дефолтная реализация через loadFileOrNull — используется NostrTransport и др.
     */
    suspend fun loadReactions(): String = loadFileOrNull("reactions.txt") ?: ""

    /**
     * Атомарно переключает реакцию (add / remove toggle).
     * Возвращает true = реакция добавлена, false = удалена.
     *
     * LegacyTransport переопределяет для атомарной операции через writeMutex.
     * Дефолтная реализация — read-modify-write через saveFile (non-atomic).
     */
    suspend fun toggleReaction(msgId: String, emoji: String, userId: String): Boolean {
        val line = "$msgId|$emoji|$userId"
        val content = loadFileOrNull("reactions.txt") ?: ""
        val lines = content.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val idx = lines.indexOfFirst { it == line }
        return if (idx != -1) {
            lines.removeAt(idx)
            saveFile("reactions.txt", lines.joinToString("\n").ifBlank { "\n" })
            false
        } else {
            lines.add(line)
            saveFile("reactions.txt", lines.joinToString("\n"))
            true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Подписи авторства сообщений (ADR_MESSAGE_AUTHENTICITY.md, Фаза 2).
    // Отдельный файл "sigs.txt" — как reactions.txt: строка сообщения (chat.txt) НЕ
    // меняется, старые клиенты этот файл не читают → нулевой риск доставки (§1).
    // Содержимое ШИФРУЕТСЯ вызывающим (ChatActivity) доменом чата — чтобы реле не
    // видело связку identityPubKey↔msgId (§1 Безопасность). Здесь — только файловый I/O.
    // ─────────────────────────────────────────────────────────────────────────

    /** Загружает (зашифрованный) blob подписей. Пустая строка, если файла нет. */
    suspend fun loadSignatures(): String = loadFileOrNull("sigs.txt") ?: ""

    /** Перезаписывает blob подписей. Вызывающий уже зашифровал содержимое. */
    suspend fun saveSignatures(encryptedBlob: String) = saveFile("sigs.txt", encryptedBlob.ifBlank { "\n" })

    /**
     * Цепочка сертификатов передачи владения (ADR_MESSAGE_AUTHENTICITY.md §10). Отдельный
     * файл "owner.txt" — как sigs.txt: старые клиенты его не читают. Содержимое ШИФРУЕТСЯ
     * вызывающим доменом чата. Пусто, если файла нет.
     */
    suspend fun loadOwnerCerts(): String = loadFileOrNull("owner.txt") ?: ""

    /** Перезаписывает (зашифрованную) цепочку сертификатов владения. */
    suspend fun saveOwnerCerts(encryptedBlob: String) = saveFile("owner.txt", encryptedBlob.ifBlank { "\n" })

    /**
     * Сертификаты отзыва/возврата создателя verified-root'ом (RevokeSync). Отдельный файл
     * "revoke.txt" — как owner.txt: старые клиенты его не читают (§17). Содержимое ШИФРУЕТСЯ
     * вызывающим доменом чата. Пусто, если файла нет.
     */
    suspend fun loadRevokes(): String = loadFileOrNull("revoke.txt") ?: ""

    /** Перезаписывает (зашифрованную) цепочку сертификатов отзыва. */
    suspend fun saveRevokes(encryptedBlob: String) = saveFile("revoke.txt", encryptedBlob.ifBlank { "\n" })

    /**
     * Загружает зашифрованный контент изображения по ссылке.
     *
     * Поддерживаемые форматы [ref]:
     *   "source:ID"     → загрузить из отдельного хранилища (Content Room)
     *   "img_xxx.txt"   → загрузить файл из основного канала (Legacy)
     *
     * Возвращает сырую зашифрованную строку. Расшифровка — в ImageLoader.
     * LegacyTransport переопределяет для обработки "source:" ссылок.
     */
    suspend fun loadImageByRef(ref: String): String = loadFile(ref)

    /**
     * Загружает изображение в оптимальное хранилище и возвращает ссылку.
     *
     * LegacyTransport: создаёт НОВЫЙ приватный контейнер одним POST-запросом
     * → не трогает основной канал, не конкурирует с heartbeat/typing
     * → полный обход лимитов без задержек.
     *
     * Остальные транспорты: fallback — saveFileChunked в основном транспорте.
     *
     * @param encryptedContent уже зашифрованный base64 изображения
     * @param password         пароль чата (для fallback saveFileChunked)
     * @param onProgress       прогресс загрузки (только для fallback)
     * @return "source:ID" (Content Room) или "img_xxx.txt" (Legacy)
     */
    suspend fun uploadImage(
        encryptedContent: String,
        password: String,
        onProgress: ((current: Int, total: Int) -> Unit)? = null
    ): String {
        val fileName = com.atrum.chat.Message.newImageFileName()
        saveFileChunked(fileName, encryptedContent, password, onProgress)
        return fileName
    }
}
