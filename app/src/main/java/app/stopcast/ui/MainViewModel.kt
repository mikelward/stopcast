package app.stopcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.LineRef
import app.stopcast.domain.LineStatus
import app.stopcast.domain.NearbySelection
import app.stopcast.domain.Snapshot
import app.stopcast.domain.SnapshotStore
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StarredRowSet
import app.stopcast.domain.StarredRowsStore
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.TflClient
import app.stopcast.domain.TflException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
data class StopRef(
    val id: String,
    val name: String,
    val lines: List<LineRef> = emptyList(),
    // The stop's cluster (TfL `stationNaptan` else display name) for per-place grouping (SPEC D8);
    // blank groups the stop alone. Set from the nearby lookup ([StopLocation.clusterId]).
    val clusterId: String = "",
    // The stop's interchange (TfL `hubNaptanCode`, [StopLocation.hubId]): `HUBKGX` ties King's
    // Cross and St Pancras. Blank for a stop in no hub. When the stop has a disruption, its hub
    // name is resolved so the near-me alert titles by the interchange (SPEC *Disruptions*).
    val hubId: String = "",
)

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
    seedStops: List<StopRef>,
    // The farther "more" clusters (SPEC *Finding stops → Near me now*), paged in on a per-mode
    // "More" tap. Empty for a watched-stops view or a nearby set with nothing beyond the eager tier.
    initialMore: List<NearbySelection.NearbyCluster> = emptyList(),
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
    // Best-effort widget redraw: poked after a star toggle (so the widget's pinned order updates
    // at once — SPEC D8) AND after a completed refresh that did NOT save (a failed or aged-only
    // cycle), so the static RemoteViews recompute the snapshot's age from the current clock and
    // withhold stale countdowns (SPEC D4) rather than freezing at the last save's stamp. Doesn't
    // persist anything. No-op by default; MainActivity supplies the widget update.
    private val redrawWidget: suspend () -> Unit = {},
) : ViewModel() {
    // The near-me tiers, updatable IN PLACE so a relocation that keeps the same nearby set can
    // reconcile them without rebuilding this ViewModel (which would drop a revealed expansion —
    // the redesign this reveal is for). The eager tier is fetched and shown at once; a `more`
    // cluster's stops join the fetched set once its key is revealed.
    private var eagerStops: List<StopRef> = seedStops
    private var more: List<NearbySelection.NearbyCluster> = initialMore
    private var revealedKeys: Set<String> = emptySet()

    // The set actually fetched and shown: the eager tier plus every revealed `more` cluster's
    // stops. DERIVED — so a cluster dropped on a relocation leaves the fetched set automatically,
    // with no parallel list to fall out of sync (the single-owner reveal design).
    private val fetchedStops: List<StopRef>
        get() = eagerStops + more.asSequence()
            .filter { it.key in revealedKeys }
            .flatMap { cluster -> cluster.stops.asSequence().map { it.toStopRef() } }
            .toList()

    // The "More" buttons to offer: the modes (or the generic bucket) that still have an unrevealed
    // `more` cluster (SPEC *Finding stops → Near me now*). Empty when nothing is left to page.
    private val _moreState = MutableStateFlow(NearbySelection.revealableBuckets(initialMore, emptySet()))
    val moreState: StateFlow<Set<String>> = _moreState.asStateFlow()

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

    // Resolved interchange display names (hubId → name), so a hub with a disruption is looked up
    // once and reused across refreshes and across the stops sharing it (King's Cross and St
    // Pancras both resolve `HUBKGX` from one call). Only a real name is cached here — a failed or
    // blank lookup is retried on a later refresh rather than pinned. Within a single refresh a
    // failure is memoized separately (see `resolveHubName`), so a failing hub is not re-requested
    // once per member. In-memory only; hub names are public TfL place names, never persisted.
    private val hubNameCache = mutableMapOf<String, String>()

    // The init coroutine that loads the last-good snapshot and then calls refresh(). Tracked so
    // cancelFetch() can stop it too: during its load() the fetchJob isn't assigned yet, so
    // without this a re-locate that cancels mid-init would still let the init-driven refresh()
    // fetch and save the old seed set during the fix window (SPEC D4 / principle 1, Codex).
    private var initLoadJob: Job? = null

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
        initLoadJob = viewModelScope.launch {
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

    /**
     * Cancel any in-flight fetch without starting a new one. Used when a re-locate begins: a
     * fetch already running for this (soon-to-be-previous) set must not finish first and save a
     * freshly stamped snapshot during the fix window (SPEC D4 / principle 1). Clears the
     * refreshing flag so an indicator started by that fetch doesn't stick on.
     */
    fun cancelFetch() {
        // Cancel the init pipeline too: it calls refresh() after its snapshot load(), and during
        // that load fetchJob isn't set yet, so cancelling only fetchJob would let the init-driven
        // fetch run and save during a re-locate's fix window.
        initLoadJob?.cancel()
        fetchJob?.cancel()
        _refreshing.value = false
    }

    /** Re-fetch every fetched stop and swap in a fresh snapshot; safe to call repeatedly. */
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
            // Memoizes this refresh's hub-name attempts — successes AND failures — so a hub shared
            // by several disrupted stops is requested at most once per refresh, even when the
            // lookup fails: without it a failing hub would be re-requested (and re-timed-out) once
            // per member, and members could disagree if a later one happened to succeed. A success
            // also promotes to the durable `hubNameCache`; a failure stays here only, so the next
            // refresh (a fresh map) retries (Codex).
            val hubNamesThisRefresh = HashMap<String, String>()

            for (stop in fetchedStops) {
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
                // Resolve the interchange name only when this stop has a fresh disruption to
                // title AND belongs to a hub — the one case a folded alert titles by the
                // interchange (SPEC *Disruptions*). Cached and off the render path; a stop with
                // no disruption, or no hub, costs no call and titles by its own name.
                val hubName =
                    if (stop.hubId.isNotBlank() && !disruptions.isNullOrEmpty()) {
                        resolveHubName(stop.hubId, hubNamesThisRefresh)
                    } else {
                        ""
                    }
                Snapshot.mergeStop(
                    stopId = stop.id,
                    stopName = stop.name,
                    clusterId = stop.clusterId,
                    lines = stop.lines,
                    freshDepartures = departures,
                    freshDisruptions = disruptions,
                    prior = prior[stop.id],
                    now = now,
                    hubId = stop.hubId,
                    hubName = hubName,
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
            // one that restores as partial. Best-effort, off the render path. (Removing a
            // departed stop from the widget snapshot is NOT done here — it happens at prune time
            // in [reconcile], independent of this save, so a failed/canceled refresh can't strand
            // it; see [pruneDepartedFromWidget].)
            val authoritative = anyFreshArrivals || (merged.isEmpty() && firstError == null)
            val toSave: DeparturesSnapshot? =
                if (newState is DeparturesUiState.Loaded && authoritative) {
                    DeparturesSnapshot(newState.stops, newState.fetchedAt)
                } else {
                    null
                }
            if (toSave != null) {
                try {
                    withContext(io) { snapshotStore.save(toSave) }
                    // save() pokes the widget itself (WidgetSnapshotStore), so it re-renders with
                    // the fresh snapshot; no separate redraw needed on this path.
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot save failed: ${reason(e)}")
                }
            } else {
                // No save this cycle — a failed refresh (kept the aged last-good) or an error.
                // The widget's RemoteViews are static: without a redraw they keep showing the age
                // and countdowns from the last save, ageing invisibly and never crossing into the
                // withheld "?" state (SPEC D4 / principle 2). Poke a best-effort redraw so it
                // recomputes from the current clock, without overwriting the last-good snapshot.
                redrawWidgetBestEffort("failed refresh")
            }
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
    }

    /**
     * Reveal the next page of [bucket]'s farther clusters (SPEC *Finding stops → Near me now*):
     * add them to the fetched set and re-fetch so they merge in beside the eager stops. A no-op
     * when the bucket has nothing left to page; the caller ignores a tap while a relocation's
     * fresh fix is in flight, so a "More" never pages the pre-fix set. Reveal only *adds* stops,
     * so no prune is needed — an already-present stop keeps its aged rows until its fetch returns.
     */
    fun reveal(bucket: String) {
        val next = NearbySelection.nextReveal(more, bucket, revealedKeys)
        if (next.isEmpty()) return
        revealedKeys = revealedKeys + next
        _moreState.value = NearbySelection.revealableBuckets(more, revealedKeys)
        refresh()
    }

    /**
     * Reconcile the tiers to a fresh fix of the SAME nearby set (both tiers, order-independent — see
     * [NearbyStopsViewModel.State.Ready.clusterSetKey]), keeping a revealed expansion across the
     * relocation. Updates the tiers, drops a revealed cluster the fresh fix no longer offers,
     * **synchronously prunes** a departed revealed stop from the shown state (before the re-fetch,
     * so it can't linger with stale departures through the fetch window — SPEC D4 / principle 1),
     * then re-fetches. A revealed cluster promoted into the eager tier stays fetched (it's eager
     * now) *and* keeps its reveal identity, so a later relocation that demotes it back into *more*
     * keeps it expanded; a member that crossed the radius is pruned here and its replacement
     * fetched by [refresh].
     */
    fun reconcile(newEager: List<NearbySelection.NearbyCluster>, newMore: List<NearbySelection.NearbyCluster>) {
        val before = fetchedStops.mapTo(mutableSetOf()) { it.id }
        eagerStops = newEager.flatMap { cluster -> cluster.stops.map { it.toStopRef() } }
        more = newMore
        // Keep a revealed cluster's identity while it is present in EITHER tier. A cluster promoted
        // into the eager tier is still fetched (via eager) AND stays revealed, so a later relocation
        // that demotes it back into *more* while the whole set is unchanged keeps it expanded rather
        // than reverting it to a "More" button (SPEC — a revealed expansion survives an eager/more
        // boundary shift). Intersecting with `newMore` alone would drop it on the promotion and lose
        // that on the demotion. A key kept here that is currently eager doesn't affect paging (it
        // isn't in `more`, so `revealableBuckets`/`nextReveal` never see it).
        val presentKeys = (newEager + newMore).mapTo(mutableSetOf()) { it.key }
        revealedKeys = revealedKeys intersect presentKeys
        _moreState.value = NearbySelection.revealableBuckets(more, revealedKeys)
        val departed = before - fetchedStops.mapTo(mutableSetOf()) { it.id }
        if (departed.isNotEmpty()) {
            (_state.value as? DeparturesUiState.Loaded)?.let { loaded ->
                val kept = loaded.stops.filterNot { it.stopId in departed }
                _state.value = if (kept.isEmpty()) {
                    // Every shown stop departed; don't leave a trusted, recent-stamped empty
                    // "No departures" up through the replacement fetch (which hasn't been checked)
                    // — show the loading placeholder until it returns (SPEC principle 2; Codex).
                    DeparturesUiState.Loading
                } else {
                    // The shown set just lost stops and a re-fetch is pending, so it is genuinely
                    // incomplete — flag it partial rather than pass the reduced list off as a
                    // complete, uniformly-fresh whole (SPEC principle 2).
                    loaded.copy(
                        stops = kept,
                        fetchedAt = kept.maxOfOrNull { it.fetchedAt } ?: loaded.fetchedAt,
                        partialRefresh = true,
                    )
                }
            }
            // Remove the departed stops from the widget snapshot NOW — at prune time, on a scope
            // that outlives both the re-fetch below and this per-set ViewModel (a different-set
            // relocation discards it via NearbyDeparturesStores.ownerFor). Coupling the removal to
            // the re-fetch's save left a departed stop on disk whenever that save was skipped
            // (a non-authoritative or Error cycle), canceled, or lost with the ViewModel — three
            // findings on one mechanism (#87). A direct, save-independent removal closes the class
            // (SPEC D4 / principle 1).
            pruneDepartedFromWidget(departed)
        }
        refresh()
    }

    /**
     * Remove [departed] from the persisted widget snapshot, off this ViewModel's lifecycle. Launched
     * under [NonCancellable] so it completes even if a following different-set relocation cancels
     * [viewModelScope] (`ownerFor` clears the old set's store) before the write lands — the whole
     * point is that the removal does not depend on the re-fetch's save or this ViewModel surviving.
     * Best-effort like the snapshot save: a failure is logged, sanitized, and swallowed.
     */
    private fun pruneDepartedFromWidget(departed: Set<String>) {
        viewModelScope.launch {
            try {
                withContext(NonCancellable + io) { snapshotStore.pruneStops(departed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("widget snapshot prune failed: ${reason(e)}")
            }
        }
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
                return@launch
            }
            // The pin is persisted and the in-app list has already re-ordered off [starred].
            // The widget redraw is a separate, secondary surface: re-render it now so the star
            // change shows without waiting for the next fetch (it pins starred rows from the
            // persisted state — SPEC D8), but a redraw failure is logged only — it must not
            // report "couldn't save your pin," which is reserved for an actual store failure.
            redrawWidgetBestEffort("star change")
        }
    }

    /**
     * Redraw the widget, best-effort: a secondary surface, so a failure is logged (sanitized) and
     * swallowed — it never fails the primary operation. Rethrows [CancellationException] first so
     * structured concurrency isn't broken. [context] names the trigger for the log line.
     */
    private suspend fun redrawWidgetBestEffort(context: String) {
        try {
            redrawWidget()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("widget redraw after $context failed: ${reason(e)}")
        }
    }

    /** Called by the screen once it has surfaced the star-write failure, so it isn't shown again. */
    fun starWriteFailureShown() {
        _starWriteFailed.value = false
    }

    /**
     * Whether a saved snapshot is incomplete relative to the fetched set — a fetched stop is missing
     * (an earlier partial refresh saved only the stops that succeeded), or a stop is present
     * but was carried-forward-stale when saved (`arrivalsFresh == false`), so the snapshot is
     * mixed-age. Either way it's shown as partial rather than passed off as a complete,
     * uniformly-fresh whole (SPEC principle 2). A restored stop is in exactly one of three
     * states — present-and-fresh, present-and-carried-stale, or missing — so this is the
     * complete incompleteness test.
     */
    private fun isIncomplete(stops: List<StopArrivals>): Boolean =
        fetchedStops.any { seed -> stops.none { it.stopId == seed.id } } ||
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

    /**
     * The display name of interchange [hubId], from cache or a one-time [TflClient.hubName]
     * lookup, so a folded near-me disruption alert titles by the interchange (SPEC *Disruptions*).
     *
     * Two-tier memoization: the durable [hubNameCache] holds successes across refreshes, while
     * [thisRefresh] holds this refresh's attempts — successes and failures alike — so a hub shared
     * by several disrupted stops costs at most one call per refresh even when it fails (every later
     * member this cycle reuses the recorded blank, and they agree). A failure is memoized only in
     * [thisRefresh], never the durable cache, so a fresh map next refresh retries rather than the
     * title being permanently blanked.
     *
     * Best-effort: a failed lookup returns blank and the alert falls back to the stop's own name.
     * Rethrows [CancellationException] first (structured concurrency). The log carries only the hub
     * id — a public TfL place identifier, like a stop id (SPEC *Privacy*).
     */
    private suspend fun resolveHubName(hubId: String, thisRefresh: MutableMap<String, String>): String {
        hubNameCache[hubId]?.let { return it }
        thisRefresh[hubId]?.let { return it }
        val name = try {
            withContext(io) { client.hubName(hubId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("hub name lookup failed for $hubId: ${reason(e)}")
            ""
        }
        // A success is durable; a blank is remembered only for this refresh, so the next one retries.
        if (name.isNotBlank()) hubNameCache[hubId] = name
        thisRefresh[hubId] = name
        return name
    }

    private fun reason(e: Throwable): String =
        (e as? TflException)?.message ?: e::class.simpleName.orEmpty()

    private fun kindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}
