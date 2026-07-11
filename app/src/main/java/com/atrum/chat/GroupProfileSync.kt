package com.atrum.chat

import com.atrum.chat.data.Chat
import com.atrum.chat.data.ChatDao
import com.atrum.chat.transport.ChatTransport
import org.json.JSONObject

/**
 * «Профиль беседы» — groupprofile.txt (идея пользователя: «давай беседе сделаем такой же
 * профиль, как у людей — у людей же всё быстро ловит»).
 *
 * Имя/аватар/описание группы исторически ехали ВНУТРИ members.txt. Это медленно и хрупко:
 * аватар — ~25КБ base64 внутри события, реле такие события подрезают/медленно отдают, а
 * сам members.txt перезаписывается КАЖДЫМ энроллом участника — свежезашедший клиент легко
 * получал копию без авы (или вовсе битую) и ждал следующего апдейта членства.
 *
 * Теперь профиль беседы — ОТДЕЛЬНОЕ replaceable-событие (NIP-78, d=wireName), которое:
 *   • подписано детерминированным ключом администратора и проверяется транспортом ТЕМ ЖЕ
 *     механизмом, что и members.txt (NostrTransport.latestVerifiedAdminFile) — подделка
 *     от рядового участника отбрасывается до выхода из транспортного слоя;
 *   • меняется ТОЛЬКО когда админ реально правит имя/аву/описание — энроллы/муты/баны
 *     его не трогают, событие «стабильное» и надёжно оседает на реле;
 *   • приходит в том же самом poll-запросе, что и профили людей (chatFilter) — ноль
 *     дополнительных сетевых запросов.
 *
 * Обратная совместимость (§1 CLAUDE.md): members.txt ПРОДОЛЖАЕТ нести имя/аву/описание —
 * старые клиенты читают их оттуда, как раньше. Новые клиенты применяют оба источника;
 * анти-откат — по метке времени [MembersFileTs] в Prefs (без миграции Room).
 *
 * Формат (plaintext ДО шифрования; шифруется CryptoHelper.encryptMetadata тем же
 * детерминированным V4-доменом, что и members.txt/profiles.txt):
 *   { "v": 1, "ts": 1730000000000, "groupName": "...",
 *     "groupAvatarBase64": "...", "groupDescription": "..." }
 */
object GroupProfileSync {

    /** Имя файла на проводе (реле видит только wireName-токен, см. NostrTransport.wireName). */
    const val FILE_NAME = "groupprofile.txt"

    data class GroupProfile(
        val ts: Long,
        val groupName: String?,
        val groupAvatarBase64: String?,
        val groupDescription: String?
    )

    fun parse(decryptedJson: String): GroupProfile? = try {
        val j = JSONObject(decryptedJson)
        GroupProfile(
            ts = j.optLong("ts", 0L),
            groupName = j.optString("groupName", "").takeIf { it.isNotBlank() },
            groupAvatarBase64 = j.optString("groupAvatarBase64", "").takeIf { it.isNotBlank() },
            groupDescription = j.optString("groupDescription", "").takeIf { it.isNotBlank() }
        ).takeIf { it.ts > 0L }
    } catch (_: Exception) {
        null
    }

    fun buildContent(
        groupName: String?,
        groupAvatarBase64: String?,
        groupDescription: String?,
        ts: Long = System.currentTimeMillis()
    ): String = JSONObject().apply {
        put("v", 1)
        put("ts", ts)
        if (!groupName.isNullOrBlank()) put("groupName", groupName)
        if (!groupAvatarBase64.isNullOrBlank()) put("groupAvatarBase64", groupAvatarBase64)
        if (!groupDescription.isNullOrBlank()) put("groupDescription", groupDescription)
    }.toString()

    /**
     * Публикует профиль беседы. Вызывать ТОЛЬКО от лица администратора (иначе событие
     * подпишется не тем ключом и все клиенты его молча проигнорируют — тот же контракт,
     * что у MembersSync.publish). [ts] сохраняется вызывающим в Prefs как локальный
     * анти-откат (см. [applyIncoming]), чтобы свой же только что опубликованный профиль
     * не «откатывался» устаревшей копией с отставшего реле.
     */
    suspend fun publish(
        transport: ChatTransport,
        password: String,
        chatId: String,
        groupName: String?,
        groupAvatarBase64: String?,
        groupDescription: String?,
        ts: Long = System.currentTimeMillis()
    ) {
        // Бюджет авы + запрет чанкования — тот же фикс, что в MembersSync.publish
        // (чанкованное replaceable-событие нечитаемо для приёмников).
        val safeAvatar = AvatarUtils.boundedGroupAvatarBase64(groupAvatarBase64)
        val content = buildContent(groupName, safeAvatar, groupDescription, ts)
        val encrypted = CryptoHelper.encryptMetadata(content, password, chatId)
        if (encrypted.length <= MembersSync.METADATA_EVENT_MAX_CHARS) {
            transport.saveFile(FILE_NAME, encrypted)
            return
        }
        val slim = buildContent(groupName, null, groupDescription, ts)
        transport.saveFile(FILE_NAME, CryptoHelper.encryptMetadata(slim, password, chatId))
    }

    /**
     * Расшифровывает УЖЕ проверенный по подписи админа groupprofile.txt и применяет
     * имя/аву/описание к строке чата. Анти-откат — по ts в Prefs (ключ на chatId):
     * применяется только профиль СТРОЖЕ НОВЕЕ уже применённого, поэтому мигание
     * старой копией с отставшего реле невозможно. null-поля означают «не задано» —
     * не затирают локальное значение (тот же принцип «null = не менять», что и в
     * MembersSync.applyIncoming).
     *
     * @return true, если применился новый профиль (вызывающий может перерисовать шапку).
     */
    suspend fun applyIncoming(
        chat: Chat,
        contentEncrypted: String,
        password: String,
        chatDao: ChatDao,
        prefs: Prefs
    ): Boolean {
        if (contentEncrypted.isBlank()) return false
        val decrypted = CryptoHelper.decrypt(contentEncrypted, password, chat.chatId) ?: return false
        val parsed = parse(decrypted) ?: return false
        if (parsed.ts <= prefs.getGroupProfileTs(chat.chatId)) return false // анти-откат
        if (parsed.groupName == null && parsed.groupAvatarBase64 == null && parsed.groupDescription == null) return false
        chatDao.updateGroupProfile(
            id = chat.id,
            name = parsed.groupName ?: chat.groupName,
            avatar = parsed.groupAvatarBase64 ?: chat.groupAvatarBase64,
            description = parsed.groupDescription ?: chat.groupDescription
        )
        prefs.setGroupProfileTs(chat.chatId, parsed.ts)
        return true
    }
}
