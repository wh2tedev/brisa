package app.brisa.browser

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

/** What the person answered when a site asked to use the camera, microphone, location or MIDI. */
object SitePermissions {
    const val CAMERA = "camera"
    const val MICROPHONE = "microphone"
    const val LOCATION = "location"
    const val MIDI = "midi"

    private const val KEY = "site_permissions"

    private fun read(context: Context): MutableMap<String, Boolean> {
        val raw = context.defaultPrefs().getString(KEY, null) ?: return mutableMapOf()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getBoolean(it) }.toMutableMap()
        }.getOrDefault(mutableMapOf())
    }

    private fun id(host: String, kind: String) = "$host|$kind"

    /** true = always allowed, false = always blocked, null = ask. */
    fun get(context: Context, host: String?, kind: String): Boolean? {
        val site = SiteFolders.key(host) ?: return null
        return read(context)[id(site, kind)]
    }

    fun set(context: Context, host: String?, kind: String, allow: Boolean?) {
        val site = SiteFolders.key(host) ?: return
        val map = read(context)
        if (allow == null) map.remove(id(site, kind)) else map[id(site, kind)] = allow
        context.defaultPrefs().edit { putString(KEY, JSONObject(map as Map<*, *>).toString()) }
    }

    /** (site, kind, allowed) for every remembered answer. */
    fun all(context: Context): List<Triple<String, String, Boolean>> =
        read(context).toSortedMap().map { (key, value) ->
            Triple(key.substringBefore('|'), key.substringAfter('|'), value)
        }
}
