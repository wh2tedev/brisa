package app.brisa.browser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Bookmarks {
    data class Item(val title: String, val url: String)

    private const val FILE = "bookmarks"
    private const val KEY = "items"

    fun all(context: Context): List<Item> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { i ->
                array.getJSONObject(i).let { Item(it.getString("title"), it.getString("url")) }
            }
        }.getOrDefault(emptyList())
    }

    fun contains(context: Context, url: String) = all(context).any { it.url == url }

    fun add(context: Context, title: String, url: String) = save(context, all(context) + Item(title, url))

    fun remove(context: Context, url: String) = save(context, all(context).filterNot { it.url == url })

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("title", it.title).put("url", it.url)) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
