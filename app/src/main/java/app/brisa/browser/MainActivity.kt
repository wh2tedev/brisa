package app.brisa.browser

import android.Manifest
import android.app.ActivityOptions
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val tabIds = AtomicLong()

class MainActivity : FragmentActivity(), BrowserHost {

    private class Tab(val web: WebView, val isPrivate: Boolean = false) {
        val id = tabIds.incrementAndGet()
        var title = ""
        var loading = false
        var progress = 0
        var upgradedUrl: String? = null
        var fallbackUrl: String? = null
        var pendingUrl: String? = null
        var favicon: Bitmap? = null
        var opener: Tab? = null
        var errorUrl: String? = null
        var sslBlocked = false

        /** Locked tabs and sites ask for the phone's lock again after the person leaves them. */
        var unlocked = false
        var userLocked = false

        /** Read from the network thread, so kept volatile. */
        @Volatile
        var pageHost: String? = null
        val blocked = AtomicInteger()

        fun clearError() {
            errorUrl = null
            sslBlocked = false
        }
    }

    private val ui = BrowserState()
    private lateinit var prefs: SharedPreferences
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private val tabs = mutableListOf<Tab>()
    private lateinit var current: Tab
    private val web get() = current.web
    private var blobToken: String? = null
    private var blobName = ""
    private var blobHost: String? = null
    private val blobBridge = BlobBridge(
        onSave = { token, mime, data -> runOnUiThread { finishBlob(token, mime, data) } },
        onFail = { token ->
            runOnUiThread {
                if (token == blobToken) {
                    blobToken = null
                    notify(R.string.download_failed)
                }
            }
        },
    )

    private val folderPicker = FolderPicker(this)

