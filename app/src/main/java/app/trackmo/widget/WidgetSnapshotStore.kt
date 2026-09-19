package app.trackmo.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.trackmo.data.DataStoreSnapshotStore
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.SnapshotStore
import kotlinx.coroutines.CancellationException

/**
 * Feeds the widget without changing the in-app view. [save] writes the app's last-good
 * snapshot to the shared [DataStoreSnapshotStore] (the file the widget reads) and pokes the
 * widget to re-render; [load] returns null so the in-app `MainViewModel` does **not** restore
 * it. That asymmetry is deliberate: the in-app nearby set is location-derived, and restoring a
 * previous location's stops under a newly-resolved set would mislead — the reason `MainActivity`
 * used `SnapshotStore.NONE`. This keeps that in-app behavior while still giving the widget data.
 * Interim until Phase 2's user-chosen watched stops make the persisted set meaningful to
 * restore in-app too.
 */
class WidgetSnapshotStore(context: Context) : SnapshotStore {
    private val appContext = context.applicationContext
    // Wire the sanitized warn sink so a discarded corrupt snapshot file is logged rather than
    // silently dropped. from() keeps the first caller's sink, so the widget's provideGlance
    // passes the same one — whichever initializes the singleton first, corruption is traced.
    private val delegate = DataStoreSnapshotStore.from(appContext, warn = ::logWidgetSnapshotWarning)

    override suspend fun load(): DeparturesSnapshot? = null

    /**
     * Scopes the widget to the current nearby set: atomically clears the persisted snapshot when
     * it describes a different set than [resolvedStopIds] (or the location resolved to no stops),
     * then redraws the widget so it goes blank rather than showing a previous area's departures as
     * if live (SPEC principle 1). A no-op when the set is unchanged, so returning to it doesn't
     * flicker. The compare-and-clear is one DataStore transaction (see
     * [DataStoreSnapshotStore.clearIfStopSetNot]) so a concurrent authoritative save of the new
     * set isn't clobbered.
     */
    suspend fun clearForNewStopSet(resolvedStopIds: Set<String>) {
        // Whole thing is best-effort: the caller invokes this straight from a LaunchedEffect, so
        // a DataStore read/write failure here must not escape and cancel composition or crash the
        // app — scoping the widget is secondary to showing the app (Codex). Rethrow cancellation;
        // log anything else sanitized and leave the widget as-is (it re-renders on the next
        // successful save or host rebind).
        val cleared = try {
            delegate.clearIfStopSetNot(resolvedStopIds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget snapshot clear check failed: ${e::class.simpleName}")
            return
        }
        if (!cleared) return
        // The snapshot is already cleared, so a redraw failure can't surface as anything — but it
        // also can't be dropped, or the previous area's RemoteViews stay visible with no
        // self-repair (a later load sees null and won't re-clear). Log sanitized and hand the
        // redraw to the retrying worker so the blank isn't lost.
        try {
            TrackmoWidget().updateAll(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget redraw after snapshot clear failed: ${e::class.simpleName}")
            enqueueWidgetRedrawNow(appContext)
        }
    }

    override suspend fun save(snapshot: DeparturesSnapshot) {
        // The primary operation: persist the last-good snapshot. Its failure propagates to the
        // caller, which reports it as a real save failure.
        delegate.save(snapshot)
        // The widget redraw is secondary and best-effort — the snapshot is already committed, so
        // a redraw failure must NOT surface as a save failure (the caller would log "snapshot
        // save failed" for a save that in fact succeeded). Log it separately, sanitized, and
        // swallow; the widget re-renders on the next successful save or host refresh. Rethrow
        // cancellation first so structured concurrency isn't broken.
        // Pokes the widget to re-render with the new snapshot. That updateAll re-runs
        // provideGlance, which arms the one-shot staleness-boundary redraw for the app-closed
        // case (SPEC D4) — so scheduling lives on the render path, not here. Best-effort: a
        // redraw failure must not surface as a save failure; cancellation is rethrown.
        try {
            TrackmoWidget().updateAll(appContext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget redraw after snapshot save failed: ${e::class.simpleName}")
        }
    }
}
