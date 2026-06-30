package com.atrum.chat.mods

import org.json.JSONObject

/**
 * Разобранный каталог модов. [canonical] — каноничная строка, над которой проверяется
 * подпись издателя (см. ModManager): "v|id:version|id:version|…".
 */
data class ModCatalog(
    val version: Int,
    val mods: List<ModInfo>,
    val signature: String,
    val canonical: String
)

object ModCatalogParser {

    /** JSON каталога → [ModCatalog] или null при ошибке формата. */
    fun parse(json: String): ModCatalog? = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("mods")
        val mods = (0 until arr.length()).map { i ->
            val m = arr.getJSONObject(i)
            val ver = m.getInt("version")
            ModInfo(
                id = m.getString("id"),
                name = m.getString("name"),
                description = m.optString("description"),
                version = ver,
                versionName = m.optString("versionName", ver.toString()),
                minAppVersion = m.optInt("minAppVersion", 0),
                dexUrl = m.optString("dexUrl"),
                dexSha256 = m.optString("dexSha256"),
                entryClass = m.optString("entryClass"),
                signature = m.optString("signature")
            )
        }
        val v = o.getInt("v")
        // Каноничная строка для проверки подписи каталога (стабильный порядок из массива).
        val canonical = buildString {
            append(v)
            for (mod in mods) {
                append('|'); append(mod.id); append(':'); append(mod.version)
            }
        }
        ModCatalog(v, mods, o.optString("sig"), canonical)
    } catch (_: Exception) {
        null
    }
}
