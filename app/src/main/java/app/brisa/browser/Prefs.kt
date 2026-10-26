package app.brisa.browser

import android.content.SharedPreferences
import android.net.Uri

object Prefs {
    const val JAVASCRIPT = "javascript"
    const val HTTPS_FIRST = "https_first"
    const val BLOCK_TRACKERS = "block_trackers"
    const val RESTORE_TABS = "restore_tabs"
    const val SAVE_HISTORY = "save_history"
    const val BLOCK_THIRD_PARTY = "block_third_party"
    const val SEND_DNT = "send_dnt"
    const val DESKTOP = "desktop_site"
    const val CLEAR_ON_EXIT = "clear_on_exit"
    const val CLEAR_NOW = "clear_now"
    const val PENDING_CLEAR = "pending_clear_items"
    const val SEARCH_ENGINE = "search_engine"
    const val HOMEPAGE = "homepage"
    const val PULL_REFRESH = "pull_to_refresh"
    const val THEME = "theme_mode"
    const val AMOLED = "amoled"
    const val ACCENT = "accent"
    const val TOOLBAR = "toolbar_position"
    const val WEB_DARK = "web_dark"
    const val AUTOPLAY = "autoplay"
    const val TEXT_SIZE = "text_size"
    const val CONFIRM_DOWNLOADS = "confirm_downloads"
    const val DOWNLOAD_TREE = "download_tree"

    const val CLEAR_HISTORY = "history"
    const val CLEAR_COOKIES = "cookies"
    const val CLEAR_CACHE = "cache"
}

object SearchEngines {
    class Engine(val id: String, val name: String, val template: String, val home: String) {
        fun url(query: String): String = template.replace("%s", Uri.encode(query))
    }

    private val all = listOf(
        Engine("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
        Engine("brave", "Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
        Engine("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org"),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
    )

    fun current(prefs: SharedPreferences): Engine {
        val id = prefs.getString(Prefs.SEARCH_ENGINE, "google")
        return all.firstOrNull { it.id == id } ?: all.first()
    }
}
