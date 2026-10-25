package app.brisa.browser

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
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
import android.webkit.WebViewClient
import android.net.http.SslError
import android.os.Bundle
import android.widget.PopupMenu
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import app.brisa.browser.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity() {

    private lateinit var ui: ActivityMainBinding
    private lateinit var prefs: SharedPreferences
    private val defaultUa by lazy { WebSettings.getDefaultUserAgent(this) }
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = ActivityMainBinding.inflate(layoutInflater)
        setContentView(ui.root)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        setupWebView()
        setupBars()
        applySettings()

        onBackPressedDispatcher.addCallback(this) {
            if (ui.web.canGoBack()) ui.web.goBack() else finish()
        }

        if (savedInstanceState == null) open(externalUrl(intent) ?: HOME)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        externalUrl(intent)?.let(::open)
    }

    override fun onResume() {
        super.onResume()
        ui.web.onResume()
        applySettings()
        if (prefs.getBoolean(Prefs.PENDING_CLEAR, false)) {
            prefs.edit { remove(Prefs.PENDING_CLEAR) }
            clearData()
        }
    }

    override fun onPause() {
        ui.web.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        if (isFinishing && prefs.getBoolean(Prefs.CLEAR_ON_EXIT, false)) clearData()
        (ui.web.parent as? ViewGroup)?.removeView(ui.web)
        ui.web.destroy()
        super.onDestroy()
    }

    private fun setupWebView() {
        ui.web.settings.apply {
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

        ui.web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return when (uri.scheme) {
                    "https" -> false
                    "http" -> {
                        if (request.isForMainFrame) open(uri.buildUpon().scheme("https").build().toString())
                        true
                    }
                    else -> true
                }
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                setLoading(true)
                showUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(false)
                showUrl(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                updateNav()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) notify(R.string.load_error)
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                notify(R.string.ssl_error)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                recreate()
                return true
            }
        }

        ui.web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                ui.progress.setProgressCompat(newProgress, true)
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = callback.invoke(origin, false, false)
        }

        ui.web.setDownloadListener { url, userAgent, disposition, mime, _ ->
            if (!url.startsWith("https://")) return@setDownloadListener
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

    private fun setupBars() {
        ui.urlBar.setOnEditorActionListener { v, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                open(resolve(v.text.toString()))
                v.clearFocus()
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(v.windowToken, 0)
                true
            } else false
        }
        ui.urlBar.setOnFocusChangeListener { v, focused ->
            val field = v as android.widget.EditText
            if (focused) {
                field.setText(ui.web.url.orEmpty())
                field.selectAll()
            } else {
                field.setText(display(ui.web.url))
            }
        }

        ui.back.setOnClickListener { ui.web.goBack() }
        ui.forward.setOnClickListener { ui.web.goForward() }
        ui.reload.setOnClickListener { if (loading) ui.web.stopLoading() else ui.web.reload() }
        ui.menu.setOnClickListener { showMenu() }
        updateNav()
    }

    private fun showMenu() {
        PopupMenu(this, ui.menu).apply {
            menuInflater.inflate(R.menu.main, menu)
            menu.findItem(R.id.action_desktop).isChecked = prefs.getBoolean(Prefs.DESKTOP, false)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_share -> share()
                    R.id.action_desktop -> {
                        prefs.edit { putBoolean(Prefs.DESKTOP, !item.isChecked) }
                        applySettings()
                        ui.web.reload()
                    }
                    R.id.action_settings -> startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                }
                true
            }
            show()
        }
    }

    private fun share() {
        val url = ui.web.url ?: return
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(send, null))
    }

    private fun applySettings() {
        val desktop = prefs.getBoolean(Prefs.DESKTOP, false)
        ui.web.settings.apply {
            javaScriptEnabled = prefs.getBoolean(Prefs.JAVASCRIPT, true)
            userAgentString = if (desktop) DESKTOP_UA else defaultUa
            useWideViewPort = desktop
            loadWithOverviewMode = desktop
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(ui.web, !prefs.getBoolean(Prefs.BLOCK_THIRD_PARTY, true))
        }
    }

    private fun clearData() {
        ui.web.clearCache(true)
        ui.web.clearHistory()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        updateNav()
    }

    private fun open(url: String) {
        val safe = if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
        val headers = if (prefs.getBoolean(Prefs.SEND_DNT, true)) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()
        ui.web.loadUrl(safe, headers)
    }

    private fun resolve(input: String): String {
        val text = input.trim()
        return when {
            text.startsWith("https://", true) || text.startsWith("http://", true) -> text
            "://" in text || ' ' in text || '.' !in text -> searchUrl(text)
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

    private fun display(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return Uri.parse(url).host?.removePrefix("www.") ?: url
    }

    private fun showUrl(url: String?) {
        if (!ui.urlBar.hasFocus()) ui.urlBar.setText(display(url))
    }

    private fun setLoading(on: Boolean) {
        loading = on
        ui.progress.animate().cancel()
        if (on) {
            ui.progress.alpha = 1f
            ui.progress.isVisible = true
        } else {
            ui.progress.animate().alpha(0f).setDuration(200).withEndAction { ui.progress.isGone = true }.start()
        }
        ui.reload.setImageResource(if (on) R.drawable.ic_close else R.drawable.ic_refresh)
        ui.reload.contentDescription = getString(if (on) R.string.stop else R.string.reload)
    }

    private fun updateNav() {
        fade(ui.back, ui.web.canGoBack())
        fade(ui.forward, ui.web.canGoForward())
    }

    private fun fade(button: android.view.View, enabled: Boolean) {
        button.isEnabled = enabled
        button.animate().alpha(if (enabled) 1f else 0.38f).setDuration(150).start()
    }

    private fun notify(message: Int) = Snackbar.make(ui.root, message, Snackbar.LENGTH_SHORT).show()

    private companion object {
        const val HOME = "https://www.google.com"
        const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
