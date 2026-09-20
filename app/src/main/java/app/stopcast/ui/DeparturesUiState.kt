package app.stopcast.ui

import app.stopcast.domain.LineStatus
import app.stopcast.domain.StopArrivals
import java.time.Instant

/**
 * What `MainScreen` renders — a snapshot, never the network. The screen only ever
 * reads one of these; the fetch happens off the render path in the ViewModel and
 * swaps a new value in when it completes (SPEC staleness contract / D4). Rendering
 * from a value like this is what lets the screen appear at once with a stamp or a
 * placeholder rather than blocking the first frame on a request.
 */
sealed interface DeparturesUiState {
    /** No snapshot yet — show a placeholder, not a blank screen (SPEC principle 5). */
    data object Loading : DeparturesUiState

    /**
     * The last-good snapshot: the raw [stops], each stamped with its own fetch age. The
     * screen groups them into rows against the *current* clock, not fetch time, so a
     * departed service leaves the list and the countdowns stay honest as time advances
     * between fetches (SPEC D4). Once a stop's fetch is [Staleness]-stale the screen
     * withholds *that stop's* countdowns rather than show numbers that are probably wrong,
     * per stop — a stop that failed to refresh goes to "—" while a fresh one beside it
     * stays live. [fetchedAt] is the freshest stop's age, for the whole-screen "updated N
     * ago" stamp; per-row staleness reads each stop's own age.
     *
     * A snapshot with nothing upcoming is a real state — distinct from [Error] — and the
     * screen says so. [partialRefresh] is true when some stops refreshed but at least one
     * couldn't and was kept at its older age: the list mixes fresh and aged stops, so the
     * screen flags it rather than passing a mixed-age list off as one fresh whole (SPEC
     * principle 2). [refreshFailure] is set when a later refresh got *nothing* fresh and
     * this whole aged snapshot was kept — the screen shows the failure explicitly rather
     * than passing stale rows off as fresh, and it clears on the next refresh that gets
     * anything.
     *
     * [lineStatuses] carries the disruptions found for the shown lines (keyed by line id,
     * disrupted lines only), so a delayed or suspended line's rows are marked rather than
     * shown as trustworthy (SPEC *Disruptions* / D3). [disruptionUnknown] is true when the
     * status lookup itself failed while arrivals succeeded: the disruption state of these
     * departures was never checked, so the screen says so rather than pass them off as
     * verified-clean (SPEC *Disruptions*).
     */
    data class Loaded(
        val stops: List<StopArrivals>,
        val fetchedAt: Instant,
        val partialRefresh: Boolean = false,
        val refreshFailure: Error.Kind? = null,
        val lineStatuses: Map<String, LineStatus> = emptyMap(),
        val disruptionUnknown: Boolean = false,
    ) : DeparturesUiState

    /**
     * The fetch failed with no snapshot to fall back on, shown honestly rather than as
     * an empty or stale list (SPEC principles 1–2). Once persistence lands (Phase 1's
     * snapshot item) a failure keeps showing the aged last-good data instead.
     */
    data class Error(val kind: Kind) : DeparturesUiState {
        enum class Kind { OFFLINE, RATE_LIMITED, UNREACHABLE }
    }
}