    private var permissionCallback: ((Map<String, Boolean>) -> Unit)? = null
    private var activePermission: PermissionRequest? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val callback = permissionCallback
            permissionCallback = null
            callback?.invoke(result)
        }
    private var scrollAcc = 0
    private var lastBarToggle = 0L

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private val filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }

    override val webContainer: ViewGroup by lazy {
        FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = defaultPrefs()
        applyToolbarPosition()
        if (!prefs.getBoolean(Prefs.HIDE_BAR_SCROLL, true)) ui.barVisible = true
        ui.pullEnabled = prefs.getBoolean(Prefs.PULL_REFRESH, true)

        // Added before the content so Compose's own back handlers (find bar, address bar, fullscreen) take priority.
        onBackPressedDispatcher.addCallback(this) { goBack() }
        setContent {
            BrisaTheme {
                BrowserScreen(ui, this@MainActivity)
                folderPicker.Dialog()
            }
        }

        dropPrivateProfile()
        Blocklist.load(this)
        SiteExceptions.init(this)
        val external = externalUrl(intent)
        val saved = Session.load(this)?.takeIf { restoreEnabled() }
        saved?.let(::restore)
        if (external != null) newTab(external) else if (saved == null) newTab(home())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalUrl(intent)?.let(::newTab)
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        tabs.forEach(::applySettings)
        applyToolbarPosition()
        ui.pullEnabled = prefs.getBoolean(Prefs.PULL_REFRESH, true)
        applyScreenFlags()
        refreshLock()
        syncTabs()
        checkEngineAge()
        val pending = prefs.getStringSet(Prefs.PENDING_CLEAR, null)
        if (!pending.isNullOrEmpty()) {
            prefs.edit { remove(Prefs.PENDING_CLEAR) }
            clearData(pending.toSet())
        }
    }

    override fun onPause() {
        web.onPause()
        saveSession()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onStop() {
        // Locked tabs and sites ask again when the person comes back.
        tabs.forEach { it.unlocked = false }
        refreshLock()
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.getBoolean(Prefs.CLEAR_ON_EXIT, false)) {
            clearData(setOf(Prefs.CLEAR_HISTORY, Prefs.CLEAR_COOKIES, Prefs.CLEAR_CACHE))
        }
        tabs.toList().forEach(::destroy)
        tabs.clear()
        dropPrivateProfile()
        super.onDestroy()
    }

    // region tabs

    private fun createTab(incognito: Boolean = false): Tab {
        val web = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        // A separate WebView profile keeps private cookies and storage away from normal tabs.
        if (incognito && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            runCatching { WebViewCompat.setProfile(web, PRIVATE_PROFILE) }
        }
        val tab = Tab(web, incognito)
        configure(tab)
        tabs += tab
        return tab
    }

    private fun newTab(url: String) = openNewTab(url, incognito = false)

    private fun openNewTab(url: String, incognito: Boolean) {
        val tab = createTab(incognito)
        select(tab)
        open(url, tab)
    }

    /** Deletes the private profile's data. Only possible once no private tab is using it. */
    private fun dropPrivateProfile() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return
        runCatching { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) }
    }

    /** Recreates saved tabs without loading them; a tab loads when it is first selected. */
    private fun restore(saved: Session.Saved) {
        saved.tabs.forEach { entry ->
            createTab().apply {
                title = entry.title
                pendingUrl = entry.url
            }
        }
        select(tabs[saved.current.coerceIn(0, tabs.lastIndex)])
    }

    private fun restoreEnabled() =
        prefs.getBoolean(Prefs.RESTORE_TABS, true) && !prefs.getBoolean(Prefs.CLEAR_ON_EXIT, false)

    private fun saveSession() {
        if (!restoreEnabled()) {
            Session.clear(this)
            return
        }
        // Private tabs are never written to disk.
        val normal = tabs.filter { !it.isPrivate }
        if (normal.isEmpty()) {
            Session.clear(this)
            return
        }
        Session.save(this, normal.map { Session.Entry(titleOf(it), urlOf(it) ?: home()) }, normal.indexOf(current).coerceAtLeast(0))
    }

    private fun select(tab: Tab) {
        exitFullscreen()
        closeFind()
        if (::current.isInitialized) current.unlocked = false
        current = tab
        webContainer.removeAllViews()
        webContainer.addView(tab.web)
        ui.selectNonce++
        ui.currentPrivate = tab.isPrivate
        ui.barVisible = true
        refreshLock()
        // Private tabs also stay out of screenshots and the recents list.
        applyScreenFlags()
        showUrl(urlOf(tab))
        ui.progress = tab.progress
        renderLoading()
        syncTabs()
        tab.pendingUrl?.let {
            tab.pendingUrl = null
            open(it, tab)
        }
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) newTab(home())
        else if (tab === current) select(tab.opener?.takeIf { it in tabs } ?: tabs[minOf(index, tabs.lastIndex)])
        destroy(tab)
        if (tab.isPrivate && tabs.none { it.isPrivate }) dropPrivateProfile()
        syncTabs()
    }

    private fun destroy(tab: Tab) {
        (tab.web.parent as? ViewGroup)?.removeView(tab.web)
        tab.web.destroy()
    }

    private fun goBack() {
        when {
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> finish()
        }
    }

    /** Publishes the tab list to the UI. Cheap: there are at most [MAX_TABS] tabs. */
    private fun syncTabs() {
        if (!::current.isInitialized) return
        ui.tabEntries = tabs.map {
            val hidden = it !== current && needsUnlock(it)
            val title = if (hidden) getString(R.string.tab_locked) else titleOf(it)
            val subtitle = when {
                it.isPrivate -> getString(R.string.private_tab)
                hidden -> ""
                else -> displayUrl(urlOf(it))
            }
            TabEntry(it.id, title, subtitle, if (hidden) null else it.favicon, it === current, it.isPrivate)
        }
    }

    private fun urlOf(tab: Tab): String? = tab.web.url ?: tab.pendingUrl

    private fun titleOf(tab: Tab): String =
        tab.title.ifBlank { displayUrl(urlOf(tab)) }.ifBlank { getString(R.string.new_tab) }

    override fun newTab() = newTab(home())

    override fun selectTab(id: Long) {
        tabs.firstOrNull { it.id == id }?.let(::select)
    }

    override fun closeTab(id: Long) {
        tabs.firstOrNull { it.id == id }?.let(::closeTab)
    }

    // endregion

    // region downloads and file access

    private fun startDownload(
        source: WebView,
        url: String,
        userAgent: String,
        disposition: String?,
        mime: String?,
        length: Long,
    ) {
        val name = URLUtil.guessFileName(if (url.startsWith("http")) url else "download", disposition, mime)
        val site = Uri.parse(source.url.orEmpty()).host
        when {
            url.startsWith("https://") || url.startsWith("http://") ->
                confirmDownload(name, length, site) { chosen -> enqueue(url, userAgent, mime, chosen, site) }
            url.startsWith("data:") -> {
                val decoded = decodeDataUrl(url) ?: return notify(R.string.download_failed)
                confirmDownload(name, decoded.second.size.toLong(), site) { chosen -> saveBytes(chosen, decoded.first, decoded.second, site) }
            }
            url.startsWith("blob:") -> confirmDownload(name, length, site) { chosen ->
                val token = UUID.randomUUID().toString()
                blobToken = token
                blobName = chosen
                blobHost = site
                source.evaluateJavascript(blobScript(url, token), null)
            }
        }
    }

    private fun confirmDownload(name: String, size: Long, site: String?, onConfirm: (String) -> Unit) {
        val risky = name.substringAfterLast('.', "").lowercase() in RISKY_EXTENSIONS
        if (!risky && !prefs.getBoolean(Prefs.CONFIRM_DOWNLOADS, true)) {
            onConfirm(name)
            return
        }
        val detail = if (size > 0) Formatter.formatShortFileSize(this, size) else getString(R.string.unknown_size)
        val message = "$detail\n\n${getString(R.string.save_to)}: ${Storage.label(this, site)}" +
            if (risky) "\n\n" + getString(R.string.download_risky) else ""
        ui.prompt = Prompt.Download(
            name = name,
            message = message,
            onConfirm = { typed -> onConfirm(cleanFileName(typed, name)) },
            onChangeFolder = { typed -> folderPicker.show(site) { confirmDownload(typed, size, site, onConfirm) } },
        )
    }

    /** The person may type anything, so path separators are removed before it becomes a file name. */
    private fun cleanFileName(input: String, fallback: String): String {
        val cleaned = input.trim().replace('/', '_').replace('\\', '_').take(200)
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    /** Hands an http(s) download to the system downloader and records it in the Downloads list. */
    private fun enqueue(url: String, userAgent: String, mime: String?, suggested: String, site: String?) {
        val started = DownloadStarter.enqueue(this, url, userAgent, mime, suggested, Storage.treeUri(this, site))
        if (started) notify(getString(R.string.downloading), R.string.view) { openDownloads() }
        else notify(R.string.download_failed)
    }

    /** Saves bytes we already have (data: and blob: downloads) off the main thread. */
    private fun saveBytes(name: String, mime: String, bytes: ByteArray, site: String?) {
        val app = applicationContext
        val tree = Storage.treeUri(this, site)
        Thread {
            val uri = Storage.save(app, name, mime, tree) { it.write(bytes) }
            if (uri != null) {
                val size = bytes.size.toLong()
                Downloads.add(
                    app,
                    Downloads.Item(
                        UUID.randomUUID().toString(), 0, name, "", mime, size, size, System.currentTimeMillis(),
                        Downloads.DONE, uri.toString(), tree?.toString(), null,
                    ),
                )
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (uri != null) notify(getString(R.string.saved_to, Storage.labelOf(this, tree)), R.string.view) { openDownloads() }
                else notify(R.string.download_failed)
            }
        }.start()
    }

    override fun openDownloads() {
        val options = ActivityOptions.makeCustomAnimation(this, R.anim.enter_from_right, R.anim.exit_to_left)
        startActivity(Intent(this, DownloadsActivity::class.java), options.toBundle())
    }

    override fun openSettings() {
        val options = ActivityOptions.makeCustomAnimation(this, R.anim.enter_from_right, R.anim.exit_to_left)
        startActivity(Intent(this, SettingsActivity::class.java), options.toBundle())
    }

    private fun blobScript(url: String, token: String) = """
        (function (url, token) {
            fetch(url).then(function (r) { return r.blob(); }).then(function (b) {
                if (b.size > $MAX_BLOB) { BrisaBlob.fail(token); return; }
                var reader = new FileReader();
                reader.onloadend = function () {
                    BrisaBlob.save(token, b.type || 'application/octet-stream', String(reader.result).split(',')[1] || '');
                };
                reader.readAsDataURL(b);
            }).catch(function () { BrisaBlob.fail(token); });
        })(${JSONObject.quote(url)}, ${JSONObject.quote(token)});
    """.trimIndent()

    private fun finishBlob(token: String, mime: String, data: String) {
        if (token != blobToken) return
        blobToken = null
        val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull()
            ?: return notify(R.string.download_failed)
        saveBytes(blobName, mime, bytes, blobHost)
    }

    private fun decodeDataUrl(url: String): Pair<String, ByteArray>? = runCatching {
        val comma = url.indexOf(',')
        val meta = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifEmpty { "text/plain" }
        val bytes = if (meta.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
        mime to bytes
    }.getOrNull()

    /**
     * Maps a WebView file request to a system picker. The folder and save modes, and the read/write hint,
     * are what the File System Access API needs; they only arrive on Android 17 (API 37) and later.
     */
    private fun chooserIntent(params: WebChromeClient.FileChooserParams): Intent {
        val writable = Build.VERSION.SDK_INT >= 37 &&
            params.permissionMode == WebChromeClient.FileChooserParams.PERMISSION_MODE_READ_WRITE
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val readWrite = read or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val mimes = params.acceptTypes.filter { '/' in it }
        return when (params.mode) {
            WebChromeClient.FileChooserParams.MODE_OPEN_FOLDER ->
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(if (writable) readWrite else read)
            WebChromeClient.FileChooserParams.MODE_SAVE ->
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(mimes.firstOrNull() ?: "*/*")
                    .putExtra(Intent.EXTRA_TITLE, params.filenameHint.orEmpty())
                    .addFlags(readWrite)
            else -> if (writable) {
                Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (mimes.size == 1) mimes[0] else "*/*")
                    .putExtra(
                        Intent.EXTRA_ALLOW_MULTIPLE,
                        params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE,
                    )
                    .apply { if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray()) }
                    .addFlags(readWrite)
            } else params.createIntent()
        }
    }

    // endregion

    // region history

    private fun recordVisit(tab: Tab, url: String?) {
        if (tab.isPrivate) return
        if (url != null && url == tab.errorUrl) return
        if (url == null || !prefs.getBoolean(Prefs.SAVE_HISTORY, true)) return
        History.record(this, tab.title.ifBlank { url }, url)
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
            setSupportMultipleWindows(true)
            setGeolocationEnabled(true)
            setSafeBrowsingEnabled(true)
        }
        applySettings(tab)

        tab.web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) return null
                val level = prefs.getString(Prefs.TRACKER_LEVEL, "standard")
                if (level == "off" || SiteExceptions.isAllowed(tab.pageHost)) return null
                if (!Blocklist.isBlocked(request.url.host, strictMode = level == "strict")) return null
                tab.blocked.incrementAndGet()
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                if (request.isForMainFrame) tab.clearError()
                return when (uri.scheme) {
                    "https" -> false
                    "http" -> {
                        if (request.isForMainFrame && !request.isRedirect && shouldUpgrade(uri)) {
                            open(uri.toString(), tab)
                            true
                        } else false
                    }
                    else -> {
                        if (request.hasGesture()) openExternal(uri.toString(), tab)
                        true
                    }
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                tab.pageHost = url?.let { Uri.parse(it).host }
                tab.blocked.set(0)
                tab.title = ""
                tab.favicon = null
                setLoading(tab, true)
                if (tab === current) {
                    showUrl(url)
                    ui.barVisible = true
                    refreshLock()
                }
                syncTabs()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(tab, false)
                if (tab === current) showUrl(url)
                recordVisit(tab, url)
                syncTabs()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val failed = request.url.toString()
                val fallback = tab.fallbackUrl
                when {
                    fallback != null && failed == tab.upgradedUrl -> {
                        tab.fallbackUrl = null
                        view.loadUrl(fallback, headers())
                    }
                    tab.sslBlocked || failed == tab.errorUrl || error.description.contains("ERR_ABORTED") -> Unit
                    else -> {
                        val (title, detail) = errorStrings(error.errorCode, Urls.isPrivateHost(request.url.host))
                        showError(tab, failed, title, detail, error.description.toString())
                    }
                }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                tab.sslBlocked = true
                showError(tab, error.url, getString(R.string.ssl_title), getString(R.string.ssl_detail), "SSL error ${error.primaryError}")
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
                askCredentials(view, handler, host, realm)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                closeTab(tab)
                return true
            }
        }

        tab.web.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (!isUserGesture || tabs.size >= MAX_TABS) {
                    notify(R.string.popup_blocked)
                    return false
                }
                val popup = createTab(tab.isPrivate)
                popup.opener = tab
                select(popup)
                (resultMsg.obj as WebView.WebViewTransport).webView = popup.web
                resultMsg.sendToTarget()
                return true
            }

            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.web === window }?.let(::closeTab)
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (tab === current) ui.progress = newProgress
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
                tab.favicon = icon
                syncTabs()
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tab.title = title.orEmpty()
                recordVisit(tab, view.url)
                syncTabs()
            }

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                try {
                    filePicker.launch(chooserIntent(params))
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    callback.onReceiveValue(null)
                }
                return true
            }

            override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
                if (ui.customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customCallback = callback
                ui.customView = view
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = exitFullscreen()

            override fun onPermissionRequest(request: PermissionRequest) = handleWebPermission(request)

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (activePermission === request) {
                    activePermission = null
                    if (ui.prompt is Prompt.Permission) ui.prompt = null
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = handleLocation(origin, callback)
        }

        tab.web.setOnLongClickListener { showContextMenu(tab) }
        tab.web.setFindListener { active, matches, done ->
            if (done && tab === current) {
                ui.findCount = if (matches == 0) "0/0" else "${active + 1}/$matches"
            }
        }

        tab.web.setOnScrollChangeListener { _, _, y, _, oldY -> onPageScroll(tab, y, oldY) }

        tab.web.addJavascriptInterface(blobBridge, "BrisaBlob")
        tab.web.setDownloadListener { url, userAgent, disposition, mime, length ->
            startDownload(tab.web, url, userAgent, disposition, mime, length)
        }
    }

    private fun applySettings(tab: Tab) {
        val desktop = prefs.getBoolean(Prefs.DESKTOP, false)
        tab.web.settings.apply {
            javaScriptEnabled = prefs.getBoolean(Prefs.JAVASCRIPT, true)
            userAgentString = if (desktop) DESKTOP_UA else defaultUa
            useWideViewPort = true
            loadWithOverviewMode = desktop
            textZoom = prefs.getInt(Prefs.TEXT_SIZE, 100)
            mediaPlaybackRequiresUserGesture = !prefs.getBoolean(Prefs.AUTOPLAY, false)
            applyDark(prefs.getBoolean(Prefs.WEB_DARK, false))
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(tab.web, !prefs.getBoolean(Prefs.BLOCK_THIRD_PARTY, true))
        }
    }

    @Suppress("DEPRECATION")
    private fun WebSettings.applyDark(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) isAlgorithmicDarkeningAllowed = on
        else forceDark = if (on) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
    }

    private fun clearData(items: Set<String>) {
        if (Prefs.CLEAR_CACHE in items) current.web.clearCache(true)
        if (Prefs.CLEAR_HISTORY in items) {
            History.clear(this)
            tabs.forEach { it.web.clearHistory() }
        }
        if (Prefs.CLEAR_COOKIES in items) {
            WebStorage.getInstance().deleteAllData()
            tabs.forEach { it.web.clearFormData() }
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
    }

    // endregion

    // region navigation

    private class ExternalLink(val intent: Intent, val fallback: String?, val label: String)

    /** Hands mailto:, tel:, intent: and similar links to another app, after the user confirms. */
    private fun openExternal(url: String, tab: Tab) {
        val link = parseExternal(url) ?: return
        ui.prompt = Prompt.External(
            message = getString(R.string.external_message, link.label.take(120)),
            onOpen = {
                try {
                    startActivity(link.intent)
                } catch (e: Exception) {
                    val fallback = link.fallback
                    if (fallback != null) open(fallback, tab) else notify(R.string.external_missing)
                }
            },
        )
    }

    private fun parseExternal(url: String): ExternalLink? {
        val uri = Uri.parse(url)
        return when (uri.scheme?.lowercase()) {
            "mailto", "sms", "smsto" ->
                ExternalLink(Intent(Intent.ACTION_SENDTO, uri), null, uri.schemeSpecificPart.substringBefore('?'))
            "tel" -> ExternalLink(Intent(Intent.ACTION_DIAL, uri), null, uri.schemeSpecificPart)
            "geo", "market" -> ExternalLink(Intent(Intent.ACTION_VIEW, uri), null, url)
            "intent" -> parseIntentLink(url)
            else -> null
        }
    }

    /** intent: links can carry anything, so only a plain, browsable VIEW intent is let through. */
    private fun parseIntentLink(url: String): ExternalLink? {
        val intent = runCatching { Intent.parseUri(url, Intent.URI_INTENT_SCHEME) }.getOrNull() ?: return null
        val fallback = intent.getStringExtra("browser_fallback_url")?.takeIf { it.startsWith("https://") }
        val scheme = intent.data?.scheme?.lowercase()
        if (intent.action != Intent.ACTION_VIEW || scheme == null || scheme in setOf("file", "content", "javascript", "data")) {
            return null
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        intent.flags = 0
        intent.replaceExtras(null as Bundle?)
        return ExternalLink(intent, fallback, intent.`package` ?: intent.dataString.orEmpty())
    }

    override fun reload() {
        val failed = current.errorUrl
        if (failed != null) open(failed) else web.reload()
    }

    private fun errorStrings(code: Int, local: Boolean): Pair<String, String> {
        val (title, detail) = when (code) {
            WebViewClient.ERROR_HOST_LOOKUP -> R.string.err_dns_title to R.string.err_dns_detail
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO -> R.string.err_connect_title to R.string.err_connect_detail
            WebViewClient.ERROR_TIMEOUT -> R.string.err_timeout_title to R.string.err_timeout_detail
            WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> R.string.err_tls_title to R.string.err_tls_detail
            WebViewClient.ERROR_REDIRECT_LOOP -> R.string.err_redirect_title to R.string.err_redirect_detail
            WebViewClient.ERROR_BAD_URL, WebViewClient.ERROR_UNSUPPORTED_SCHEME -> R.string.err_url_title to R.string.err_url_detail
            else -> R.string.err_generic_title to R.string.err_generic_detail
        }
        val hint = if (local && code == WebViewClient.ERROR_CONNECT) " " + getString(R.string.err_local_hint) else ""
        return getString(title) to getString(detail) + hint
    }

    /** Replaces WebView's plain error page with one that follows the theme. */
    private fun showError(tab: Tab, url: String, title: String, detail: String, technical: String) {
        tab.errorUrl = url
        val settings = Appearance.read(prefs)
        val scheme = Appearance.colorScheme(this, settings, Appearance.isDark(this, settings))
        fun esc(text: String) = TextUtils.htmlEncode(text)
        val bg = Appearance.hex(scheme.surface)
        val onBg = Appearance.hex(scheme.onSurface)
        val muted = Appearance.hex(scheme.onSurfaceVariant)
        val accent = Appearance.hex(scheme.primary)
        val onAccent = Appearance.hex(scheme.onPrimary)
        val html = """
            <!doctype html><html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${esc(title)}</title>
            <style>
            body { margin: 0; min-height: 100vh; box-sizing: border-box; padding: 32px; display: flex; flex-direction: column;
                   justify-content: center; background: $bg; color: $onBg;
                   font: 16px/1.5 system-ui, sans-serif; animation: in .24s cubic-bezier(.05, .7, .1, 1); }
            @keyframes in { from { opacity: 0; transform: translateY(8px); } }
            @media (prefers-reduced-motion: reduce) { body { animation: none; } }
            svg { width: 48px; height: 48px; margin-bottom: 20px; fill: $muted; }
            h1 { font-size: 22px; font-weight: 500; margin: 0 0 4px; }
            .host { margin: 0 0 16px; color: $accent; word-break: break-all; }
            p { margin: 0 0 24px; color: $muted; }
            a { align-self: flex-start; padding: 12px 24px; border-radius: 24px; text-decoration: none; font-weight: 500;
                background: $accent; color: $onAccent; }
            code { margin-top: 28px; font-size: 12px; color: $muted; word-break: break-all; }
            </style></head><body>
            <svg viewBox="0 0 24 24"><path d="M1,21h22L12,2 1,21zM13,18h-2v-2h2v2zM13,14h-2v-4h2v4z"/></svg>
            <h1>${esc(title)}</h1>
            <div class="host">${esc(displayUrl(url).ifEmpty { url })}</div>
            <p>${esc(detail)}</p>
            <a href="${esc(url)}">${esc(getString(R.string.error_retry))}</a>
            <code>${esc(technical)}</code>
            </body></html>
        """.trimIndent()
        tab.web.loadDataWithBaseURL(url, html, "text/html", "utf-8", url)
    }

    /** Asks for a username and password, as a normal browser does for HTTP authentication. */
    private fun askCredentials(view: WebView, handler: HttpAuthHandler, host: String, realm: String?) {
        if (isFinishing || isDestroyed || ui.prompt != null) {
            handler.cancel()
            return
        }
        val bare = runCatching { Uri.parse("http://$host").host }.getOrNull() ?: host
        val insecure = view.url?.startsWith("http://") == true && !Urls.isPrivateHost(bare)
        val message = getString(R.string.auth_message, host) + if (realm.isNullOrBlank()) "" else "\n$realm"
        ui.prompt = Prompt.Auth(
            message = message,
            insecure = insecure,
            onSubmit = { user, pass ->
                ui.prompt = null
                handler.proceed(user, pass)
            },
            onCancel = {
                ui.prompt = null
                handler.cancel()
            },
        )
    }

    private fun headers(): Map<String, String> =
        if (prefs.getBoolean(Prefs.SEND_DNT, true)) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()

    private fun shouldUpgrade(uri: Uri) =
        prefs.getBoolean(Prefs.HTTPS_FIRST, true) && !Urls.isPrivateHost(uri.host)

    private fun open(url: String, tab: Tab = current) {
        val uri = Uri.parse(url)
        tab.clearError()
        tab.pageHost = Uri.parse(url).host
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

    private fun resolve(input: String) = Urls.resolve(input, ::searchUrl)

    override fun searchUrl(query: String) = SearchEngines.current(prefs).url(query)

    private fun home(): String {
        val custom = prefs.getString(Prefs.HOMEPAGE, "").orEmpty().trim()
        return if (custom.isEmpty()) SearchEngines.current(prefs).home else resolve(custom)
    }

    private fun externalUrl(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_VIEW) return null
        val data = intent.data ?: return null
        return data.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
    }

    // endregion

    // region BrowserHost

    override fun canScrollUp() = web.scrollY > 0

    override fun currentUrl(): String = web.url.orEmpty()

    override fun submit(input: String) = open(resolve(input))

    override fun openUrl(url: String) = open(url)

    override fun resolveInput(input: String) = resolve(input)

    override fun engineName(): String = SearchEngines.current(prefs).name

    override fun menuInfo(): MenuInfo {
        val url = web.url
        val siteHost = Uri.parse(urlOf(current).orEmpty()).host
        val level = prefs.getString(Prefs.TRACKER_LEVEL, "standard")
        return MenuInfo(
            canGoBack = web.canGoBack(),
            canGoForward = web.canGoForward(),
            canBookmark = url != null,
            bookmarked = url != null && Bookmarks.contains(this, url),
            loading = current.loading,
            desktop = prefs.getBoolean(Prefs.DESKTOP, false),
            showProtection = level != "off" && siteHost != null,
            protectionOn = !SiteExceptions.isAllowed(siteHost),
            blocked = current.blocked.get(),
            tabLocked = current.userLocked,
            siteLocked = LockedSites.isLocked(this, siteHost),
            siteLockable = siteHost != null,
        )
    }

    override fun goBackPage() = web.goBack()

    override fun goForwardPage() = web.goForward()

    override fun reloadOrStop() {
        if (current.loading) web.stopLoading() else reload()
    }

    override fun toggleDesktop() {
        prefs.edit { putBoolean(Prefs.DESKTOP, !prefs.getBoolean(Prefs.DESKTOP, false)) }
        tabs.forEach(::applySettings)
        reload()
    }

    override fun toggleBookmark() {
        val url = web.url ?: return
        if (Bookmarks.contains(this, url)) {
            Bookmarks.remove(this, url)
            notify(R.string.bookmark_removed)
        } else {
            Bookmarks.add(this, titleOf(current), url)
            notify(R.string.bookmark_added)
        }
    }

    override fun setProtection(on: Boolean) {
        val host = Uri.parse(urlOf(current).orEmpty()).host ?: return
        if (on) SiteExceptions.remove(this, host) else SiteExceptions.add(this, host)
        reload()
    }

    override fun openFind() {
        ui.findVisible = true
    }

    override fun onFind(query: String) {
        if (query.isEmpty()) {
            web.clearMatches()
            ui.findCount = ""
        } else {
            web.findAllAsync(query)
        }
    }

    override fun findNext(forward: Boolean) = web.findNext(forward)

    override fun closeFind() {
        if (!ui.findVisible) return
        web.clearMatches()
        ui.findQuery = ""
        ui.findCount = ""
        ui.findVisible = false
    }

    override fun newPrivateTab() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) notify(R.string.private_shared_cookies)
        openNewTab(home(), incognito = true)
    }

    private fun needsUnlock(tab: Tab): Boolean {
        if (tab.unlocked) return false
        return tab.userLocked || LockedSites.isLocked(this, Uri.parse(urlOf(tab).orEmpty()).host)
    }

    private fun refreshLock() {
        ui.locked = ::current.isInitialized && needsUnlock(current)
    }

    private fun canLock(): Boolean {
        val ready = AppLock.availability(this) == AppLock.Availability.READY
        if (!ready) notify(R.string.lock_unavailable)
        return ready
    }

    override fun unlockCurrent() {
        confirmIdentity {
            current.unlocked = true
            refreshLock()
            syncTabs()
        }
    }

    override fun toggleTabLock() {
        if (current.userLocked) {
            confirmIdentity {
                current.userLocked = false
                refreshLock()
                syncTabs()
            }
        } else if (canLock()) {
            // Stays visible now; it locks when the person leaves the tab or the app.
            current.userLocked = true
            current.unlocked = true
        }
    }

    override fun toggleSiteLock() {
        val host = Uri.parse(urlOf(current).orEmpty()).host ?: return
        val locked = LockedSites.match(this, host)
        if (locked != null) {
            confirmIdentity {
                LockedSites.remove(this, locked)
                refreshLock()
                syncTabs()
            }
        } else if (canLock()) {
            LockedSites.add(this, host)
            current.unlocked = true
        }
    }

    // region web permissions

    private fun requestAndroidPermissions(perms: List<String>, needAll: Boolean, done: (Boolean) -> Unit) {
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            done(true)
            return
        }
        permissionCallback?.invoke(emptyMap())
        permissionCallback = { result ->
            done(if (needAll) missing.all { result[it] == true } else missing.any { result[it] == true } || missing.size < perms.size)
        }
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun kindOf(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> SitePermissions.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> SitePermissions.MICROPHONE
        PermissionRequest.RESOURCE_MIDI_SYSEX -> SitePermissions.MIDI
        else -> null
    }

    private fun kindLabel(kind: String): String = getString(
        when (kind) {
            SitePermissions.CAMERA -> R.string.perm_camera
            SitePermissions.MICROPHONE -> R.string.perm_microphone
            SitePermissions.LOCATION -> R.string.perm_location
            else -> R.string.perm_midi
        },
    )

    private fun askPermission(host: String?, kinds: List<String>, onDecision: (Boolean, Boolean) -> Unit) {
        if (ui.prompt != null) {
            onDecision(false, false)
            return
        }
        val label = kinds.joinToString(", ") { kindLabel(it) }
        ui.prompt = Prompt.Permission(getString(R.string.perm_message, host ?: "?", label), onDecision)
    }

    /** Camera, microphone and MIDI requests from a page. The person decides; the answer can be remembered per site. */
    private fun handleWebPermission(request: PermissionRequest) {
        val host = request.origin.host
        val wanted = request.resources.filter { kindOf(it) != null || it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        val kinds = wanted.mapNotNull(::kindOf).distinct()
        val silent = wanted.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
        if (kinds.isEmpty()) {
            if (silent.isEmpty()) request.deny() else request.grant(silent.toTypedArray())
            return
        }
        val saved = kinds.map { SitePermissions.get(this, host, it) }
        val allow = {
            val android = wanted.mapNotNull {
                when (it) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                }
            }
            requestAndroidPermissions(android, needAll = true) { ok ->
                if (ok) request.grant(wanted.toTypedArray()) else request.deny()
            }
        }
        when {
            saved.all { it == true } -> allow()
            saved.any { it == false } -> request.deny()
            else -> {
                activePermission = request
                askPermission(host, kinds) { yes, remember ->
                    activePermission = null
                    if (remember) kinds.forEach { SitePermissions.set(this, host, it, yes) }
                    if (yes) allow() else request.deny()
                }
            }
        }
    }

    private fun handleLocation(origin: String, callback: GeolocationPermissions.Callback) {
        val host = Uri.parse(origin).host
        val grant = {
            requestAndroidPermissions(
                listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                needAll = false,
            ) { ok -> callback.invoke(origin, ok, false) }
        }
        when (SitePermissions.get(this, host, SitePermissions.LOCATION)) {
            true -> grant()
            false -> callback.invoke(origin, false, false)
            null -> askPermission(host, listOf(SitePermissions.LOCATION)) { yes, remember ->
                if (remember) SitePermissions.set(this, host, SitePermissions.LOCATION, yes)
                if (yes) grant() else callback.invoke(origin, false, false)
            }
        }
    }

    // endregion

    /** Slides the address bar away while scrolling down and back when scrolling up. */
    private fun onPageScroll(tab: Tab, y: Int, oldY: Int) {
        if (tab !== current || !prefs.getBoolean(Prefs.HIDE_BAR_SCROLL, true)) return
        if (y <= 0) {
            ui.barVisible = true
            scrollAcc = 0
            return
        }
        if (ui.urlFocused || ui.findVisible) return
        val now = SystemClock.uptimeMillis()
        // Resizing the page can itself cause scroll events, so ignore the moments right after a change.
        if (now - lastBarToggle < 350) {
            scrollAcc = 0
            return
        }
        val dy = y - oldY
        if (dy == 0) return
        scrollAcc = if ((scrollAcc > 0) == (dy > 0)) scrollAcc + dy else dy
        val threshold = (48 * resources.displayMetrics.density).toInt()
        if (scrollAcc > threshold && ui.barVisible) {
            ui.barVisible = false
            lastBarToggle = now
            scrollAcc = 0
        } else if (scrollAcc < -threshold && !ui.barVisible) {
            ui.barVisible = true
            lastBarToggle = now
            scrollAcc = 0
        }
    }

    override fun confirmIdentity(onSuccess: () -> Unit) {
        if (AppLock.availability(this) != AppLock.Availability.READY) {
            onSuccess()
            return
        }
        AppLock.authenticate(this, getString(R.string.lock_confirm_title), onSuccess = onSuccess)
    }

    override fun exitFullscreen() {
        val view = ui.customView ?: return
        ui.customView = null
        (view.parent as? ViewGroup)?.removeView(view)
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    override fun share() {
        val url = web.url ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    // endregion

    // region ui helpers

    /** Long press on a link or image: shows the full address first, which also helps spot fake links. */
    private fun showContextMenu(tab: Tab): Boolean {
        val hit = tab.web.hitTestResult
        val extra = hit.extra?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return false
        return when (hit.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> {
                linkMenu(tab, extra, null)
                true
            }
            WebView.HitTestResult.IMAGE_TYPE -> {
                linkMenu(tab, null, extra)
                true
            }
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                val handler = Handler(Looper.getMainLooper()) { message ->
                    linkMenu(tab, message.data.getString("url")?.takeIf { it.startsWith("http") }, extra)
                    true
                }
                tab.web.requestFocusNodeHref(handler.obtainMessage())
                true
            }
            else -> false
        }
    }

    private fun linkMenu(tab: Tab, link: String?, image: String?) {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (link != null) {
            actions += getString(R.string.open_new_tab) to { newTab(link) }
            actions += getString(R.string.copy_link) to { copyText(link) }
            actions += getString(R.string.share_link) to { shareText(link) }
        }
        if (image != null) {
            actions += getString(R.string.open_image) to { newTab(image) }
            actions += getString(R.string.save_image) to {
                startDownload(tab.web, image, tab.web.settings.userAgentString, null, null, -1)
            }
            actions += getString(R.string.copy_image_link) to { copyText(image) }
        }
        if (actions.isEmpty()) return
        ui.prompt = Prompt.LinkMenu((link ?: image).orEmpty().take(100), actions)
    }

    private fun copyText(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("link", text))
        notify(R.string.link_copied)
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    private fun applyPreviewSetting() = AppLock.applyPrivacyFlags(this)

    /** Private tabs are always hidden from screenshots and recents, whatever the general setting says. */
    private fun applyScreenFlags() {
        if (::current.isInitialized && current.isPrivate) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            applyPreviewSetting()
        }
    }

    /** Page security depends on the system WebView, so warn (at most weekly) when it has not been updated for a while. */
    private fun checkEngineAge() {
        val engine = WebView.getCurrentWebViewPackage() ?: return
        val now = System.currentTimeMillis()
        val days = (now - engine.lastUpdateTime) / DateUtils.DAY_IN_MILLIS
        if (days < ENGINE_MAX_AGE_DAYS) return
        if (now - prefs.getLong(Prefs.WEBVIEW_WARNED, 0) < DateUtils.WEEK_IN_MILLIS) return
        prefs.edit { putLong(Prefs.WEBVIEW_WARNED, now) }
        notify(getString(R.string.webview_old, days), R.string.update) {
            val store = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${engine.packageName}"))
            try {
                startActivity(store)
            } catch (e: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${engine.packageName}")))
            }
        }
    }

    private fun showUrl(url: String?) {
        ui.pageUrl = url
    }

    private fun setLoading(tab: Tab, on: Boolean) {
        tab.loading = on
        if (tab === current) renderLoading()
    }

    private fun renderLoading() {
        ui.loading = current.loading
        if (!current.loading) ui.refreshing = false
    }

    private fun notify(@StringRes message: Int) = notify(getString(message))

    private fun notify(message: String, @StringRes actionLabel: Int? = null, action: (() -> Unit)? = null) {
        lifecycleScope.launch {
            ui.snackbar.currentSnackbarData?.dismiss()
            val result = ui.snackbar.showSnackbar(
                message = message,
                actionLabel = actionLabel?.let { getString(it) },
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) action?.invoke()
        }
    }

    /** Moves the address bar to the top or bottom of the screen, following Settings. */
    private fun applyToolbarPosition() {
        ui.oneHand = prefs.getString(Prefs.ONE_HAND, "off") ?: "off"
        // One-handed mode keeps the controls within thumb reach, so the bar always goes to the bottom.
        ui.toolbarBottom = ui.oneHand != "off" || prefs.getString(Prefs.TOOLBAR, "top") == "bottom"
    }

    // endregion

    private companion object {
        const val ENGINE_MAX_AGE_DAYS = 60
        val RISKY_EXTENSIONS = setOf(
            "apk", "apks", "xapk", "exe", "msi", "bat", "cmd", "scr", "jar", "js", "vbs", "ps1", "sh", "lnk", "dmg",
        )
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MAX_TABS = 30
        const val PRIVATE_PROFILE = "brisa_private"
        const val MAX_BLOB = 26214400
    }
}
