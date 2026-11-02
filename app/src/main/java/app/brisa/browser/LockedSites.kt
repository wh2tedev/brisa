package app.brisa.browser

import android.content.Context
import androidx.core.content.edit

/** Sites that need the phone's own lock (fingerprint, face, PIN) before they are shown. */
object LockedSites {
    private const val KEY = "locked_sites"

    fun all(context: Context): List<String> =
        (context.defaultPrefs().getStringSet(KEY, null) ?: emptySet()).sorted()

    /** The locked entry that covers [host] (the site itself or its parent domain), if any. */
    fun match(context: Context, host: String?): String? {
        val site = SiteFolders.key(host) ?: return null
        val locked = context.defaultPrefs().getStringSet(KEY, null) ?: return null
        if (locked.isEmpty()) return null
        val parts = site.split('.')
        for (i in 0 until (parts.size - 1).coerceAtLeast(1)) {
            val candidate = parts.drop(i).joinToString(".")
            if (candidate in locked) return candidate
        }
        return null
    }

    fun isLocked(context: Context, host: String?): Boolean = match(context, host) != null

    fun add(context: Context, host: String?) {
        val site = SiteFolders.key(host) ?: return
        val set = (context.defaultPrefs().getStringSet(KEY, null) ?: emptySet()).toMutableSet()
        set += site
        context.defaultPrefs().edit { putStringSet(KEY, set) }
    }

    fun remove(context: Context, site: String) {
        val set = (context.defaultPrefs().getStringSet(KEY, null) ?: emptySet()).toMutableSet()
        set -= site
        context.defaultPrefs().edit { putStringSet(KEY, set) }
    }
}
