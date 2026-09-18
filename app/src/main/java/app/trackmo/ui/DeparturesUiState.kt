package app.trackmo.ui

import app.trackmo.domain.StopArrivals
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
     * The last-good snapshot: the raw [stops] fetched at [fetchedAt]. The screen groups
     * them into rows against the *current* clock, not [fetchedAt], so a departed service
     * leaves the list and the countdowns and "updated N ago" stamp stay honest as time
     * advances between fetches (SPEC D4). Once the fetch is [Staleness]-stale the screen
     * withholds the countdowns rather than show numbers that are probably wrong.
     *
     * A snapshot with nothing upcoming is a real state — distinct from [Error] — and the
     * screen says so. [partialRefresh] is true when some stops came back but at least one
     * failed: the rows shown are incomplete, so the screen flags it rather than passing an
     * incomplete list off as the whole picture (SPEC principle 2). [refreshFailure] is set
     * when a later refresh failed outright and this aged snapshot was kept — the screen
     * shows the failure explicitly rather than passing stale rows off as fresh, and it
     * clears on the next successful refresh.
     */
    data class Loaded(
        val stops: List<StopArrivals>,
        val fetchedAt: Instant,
        val partialRefresh: Boolean = false,
        val refreshFailure: Error.Kind? = null,
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
