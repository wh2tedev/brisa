package app.brisa.browser

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Updates the downloads list, and moves files to the chosen folder, when the system finishes a download. */
class DownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Downloads.sync(app)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
