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
