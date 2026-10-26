package app.brisa.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Session {
    data class Entry(val title: String, val url: String)
    data class Saved(val tabs: List<Entry>, val current: Int)

    private const val FILE = "session"
    private const val KEY = "state"

    fun save(context: Context, tabs: List<Entry>, current: Int) {
        val array = JSONArray()
        tabs.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        val state = JSONObject().put("tabs", array).put("current", current)
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, state.toString()).apply()
    }

    fun load(context: Context): Saved? {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        return runCatching {
            val state = JSONObject(raw)
            val array = state.getJSONArray("tabs")
            val tabs = List(array.length()) { i ->
                array.getJSONObject(i).let { Entry(it.getString("title"), it.getString("url")) }
            }.filter { it.url.startsWith("https://") || it.url.startsWith("http://") }
            Saved(tabs, state.getInt("current"))
        }.getOrNull()?.takeIf { it.tabs.isNotEmpty() }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
