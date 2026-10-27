package app.brisa.browser

import android.content.Context

/** Two levels: Standard (ads and trackers) and Strict (adds tag managers and fingerprinting, may break sites). */
object Blocklist {
    @Volatile
    private var standard: Set<String> = emptySet()

    @Volatile
    private var strict: Set<String> = emptySet()

    fun load(context: Context) {
        load(readAsset(context, "blocklist.txt"))
        loadStrict(readAsset(context, "blocklist_strict.txt"))
    }

    fun load(entries: Collection<String>) {
        standard = entries.map { it.lowercase() }.toHashSet()
    }

    fun loadStrict(entries: Collection<String>) {
        strict = entries.map { it.lowercase() }.toHashSet()
    }

    fun isBlocked(host: String?, strictMode: Boolean = false): Boolean =
        Domains.contains(standard, host) || (strictMode && Domains.contains(strict, host))

    private fun readAsset(context: Context, name: String): List<String> = runCatching {
        context.assets.open(name).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        }
    }.getOrDefault(emptyList())
}
