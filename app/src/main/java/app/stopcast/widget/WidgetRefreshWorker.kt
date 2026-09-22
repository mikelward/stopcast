package app.stopcast.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import app.stopcast.data.DataStoreAppSettings
import app.stopcast.data.DataStoreSnapshotStore
import app.stopcast.data.KtorTflClient
import app.stopcast.data.SharedTflRateLimiter
import app.stopcast.data.SharedTflRequestPool
import app.stopcast.data.logAppSettingsWarning
import app.stopcast.domain.AppSettings
import app.stopcast.domain.WidgetRefresh
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Unique-work name so each scheduled refresh REPLACEs the previous one — at most one pending. */
internal const val WIDGET_REFRESH_WORK = "stopcast-widget-refresh"

/** The cadence of the opt-in "live widget" refresh (SPEC D5). One minute matches the app's own
 *  auto-refresh (D6) and TfL's ~30 s prediction cadence, at a tiny fraction of the rate budget. */
internal const val WIDGET_REFRESH_INTERVAL_MILLIS = 60_000L

/** Ceiling on a one-shot settings read (see [liveWidgetRefreshNow]). Normal reads are instant;
 *  this only bites on a persistent storage error (Codex P2 on #56). */
private const val SETTINGS_READ_BOUND_MILLIS = 2_000L

/**
 * Reads the live-refresh setting once, bounded — for the two *user-facing* one-shot callers that
 * can't wait indefinitely: the startup sync (blocks the restore + error notice) and the widget
 * render path (blocks `provideContent`). Returns the current value, or **null** when it can't be
 * read within [timeoutMillis].
 *
 * [AppSettings.liveWidgetRefresh] retries a transient `IOException` forever so the long-lived in-app
 * collector stays alive and recovers rather than reading a synthetic "off" (Codex P2 on #56) — but
 * that same infinite retry means a one-shot `.first()` never completes on a *persistent* storage
 * error, hanging its caller. This is the single bounded seam those two read through, so neither
 * hangs and neither re-derives its own bound (Codex P2 on #56, ×2). A null is "couldn't read",
 * never "off" — each caller decides what that means for it, never treating it as a toggle-off.
 * The background worker deliberately reads plainly instead: WorkManager already bounds its
 * execution, so an unbounded read there stalls one background tick rather than anything the user
 * sees, and bounding it would race the test's virtual clock against a real DataStore read.
 */
internal suspend fun AppSettings.liveWidgetRefreshNow(
    timeoutMillis: Long = SETTINGS_READ_BOUND_MILLIS,
): Boolean? = withTimeoutOrNull(timeoutMillis) { liveWidgetRefresh().first() }

/**
 * Enqueues one refresh tick ~a minute out, REPLACE so only one is ever pending. The worker
 * re-enqueues the next tick itself, so a single call starts a self-sustaining ~1/min chain that
 * runs until [cancelWidgetRefresh]. A deferrable [OneTimeWorkRequest], not a periodic one:
 * WorkManager's periodic floor is 15 min, far coarser than the minute this needs, and it is not
 * a foreground service — so the OS runs it ~1/min while the device is active and defers it in Doze
 * rather than guaranteeing the exact minute. It is **not** screen-state-gated: with the app closed
 * there is no live component to hear screen on/off, so it can still run screen-off while charging.
 * A true screen-on-only scope (and the exact minute with the screen off) is the deferred
 * foreground-service option (mechanism A, SPEC D5, *Widget follow-ups*).
 */
internal suspend fun scheduleWidgetRefresh(context: Context) {
    // await() surfaces a WorkManager DB-write failure to the caller (all of which wrap this in a
    // try/catch) instead of discarding the Operation, which would leave the setting on with no
    // tick enqueued and nothing logged (Codex P2 on #56).
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        WIDGET_REFRESH_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay(WIDGET_REFRESH_INTERVAL_MILLIS, TimeUnit.MILLISECONDS)
            // Only wake when there's a network path — offline, the tick defers instead of
            // building a client and failing every persisted stop each minute (Codex P2 on #56).
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build(),
    ).await()
}

/** Cancels the pending refresh tick — called when the setting is turned off, and when the last
 *  widget is removed ([StopCastWidget.onDelete]). Awaited for the same reason as the enqueue. */
internal suspend fun cancelWidgetRefresh(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WIDGET_REFRESH_WORK).await()
}

