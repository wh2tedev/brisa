package app.brisa.browser

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Finishes downloads (moving big files to the chosen folder) without the time limit of a broadcast receiver. */
class DownloadWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        Downloads.sync(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}
