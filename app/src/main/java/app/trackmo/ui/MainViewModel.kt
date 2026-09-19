package app.trackmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.LineRef
import app.trackmo.domain.LineStatus
import app.trackmo.domain.Snapshot
import app.trackmo.domain.SnapshotStore
import app.trackmo.domain.StarredRow
import app.trackmo.domain.StarredRowSet
import app.trackmo.domain.StarredRowsStore
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
    // Persists the last-good snapshot across sessions and to the widget. No-op by default so
    // tests and an unwired build run identically minus the restore.
    private val snapshotStore: SnapshotStore = SnapshotStore.NONE,
    // Persists which rows the user has starred (the ranking overlay). No-op by default, so
    // tests and an unwired build run identically minus starring.
    private val starredStore: StarredRowsStore = StarredRowsStore.NONE,
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

    // The starred set the screen pins to the top (SPEC D8). Collected from the store so a
    // toggle re-orders the list at once; an Unavailable set (a newer-version file this build
    // can't read) pins nothing rather than guessing — the stars are preserved on disk.
    private val _starred = MutableStateFlow<Set<StarredRow>>(emptySet())
    val starred: StateFlow<Set<StarredRow>> = _starred.asStateFlow()

    // Whether the star control should be offered at all. Starts false — the store's first
    // read hasn't arrived, so we don't yet know which rows are starred; enabling the control
    // before then would show a persisted-starred row as unstarred with a "Pin to top" action,
    // and a tap in that window would toggle the real persisted membership *off* (SPEC
    // principle 2). It turns true on the first [StarredRowSet.Loaded]. It stays false when the
    // stored set is a newer-schema file this build can't read ([StarredRowSet.Unavailable]) or
    // when the read flow fails: the stars exist (or their state is unknown) but we can't show
    // which rows are starred, so the screen hides the control rather than rendering every star
    // unfilled — the false "nothing is starred" claim [StarredRowSet.Unavailable] exists to
    // prevent — on a control whose taps would be a no-op or unsafe anyway.
    private val _starringAvailable = MutableStateFlow(false)
    val starringAvailable: StateFlow<Boolean> = _starringAvailable.asStateFlow()

    // Set when a star write failed (storage full, an IO error) so the screen can show a
    // transient message — a tap that didn't take otherwise reads as the app being broken
    // (SPEC principle 2: do the safe thing and say so). An *acknowledged* StateFlow, not a
    // one-shot event: the ViewModel outlives a configuration change, so the flag survives a
    // rotation that happens between the failed tap and the screen showing the message (a
    // replay-0 event would be lost in that gap). The screen calls [starWriteFailureShown]
    // once it has surfaced it, which clears the flag so it isn't shown again.
    private val _starWriteFailed = MutableStateFlow(false)
    val starWriteFailed: StateFlow<Boolean> = _starWriteFailed.asStateFlow()

    private var fetchJob: Job? = null

    init {
        viewModelScope.launch {
            // A read failure (DataStore IOException, a non-corruption disk error) must not
            // escape and crash the departures screen as it starts. Handle it explicitly:
            // rethrow cancellation (structured concurrency), log sanitized, and leave starring
            // in an honest unavailable state (control hidden, nothing pinned) — the same shape
            // as an Unavailable set, since a failed read equally means we can't say which rows
            // are starred (SPEC principle 2 / error-handling rule).
            try {
                starredStore.starred().collect { set ->
                    when (set) {
                        is StarredRowSet.Loaded -> {
                            _starred.value = set.starred
                            _starringAvailable.value = true
                        }
                        StarredRowSet.Unavailable -> {
                            _starred.value = emptySet()
                            _starringAvailable.value = false
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _starred.value = emptySet()
                _starringAvailable.value = false
                warn("starred set read failed: ${reason(e)}")
            }
        }
        // Show the persisted last-good at once (a stamped placeholder, aged), then refresh.
        // The read is off the main thread and the first frame is already the Loading
        // placeholder, so nothing blocks on the DataStore read (SPEC snapshot-render). The
        // restored snapshot becomes the `prior` the refresh merges into, so a stop that then
        // fails to refresh keeps its aged rows rather than dropping out.
        viewModelScope.launch {
            val restored = try {
                withContext(io) { snapshotStore.load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("snapshot restore failed: ${reason(e)}")
                null
            }
            if (restored != null && _state.value is DeparturesUiState.Loading) {
                _state.value = restoredLoaded(restored)
            }
            refresh()
        }
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
            // Prior to merge into, plus whether it was already incomplete. The in-memory
            // last-good if we have one (its partialRefresh is already accurate), else the
            // persisted snapshot read from disk (completeness derived the same way the init
            // restore does). Falling back to the store — not just the in-memory state — means
            // a refresh that runs before (or races) the init restore, e.g. a manual refresh
            // during the disk read, still merges into the last-good and keeps aged rows on
            // failure, rather than falling to an Error that discards data still valid on disk
            // (SPEC principle 2). Carrying the completeness too keeps a total-failure refresh
            // from clearing the "some stops couldn't be refreshed" warning on an
            // already-incomplete snapshot recovered from the store.
            val priorLoaded = previous as? DeparturesUiState.Loaded
            val priorStops: List<StopArrivals>
            val priorPartial: Boolean
            if (priorLoaded != null) {
                priorStops = priorLoaded.stops
                priorPartial = priorLoaded.partialRefresh
            } else {
                val loaded = try {
                    withContext(io) { snapshotStore.load() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot restore failed: ${reason(e)}")
                    null
                }
                priorStops = loaded?.stops ?: emptyList()
                priorPartial = loaded != null && isIncomplete(loaded.stops)
                // Show the aged last-good at once rather than holding the spinner through the
                // whole fetch: this branch runs only when the state wasn't Loaded (a refresh
                // that raced or replaced the init restore), and if the network then hangs a
                // Loading spinner would hide valid data already read from disk (SPEC principle
                // 5). Same construction as the init restore, so both restore paths reach the
                // screen identically. The fetch below then replaces it.
                if (loaded != null) {
                    _state.value = restoredLoaded(loaded)
                }
            }
            val prior = priorStops.associateBy { it.stopId }
            val merged = mutableListOf<StopArrivals>()
            var firstError: Throwable? = null
            var anyArrivalsFailed = false
            // True once any request returned fresh data (arrivals or disruption, any stop):
            // the difference between a partial refresh (keep the fresh, age the rest) and a
            // total failure (nothing new — keep the whole aged snapshot and say so).
            var anyFreshData = false
            // True once any stop's ARRIVALS returned (fresh durable content). Distinct from
            // anyFreshData because disruptions aren't persisted: a cycle where every arrivals
            // request failed but a disruption returned has nothing durable to save, so it must
            // not overwrite a complete saved snapshot with carried arrivalsFresh=false rows.
            var anyFreshArrivals = false
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
                if (departures != null) anyFreshArrivals = true
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
                val blankLineIdCount = predictedLineIds.count { it.isBlank() }
                if (blankLineIdCount > 0) {
                    disruptionUnknown = true
                    // Name the reason so a persistent "couldn't check for disruptions" is
                    // diagnosable: a count of unidentifiable predictions, no user data.
                    warn("disruption status unknown: $blankLineIdCount prediction(s) had no line id to check")
                }
                if (lineIds.isNotEmpty()) {
                    try {
                        val statuses = withContext(io) { client.lineStatuses(lineIds) }
                        lineStatuses = statuses.filter { it.disrupted }.associateBy { it.lineId }
                        // A line TfL returned no determinable status for is unknown, not
                        // clean — flag it so those rows aren't shown as verified-clean
                        // (the client drops such lines, so they're absent here).
                        val determined = statuses.mapTo(mutableSetOf()) { it.lineId }
                        val undetermined = lineIds.filterNot { it in determined }
                        if (undetermined.isNotEmpty()) {
                            disruptionUnknown = true
                            // Name the specific lines so a persistent "couldn't check for
                            // disruptions" is diagnosable — a line id is a canned identifier,
                            // not user data (SPEC *Privacy*: line ids are allowed in the log).
                            warn("disruption status unknown: TfL returned no status for line(s) ${undetermined.joinToString(",")}")
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        disruptionUnknown = true
                        warn("line status fetch failed for ${lineIds.joinToString(",")}: ${reason(e)}")
                    }
                }
            }

            val newState = when {
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
                        // priorPartial carries that flag whether the prior was in-memory or
                        // recovered from the store, so a store-recovered incomplete snapshot
                        // stays flagged too.
                        partialRefresh =
                            if (anyFreshData) {
                                anyArrivalsFailed
                            } else {
                                priorPartial
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
            _state.value = newState

            // Persist the new last-good so a later launch — and the widget — render it before
            // any fetch, but only when this cycle was authoritative: it returned fresh
            // ARRIVALS (the durable content), or it was the authoritative *empty* (no stops to
            // fetch — e.g. the watched list was emptied), which must overwrite a now-obsolete
            // saved snapshot rather than leaving removed stops on disk for the next launch and
            // the widget to resurrect. A cycle with no fresh arrivals — a total failure, or
            // one where only a disruption returned (disruptions aren't persisted) — has no
            // durable content to save, and saving it would rewrite every stop to
            // `arrivalsFresh = false` and so degrade a previously-complete saved snapshot into
            // one that restores as partial. Best-effort, off the render path.
            val authoritative = anyFreshArrivals || (merged.isEmpty() && firstError == null)
            if (newState is DeparturesUiState.Loaded && authoritative) {
                try {
                    withContext(io) {
                        snapshotStore.save(DeparturesSnapshot(newState.stops, newState.fetchedAt))
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot save failed: ${reason(e)}")
                }
            }
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
    }

    /**
     * Flip [row]'s star (SPEC D8) — pin it to the top of the list, or unpin it — and persist
     * the change. Off the main thread; the [starred] flow re-emits from the store, so the list
     * re-orders without this touching UI state directly. Best-effort: a write failure is logged
     * and the set is unchanged (a preserved [StarredRowSet.Unavailable] is a no-op in the store).
     */
    fun toggleStar(row: DepartureRow) {
        viewModelScope.launch {
            try {
                withContext(io) { starredStore.toggle(StarredRow.of(row)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("star toggle failed: ${reason(e)}")
                // The write didn't take and the store won't re-emit, so the star silently
                // stays as it was — tell the user rather than let the tap look broken.
                _starWriteFailed.value = true
            }
        }
    }

    /** Called by the screen once it has surfaced the star-write failure, so it isn't shown again. */
    fun starWriteFailureShown() {
        _starWriteFailed.value = false
    }

    /**
     * Whether a saved snapshot is incomplete relative to [seedStops] — a seed stop is missing
     * (an earlier partial refresh saved only the stops that succeeded), or a stop is present
     * but was carried-forward-stale when saved (`arrivalsFresh == false`), so the snapshot is
     * mixed-age. Either way it's shown as partial rather than passed off as a complete,
     * uniformly-fresh whole (SPEC principle 2). A restored stop is in exactly one of three
     * states — present-and-fresh, present-and-carried-stale, or missing — so this is the
     * complete incompleteness test.
     */
    private fun isIncomplete(stops: List<StopArrivals>): Boolean =
        seedStops.any { seed -> stops.none { it.stopId == seed.id } } ||
            stops.any { !it.arrivalsFresh }

    /**
     * The aged last-good [DeparturesUiState.Loaded] to show from a restored [snapshot] before
     * any network. A saved snapshot can be incomplete (a missing stop, or a carried-stale
     * one), shown as partial rather than passed off as a complete, fresh whole (see
     * [isIncomplete]). It hasn't been status-checked, so it's flagged disruption-unknown — a
     * service suspended since the snapshot isn't shown as normal until the refresh
     * re-establishes status (SPEC principle 1); stop closures aren't persisted at all
     * (point-in-time; see PersistedSnapshot), so there are none to resurrect. Both restore
     * paths — init, and a refresh that beats or replaces it — build the state here, so the
     * aged snapshot always reaches the screen the same way (SPEC principle 5). The immediate
     * refresh recomputes everything once it completes.
     */
    private fun restoredLoaded(snapshot: DeparturesSnapshot): DeparturesUiState.Loaded =
        DeparturesUiState.Loaded(
            stops = snapshot.stops,
            fetchedAt = snapshot.fetchedAt,
            partialRefresh = isIncomplete(snapshot.stops),
            disruptionUnknown = true,
        )

    private fun reason(e: Throwable): String =
        (e as? TflException)?.message ?: e::class.simpleName.orEmpty()

    private fun kindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}
