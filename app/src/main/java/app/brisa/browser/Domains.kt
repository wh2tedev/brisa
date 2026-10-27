package app.brisa.browser

object Domains {
    /** True if the host, or any parent domain of it, is in the set. */
    fun contains(set: Set<String>, host: String?): Boolean {
        var current = host?.lowercase() ?: return false
        while (true) {
            if (current in set) return true
            val dot = current.indexOf('.')
            if (dot < 0) return false
            current = current.substring(dot + 1)
        }
    }
}