/**
 * Schedules the next tick only when no tick is already enqueued or running — the idempotent
 * entry point used by everything except the worker's own end-of-cycle reschedule. Startup sync
 * and the enable toggle re-run on activity recreation (a rotation re-runs the startup
 * `LaunchedEffect`), and an unconditional [scheduleWidgetRefresh] there REPLACEs — resetting a
 * pending tick's delay and cancelling a currently-running worker, so repeated config changes could
 * postpone refresh indefinitely or abort an in-flight fetch (Codex P2 on #56). Retaining existing
 * pending/running work keeps the chain alive across recreation. The worker's own reschedule must
 * NOT use this: it is itself RUNNING when it enqueues the next tick, so an "already running" guard
 * would see itself and kill the chain — it keeps the REPLACE [scheduleWidgetRefresh]. */
private suspend fun scheduleWidgetRefreshIfNotPending(context: Context) {
    val alreadyPending = WorkManager.getInstance(context.applicationContext)
        .getWorkInfosForUniqueWorkFlow(WIDGET_REFRESH_WORK).first()
        .any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
    if (!alreadyPending) scheduleWidgetRefresh(context)
}

/** True when at least one [StopCastWidget] is installed on a host. A refresh cycle with none
 *  installed would fetch every persisted stop each minute with no surface to update, so the
 *  chain must neither run nor reschedule without one (Codex P1 on #56). */
private suspend fun anyWidgetInstalled(context: Context): Boolean =
    GlanceAppWidgetManager(context.applicationContext)
        .getGlanceIds(StopCastWidget::class.java).isNotEmpty()

/**
 * Restarts the live-refresh chain when a widget render shows a widget now exists (an add, or a
 * host rebind) and the setting is on — but only when no tick is already pending, so an active
 * chain isn't pushed back on every render. Armed from [StopCastWidget.provideGlance] (the render
 * path, like the staleness redraw): the worker retires the chain when the last widget is removed,
 * so this is what resumes it after one is re-added (Codex P1 on #56).
 */
suspend fun resumeWidgetRefreshIfEnabled(
    context: Context,
    settings: AppSettings =
        DataStoreAppSettings.from(context.applicationContext, warn = ::logAppSettingsWarning),
) {
    // Bounded one-shot read (see liveWidgetRefreshNow). This runs on the widget render path —
    // provideGlance awaits it before provideContent — so an unbounded read would hang the frame.
    // Resume is best-effort scheduler recovery, so on a slow/failing read (null) skip it and let
    // the snapshot paint; the next render retries (SPEC jank-free UI / principle 5). null is never
    // read as "off", which would wrongly retire the chain.
    if (settings.liveWidgetRefreshNow() != true) return
    scheduleWidgetRefreshIfNotPending(context)
}

/**
 * Applies the "refresh widget every minute" setting: start the chain when enabled, cancel it when
 * not. Called from the Settings toggle and once at app start (so an enabled setting resumes after
 * the process is recreated). Enabling uses the idempotent [scheduleWidgetRefreshIfNotPending] so a
 * startup re-sync on activity recreation retains an existing tick rather than resetting or aborting
 * it (Codex P2 on #56). Reboot persistence would need a boot receiver and is a deferred follow-up
 * (SPEC D5, *Widget follow-ups*).
 */
suspend fun applyWidgetRefreshSetting(context: Context, enabled: Boolean) {
    if (enabled) scheduleWidgetRefreshIfNotPending(context) else cancelWidgetRefresh(context)
}

/**
 * Re-fetches arrivals for the widget's persisted stops and saves the refreshed snapshot (which
 * pokes the widget to re-render), then schedules the next tick — the opt-in "live widget" loop
 * (SPEC D5). Reads no location (D1): it refreshes exactly the stops already in the snapshot.
 *
 * Best-effort throughout: a failed cycle keeps the last-good on the widget and still reschedules,
 * so a transient TfL error doesn't break the chain; cancellation propagates. The chain stops only
 * when the setting reads off — checked at the start (so a toggle-off retires the loop) and again
 * before rescheduling.
 */
class WidgetRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = try {
        runRefreshCycle()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A transient failure reading settings, querying installed widgets, or scheduling the next
        // tick must not silently kill the chain — an uncaught throw becomes Result.failure() with no
        // retry, leaving the loop dead until an app launch or widget render happens to restart it.
        // Ask WorkManager to retry so the chain survives (Codex P2 on #56).
        logWidgetSnapshotWarning("widget refresh worker failed: ${e::class.simpleName}")
        Result.retry()
    }

    private suspend fun runRefreshCycle(): Result {
        val settings = DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
        // A plain read, not the bounded liveWidgetRefreshNow the render path and startup sync use:
        // this is background work WorkManager already bounds by its own execution timeout, so an
        // unbounded read on a persistent storage error stalls one background tick (then WorkManager
        // retries) rather than hanging anything user-facing. If it can't be read at all the outer
        // catch turns the throw into Result.retry(); an actual retry read here never spuriously
        // times out under the test's virtual clock either.
        if (!settings.liveWidgetRefresh().first()) return Result.success() // toggled off → chain ends
        // No widget installed → nothing to refresh; retire the chain rather than fetch every
        // persisted stop each minute with no surface to update (Codex P1). A widget added later
        // resumes it via resumeWidgetRefreshIfEnabled from the render path.
        if (!anyWidgetInstalled(applicationContext)) return Result.success()
        val store = DataStoreSnapshotStore.from(applicationContext, warn = ::logWidgetSnapshotWarning)
        // No snapshot yet (before the first fetch, or after a corrupt one was discarded) → there
        // are no stops to refresh, so retire the chain rather than wake every minute doing nothing
        // (Codex P2 on #56). A later snapshot save pokes the widget (updateAll → provideGlance →
        // resumeWidgetRefreshIfEnabled), which restarts the chain. A load failure throws and the
        // outer catch turns it into Result.retry().
        val prior = store.load() ?: return Result.success()
        // The user's app_key for this refresh, read once here (SPEC D7). A snapshot is enough — the
        // worker is a one-shot background run — so unlike the app's long-lived clients it needs no
        // live provider. Keyless (null) when unset; never logged. The client reads this provider
        // once per request and drives both the app_key and the limiter's budget from that one read
        // (rateLimiterFor), so a widget-only process needn't wait for the process-wide holder to
        // warm and the budget always matches the key sent.
        val userKey = settings.userApiKey().first()
        try {
            val http = KtorTflClient.defaultHttpClient()
            try {
                val client = KtorTflClient(
                    http,
                    appKey = { userKey },
                    rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                    requestPool = SharedTflRequestPool.pool,
                )
                val refreshed = WidgetRefresh.refreshedArrivals(prior, Instant.now()) { stopId ->
                    try {
                        client.arrivals(stopId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Sanitized: a stop id is a canned identifier, not user data, but the
                        // message stays a bare fact (SPEC *Privacy* / *Error handling*).
                        logWidgetSnapshotWarning(
                            "widget refresh arrivals failed for stop $stopId: ${e::class.simpleName}",
                        )
                        null
                    }
                }
                if (refreshed != null) {
                    // Conditional save: persist and poke the widget only if the stored stop set
                    // still matches the one this cycle loaded and fetched for. In the seconds
                    // spent fetching, the app may have persisted a different set (the user
                    // relocated in-app); an unconditional save would let this slow cycle win last
                    // and stamp the old location's departures fresh over the new set. On a discard
                    // the newer in-app snapshot is already stored and has poked the widget itself
                    // (Codex P1 on #56).
                    val applied = WidgetSnapshotStore(applicationContext)
                        .saveIfStopsMatch(refreshed, prior.stops.map { it.stopId })
                    if (!applied) {
                        logWidgetSnapshotWarning(
                            "widget refresh result discarded: stop set changed during fetch",
                        )
                    }
                } else {
                    // Nothing fetched fresh: re-render so the unchanged snapshot ages honestly.
                    StopCastWidget().updateAll(applicationContext)
                }
            } finally {
                http.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget refresh cycle failed: ${e::class.simpleName}")
        }
        // Reschedule the next tick unless the setting was turned off or the last widget was removed
        // during this cycle. Best-effort even after a failure above, so a transient error doesn't
        // retire the loop. Plain read, same as the top of the cycle (background, WorkManager-bounded).
        if (settings.liveWidgetRefresh().first() && anyWidgetInstalled(applicationContext)) {
            scheduleWidgetRefresh(applicationContext)
        }
        return Result.success()
    }
}
