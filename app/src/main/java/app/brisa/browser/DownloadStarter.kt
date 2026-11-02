package app.brisa.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import java.io.File
import java.util.UUID

/** Starts http(s) downloads with the system downloader and records them in the Downloads list. */
object DownloadStarter {
    /** The system downloader fails when the target file exists, so repeat downloads get a numbered name. */
    fun uniqueName(context: Context, suggested: String): String {
        val used = Downloads.all(context).map { it.name }.toSet()
        if (suggested !in used) return suggested
        val dot = suggested.lastIndexOf('.')
        val base = if (dot > 0) suggested.substring(0, dot) else suggested
        val ext = if (dot > 0) suggested.substring(dot) else ""
        var n = 1
        while ("$base ($n)$ext" in used) n++
        return "$base ($n)$ext"
    }

    /** Returns false if the system downloader refused the request. */
    fun enqueue(context: Context, url: String, userAgent: String, mime: String?, suggested: String, tree: Uri?): Boolean {
        val name = uniqueName(context, suggested)
        val id = UUID.randomUUID().toString()
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setMimeType(mime)
            .addRequestHeader("User-Agent", userAgent)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        // A custom folder cannot be written by the system downloader: it saves to a private
        // temporary file first and DownloadReceiver moves it when it finishes.
        var temp: String? = null
        if (tree != null && base != null) {
            temp = File(base, "brisa/$id/$name").absolutePath
            request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "brisa/$id/$name")
        } else {
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        }
        CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
        val dmId = runCatching { context.getSystemService(DownloadManager::class.java).enqueue(request) }.getOrNull()
            ?: return false
        Downloads.add(
            context,
            Downloads.Item(
                id, dmId, name, url, mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream",
                -1, 0, System.currentTimeMillis(), Downloads.RUNNING, null,
                if (temp != null) tree?.toString() else null, temp,
            ),
        )
        return true
    }
}
