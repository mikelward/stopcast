package app.trackmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackmo.domain.LineRef
import app.trackmo.domain.LineStatus
import app.trackmo.domain.Snapshot
import app.trackmo.domain.StopArrivals
import app.trackmo.domain.TflClient
import app.trackmo.domain.TflException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A stop to show, until Phase 2's watched-stops persistence replaces the seed set.
 * [lines] is the stop's served lines, carried so a disrupted line with no predictions
 * still surfaces as a status row (SPEC *Departures*); empty means only predicted lines
 * are known for the stop.
 */
data class StopRef(val id: String, val name: String, val lines: List<LineRef> = emptyList())

/**
 * Owns the departures snapshot the screen renders (SPEC staleness contract): the fetch
 * runs off the main thread in [viewModelScope]; the screen only ever reads [state].
 * A failed stop is logged and skipped so one bad stop doesn't blank the others; only
 * when *every* stop fails does the screen show an [DeparturesUiState.Error], mapped
 * from the failure so it reads honestly (offline / rate-limited / can't-reach-TfL)
 * rather than as an empty list (SPEC principles 1–2).
 *
 * [clock] and [io] are injected so the state machine is JVM-testable with a fixed clock
 * and a test dispatcher; [warn] is the sanitized failure log (the shared on-device
 * logger lands with its own Phase 1 item — until then this is the seam it plugs into).
 */
