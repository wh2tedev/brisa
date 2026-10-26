package app.brisa.browser

import android.webkit.JavascriptInterface

/** Receives the bytes of a blob: download. Calls are ignored unless they carry the one-time token. */
class BlobBridge(
    private val onSave: (token: String, mime: String, data: String) -> Unit,
    private val onFail: (token: String) -> Unit,
) {
    @JavascriptInterface
    fun save(token: String, mime: String, data: String) = onSave(token, mime, data)

    @JavascriptInterface
    fun fail(token: String) = onFail(token)
}
