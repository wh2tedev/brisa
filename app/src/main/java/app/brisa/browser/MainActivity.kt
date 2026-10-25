package app.brisa.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.ValueCallback
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.preference.PreferenceManager
import app.brisa.browser.databinding.ActivityMainBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        var home = false
        var loading = false
        var progress = 0
        var upgradedUrl: String? = null
        var fallbackUrl: String? = null
    }

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private val tabs = mutableListOf<Tab>()
    private lateinit var current: Tab
    private val web get() = current.web

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setupBars()
        onBackPressedDispatcher.addCallback(this) { goBack() }
        newTab(externalUrl(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalUrl(intent)?.let(::newTab)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        tabs.forEach(::applySettings)
        if (prefs.getBoolean(Prefs.PENDING_CLEAR, false)) {
            prefs.edit { remove(Prefs.PENDING_CLEAR) }
            clearData()
        }
    }

    override fun onPause() {
        web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.getBoolean(Prefs.CLEAR_ON_EXIT, false)) clearData()
        tabs.toList().forEach(::destroy)
        tabs.clear()
        super.onDestroy()
    }

    // region tabs

    private fun newTab(url: String?) {
        val tab = Tab(WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        configure(tab)
        tabs += tab
        tab.home = url == null
        select(tab)
        if (url != null) open(url, tab)
    }

    private fun select(tab: Tab) {
        exitFullscreen()
        closeFind()
        current = tab
        ui.home.isVisible = tab.home
        ui.webContainer.removeAllViews()
        ui.webContainer.addView(tab.web)
        ui.urlBar.clearFocus()
        showUrl(tab.web.url)
        ui.progress.setProgressCompat(tab.progress, false)
        renderLoading()
        updateTabCount()
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) newTab(null)
        else if (tab === current) select(tabs[minOf(index, tabs.lastIndex)])
        destroy(tab)
        updateTabCount()
    }

    private fun destroy(tab: Tab) {
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tab.web.destroy()
    }

    private fun goBack() {
        when {
            customView != null -> exitFullscreen()
            ui.findBar.isVisible -> closeFind()
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> finish()
        }
    }

    private fun updateTabCount() {
        ui.tabCount.text = tabs.size.toString()
    }

    private fun titleOf(tab: Tab): String =
        tab.title.ifBlank { display(tab.web.url) }.ifBlank { getString(R.string.new_tab) }

    private fun showTabs() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_tabs, null)
        val list = content.findViewById<LinearLayout>(R.id.tabList)

        fun fill() {
            list.removeAllViews()
            for (tab in tabs.toList()) {
                val row = layoutInflater.inflate(R.layout.item_tab, list, false)
                row.findViewById<TextView>(R.id.tabTitle).text = titleOf(tab)
                if (tab === current) row.setBackgroundColor(getColor(R.color.toolbar))
                row.setOnClickListener {
                    select(tab)
                    sheet.dismiss()
                }
                row.findViewById<View>(R.id.tabClose).setOnClickListener {
                    closeTab(tab)
                    fill()
                }
                list.addView(row)
            }
        }

        content.findViewById<View>(R.id.newTab).setOnClickListener {
            newBlankTab()
            sheet.dismiss()
        }
        fill()
        sheet.setContentView(content)
        sheet.show()
    }

    // endregion

    // region web view

    private fun configure(tab: Tab) {
        tab.web.settings.apply {
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            setSafeBrowsingEnabled(true)
        }
        applySettings(tab)

        tab.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme) {
                    "https" -> false
                    "http" -> {
                        if (request.isForMainFrame && !request.isRedirect && shouldUpgrade(uri)) {
                            open(uri.toString(), tab)
                            true
                        } else false
                    }
                    else -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.title = ""
                setLoading(tab, true)
                if (tab === current) showUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(tab, false)
                if (tab === current) showUrl(url)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val fallback = tab.fallbackUrl
                if (fallback != null && request.url.toString() == tab.upgradedUrl) {
                    tab.fallbackUrl = null
                    view.loadUrl(fallback, headers())
                } else if (tab === current) {
                    notify(R.string.load_error)
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                if (tab === current) notify(R.string.ssl_error)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                closeTab(tab)
                return true
            }
        }

        tab.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (tab === current) ui.progress.setProgressCompat(newProgress, true)
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title.orEmpty()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                try {
                    filePicker.launch(params.createIntent())
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                }
                return true
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                ui.fullscreen.addView(view)
                ui.fullscreen.isVisible = true
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = exitFullscreen()

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = callback.invoke(origin, false, false)
        }

        tab.web.setFindListener { active, matches, done ->
            if (done && tab === current) {
                ui.findCount.text = if (matches == 0) "0/0" else "${active + 1}/$matches"
            }
        }

        tab.web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (!url.startsWith("https://") && !url.startsWith("http://")) return@setDownloadListener
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", userAgent)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    URLUtil.guessFileName(url, disposition, mime),
                )
            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            getSystemService(DownloadManager::class.java).enqueue(request)
            notify(R.string.downloading)
        }
    }

    private fun applySettings(tab: Tab) {
        val desktop = prefs.getBoolean(Prefs.DESKTOP, false)
        tab.web.settings.apply {
            javaScriptEnabled = prefs.getBoolean(Prefs.JAVASCRIPT, true)
            userAgentString = if (desktop) DESKTOP_UA else defaultUa
            useWideViewPort = desktop
            loadWithOverviewMode = desktop
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(tab.web, !prefs.getBoolean(Prefs.BLOCK_THIRD_PARTY, true))
        }
    }

    private fun clearData() {
        current.web.clearCache(true)
        tabs.forEach { it.web.clearHistory() }
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    // endregion

    // region navigation

    private fun headers(): Map<String, String> =
        if (prefs.getBoolean(Prefs.SEND_DNT, true)) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    private fun shouldUpgrade(uri: Uri) =
        prefs.getBoolean(Prefs.HTTPS_FIRST, true) && !isPrivateHost(uri.host)

    private fun open(url: String, tab: Tab = current) {
        val uri = Uri.parse(url)
        tab.home = false
        if (tab === current) ui.home.isGone = true
        tab.fallbackUrl = null
        tab.upgradedUrl = null
        var target = url
        if (uri.scheme == "http" && shouldUpgrade(uri)) {
            target = uri.buildUpon().scheme("https").build().toString()
            tab.upgradedUrl = target
            tab.fallbackUrl = url
        }
        tab.web.loadUrl(target, headers())
    }

    private fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return '.' !in h || h.endsWith(".local") || h.endsWith(".localhost") || PRIVATE_IP.containsMatchIn(h)
    }

    private fun resolve(input: String): String {
        val text = input.trim()
        if (text.startsWith("https://", true) || text.startsWith("http://", true)) return text
        if ("://" in text || ' ' in text) return searchUrl(text)
        val host = runCatching { Uri.parse("http://$text").host }.getOrNull()?.lowercase()
        return when {
            host.isNullOrEmpty() -> searchUrl(text)
            host == "localhost" || host.startsWith("[") -> "http://$text"
            '.' !in host -> searchUrl(text)
            isPrivateHost(host) -> "http://$text"
            else -> "https://$text"
        }
    }

    private fun searchUrl(query: String) = Uri.Builder()
        .scheme("https")
        .authority("www.google.com")
        .path("search")
        .appendQueryParameter("q", query)
        .build()
        .toString()

    private fun externalUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return data.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
    }

    // endregion

    // region ui

    private fun setupBars() {
        ui.urlBar.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                open(resolve(v.text.toString()))
                v.clearFocus()
                hideKeyboard(v)
                true
            } else false
        }
        ui.urlBar.setOnFocusChangeListener { v, focused ->
            val field = v as EditText
            if (focused) {
                field.setText(web.url.orEmpty())
                field.selectAll()
            } else {
                field.setText(display(web.url))
            }
        }
        ui.tabsButton.setOnClickListener { showTabs() }
        ui.homeSearch.setOnClickListener { focusUrlBar() }
        ui.menu.setOnClickListener { showMenu() }

        ui.findInput.doOnTextChanged { text, _, _, _ ->
            if (text.isNullOrEmpty()) {
                web.clearMatches()
                ui.findCount.text = ""
            } else {
                web.findAllAsync(text.toString())
            }
        }
        ui.findInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) web.findNext(true)
            true
        }
        ui.findNext.setOnClickListener { web.findNext(true) }
        ui.findPrev.setOnClickListener { web.findNext(false) }
        ui.findClose.setOnClickListener { closeFind() }
    }

    private fun showMenu() {
        PopupMenu(this, ui.menu).apply {
            menuInflater.inflate(R.menu.main, menu)
            menu.findItem(R.id.action_back).isEnabled = web.canGoBack()
            menu.findItem(R.id.action_forward).isEnabled = web.canGoForward()
            menu.findItem(R.id.action_reload).setTitle(if (current.loading) R.string.stop else R.string.reload)
            menu.findItem(R.id.action_desktop).isChecked = prefs.getBoolean(Prefs.DESKTOP, false)
            menu.findItem(R.id.action_bookmark).apply {
                val url = web.url
                isEnabled = url != null
                setTitle(if (url != null && Bookmarks.contains(this@MainActivity, url)) R.string.remove_bookmark else R.string.add_bookmark)
            }
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_new_tab -> newBlankTab()
                    R.id.action_back -> web.goBack()
                    R.id.action_forward -> web.goForward()
                    R.id.action_reload -> if (current.loading) web.stopLoading() else web.reload()
                    R.id.action_bookmark -> toggleBookmark()
                    R.id.action_bookmarks -> showBookmarks()
                    R.id.action_find -> openFind()
                    R.id.action_share -> share()
                    R.id.action_desktop -> {
                        prefs.edit { putBoolean(Prefs.DESKTOP, !item.isChecked) }
                        tabs.forEach(::applySettings)
                        web.reload()
                    }
                    R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun openFind() {
        ui.findBar.isVisible = true
        ui.findInput.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(ui.findInput, 0)
    }

    private fun closeFind() {
        if (!ui.findBar.isVisible) return
        web.clearMatches()
        ui.findInput.text?.clear()
        ui.findCount.text = ""
        ui.findBar.isGone = true
        hideKeyboard(ui.findInput)
    }

    private fun newBlankTab() {
        newTab(null)
        focusUrlBar()
    }

    private fun focusUrlBar() {
        ui.urlBar.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(ui.urlBar, 0)
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        ui.fullscreen.removeView(view)
        ui.fullscreen.isGone = true
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun toggleBookmark() {
        val url = web.url ?: return
        if (Bookmarks.contains(this, url)) Bookmarks.remove(this, url)
        else Bookmarks.add(this, titleOf(current), url)
    }

    private fun showBookmarks() {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_tabs, null)
        content.findViewById<TextView>(R.id.sheetTitle).setText(R.string.bookmarks)
        content.findViewById<View>(R.id.newTab).isGone = true
        val list = content.findViewById<LinearLayout>(R.id.tabList)

        fun fill() {
            list.removeAllViews()
            val items = Bookmarks.all(this)
            if (items.isEmpty()) {
                list.addView(TextView(this).apply {
                    setText(R.string.empty_bookmarks)
                    setTextColor(getColor(R.color.muted))
                    setPadding(dp(24), dp(12), dp(24), dp(12))
                })
            }
            for (item in items) {
                val row = layoutInflater.inflate(R.layout.item_tab, list, false)
                row.findViewById<TextView>(R.id.tabTitle).text = item.title.ifBlank { item.url }
                row.setOnClickListener {
                    open(item.url)
                    sheet.dismiss()
                }
                row.findViewById<View>(R.id.tabClose).setOnClickListener {
                    Bookmarks.remove(this, item.url)
                    fill()
                }
                list.addView(row)
            }
        }

        fill()
        sheet.setContentView(content)
        sheet.show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun share() {
        val url = web.url ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun display(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return Uri.parse(url).host?.removePrefix("www.") ?: url
    }

    private fun showUrl(url: String?) {
        if (!ui.urlBar.hasFocus()) ui.urlBar.setText(display(url))
    }

    private fun setLoading(tab: Tab, on: Boolean) {
        tab.loading = on
        if (tab === current) renderLoading()
    }

    private fun renderLoading() {
        ui.progress.animate().cancel()
        if (current.loading) {
            ui.progress.alpha = 1f
            ui.progress.isVisible = true
        } else {
            ui.progress.animate().alpha(0f).setDuration(200).withEndAction { ui.progress.isGone = true }.start()
        }
    }

    private fun notify(message: Int) = Snackbar.make(ui.root, message, Snackbar.LENGTH_SHORT).show()

    // endregion

    private companion object {
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        val PRIVATE_IP = Regex("^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.)")
    }
}
