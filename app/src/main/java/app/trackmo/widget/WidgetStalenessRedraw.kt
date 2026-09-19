package app.trackmo.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.trackmo.domain.Staleness
import java.time.Duration as JavaDuration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException

/** Unique-work name so each scheduled redraw REPLACEs the previous one — at most one pending. */
internal const val WIDGET_STALENESS_WORK = "trackmo-widget-staleness-redraw"

/**
 * Arms (or cancels) the one-shot render-only redraw at the staleness boundary of the snapshot
 * the widget is currently rendering, so a widget left untouched after the app closes flips from
 * live-looking countdowns to the stale `?` treatment on its own (SPEC D4). With the provider's
 * `updatePeriodMillis="0"` the host never re-renders the widget, and [updateAll] is only reached
 * while the app runs — so without this, a closed-app widget can show "Updated just now" and a
 * live countdown indefinitely past the threshold.
 *
 * Called from `provideGlance` — the render path — so *every* way the widget comes to show a
 * snapshot arms the flip from the snapshot it just drew: first add, host rebind, and the app's
 * `updateAll` after a fetch (which re-runs `provideGlance`). It follows that a host with no
 * widget never runs this, so a widgetless user pays no scheduling or wake cost — no separate
 * installed-id guard needed. [snapshotFetchedAt] is null for the no-snapshot empty state (no
 * stamp or countdown to age), which cancels any pending wake.
 *
 * One deferrable, unique wake per snapshot (REPLACE), fired at most once and never rescheduling
 * once stale (the boundary redraw re-renders, finds the snapshot already stale, and cancels) —
 * negligible battery, not a polling cadence (a live *refresh* cadence stays deferred, SPEC D5).
 */
internal fun scheduleStalenessRedrawFor(context: Context, snapshotFetchedAt: Instant?, now: Instant) {
    val remaining = if (snapshotFetchedAt == null) {
        Duration.ZERO
    } else {
        Staleness.remainingUntilStale(JavaDuration.between(snapshotFetchedAt, now).toKotlinDuration())
    }
    applyStalenessRedrawPlan(WorkManager.getInstance(context.applicationContext), remaining)
}

/**
 * The scheduling decision over WorkManager, split out to be testable without a Glance host:
 * enqueue the one-shot redraw when the snapshot is not yet stale ([remaining] > 0); otherwise do
 * nothing.
 *
 * It deliberately does **not** cancel on the stale ([remaining] == 0) branch. The boundary redraw
 * itself calls `updateAll`, which re-enters `provideGlance` and reaches here with zero remaining —
 * cancelling `WIDGET_STALENESS_WORK` there would cancel the very worker that is running, before its
 * `provideContent` commits, leaving the old live-looking countdowns installed. Nothing needs
 * cancelling in the stale case anyway: a fired one-shot is already consumed, and the next fresh
 * render REPLACEs it. Cancelling when the widget is *removed* is [cancelWidgetStalenessRedraw],
 * driven from `onDelete`, not from a render.
 */
internal fun applyStalenessRedrawPlan(workManager: WorkManager, remaining: Duration) {
    if (remaining == Duration.ZERO) return
    workManager.enqueueUniqueWork(
        WIDGET_STALENESS_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetStalenessWorker>()
            .setInitialDelay(remaining.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .build(),
    )
}

/** Cancels a pending staleness redraw — called when the last widget instance is removed. */
internal fun cancelWidgetStalenessRedraw(context: Context) {
    WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WIDGET_STALENESS_WORK)
}

/**
 * Enqueues an immediate render-only redraw with the same retry backstop as the boundary redraw.
 * Used after clearing the snapshot for a new nearby set: if the inline `updateAll` throws, the
 * previous area's RemoteViews would otherwise stay visible with no self-repair (a later load sees
 * the cleared null and won't re-clear), so [WidgetStalenessWorker] re-renders the now-empty
 * snapshot and retries on WorkManager's backoff. REPLACEs any pending boundary redraw — after a
 * clear the old snapshot's staleness boundary is moot.
 */
internal fun enqueueWidgetRedrawNow(context: Context) {
    WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
        WIDGET_STALENESS_WORK,
        ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetStalenessWorker>().build(),
    )
}

/**
 * Re-renders the widget from the unchanged persisted snapshot; `provideGlance` recomputes the age
 * against the current clock, so a boundary crossing now shows the stale treatment (`?`, "tap to
 * refresh"). Render-only — reads no network and writes no snapshot.
 */
class WidgetStalenessWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result =
        try {
            TrackmoWidget().updateAll(applicationContext)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning(
                "widget staleness redraw failed (attempt ${runAttemptCount + 1}): ${e::class.simpleName}",
            )
            // This is the one boundary wake and it is honesty-critical (SPEC D4), so retry a few
            // times on WorkManager's backoff rather than consuming it on a transient failure; give
            // up after a bound so a persistently failing redraw doesn't loop forever (the next
            // fetch or host rebind will re-render regardless). Sanitized log (SPEC Privacy).
            if (runAttemptCount < MAX_REDRAW_ATTEMPTS) Result.retry() else Result.success()
        }

    private companion object {
        const val MAX_REDRAW_ATTEMPTS = 5
    }
}
