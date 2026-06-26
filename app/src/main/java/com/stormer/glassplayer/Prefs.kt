package com.stormer.glassplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists resume positions and a recent-files list across sessions. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glassplayer", Context.MODE_PRIVATE)

    // Resume position per file URI (ms)
    fun savePosition(uri: String, posMs: Long) {
        sp.edit().putLong("pos_${uri.hashCode()}", posMs).apply()
    }
    fun getPosition(uri: String): Long = sp.getLong("pos_${uri.hashCode()}", 0L)

    // Recent files: list of {uri, name} most-recent-first, capped at 15
    fun addRecent(uri: String, name: String) {
        val list = getRecents().toMutableList()
        list.removeAll { it.first == uri }
        list.add(0, uri to name)
        while (list.size > 15) list.removeAt(list.size - 1)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("uri", it.first).put("name", it.second)) }
        sp.edit().putString("recents", arr.toString()).apply()
    }

    fun getRecents(): List<Pair<String, String>> {
        val raw = sp.getString("recents", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                o.getString("uri") to o.getString("name")
            }
        }.getOrDefault(emptyList())
    }

    fun clearRecents() = sp.edit().remove("recents").apply()
}
