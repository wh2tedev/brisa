package app.brisa.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object History {
    data class Entry(val title: String, val url: String, val time: Long)

    private const val FILE = "history"
    private const val KEY = "entries"
    private const val MAX = 500
    private var cache: MutableList<Entry>? = null

    fun all(context: Context): List<Entry> = entries(context).toList()

    fun record(context: Context, title: String, url: String) {
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        val list = entries(context)
        list.removeAll { it.url == url }
        list.add(0, Entry(title, url, System.currentTimeMillis()))
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save(context)
    }

    fun remove(context: Context, url: String) {
        entries(context).removeAll { it.url == url }
        save(context)
    }

    fun clear(context: Context) {
        entries(context).clear()
        save(context)
    }

    private fun entries(context: Context): MutableList<Entry> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        val loaded = runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                array.getJSONObject(i).let { Entry(it.getString("title"), it.getString("url"), it.getLong("time")) }
            }
        }.getOrDefault(mutableListOf())
        cache = loaded
        return loaded
    }

    private fun save(context: Context) {
        val array = JSONArray()
        entries(context).forEach {
            array.put(JSONObject().put("title", it.title).put("url", it.url).put("time", it.time))
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
