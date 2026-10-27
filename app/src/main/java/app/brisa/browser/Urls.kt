package app.brisa.browser

/** Address-bar rules that need no Android classes, so they can be unit tested. */
object Urls {
    private val PRIVATE_IP = Regex("^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.)")

    /** localhost, single-label names, *.local and private IPv4 ranges stay on plain HTTP. */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return '.' !in h || h.endsWith(".local") || h.endsWith(".localhost") || PRIVATE_IP.containsMatchIn(h)
    }

    fun hostOf(text: String): String? {
        val authority = text.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            return if (end > 0) authority.substring(0, end + 1).lowercase() else null
        }
        return authority.substringBefore(':').lowercase().ifEmpty { null }
    }

    /** Turns what was typed into an address, or into a search when it does not look like one. */
    fun resolve(input: String, search: (String) -> String): String {
        val text = input.trim()
        if (text.startsWith("https://", true) || text.startsWith("http://", true)) return text
        if ("://" in text || ' ' in text) return search(text)
        val host = hostOf(text)
        return when {
            host.isNullOrEmpty() -> search(text)
            host == "localhost" || host.startsWith("[") -> "http://$text"
            '.' !in host -> search(text)
            isPrivateHost(host) -> "http://$text"
            else -> "https://$text"
        }
    }
}
