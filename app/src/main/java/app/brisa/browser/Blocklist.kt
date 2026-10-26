package app.brisa.browser

import android.content.Context

object Blocklist {
    @Volatile
    private var domains: Set<String> = emptySet()

    fun load(context: Context) {
        domains = runCatching {
            context.assets.open("blocklist.txt").bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toHashSet()
            }
        }.getOrDefault(emptySet())
    }

    /** True if the host or any of its parent domains is listed. */
    fun isBlocked(host: String?): Boolean {
        var current = host?.lowercase() ?: return false
        val set = domains
        while (true) {
            if (current in set) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }
}
