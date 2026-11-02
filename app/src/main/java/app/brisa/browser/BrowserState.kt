package app.brisa.browser

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.TextFieldValue

/** One row in the tabs sheet. */
data class TabEntry(
    val id: Long,
    val title: String,
    val subtitle: String,
    val favicon: Bitmap?,
    val selected: Boolean,
    val isPrivate: Boolean,
)

/** What the overflow menu needs to know about the current page, captured when it opens. */
data class MenuInfo(
    val canGoBack: Boolean,
    val canGoForward: Boolean,
    val canBookmark: Boolean,
    val bookmarked: Boolean,
    val loading: Boolean,
    val desktop: Boolean,
    val showProtection: Boolean,
    val protectionOn: Boolean,
    val blocked: Int,
    val tabLocked: Boolean,
    val siteLocked: Boolean,
    val siteLockable: Boolean,
)

enum class Sheet { TABS, BOOKMARKS, HISTORY }

/** Dialogs the browser can ask for. They carry their own callbacks so the UI needs no knowledge of WebView. */
sealed interface Prompt {
    class Download(
        val name: String,
        val message: String,
        val onConfirm: (name: String) -> Unit,
        val onChangeFolder: (name: String) -> Unit,
    ) : Prompt
    class Permission(val message: String, val onDecision: (allow: Boolean, remember: Boolean) -> Unit) : Prompt
    class External(val message: String, val onOpen: () -> Unit) : Prompt
    class Auth(
        val message: String,
        val insecure: Boolean,
        val onSubmit: (user: String, pass: String) -> Unit,
        val onCancel: () -> Unit,
    ) : Prompt
    class LinkMenu(val title: String, val actions: List<Pair<String, () -> Unit>>) : Prompt
}

/** Everything the browser screen shows. Written by [MainActivity], read by composables. */
@Stable
class BrowserState {
    var pageUrl by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var progress by mutableIntStateOf(0)
    var refreshing by mutableStateOf(false)
    var toolbarBottom by mutableStateOf(false)

    /** "off", "left" or "right": which hand the controls are arranged for. */
    var oneHand by mutableStateOf("off")
    var pullEnabled by mutableStateOf(true)
    var tabEntries by mutableStateOf<List<TabEntry>>(emptyList())
    var sheet by mutableStateOf<Sheet?>(null)
    var prompt by mutableStateOf<Prompt?>(null)
    var findVisible by mutableStateOf(false)
    var findQuery by mutableStateOf("")
    var findCount by mutableStateOf("")
    var customView by mutableStateOf<View?>(null)
    var urlFocused by mutableStateOf(false)
    var currentPrivate by mutableStateOf(false)

    /** The current tab is locked and must be unlocked with the phone's lock before it is shown. */
    var locked by mutableStateOf(false)

    /** False while the address bar is tucked away after scrolling down. */
    var barVisible by mutableStateOf(true)
    var typed by mutableStateOf(TextFieldValue(""))

    /** Bumped when a tab is selected: replays the reveal animation and drops address bar focus. */
    var selectNonce by mutableIntStateOf(0)

    val snackbar = SnackbarHostState()
}

/** Actions the browser screen can trigger. Implemented by [MainActivity]. */
interface BrowserHost {
    val webContainer: ViewGroup
    fun canScrollUp(): Boolean
    fun currentUrl(): String
    fun submit(input: String)
    fun openUrl(url: String)
    fun resolveInput(input: String): String
    fun searchUrl(query: String): String
    fun engineName(): String
    fun reload()
    fun newTab()
    fun newPrivateTab()
    fun unlockCurrent()
    fun toggleTabLock()
    fun toggleSiteLock()
    fun selectTab(id: Long)
    fun closeTab(id: Long)
    fun menuInfo(): MenuInfo
    fun goBackPage()
    fun goForwardPage()
    fun toggleBookmark()
    fun reloadOrStop()
    fun openFind()
    fun onFind(query: String)
    fun findNext(forward: Boolean)
    fun closeFind()
    fun share()
    fun openDownloads()
    fun openSettings()
    fun toggleDesktop()
    fun setProtection(on: Boolean)
    fun exitFullscreen()

    /** Runs [onSuccess] once the phone's own lock has been confirmed (or at once if the phone has none). */
    fun confirmIdentity(onSuccess: () -> Unit)
}

/** Host name without "www.", used wherever an address is shown to the person. */
fun displayUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
}
