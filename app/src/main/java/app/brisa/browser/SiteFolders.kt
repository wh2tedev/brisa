package app.brisa.browser

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit
import org.json.JSONObject

/** Download folders chosen for specific sites. A site without an entry uses the general folder. */
object SiteFolders {
    private const val KEY = "site_download_folders"

    /** "www.Example.com" and "example.com" are the same site. */
    fun key(host: String?): String? = host?.trim()?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    private fun read(context: Context): MutableMap<String, String> {
        val raw = context.defaultPrefs().getString(KEY, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun write(context: Context, map: Map<String, String>) {
        context.defaultPrefs().edit { putString(KEY, JSONObject(map as Map<*, *>).toString()) }
    }

    /** Every configured site and its folder, sorted by site. */
    fun all(context: Context): List<Pair<String, Uri>> =
        read(context).toSortedMap().map { it.key to Uri.parse(it.value) }

    /** The folder set for exactly this site, if any. */
    fun get(context: Context, host: String?): Uri? =
        key(host)?.let { read(context)[it] }?.let(Uri::parse)

    /** The folder for this site or, failing that, for its parent domain ("a.example.com" uses "example.com"). */
    fun find(context: Context, host: String?): Uri? {
        val site = key(host) ?: return null
        val map = read(context)
        if (map.isEmpty()) return null
        val parts = site.split('.')
        for (i in 0 until (parts.size - 1).coerceAtLeast(1)) {
            map[parts.drop(i).joinToString(".")]?.let { return Uri.parse(it) }
        }
        return null
    }

    fun set(context: Context, host: String, uri: Uri?) {
        val site = key(host) ?: return
        val map = read(context)
        val old = map[site]?.let(Uri::parse)
        if (uri == null) map.remove(site) else map[site] = uri.toString()
        write(context, map)
        releaseIfUnused(context, old)
    }

    fun inUse(context: Context, uri: Uri): Boolean =
        context.defaultPrefs().getString(Prefs.DOWNLOAD_TREE, null) == uri.toString() ||
            read(context).values.any { it == uri.toString() }

    /** Gives back the system permission for a folder nothing points to any more. */
    fun releaseIfUnused(context: Context, uri: Uri?) {
        if (uri == null || inUse(context, uri)) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }
}
