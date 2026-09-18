package app.trackmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/** A stop to show, until Phase 2's watched-stops persistence replaces the seed set. */
data class StopRef(val id: String, val name: String)

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
            val fetched = mutableListOf<StopArrivals>()
            var firstError: Throwable? = null
            for (stop in seedStops) {
                try {
                    val departures = withContext(io) { client.arrivals(stop.id) }
                    fetched += StopArrivals(stop.id, stop.name, departures)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstError == null) firstError = e
                    warn("arrivals fetch failed for stop ${stop.id}: ${reason(e)}")
                }
            }
            _state.value = when {
                fetched.isNotEmpty() ->
                    // Grouping into rows is the screen's job, recomputed from the live
                    // clock (SPEC D4) — the snapshot is just the raw stops as fetched.
                    DeparturesUiState.Loaded(
                        stops = fetched,
                        fetchedAt = clock(),
                        partialRefresh = firstError != null,
                    )
                // Nothing came back and nothing failed → there were no stops to fetch
                // (no watched stops yet, or the seed is empty). That's an empty list, not
                // a network error — TfL was never contacted.
                firstError == null -> DeparturesUiState.Loaded(stops = emptyList(), fetchedAt = clock())
                // A refresh that fails outright keeps the aged last-good snapshot rather
                // than blanking the departures the user was reading — but carries the
                // failure so the screen says "couldn't refresh" explicitly, not silently
                // (SPEC D4 / principle 2). Cleared by the next successful refresh above.
                previous is DeparturesUiState.Loaded ->
                    previous.copy(refreshFailure = kindOf(firstError))
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
