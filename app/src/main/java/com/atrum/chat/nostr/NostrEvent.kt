package com.atrum.chat.nostr

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Nostr Event согласно NIP-01.
 *
 * id        = SHA-256 от канонической сериализации (hex)
 * pubkey    = x-координата публичного ключа Schnorr (hex, 32 байта)
 * created_at = Unix timestamp (секунды)
 * kind      = тип события
 * tags      = список тегов [[name, value, ...], ...]
 * content   = строковой контент
 * sig       = Schnorr-подпись id (hex, 64 байта)
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val created_at: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
    val sig: String
) {
    /** Сериализует событие в JSON для передачи через WebSocket. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("pubkey", pubkey)
        put("created_at", created_at)
        put("kind", kind)
        put("tags", tagsToJsonArray(tags))
        put("content", content)
        put("sig", sig)
    }

    companion object {

        /**
         * Создаёт и подписывает новое событие.
         *
         * @param privkeyBytes  32-байтовый приватный ключ (big-endian)
         * @param kind          тип (1 = note, 5 = deletion, 30078 = NIP-78 app data)
         * @param tags          теги: [["t", channelId], ["file", "profiles.txt"], ...]
         * @param content       контент события
         */
        fun create(
            privkeyBytes: ByteArray,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): NostrEvent {
            val pubkeyBytes = Schnorr.pubkeyFromPrivkey(privkeyBytes)
            val pubkeyHex = pubkeyBytes.toHex()
            val createdAt = System.currentTimeMillis() / 1000L

            // Каноническая сериализация: [0, pubkey, created_at, kind, tags, content]
            val serialized = canonical(pubkeyHex, createdAt, kind, tags, content)
            val idBytes = sha256(serialized.toByteArray(Charsets.UTF_8))
            val idHex = idBytes.toHex()

            val sigBytes = Schnorr.sign(privkeyBytes, idBytes)

            return NostrEvent(
                id = idHex,
                pubkey = pubkeyHex,
                created_at = createdAt,
                kind = kind,
                tags = tags,
                content = content,
                sig = sigBytes.toHex()
            )
        }

        /**
         * NIP-09: создаёт событие-запрос на удаление другого события.
         * Большинство публичных реле удалят исходное событие после получения этого.
         */
        fun createDeletion(privkeyBytes: ByteArray, targetEventId: String): NostrEvent {
            return create(
                privkeyBytes = privkeyBytes,
                kind = 5,
                tags = listOf(listOf("e", targetEventId)),
                content = "deleted"
            )
        }

        /** NIP-09: одно событие-удаление сразу для нескольких целей (e-теги). */
        fun createDeletion(privkeyBytes: ByteArray, targetEventIds: List<String>): NostrEvent {
            return create(
                privkeyBytes = privkeyBytes,
                kind = 5,
                tags = targetEventIds.map { listOf("e", it) },
                content = "deleted"
            )
        }

        /** Парсит JSON-объект события; возвращает null при любой ошибке. */
        fun fromJson(json: JSONObject): NostrEvent? = try {
            val tags = mutableListOf<List<String>>()
            val tagsArr = json.getJSONArray("tags")
            for (i in 0 until tagsArr.length()) {
                val tagArr = tagsArr.getJSONArray(i)
                tags.add((0 until tagArr.length()).map { tagArr.getString(it) })
            }
            NostrEvent(
                id         = json.getString("id"),
                pubkey     = json.getString("pubkey"),
                created_at = json.getLong("created_at"),
                kind       = json.getInt("kind"),
                tags       = tags,
                content    = json.getString("content"),
                sig        = json.getString("sig")
            )
        } catch (_: Exception) {
            null
        }

        // ─── helpers ──────────────────────────────────────────────────────────

        /**
         * Каноническая сериализация для вычисления id согласно NIP-01.
         *
         * ВАЖНО: НЕ использовать org.json — Android-реализация экранирует прямой
         * слэш '/' как '\\/'. Base64-контент почти всегда содержит '/', поэтому
         * id, посчитанный по org.json-строке, НЕ совпадает с пересчётом реле
         * (которое сериализует по NIP-01 с обычным '/') → "invalid: bad event id".
         * Строим строку вручную, экранируя только разрешённые NIP-01 символы.
         */
        private fun canonical(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<List<String>>,
            content: String
        ): String {
            val sb = StringBuilder()
            sb.append("[0,\"").append(escapeJsonNip01(pubkey)).append("\",")
            sb.append(createdAt).append(',')
            sb.append(kind).append(',')
            sb.append('[')
            tags.forEachIndexed { i, tag ->
                if (i > 0) sb.append(',')
                sb.append('[')
                tag.forEachIndexed { j, v ->
                    if (j > 0) sb.append(',')
                    sb.append('\"').append(escapeJsonNip01(v)).append('\"')
                }
                sb.append(']')
            }
            sb.append(']')
            sb.append(',')
            sb.append('\"').append(escapeJsonNip01(content)).append('\"')
            sb.append(']')
            return sb.toString()
        }

        /**
         * Экранирование строки по NIP-01: ТОЛЬКО эти 7 символов, ничего больше.
         * В частности, '/' остаётся как есть (это и ломало id через org.json).
         */
        private fun escapeJsonNip01(s: String): String {
            val sb = StringBuilder(s.length + 2)
            for (c in s) {
                when (c) {
                    '\"'     -> sb.append("\\\"")
                    '\\'     -> sb.append("\\\\")
                    '\n'     -> sb.append("\\n")
                    '\r'     -> sb.append("\\r")
                    '\t'     -> sb.append("\\t")
                    '\b'     -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else     -> sb.append(c)
                }
            }
            return sb.toString()
        }

        private fun tagsToJsonArray(tags: List<List<String>>): JSONArray =
            JSONArray().also { arr ->
                tags.forEach { tag ->
                    arr.put(JSONArray().also { t -> tag.forEach { v -> t.put(v) } })
                }
            }

        private fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)
    }
}

// ─── ByteArray / String hex utilities ────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    check(length % 2 == 0) { "Hex string length must be even" }
    return ByteArray(length / 2) { i ->
        ((Character.digit(this[i * 2], 16) shl 4) + Character.digit(this[i * 2 + 1], 16)).toByte()
    }
}
