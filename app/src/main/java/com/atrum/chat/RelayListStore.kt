package com.atrum.chat

import android.content.Context
import com.atrum.chat.nostr.NostrEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * Обновляемый список реле — БЕЗОПАСНО.
 *
 * Идея: единственный, кто может «объявить» новые реле, — владелец приватного ключа издателя.
 * В приложение вшит ПУБЛИЧНЫЙ ключ ([PUBLISHER_PUBKEY_HEX]) — якорь доверия. Список приходит
 * как подписанное Nostr-событие, читается с обычных реле; приложение принимает его ТОЛЬКО
 * если подпись сходится с вшитым ключом (проверка офлайн, Tor не нужен).
 *
 * Главные свойства безопасности:
 *  1. ADDITIVE — список лишь ДОБАВляет реле к встроенным ([NostrTransport.RELAYS]); встроенные
 *     удалить невозможно. Кривой/пустой/подменённый апдейт не делает хуже, чем сейчас.
 *  2. Подпись — без приватного ключа издателя нельзя подсунуть своё реле.
 *  3. Версия — принимаем только список с бОльшим номером версии (защита от отката).
 *
 * Содержимое события (plaintext JSON, секрета нет): {"v":Int,"relays":[...],"ts":Long}
 */
object RelayListStore {

    const val KIND = 30078                          // NIP-78 replaceable
    const val D_TAG = "atrum_relaylist_v1"

    /**
     * Публичный ключ издателя (x-only hex, 64 символа). ПУСТО = функция выключена:
     * приложение работает только на встроенных реле, удалённые списки не применяются.
     * Заполнить после генерации пары в экране «Издатель» (он покажет этот ключ).
     */
    const val PUBLISHER_PUBKEY_HEX = "82eca2579274a068f038ecb0d535cda0855ad6474112d1986ad1fd09d82ce04d"

    private const val SP = "atrum_relaylist"
    private const val MAX_RELAYS = 40

    @Volatile private var version = -1
    @Volatile private var relays: List<String> = emptyList()
    @Volatile private var updatedAt = 0L
    @Volatile private var loaded = false

    fun publisherConfigured(): Boolean = PUBLISHER_PUBKEY_HEX.length == 64

    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        val sp = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        version = sp.getInt("v", -1)
        updatedAt = sp.getLong("ts", 0L)
        relays = sp.getString("relays", "")?.split('\n')?.mapNotNull { sanitize(it) } ?: emptyList()
        loaded = true
    }

    fun extraRelays(ctx: Context): List<String> { ensureLoaded(ctx); return relays }
    fun currentVersion(ctx: Context): Int { ensureLoaded(ctx); return version }
    fun updatedAt(ctx: Context): Long { ensureLoaded(ctx); return updatedAt }

    /** Фильтр для запроса актуального списка с реле (null, если издатель не задан). */
    fun filter(): JSONObject? {
        if (!publisherConfigured()) return null
        return JSONObject().apply {
            put("authors", JSONArray().put(PUBLISHER_PUBKEY_HEX))
            put("kinds", JSONArray().put(KIND))
            put("#d", JSONArray().put(D_TAG))
            put("limit", 1)
        }
    }

    /**
     * Проверяет событие-список и применяет его, если оно подлинное и новее текущего.
     * Возвращает true, если список обновился.
     */
    fun tryApply(ctx: Context, ev: NostrEvent): Boolean {
        if (!publisherConfigured()) return false
        if (ev.pubkey.lowercase() != PUBLISHER_PUBKEY_HEX.lowercase()) {
            android.util.Log.d("RelayListStore", "Ignore event: publisher mismatch")
            return false
        }
        if (ev.kind != KIND) return false
        if (ev.tags.none { it.size >= 2 && it[0] == "d" && it[1] == D_TAG }) return false
        if (!NostrEvent.verifySignature(ev)) {
            android.util.Log.w("RelayListStore", "Invalid signature on relay list event")
            return false
        }
        val parsed = parse(ev.content) ?: return false
        ensureLoaded(ctx)
        if (parsed.first <= version) {
            android.util.Log.d("RelayListStore", "Ignore event: version ${parsed.first} <= $version")
            return false
        }
        android.util.Log.i("RelayListStore", "Applying new relay list v${parsed.first}: ${parsed.second}")
        version = parsed.first
        relays = parsed.second
        updatedAt = System.currentTimeMillis()
        persist(ctx)
        return true
    }

    /** Формирует содержимое события для публикации (вызывается экраном издателя). */
    fun buildContent(newVersion: Int, relayList: List<String>): String =
        JSONObject().apply {
            put("v", newVersion)
            put("ts", System.currentTimeMillis() / 1000L)
            put("relays", JSONArray().also { arr -> relayList.forEach { arr.put(it) } })
        }.toString()

    /** Версия из содержимого события (или -1). Для проверки доставки. */
    fun versionOf(content: String): Int = parse(content)?.first ?: -1

    private fun parse(content: String): Pair<Int, List<String>>? = try {
        val j = JSONObject(content)
        val v = j.getInt("v")
        val arr = j.getJSONArray("relays")
        val list = ArrayList<String>()
        for (i in 0 until arr.length()) sanitize(arr.optString(i))?.let { if (it !in list) list.add(it) }
        if (v < 0 || list.isEmpty()) null else (v to list.take(MAX_RELAYS))
    } catch (_: Exception) { null }

    /** Допускаем только разумные wss://-адреса. */
    private fun sanitize(s: String?): String? {
        val u = s?.trim()?.lowercase() ?: return null
        if (!u.startsWith("wss://")) return null
        if (u.length < 8 || u.length > 200 || u.contains(' ')) return null
        return u
    }

    private fun persist(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
            .putInt("v", version)
            .putLong("ts", updatedAt)
            .putString("relays", relays.joinToString("\n"))
            .apply()
    }
}
