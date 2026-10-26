package app.brisa.browser

import android.animation.LayoutTransition
import android.animation.ValueAnimator
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import android.text.format.Formatter
import android.util.Base64
import android.webkit.WebResourceResponse
import androidx.core.view.ViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID
import android.app.ActivityOptions
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Environment
import android.text.format.DateUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.OvershootInterpolator
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.preference.PreferenceManager
import app.brisa.browser.databinding.ActivityMainBinding
import app.brisa.browser.databinding.MenuPopupBinding
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private class Tab(val web: WebView) {
        var title = ""
        var loading = false
        var progress = 0
        var upgradedUrl: String? = null
        var fallbackUrl: String? = null
        var pendingUrl: String? = null
    }

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private val omniBg by lazy { ui.omnibox.background.mutate() as GradientDrawable }
    private val tabs = mutableListOf<Tab>()
    private lateinit var current: Tab
    private val web get() = current.web
    private var clearShown = false
    private var suggestShown = false
    private var blobToken: String? = null
    private var blobName = ""
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

        ViewCompat.setOnApplyWindowInsetsListener(ui.content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        Blocklist.load(this)
        val external = externalUrl(intent)
        val saved = Session.load(this)?.takeIf { restoreEnabled() }
        saved?.let(::restore)
        if (external != null) newTab(external) else if (saved == null) newTab(HOME)
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
        saveSession()
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

    private fun createTab(): Tab {
        val tab = Tab(WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        configure(tab)
        tabs += tab
        return tab
    }

    private fun newTab(url: String) {
        val tab = createTab()
        select(tab)
        open(url, tab)
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
        Session.save(this, tabs.map { Session.Entry(titleOf(it), urlOf(it) ?: HOME) }, tabs.indexOf(current))
    }

    private fun select(tab: Tab) {
        exitFullscreen()
        closeFind()
        current = tab
        ui.webContainer.removeAllViews()
        ui.webContainer.addView(tab.web)
        ui.webContainer.apply {
            alpha = 0f
            translationY = dp(10).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM).setInterpolator(Motion.decelerate).start()
        }
        hideSuggestions()
        ui.urlBar.clearFocus()
        showUrl(urlOf(tab))
        ui.progress.setProgressCompat(tab.progress, false)
        renderLoading()
        updateTabCount()
        tab.pendingUrl?.let {
            tab.pendingUrl = null
            open(it, tab)
        }
    }

    private fun closeTab(tab: Tab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) newTab(HOME)
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
            ui.urlBar.hasFocus() -> {
                ui.urlBar.clearFocus()
                hideKeyboard(ui.urlBar)
            }
            ui.findBar.isVisible -> closeFind()
            web.canGoBack() -> web.goBack()
            tabs.size > 1 -> closeTab(current)
            else -> finish()
        }
    }

    private fun updateTabCount() {
        val label = tabs.size.toString()
        if (ui.tabCount.text == label) return
        ui.tabCount.text = label
        ui.tabCount.animate().cancel()
        ui.tabCount.scaleX = 1.4f
        ui.tabCount.scaleY = 1.4f
        ui.tabCount.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(OvershootInterpolator(3f)).start()
    }

    private fun urlOf(tab: Tab): String? = tab.web.url ?: tab.pendingUrl

    private fun titleOf(tab: Tab): String =
        tab.title.ifBlank { display(urlOf(tab)) }.ifBlank { getString(R.string.new_tab) }

    // endregion

    // region sheets

    private fun sheetContent(title: Int): Triple<BottomSheetDialog, View, LinearLayout> {
        val sheet = BottomSheetDialog(this)
        val content = layoutInflater.inflate(R.layout.sheet_tabs, null)
        content.findViewById<TextView>(R.id.sheetTitle).setText(title)
        return Triple(sheet, content, content.findViewById(R.id.tabList))
    }

    private fun row(
        list: LinearLayout,
        title: String,
        subtitle: String,
        index: Int,
        animate: Boolean,
        onOpen: () -> Unit,
        onClose: (View) -> Unit,
    ): View {
        val row = layoutInflater.inflate(R.layout.item_tab, list, false)
        row.findViewById<TextView>(R.id.tabTitle).text = title
        row.findViewById<TextView>(R.id.tabSubtitle).apply {
            text = subtitle
            isGone = subtitle.isEmpty() || subtitle == title
        }
        row.setOnClickListener { onOpen() }
        row.findViewById<View>(R.id.tabClose).apply {
            pressable()
            setOnClickListener { onClose(row) }
        }
        if (animate) {
            row.alpha = 0f
            row.translationY = dp(14).toFloat()
            row.animate().alpha(1f).translationY(0f).setStartDelay(60L + index * 35L)
                .setDuration(280).setInterpolator(Motion.decelerate).start()
        }
        return row
    }

    private fun slideOut(row: View, then: () -> Unit) {
        row.animate().translationX(row.width.toFloat()).alpha(0f).setDuration(Motion.SHORT + 40)
            .setInterpolator(Motion.accelerate).withEndAction { then() }.start()
    }

    private fun smoothRemoval(list: LinearLayout) {
        list.layoutTransition = LayoutTransition().apply {
            disableTransitionType(LayoutTransition.APPEARING)
            disableTransitionType(LayoutTransition.DISAPPEARING)
            setDuration(220)
        }
    }

    private fun showTabs() {
        val (sheet, content, list) = sheetContent(R.string.tabs)

        fun fill(animate: Boolean) {
            list.removeAllViews()
            tabs.toList().forEachIndexed { index, tab ->
                val view = row(
                    list, titleOf(tab), display(urlOf(tab)), index, animate,
                    onOpen = {
                        select(tab)
                        sheet.dismiss()
                    },
                    onClose = { row ->
                        val last = tabs.size == 1
                        slideOut(row) {
                            closeTab(tab)
                            if (last) fill(false) else list.removeView(row)
                        }
                    },
                )
                if (tab === current) view.setBackgroundColor(getColor(R.color.toolbar))
                list.addView(view)
            }
        }

        content.findViewById<View>(R.id.newTab).apply {
            pressable(0.95f)
            setOnClickListener {
                newTab(HOME)
                sheet.dismiss()
            }
        }
        fill(true)
        smoothRemoval(list)
        sheet.setContentView(content)
        sheet.show()
    }

    private fun showBookmarks() {
        val (sheet, content, list) = sheetContent(R.string.bookmarks)
        content.findViewById<View>(R.id.newTab).isGone = true

        fun fill() {
            list.removeAllViews()
            val items = Bookmarks.all(this)
            if (items.isEmpty()) list.addView(emptyView(R.string.empty_bookmarks))
            items.forEachIndexed { index, item ->
                list.addView(row(
                    list, item.title.ifBlank { item.url }, display(item.url), index, true,
                    onOpen = {
                        open(item.url)
                        sheet.dismiss()
                    },
                    onClose = { row ->
                        slideOut(row) {
                            Bookmarks.remove(this, item.url)
                            list.removeView(row)
                            if (list.childCount == 0) fill()
                        }
                    },
                ))
            }
        }

        fill()
        smoothRemoval(list)
        sheet.setContentView(content)
        sheet.show()
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
        when {
            url.startsWith("https://") || url.startsWith("http://") -> confirmDownload(name, length) {
                val request = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mime)
                    .addRequestHeader("User-Agent", userAgent)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
                getSystemService(DownloadManager::class.java).enqueue(request)
                notify(R.string.downloading)
            }
            url.startsWith("data:") -> {
                val decoded = decodeDataUrl(url) ?: return notify(R.string.download_failed)
                confirmDownload(name, decoded.second.size.toLong()) {
                    saveToDownloads(name, decoded.first, decoded.second)
                }
            }
            url.startsWith("blob:") -> confirmDownload(name, length) {
                val token = UUID.randomUUID().toString()
                blobToken = token
                blobName = name
                source.evaluateJavascript(blobScript(url, token), null)
            }
        }
    }

    private fun confirmDownload(name: String, size: Long, onConfirm: () -> Unit) {
        val detail = if (size > 0) Formatter.formatShortFileSize(this, size) else getString(R.string.unknown_size)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.download_title)
            .setMessage("$name\n$detail")
            .setPositiveButton(R.string.download) { _, _ -> onConfirm() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
        saveToDownloads(blobName, mime, bytes)
    }

    private fun decodeDataUrl(url: String): Pair<String, ByteArray>? = runCatching {
        val comma = url.indexOf(',')
        val meta = url.substring(5, comma)
        val body = url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifEmpty { "text/plain" }
        val bytes = if (meta.endsWith(";base64")) Base64.decode(body, Base64.DEFAULT) else Uri.decode(body).toByteArray()
        mime to bytes
    }.getOrNull()

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        val saved = uri != null && runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(bytes) } != null
        }.getOrDefault(false)
        notify(if (saved) R.string.saved_to_downloads else R.string.download_failed)
    }

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

    // region history and suggestions

    private fun recordVisit(tab: Tab, url: String?) {
        if (url == null || !prefs.getBoolean(Prefs.SAVE_HISTORY, true)) return
        History.record(this, tab.title.ifBlank { url }, url)
    }

    private fun emptyView(text: Int) = TextView(this).apply {
        setText(text)
        setTextColor(getColor(R.color.muted))
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }

    private fun showHistory() {
        val (sheet, content, list) = sheetContent(R.string.history)
        val clearButton = content.findViewById<TextView>(R.id.newTab)
        clearButton.setText(R.string.clear_history)

        fun fill() {
            list.removeAllViews()
            val items = History.all(this).take(100)
            if (items.isEmpty()) list.addView(emptyView(R.string.empty_history))
            items.forEachIndexed { index, entry ->
                val age = DateUtils.getRelativeTimeSpanString(
                    entry.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
                )
                list.addView(row(
                    list, entry.title.ifBlank { entry.url }, "${display(entry.url)} · $age",
                    minOf(index, 10), true,
                    onOpen = {
                        open(entry.url)
                        sheet.dismiss()
                    },
                    onClose = { row ->
                        slideOut(row) {
                            History.remove(this, entry.url)
                            list.removeView(row)
                            if (list.childCount == 0) fill()
                        }
                    },
                ))
            }
        }

        clearButton.pressable(0.95f)
        clearButton.setOnClickListener {
            History.clear(this)
            fill()
        }
        fill()
        smoothRemoval(list)
        sheet.setContentView(content)
        sheet.show()
    }

    private fun renderSuggestions(query: String) {
        val q = query.trim()
        if (q.isEmpty()) {
            hideSuggestions()
            return
        }
        ui.suggestions.removeAllViews()
        val target = resolve(q)
        val isSearch = target.startsWith("https://www.google.com/search")
        ui.suggestions.addView(
            suggestionRow(
                if (isSearch) R.drawable.ic_search else R.drawable.ic_arrow_forward,
                q,
                if (isSearch) getString(R.string.search_google) else target,
            ) { open(target) },
        )
        val seen = mutableSetOf<String>()
        val bookmarks = Bookmarks.all(this).filter { matches(it.title, it.url, q) }
            .map { Triple(R.drawable.ic_star, it.title, it.url) }
        val visited = History.all(this).filter { matches(it.title, it.url, q) }
            .map { Triple(R.drawable.ic_history, it.title, it.url) }
        (bookmarks + visited).filter { seen.add(it.third) }.take(5).forEach { (icon, title, url) ->
            ui.suggestions.addView(suggestionRow(icon, title.ifBlank { url }, display(url)) { open(url) })
        }
        showSuggestions()
    }

    private fun matches(title: String, url: String, query: String) =
        title.contains(query, ignoreCase = true) || url.contains(query, ignoreCase = true)

    private fun suggestionRow(icon: Int, title: String, subtitle: String, onClick: () -> Unit): View {
        val row = layoutInflater.inflate(R.layout.item_suggestion, ui.suggestions, false)
        row.findViewById<ImageView>(R.id.suggestIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.suggestTitle).text = title
        row.findViewById<TextView>(R.id.suggestSubtitle).apply {
            text = subtitle
            isGone = subtitle.isEmpty()
        }
        row.setOnClickListener {
            onClick()
            ui.urlBar.clearFocus()
            hideKeyboard(ui.urlBar)
        }
        return row
    }

    private fun showSuggestions() {
        if (suggestShown) return
        suggestShown = true
        ui.suggestions.animate().cancel()
        ui.suggestions.alpha = 0f
        ui.suggestions.translationY = -dp(8).toFloat()
        ui.suggestions.isVisible = true
        ui.suggestions.animate().alpha(1f).translationY(0f).setDuration(Motion.SHORT)
            .setInterpolator(Motion.decelerate).start()
    }

    private fun hideSuggestions() {
        if (!suggestShown) return
        suggestShown = false
        ui.suggestions.animate().cancel()
        ui.suggestions.animate().alpha(0f).translationY(-dp(8).toFloat()).setDuration(100)
            .withEndAction { if (!suggestShown) ui.suggestions.isGone = true }.start()
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
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame || !prefs.getBoolean(Prefs.BLOCK_TRACKERS, true)) return null
                if (!Blocklist.isBlocked(request.url.host)) return null
                return WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }

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
                recordVisit(tab, url)
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
                recordVisit(tab, view.url)
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
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customCallback = callback
                ui.fullscreen.addView(view)
                ui.fullscreen.alpha = 0f
                ui.fullscreen.isVisible = true
                ui.fullscreen.animate().alpha(1f).setDuration(Motion.MEDIUM).start()
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
        History.clear(this)
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
        listOf(ui.tabsButton, ui.menu, ui.clearUrl, ui.findPrev, ui.findNext, ui.findClose).forEach { it.pressable() }

        ui.swipe.setColorSchemeColors(getColor(R.color.accent))
        ui.swipe.setProgressBackgroundColorSchemeColor(getColor(R.color.popup))
        ui.swipe.setOnChildScrollUpCallback { _, _ -> web.scrollY > 0 }
        ui.swipe.setOnRefreshListener { web.reload() }

        ui.urlBar.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                open(resolve(v.text.toString()))
                v.clearFocus()
                hideKeyboard(v)
                true
            } else false
        }
        ui.urlBar.setOnFocusChangeListener { _, focused ->
            ui.urlBar.setText(if (focused) web.url.orEmpty() else display(web.url))
            if (focused) ui.urlBar.selectAll()
            animateFocus(focused)
            if (!focused) hideSuggestions()
            updateSiteIcon(web.url)
            updateClear()
        }
        ui.urlBar.doOnTextChanged { text, _, _, _ ->
            updateClear()
            val typed = text?.toString().orEmpty()
            if (ui.urlBar.hasFocus() && typed.isNotEmpty() && typed != web.url.orEmpty()) renderSuggestions(typed)
            else hideSuggestions()
        }
        ui.clearUrl.setOnClickListener { ui.urlBar.text?.clear() }
        ui.tabsButton.setOnClickListener { showTabs() }
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

    private fun animateFocus(focused: Boolean) {
        val accent = getColor(R.color.accent)
        val clear = accent and 0x00FFFFFF
        ValueAnimator.ofArgb(if (focused) clear else accent, if (focused) accent else clear).apply {
            duration = Motion.MEDIUM
            interpolator = Motion.standard
            addUpdateListener { omniBg.setStroke(dp(2), it.animatedValue as Int) }
            start()
        }
    }

    private fun updateSiteIcon(url: String?) {
        val (icon, color) = when {
            ui.urlBar.hasFocus() || url.isNullOrEmpty() -> R.drawable.ic_search to R.color.muted
            url.orEmpty().startsWith("https://") -> R.drawable.ic_lock to R.color.muted
            else -> R.drawable.ic_lock_open to R.color.warning
        }
        if (ui.siteIcon.tag == icon) return
        ui.siteIcon.tag = icon
        ui.siteIcon.animate().cancel()
        ui.siteIcon.animate().alpha(0f).setDuration(80).withEndAction {
            ui.siteIcon.setImageResource(icon)
            ui.siteIcon.imageTintList = ColorStateList.valueOf(getColor(color))
            ui.siteIcon.animate().alpha(1f).setDuration(140).start()
        }.start()
    }

    private fun updateClear() {
        val show = ui.urlBar.hasFocus() && !ui.urlBar.text.isNullOrEmpty()
        if (show == clearShown) return
        clearShown = show
        ui.clearUrl.animate().cancel()
        if (show) {
            ui.clearUrl.alpha = 0f
            ui.clearUrl.scaleX = 0.6f
            ui.clearUrl.scaleY = 0.6f
            ui.clearUrl.isVisible = true
            ui.clearUrl.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.SHORT)
                .setInterpolator(Motion.decelerate).start()
        } else {
            ui.clearUrl.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(100)
                .withEndAction { if (!clearShown) ui.clearUrl.isGone = true }.start()
        }
    }

    private fun showMenu() {
        ui.menu.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        val m = MenuPopupBinding.inflate(layoutInflater)
        val popup = PopupWindow(m.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val url = web.url
        val bookmarked = url != null && Bookmarks.contains(this, url)
        m.iconBack.enable(web.canGoBack())
        m.iconForward.enable(web.canGoForward())
        m.iconBookmark.enable(url != null)
        m.iconBookmark.setImageResource(if (bookmarked) R.drawable.ic_star else R.drawable.ic_star_border)
        m.iconBookmark.contentDescription =
            getString(if (bookmarked) R.string.remove_bookmark else R.string.add_bookmark)
        m.iconReload.setImageResource(if (current.loading) R.drawable.ic_close else R.drawable.ic_refresh)
        m.iconReload.contentDescription = getString(if (current.loading) R.string.stop else R.string.reload)
        m.desktopSwitch.isChecked = prefs.getBoolean(Prefs.DESKTOP, false)

        fun close(action: () -> Unit) {
            m.card.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(Motion.SHORT)
                .setInterpolator(Motion.accelerate).withEndAction {
                    popup.dismiss()
                    action()
                }.start()
        }

        fun bind(view: View, action: () -> Unit) {
            view.setOnClickListener { close(action) }
        }

        listOf(m.iconBack, m.iconForward, m.iconBookmark, m.iconReload).forEach { it.pressable() }
        bind(m.iconBack) { web.goBack() }
        bind(m.iconForward) { web.goForward() }
        bind(m.iconBookmark) { toggleBookmark() }
        bind(m.iconReload) { if (current.loading) web.stopLoading() else web.reload() }
        bind(m.rowNewTab) { newTab(HOME) }
        bind(m.rowFind) { openFind() }
        bind(m.rowShare) { share() }
        bind(m.rowBookmarks) { showBookmarks() }
        bind(m.rowHistory) { showHistory() }
        bind(m.rowSettings) {
            val options = ActivityOptions.makeCustomAnimation(this, R.anim.enter_from_right, R.anim.exit_to_left)
            startActivity(Intent(this, SettingsActivity::class.java), options.toBundle())
        }
        m.rowDesktop.setOnClickListener {
            m.desktopSwitch.toggle()
            m.root.postDelayed({ close { toggleDesktop() } }, 170)
        }

        val items = listOf<View>(
            m.iconRow, m.rowNewTab, m.rowFind, m.rowShare, m.rowBookmarks, m.rowHistory, m.rowDesktop, m.rowSettings,
        )
        m.card.doOnLayout { card ->
            card.pivotX = card.width.toFloat()
            card.pivotY = 0f
            card.alpha = 0f
            card.scaleX = 0.8f
            card.scaleY = 0.8f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.decelerate).start()
            items.forEachIndexed { i, item ->
                item.alpha = 0f
                item.translationY = dp(8).toFloat()
                item.animate().alpha(1f).translationY(0f).setStartDelay(50L + i * 28L)
                    .setDuration(220).setInterpolator(Motion.decelerate).start()
            }
        }
        popup.showAsDropDown(ui.menu, 0, -dp(4), Gravity.END)
    }

    private fun View.enable(on: Boolean) {
        isEnabled = on
        alpha = if (on) 1f else 0.38f
    }

    private fun toggleDesktop() {
        prefs.edit { putBoolean(Prefs.DESKTOP, !prefs.getBoolean(Prefs.DESKTOP, false)) }
        tabs.forEach(::applySettings)
        web.reload()
    }

    private fun toggleBookmark() {
        val url = web.url ?: return
        if (Bookmarks.contains(this, url)) {
            Bookmarks.remove(this, url)
            notify(R.string.bookmark_removed)
        } else {
            Bookmarks.add(this, titleOf(current), url)
            notify(R.string.bookmark_added)
        }
    }

    private fun openFind() {
        if (!ui.findBar.isVisible) {
            ui.findBar.isVisible = true
            ui.findBar.alpha = 0f
            ui.findBar.translationY = dp(24).toFloat()
            ui.findBar.animate().alpha(1f).translationY(0f).setDuration(Motion.MEDIUM)
                .setInterpolator(Motion.decelerate).start()
        }
        ui.findInput.requestFocus()
        getSystemService(InputMethodManager::class.java).showSoftInput(ui.findInput, 0)
    }

    private fun closeFind() {
        if (!ui.findBar.isVisible) return
        web.clearMatches()
        ui.findInput.text?.clear()
        ui.findCount.text = ""
        hideKeyboard(ui.findInput)
        ui.findBar.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(Motion.SHORT)
            .setInterpolator(Motion.accelerate).withEndAction { ui.findBar.isGone = true }.start()
    }

    private fun exitFullscreen() {
        val view = customView ?: return
        customView = null
        ui.fullscreen.animate().alpha(0f).setDuration(Motion.SHORT).withEndAction {
            ui.fullscreen.removeView(view)
            ui.fullscreen.isGone = true
        }.start()
        customCallback?.onCustomViewHidden()
        customCallback = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }

    private fun share() {
        val url = web.url ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun hideKeyboard(view: View) {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun display(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return Uri.parse(url).host?.removePrefix("www.") ?: url
    }

    private fun showUrl(url: String?) {
        if (!ui.urlBar.hasFocus()) ui.urlBar.setText(display(url))
        updateSiteIcon(url)
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
            ui.swipe.isRefreshing = false
            ui.progress.animate().alpha(0f).setDuration(260).setStartDelay(120)
                .withEndAction { ui.progress.isGone = true }.start()
        }
    }

    private fun notify(message: Int) = Snackbar.make(ui.root, message, Snackbar.LENGTH_SHORT).show()

    // endregion

    private companion object {
        const val HOME = "https://www.google.com"
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val MAX_BLOB = 26214400
        val PRIVATE_IP = Regex("^(127\\.|10\\.|192\\.168\\.|169\\.254\\.|172\\.(1[6-9]|2\\d|3[01])\\.)")
    }
}
