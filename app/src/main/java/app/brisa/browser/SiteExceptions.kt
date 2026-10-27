package app.brisa.browser

import android.content.Context
import androidx.preference.PreferenceManager

/** Sites where tracker blocking is switched off. A site also covers its subdomains. */
object SiteExceptions {
    private const val KEY = "site_exception_hosts"

    @Volatile
    private var hosts: Set<String> = emptySet()

    fun init(context: Context) {
        hosts = PreferenceManager.getDefaultSharedPreferences(context).getStringSet(KEY, emptySet())?.toSet().orEmpty()
    }

    fun all(): List<String> = hosts.sorted()

    fun isAllowed(host: String?): Boolean = Domains.contains(hosts, normalize(host))

    fun add(context: Context, host: String) = save(context, hosts + normalize(host).orEmpty())

    fun remove(context: Context, host: String) = save(context, hosts - normalize(host).orEmpty())

    fun replace(context: Context, newHosts: Collection<String>) = save(context, newHosts.toSet())

    fun normalize(host: String?): String? = host?.lowercase()?.removePrefix("www.")?.takeIf { it.isNotEmpty() }

    private fun save(context: Context, newHosts: Set<String>) {
        hosts = newHosts.filter { it.isNotEmpty() }.toSet()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putStringSet(KEY, HashSet(hosts)).apply()
    }
}