class MainViewModel(
    private val client: TflClient,
    private val seedStops: List<StopRef>,
    private val clock: () -> Instant = Instant::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // No-op by default: the shared on-device logger is deferred until `docs/PRIVACY.md`
    // describes what it carries (both are their own Phase 1 items), so nothing is logged
    // in production until then. The seam stays for tests and that later wiring.
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<DeparturesUiState>(DeparturesUiState.Loading)
    val state: StateFlow<DeparturesUiState> = _state.asStateFlow()

    // Drives the pull-to-refresh indicator (SPEC D6); true only while a fetch is in flight.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private var fetchJob: Job? = null

    init {
        refresh()
    }

    /** Re-fetch every seed stop and swap in a fresh snapshot; safe to call repeatedly. */
    fun refresh() {
        fetchJob?.cancel()
        val previous = _state.value
        // Keep the last-good list on screen while refreshing; only show the spinner
        // when there's nothing yet, so a manual refresh doesn't flash a blank screen.
        if (previous !is DeparturesUiState.Loaded) {
            _state.value = DeparturesUiState.Loading
        }
        _refreshing.value = true
        val job = viewModelScope.launch {
            // Stamp each stop from the START of the fetch, not after the request chain, so
            // a slow TfL or many stops can't report the oldest departures as "just updated"
            // or push the staleness cutoff out by the chain's duration (SPEC D4). Merging
            // into the prior snapshot per stop is what keeps a failed stop's aged rows
            // rather than dropping the stop wholesale, so each stop carries its own age.
            val now = clock()
            val prior = (previous as? DeparturesUiState.Loaded)?.stops.orEmpty()
                .associateBy { it.stopId }
            val merged = mutableListOf<StopArrivals>()
            var firstError: Throwable? = null
            var anyArrivalsFailed = false
            // True once any request returned fresh data (arrivals or disruption, any stop):
            // the difference between a partial refresh (keep the fresh, age the rest) and a
            // total failure (nothing new — keep the whole aged snapshot and say so).
            var anyFreshData = false
            var disruptionUnknown = false

            for (stop in seedStops) {
                val departures = try {
                    withContext(io) { client.arrivals(stop.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                    anyArrivalsFailed = true
                    warn("arrivals fetch failed for stop ${stop.id}: ${reason(e)}")
                    null
                }
                // Fetch the stop's disruption independently of its arrivals (a closure, a
                // moved stop), so a closed stop is flagged rather than shown with
                // catchable-looking departures — and a stop whose *arrivals* failed still
                // surfaces its available closure rather than dropping out entirely (SPEC
                // *Disruptions*). Per stop (the endpoint scopes to it), off the render path.
                // A lookup that fails falls back to the aged disruption and flags the state
                // unknown rather than passing the stop off as verified-clear.
                val disruptions = try {
                    withContext(io) { client.stopDisruptions(stop.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                    disruptionUnknown = true
                    warn("stop disruption fetch failed for stop ${stop.id}: ${reason(e)}")
                    null
                }
                if (departures != null || disruptions != null) anyFreshData = true
                Snapshot.mergeStop(
                    stopId = stop.id,
                    stopName = stop.name,
                    lines = stop.lines,
                    freshDepartures = departures,
                    freshDisruptions = disruptions,
                    prior = prior[stop.id],
                    now = now,
                )?.let { merged += it }
            }

            // Check the status of every line we're about to show, so a disrupted line is
            // marked rather than its countdowns shown as trustworthy (SPEC *Disruptions* /
            // D3). One batched request, off the arrivals path. The set is the stops'
            // declared lines PLUS every predicted line: the declared lines cover a
            // suspended line that returned no predictions (so it can surface as a status
            // row), and the predicted set catches anything a stop didn't declare. A lookup
            // that fails leaves the arrivals shown but flags them "status unknown" rather
            // than passing them off as verified-clean.
            var lineStatuses = emptyMap<String, LineStatus>()
            if (merged.isNotEmpty()) {
                val predictedLineIds = merged.flatMap { it.departures }.map { it.lineId }
                val declaredLineIds = merged.flatMap { it.lines }.map { it.id }
                val lineIds = (predictedLineIds + declaredLineIds)
                    .filterTo(mutableSetOf()) { it.isNotBlank() }
                // A departure whose line TfL didn't identify (blank id) can't have its
                // status checked, so its presence alone leaves the disruption state
                // unknown — never shown as verified-clean (SPEC principle 1). This also
                // covers the all-blank case, where no status request is made at all.
                if (predictedLineIds.any { it.isBlank() }) disruptionUnknown = true
                if (lineIds.isNotEmpty()) {
                    try {
                        val statuses = withContext(io) { client.lineStatuses(lineIds) }
                        lineStatuses = statuses.filter { it.disrupted }.associateBy { it.lineId }
                        // A line TfL returned no determinable status for is unknown, not
                        // clean — flag it so those rows aren't shown as verified-clean
                        // (the client drops such lines, so they're absent here).
                        val determined = statuses.mapTo(mutableSetOf()) { it.lineId }
                        if (lineIds.any { it !in determined }) disruptionUnknown = true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        disruptionUnknown = true
                        warn("line status fetch failed for ${lineIds.joinToString(",")}: ${reason(e)}")
                    }
                }
            }

            _state.value = when {
                merged.isNotEmpty() ->
                    // Grouping into rows is the screen's job, recomputed from the live
                    // clock (SPEC D4) — the snapshot is the merged stops, each at its age.
                    DeparturesUiState.Loaded(
                        stops = merged,
                        // The whole-screen "last updated" stamp is the freshest stop's age;
                        // per-row withhold uses each stop's own age (SPEC D4).
                        fetchedAt = merged.maxOf { it.fetchedAt },
                        // Some stops shown are fresh and at least one couldn't be refreshed
                        // (kept aged) — say so, rather than pass a mixed-age list off as one
                        // fresh whole. On a total failure (nothing fresh) the merged snapshot
                        // is the prior one unchanged, so inherit its partial flag rather than
                        // clearing it — an already-incomplete list stays incomplete, and that
                        // warning must not be dropped just because the refresh also failed.
                        partialRefresh =
                            if (anyFreshData) {
                                anyArrivalsFailed
                            } else {
                                (previous as? DeparturesUiState.Loaded)?.partialRefresh == true
                            },
                        // Nothing fresh came back at all (every request failed) but a prior
                        // snapshot was kept — carry the failure so the screen says "couldn't
                        // refresh" rather than passing the aged rows off as fresh (SPEC D4 /
                        // principle 2). Cleared by the next refresh that gets anything.
                        refreshFailure = if (!anyFreshData && firstError != null) kindOf(firstError) else null,
                        lineStatuses = lineStatuses,
                        disruptionUnknown = disruptionUnknown,
                    )
                // Nothing came back and nothing failed → there were no stops to fetch
                // (no watched stops yet, or the seed is empty). That's an empty list, not
                // a network error — TfL was never contacted.
                firstError == null -> DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = now)
                // Every stop failed on a first load with no prior snapshot to fall back on
                // → an honest error, not an empty or stale list (SPEC principles 1–2).
                else -> DeparturesUiState.Error(kindOf(firstError))
            }
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
    }

    private fun reason(e: Throwable): String =
        (e as? TflException)?.message ?: e::class.simpleName.orEmpty()

    private fun kindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}
