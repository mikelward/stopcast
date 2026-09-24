package app.stopcast.watch

import android.content.Context
import app.stopcast.StopcastDebugLog
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.stopcast.data.WatchSyncContract
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService
import java.util.concurrent.TimeUnit

/**
 * Publishes the current snapshot to the watch outside the app's own collector: a bounded retry
 * after a failed publish, and a forced republish when a watch reconnects. One unique job of each
 * kind, latest request wins; it reads the stored state when it runs, so it always sends the newest
 * envelope.
 */
class WatchPublishWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val outcome = WatchSync.publishCurrent(applicationContext, force)
        if (outcome != WatchPublisher.Outcome.Failed) return Result.success()
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        // Given up for now: the next snapshot, star change, app start or reconnect tries again.
        StopcastDebugLog.warning("watch: publish retries exhausted")
        return Result.failure()
    }

    companion object {
        /** Forced and ordinary requests queue apart, so a later ordinary retry never replaces a
         *  pending forced republish (and loses its force); each keeps only its own latest. */
        private const val UNIQUE_NAME = "watch-publish"
        private const val UNIQUE_NAME_FORCED = "watch-publish-forced"
        private const val KEY_FORCE = "force"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, force: Boolean) {
            val request = OneTimeWorkRequestBuilder<WatchPublishWorker>()
                .setInputData(workDataOf(KEY_FORCE to force))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(if (force) UNIQUE_NAME_FORCED else UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * Notices a watch gaining the app or coming back in reach, and republishes the current snapshot
 * to it (forced, past the identical-content check), so a watch never keeps an older snapshot than
 * the phone's just because nothing changed while it was away.
 */
class PhoneWearListenerService : WearableListenerService() {
    override fun onCapabilityChanged(info: CapabilityInfo) {
        if (info.name == WatchSyncContract.WATCH_CAPABILITY && info.nodes.isNotEmpty()) {
            WatchPublishWorker.enqueue(this, force = true)
        }
    }
}
