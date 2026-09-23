package app.stopcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.Dismissed
import app.stopcast.domain.DismissedAlert
import app.stopcast.domain.DismissedAlertsStore
import app.stopcast.domain.HubInfo
import app.stopcast.domain.LineRef
import app.stopcast.domain.LineStatus
import app.stopcast.domain.NearbySelection
import app.stopcast.domain.Snapshot
import app.stopcast.domain.SnapshotStore
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StarredRowSet
import app.stopcast.domain.StarredRowsStore
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.StopDisruption
import app.stopcast.domain.TflClient
import app.stopcast.domain.stopPlaceKey
import app.stopcast.domain.TflException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    // The bus pole's letter, bearing, and "towards" ([StopLocation]), for the per-pole bus header
    // (SPEC D8). Blank for a station or a letter-less bus stop, and for a watched stop (whose seed
    // carries none yet — a follow-up).
    val stopLetter: String = "",
    val bearing: String = "",
    val towards: String = "",
)

/** The base backoff before restarting a failed dismissed-set read; doubled each attempt, and reset
 *  after any successful emission. */
private const val DISMISSED_READ_RETRY_MS = 500L

/** The ceiling the dismissed-set read backoff is capped at, so a persistently failing store is
 *  retried forever at a steady, quiet interval rather than giving up (storage can recover later). */
private const val DISMISSED_READ_RETRY_MAX_MS = 30_000L

/** How recently a stop's arrivals must have come back for a refresh to carry it over without a
 *  request (see MainViewModel.recentlyFetched). Under the 60 s auto-refresh, so a scheduled refresh
 *  still refetches everything; it only spares a quick retry after a rate-limited refresh. */
internal val ARRIVALS_REUSE: Duration = Duration.ofSeconds(30)

/** How long a stop's successful closure lookup is reused (see MainViewModel.disruptionCache). */
internal val DISRUPTION_REUSE: Duration = Duration.ofMinutes(5)

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
    // Persists which stop-closure alerts the user has dismissed (hidden until their text changes).
    // No-op by default, so tests and an unwired build run identically minus dismissing.
    private val dismissedStore: DismissedAlertsStore = DismissedAlertsStore.NONE,
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
    // How recently a stop must have been fetched for a refresh to carry it over without a request,
    // and how long a stop's closure lookup is reused ([recentlyFetched], [disruptionCache]). Zero —
    // always refetch — by default, so tests drive them explicitly; the app passes [ARRIVALS_REUSE]
    // and [DISRUPTION_REUSE].
    private val arrivalsReuse: Duration = Duration.ZERO,
    private val disruptionReuse: Duration = Duration.ZERO,
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

    // The stop-closure alerts the user has dismissed (SPEC *Disruptions*): the screen drops a
    // matching stop-status row. Collected from the store so a dismiss hides the card at once, and a
    // reworded notice (a new signature) is no longer matched and reappears. An unreadable set reads
    // empty — a dismissed card returns, never a warning hidden (fails safe).
    private val _dismissed = MutableStateFlow<Set<DismissedAlert>>(emptySet())
    val dismissed: StateFlow<Set<DismissedAlert>> = _dismissed.asStateFlow()

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

    // Same seam as [starWriteFailed], for a failed alert dismissal: a tap that didn't persist
    // leaves the card visible, so the screen surfaces a snackbar rather than let the dismiss
    // look broken. Acknowledged StateFlow (survives a rotation between the tap and the message),
    // cleared by [dismissWriteFailureShown].
    private val _dismissWriteFailed = MutableStateFlow(false)
    val dismissWriteFailed: StateFlow<Boolean> = _dismissWriteFailed.asStateFlow()

    private var fetchJob: Job? = null

    // Resolved interchange info (hubId → name + member aliases), so a hub with a disruption is
    // looked up once and reused across refreshes and across the stops sharing it (King's Cross and
    // St Pancras both resolve `HUBKGX` from one call). Only a real result is cached here — a failed
    // or blank lookup is retried on a later refresh rather than pinned. Within a single refresh a
    // failure is memoized separately (see `resolveHubInfo`), so a failing hub is not re-requested
    // once per member. In-memory only; hub names are public TfL place names, never persisted.
    private val hubInfoCache = mutableMapOf<String, HubInfo>()

    // Each stop's last SUCCESSFUL stop-level disruption lookup (a closure, a moved stop) and when it
    // was made, reused for [disruptionReuse] rather than re-requested every refresh: a closure
    // changes over hours, and the lookup is half of every stop's request cost against TfL's rate
    // budget. A failure is never cached, so it's retried next refresh. The line status — the fast-
    // moving signal — is still checked every refresh. In-memory only; written and read on the main
    // thread (viewModelScope), like [hubInfoCache].
    private val disruptionCache = mutableMapOf<String, Pair<Instant, List<StopDisruption>>>()

    // When each stop's arrivals last came back from a fetch by THIS ViewModel (the cycle's start
    // stamp). What makes a stop eligible to be carried over ([recentlyFetched]): a stop restored from
    // disk is never in it, since the snapshot doesn't persist the closure check a carried-over stop
    // would need. In-memory only; main thread.
    private val arrivalsFetchedAt = mutableMapOf<String, Instant>()

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
        viewModelScope.launch {
            // A read failure fails safe to "nothing dismissed" (every alert shown) — the same
            // direction the store's empty fallback takes, so a set we can't read never hides a card.
            // A transient error RESTARTS the collection with capped backoff rather than terminating
            // it: a dead collector would silently stop dismiss from taking effect (a later write would
            // update the store with no one listening) until the ViewModel is recreated. The backoff
            // never gives up (storage can recover later) and resets after any good emission, so
            // occasional, non-consecutive failures don't ratchet it to the ceiling.
            var backoff = DISMISSED_READ_RETRY_MS
            while (true) {
                try {
                    dismissedStore.dismissed().collect {
                        _dismissed.value = it
                        backoff = DISMISSED_READ_RETRY_MS
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _dismissed.value = emptySet()
                    warn("dismissed set read failed, retrying: ${reason(e)}")
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(DISMISSED_READ_RETRY_MAX_MS)
                }
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

    /**
     * The result of fetching a set of stops: the merged [StopArrivals] and the disruption/freshness
     * flags the caller needs to build state and decide whether to persist. Shared by [refresh] (the
     * whole fetched set) and [fetchIncremental] (only the newly revealed stops), so both fetch, merge
     * and status-check identically and neither drifts from the other.
     */
    private data class FetchBatch(
        val merged: List<StopArrivals>,
        val lineStatuses: Map<String, LineStatus>,
        // The line ids TfL returned a definitive status for in this batch (disrupted OR clean), so a
        // per-line surface can tell "checked, good service" from "never checked" and an incremental
        // merge can REPLACE their prior entries — dropping a now-clean line's stale disrupted flag —
        // rather than only appending the disrupted ones (Codex on #100, PR #104).
        val determinedLineIds: Set<String>,
        // The non-blank line ids this batch actually queried. On an incremental merge it's what lets a
        // line the batch re-checked but TfL then omitted drop out of the merged determined set, rather
        // than keeping a stale "checked" from an earlier fetch.
        val attemptedLineIds: Set<String>,
        // Stop ids whose OWN stop-level disruption request failed this batch — an axis independent of
        // line status (a stop's line can be determined while its closure was never checked), so a
        // per-stop surface says "couldn't check" for it (SPEC principle 1, Codex on #100).
        val stopsDisruptionUnknown: Set<String>,
        val anyArrivalsFailed: Boolean,
        val anyFreshData: Boolean,
        val anyFreshArrivals: Boolean,
        val firstError: Throwable?,
    )

    /**
     * Fetch [stops] (arrivals + disruptions, each merged into its [prior] at age [now]) and check the
     * status of every line they show, returning a [FetchBatch]. Pure of UI state — the caller decides
     * how to turn it into a [DeparturesUiState] and whether to save — so the same fetch serves a
     * whole-set refresh and an incremental reveal.
     */
    private suspend fun fetchBatch(
        stops: List<StopRef>,
        prior: Map<String, StopArrivals>,
        now: Instant,
        // Stops to carry over from [prior] unchanged, with no request at all — ones fetched moments
        // ago (see [recentlyFetched]). Each keeps its own fetchedAt and arrivalsFresh, so its age and
        // "No departures" claim stay honest (SPEC D4); only the line status is re-checked for it.
        reuse: Set<String> = emptySet(),
    ): FetchBatch {
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
        // Stop ids whose OWN stop-level disruption request failed this batch (a closure/move was
        // never checked) — an axis independent of line status, carried out so a per-stop surface
        // says "couldn't check" for it even when its line was determined (SPEC principle 1).
        val stopsDisruptionUnknown = mutableSetOf<String>()

        // Fan the per-stop requests out in parallel; the client's shared request pool caps how many
        // are in flight, so this is a bounded fan-out, not one connection per stop. Arrivals are
        // launched before disruptions so the departures the user is waiting on tend to go out first
        // when requests queue — a best effort, not a guarantee: IO scheduling and the rate limiter
        // are free to reorder them, and nothing depends on the order (maintainer, PR #121). Each
        // request catches its own failure, so one stop failing never cancels its siblings.
        // A reused stop costs no request; its results are never read (it's carried over below).
        fun reused(stop: StopRef) = stop.id in reuse && prior[stop.id] != null
        // Which stops' closure results came from [disruptionCache] rather than this batch's request:
        // a cached result is knowledge but not NEWS, so it mustn't count toward [anyFreshData] — a
        // cycle whose every request failed would otherwise pass for a partial refresh and hide the
        // real failure (offline, rate-limited) behind the generic banner (SPEC principle 2).
        val disruptionFromCache = BooleanArray(stops.size)
        val (arrivalResults, disruptionResults) = coroutineScope {
            val arrivals = stops.map { stop ->
                if (reused(stop)) {
                    null
                } else {
                    async { runCatchingTfl { withContext(io) { client.arrivals(stop.id) } } }
                }
            }
            // Fetch each stop's disruption independently of its arrivals (a closure, a moved stop),
            // so a closed stop is flagged rather than shown with catchable-looking departures — and a
            // stop whose *arrivals* failed still surfaces its available closure rather than dropping
            // out entirely (SPEC *Disruptions*). Per stop (the endpoint scopes to it), off the render
            // path. A lookup that fails falls back to the aged disruption and flags the state unknown
            // rather than passing the stop off as verified-clear.
            // A lookup that succeeded within [disruptionReuse] is reused rather than re-requested.
            val disruptions: List<Deferred<Result<List<StopDisruption>>>?> = stops.mapIndexed { i, stop ->
                val cached = disruptionCache[stop.id]
                    ?.takeIf { (at, _) -> isWithin(at, now, disruptionReuse) }
                when {
                    reused(stop) -> null
                    cached != null -> {
                        disruptionFromCache[i] = true
                        CompletableDeferred(Result.success(cached.second))
                    }
                    else -> async {
                        runCatchingTfl { withContext(io) { client.stopDisruptions(stop.id) } }
                            .onSuccess { disruptionCache[stop.id] = now to it }
                    }
                }
            }
            arrivals.map { it?.await() } to disruptions.map { it?.await() }
        }

        // Resolve each interchange once, in parallel, only for a hub with a stop that has a fresh
        // disruption to title — the one case a folded alert titles by the interchange and the strip
        // needs its member aliases (SPEC *Disruptions*). Deduplicated up front so a hub shared by
        // several disrupted stops costs one call even when it fails, and every member agrees. A stop
        // with no disruption, or no hub, costs no call and titles by its own name.
        val hubIds = stops.indices
            .filter { i -> stops[i].hubId.isNotBlank() && !disruptionResults[i]?.getOrNull().isNullOrEmpty() }
            .mapTo(LinkedHashSet()) { i -> stops[i].hubId }
        val hubs: Map<String, HubInfo> = coroutineScope {
            hubIds.map { hubId -> async { hubId to resolveHubInfo(hubId) } }.awaitAll().toMap()
        }

        // Merge in stop order, so the first error, the logs and the merged list read the same as a
        // one-at-a-time fetch would, whatever order the responses came back in.
        stops.forEachIndexed { i, stop ->
            val arrivalResult = arrivalResults[i]
            val disruptionResult = disruptionResults[i]
            if (arrivalResult == null || disruptionResult == null) {
                // Reused: carried over as it was, neither a fresh result nor a failure.
                merged += prior.getValue(stop.id)
                return@forEachIndexed
            }
            val departures = arrivalResult.getOrElse { e ->
                if (firstError == null) firstError = e
                anyArrivalsFailed = true
                warn("arrivals fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            val disruptions = disruptionResult.getOrElse { e ->
                if (firstError == null) firstError = e
                stopsDisruptionUnknown += stop.id
                warn("stop disruption fetch failed for stop ${stop.id}: ${reason(e)}")
                null
            }
            if (departures != null) {
                anyFreshArrivals = true
                arrivalsFetchedAt[stop.id] = now
            }
            if (departures != null || (disruptions != null && !disruptionFromCache[i])) anyFreshData = true
            val hub =
                if (stop.hubId.isNotBlank() && !disruptions.isNullOrEmpty()) {
                    hubs[stop.hubId] ?: HubInfo()
                } else {
                    HubInfo()
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
                hubName = hub.name,
                placeAliases = hub.aliases,
                stopLetter = stop.stopLetter,
                bearing = stop.bearing,
                towards = stop.towards,
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
        // The lines TfL returned a status for (good or disrupted), and the non-blank lines this batch
        // queried. Both feed the incremental merge: determined replaces prior verdicts, attempted lets
        // a re-checked-but-now-omitted line drop out of the merged determined set (see [fetchIncremental]).
        var determinedLineIds = emptySet<String>()
        var attemptedLineIds = emptySet<String>()
        if (merged.isNotEmpty()) {
            val predictedLineIds = merged.flatMap { it.departures }.map { it.lineId }
            val declaredLineIds = merged.flatMap { it.lines }.map { it.id }
            val lineIds = (predictedLineIds + declaredLineIds)
                .filterTo(mutableSetOf()) { it.isNotBlank() }
            attemptedLineIds = lineIds
            // A departure whose line TfL didn't identify (blank id) can't have its
            // status checked, so its presence alone leaves the disruption state
            // unknown — never shown as verified-clean (SPEC principle 1). This also
            // covers the all-blank case, where no status request is made at all.
            val blankLineIdCount = predictedLineIds.count { it.isBlank() }
            if (blankLineIdCount > 0) {
                // Per-stop attribution (a blank prediction on the stop that showed it) is done
                // below; this logs the batch-wide count so a persistent "couldn't check for
                // disruptions" is diagnosable — a count of unidentifiable predictions, no user data.
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
                    determinedLineIds = determined
                    val undetermined = lineIds.filterNot { it in determined }
                    if (undetermined.isNotEmpty()) {
                        // Name the specific lines so a persistent "couldn't check for disruptions" is
                        // diagnosable — a line id is a canned identifier, not user data (SPEC
                        // *Privacy*: line ids are allowed in the log).
                        warn("disruption status unknown: TfL returned no status for line(s) ${undetermined.joinToString(",")}")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // determinedLineIds stays empty, so every shown line reads undetermined — the
                    // screen-wide flag the callers derive is set (nothing was verified this batch).
                    warn("line status fetch failed for ${lineIds.joinToString(",")}: ${reason(e)}")
                }
            }
        }

        return FetchBatch(
            merged = merged,
            lineStatuses = lineStatuses,
            determinedLineIds = determinedLineIds,
            attemptedLineIds = attemptedLineIds,
            stopsDisruptionUnknown = stopsDisruptionUnknown,
            anyArrivalsFailed = anyArrivalsFailed,
            anyFreshData = anyFreshData,
            anyFreshArrivals = anyFreshArrivals,
            firstError = firstError,
        )
    }

    /**
     * Whether [at] is less than [window] before [now]. A negative age — the device clock moved back
     * past [at] — is never within it: it would otherwise read as "just now" until wall time caught
     * up, and keep reusing an old result the whole while.
     */
    private fun isWithin(at: Instant, now: Instant, window: Duration): Boolean {
        val age = Duration.between(at, now)
        return !age.isNegative && age < window
    }

    /**
     * The stops in [loaded] a refresh at [now] can carry over without a request: this ViewModel
     * fetched their arrivals less than [arrivalsReuse] ago ([arrivalsFetchedAt]), the stop shown is
     * that fetch's result (not an older one a canceled batch left behind), and their own closure
     * check didn't fail. A failed or carried-aged stop, or one
     * whose closure check failed, is always refetched — those are what a quick retry is for — and so
     * is a stop restored from disk, which lacks the unpersisted closure check.
     */
    private fun recentlyFetched(loaded: DeparturesUiState.Loaded, now: Instant): Set<String> =
        loaded.stops
            .filter { stop ->
                // The shown stop must BE that fetch (same stamp): a batch canceled after its
                // arrivals came back but before it was published leaves a newer stamp here than the
                // stop on screen, and carrying that older stop over would pass it off as just fetched.
                val fetchedAt = arrivalsFetchedAt[stop.stopId]
                // Nor may a closure check newer than that fetch be skipped: a later, superseded batch
                // whose arrivals failed can still have cached a new closure for the stop, and carrying
                // the stop over would keep its departures up without it.
                val closureAt = disruptionCache[stop.stopId]?.first
                fetchedAt != null &&
                    fetchedAt == stop.fetchedAt &&
                    (closureAt == null || !closureAt.isAfter(fetchedAt)) &&
                    isWithin(fetchedAt, now, arrivalsReuse) &&
                    stop.arrivalsFresh &&
                    stop.stopId !in loaded.stopsDisruptionUnknown
            }
            .mapTo(mutableSetOf()) { it.stopId }

    /**
     * The screen-wide "some shown departures' disruption state is unverified" flag, derived from the
     * merged set and its provenance so [refresh] and an incremental reveal compute it identically and
     * a reveal can't leave it stuck: true when any shown stop's own closure check failed
     * ([stopsDisruptionUnknown]), any shown prediction has no line id to check, or any shown line TfL
     * returned no status for (not in [determinedLineIds]) — never show an unverified line as clean
     * (SPEC principle 1).
     */
    private fun disruptionUnknownOf(
        stops: List<StopArrivals>,
        determinedLineIds: Set<String>,
        stopsDisruptionUnknown: Set<String>,
    ): Boolean =
        stopsDisruptionUnknown.isNotEmpty() ||
            stops.any { s ->
                s.departures.any { it.lineId.isBlank() } ||
                    (s.departures.map { it.lineId } + s.lines.map { it.id })
                        .any { it.isNotBlank() && it !in determinedLineIds }
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
            // Fetch the whole set, merged into the prior at this cycle's stamp (see [fetchBatch]) —
            // except stops fetched moments ago, carried over as they are. A retry soon after a
            // rate-limited refresh then spends the budget left only on the stops still missing,
            // rather than refetching every stop and hitting the limit again.
            val reuse = if (priorLoaded != null) recentlyFetched(priorLoaded, now) else emptySet()
            val batch = fetchBatch(fetchedStops, prior, now, reuse)
            val merged = batch.merged
            val firstError = batch.firstError
            val anyArrivalsFailed = batch.anyArrivalsFailed
            val anyFreshData = batch.anyFreshData
            val anyFreshArrivals = batch.anyFreshArrivals
            val lineStatuses = batch.lineStatuses
            val determinedLineIds = batch.determinedLineIds
            val stopsDisruptionUnknown = batch.stopsDisruptionUnknown
            // Screen-wide "status unknown" derives from the merged set and this batch's provenance,
            // so refresh() and an incremental reveal compute it the same way ([disruptionUnknownOf]).
            val disruptionUnknown = disruptionUnknownOf(merged, determinedLineIds, stopsDisruptionUnknown)

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
                        determinedLineIds = determinedLineIds,
                        stopsDisruptionUnknown = stopsDisruptionUnknown,
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
            // A stop carried over as recently fetched ([recentlyFetched]) is durable content too — its
            // arrivals came from a successful fetch moments ago — so a refresh that reuses every stop
            // still saves. Otherwise a refresh that cancels the previous one's save and then reuses
            // its stops would leave the widget and next launch on the older snapshot on disk.
            val carriedFresh = merged.any { it.stopId in reuse && it.arrivalsFresh }
            val authoritative = anyFreshArrivals || carriedFresh || (merged.isEmpty() && firstError == null)
            val toSave: DeparturesSnapshot? =
                if (newState is DeparturesUiState.Loaded && authoritative) {
                    DeparturesSnapshot(newState.stops, newState.fetchedAt)
                } else {
                    null
                }
            if (toSave != null) {
                try {
                    // Deliberately CANCELLABLE: cancelFetch() is the relocation guard — it cancels this
                    // job before a fresh fix so the soon-to-be-previous location's snapshot is NOT
                    // persisted during the fix window (SPEC D4 / principle 1). A superseding "More" tap
                    // also cancels here, but that page isn't stranded: fetchIncremental persists the
                    // merged set whenever it carries fresh arrivals — including these just-published
                    // ones carried onto the reveal — so the fix doesn't need a NonCancellable save that
                    // would defeat the relocation guard (Codex, PR #104).
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

            // Reconcile dismissals against this cycle's notices, from the batch directly rather than
            // the UI state — a total arrivals failure with no prior yields an Error state and an empty
            // [merged] even though the disruption checks were authoritative, and a resolved closure on
            // that path must still be pruned (SPEC principle 2). This refresh queries the full watched
            // set ([fetchedStops]); the reconcile is scoped per place to only the queried stops whose
            // disruption lookup succeeded (see reconcileDismissals), so it prunes a resolved notice
            // without touching a place that failed to refresh or belongs to a different nearby set.
            reconcileDismissals(fetchedStops, merged, lineStatuses, stopsDisruptionUnknown)
        }
        fetchJob = job
        // Clear the in-flight flag only when this job settles — a job superseded by a
        // newer refresh doesn't clear the newer one's indicator.
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
    }

    /**
     * Reveal the next page of [bucket]'s farther clusters (SPEC *Finding stops → Near me now*):
     * add them to the fetched set and fetch **only** the newly revealed stops, merging them in
     * beside the ones already shown, rather than re-fetching the whole set. A no-op when the bucket
     * has nothing left to page; the caller ignores a tap while a relocation's fresh fix is in flight,
     * so a "More" never pages the pre-fix set. Reveal only *adds* stops, so no prune is needed — an
     * already-present stop keeps its rows untouched.
     */
    fun reveal(bucket: String) {
        // The lines already on screen (eager plus revealed), so nextReveal can page THROUGH a run of
        // clusters that only repeat them — the near-me list collapses a route to its nearest stop, so
        // revealing such a cluster shows nothing — to the first farther cluster with a new route.
        val next = NearbySelection.nextReveal(more, bucket, revealedKeys, shownLineIds())
        if (next.isEmpty()) return
        revealedKeys = revealedKeys + next
        _moreState.value = NearbySelection.revealableBuckets(more, revealedKeys)
        // Fetch only the stops that aren't already shown, merging into the current snapshot, so the
        // Nth "More" tap costs one page of requests, not the whole shown set (TfL request budget).
        // Computed as fetchedStops minus what's on screen — so it also picks up any earlier revealed
        // stop a superseded tap didn't finish fetching, keeping this self-correcting on quick taps.
        val current = _state.value as? DeparturesUiState.Loaded
        if (current == null) {
            // No snapshot to merge into yet (still Loading, or an Error) — fall back to a full fetch,
            // which builds the first Loaded from the whole set.
            refresh()
            return
        }
        val shown = current.stops.mapTo(mutableSetOf()) { it.stopId }
        val newStops = fetchedStops.filterNot { it.id in shown }
        if (newStops.isEmpty()) return
        fetchIncremental(current, newStops)
    }

    /**
     * Fetch [newStops] and merge them into [current], without re-fetching the stops already shown
     * (SPEC *Finding stops → Near me now* — a "More" tap pages one bounded burst, not the whole
     * set). A newly revealed stop whose fetch fails is simply absent (the reveal is flagged partial),
     * never an [DeparturesUiState.Error] — the existing snapshot still stands (SPEC principle 2).
     * The widened set is persisted only when the fetch brought fresh arrivals, so the widget polls
     * the new stops too (matching [refresh]'s save rule); a reveal with nothing durable leaves the
     * saved snapshot as-is.
     */
    private fun fetchIncremental(current: DeparturesUiState.Loaded, newStops: List<StopRef>) {
        fetchJob?.cancel()
        _refreshing.value = true
        val job = viewModelScope.launch {
            val now = clock()
            val prior = current.stops.associateBy { it.stopId }
            val batch = fetchBatch(newStops, prior, now)
            // Merge the newly fetched stops beside the ones already shown, keeping the shown order
            // then appending the new ones; the screen re-sorts by distance, so order here is only for
            // a stable snapshot.
            val byId = LinkedHashMap<String, StopArrivals>()
            for (s in current.stops) byId[s.stopId] = s
            for (s in batch.merged) byId[s.stopId] = s
            val mergedStops = byId.values.toList()
            val mergedIds = byId.keys
            // Merge the disruption provenance across the shown set and the new batch, so the per-line
            // and per-stop route-detail signals — and the screen-wide banner derived from them — stay
            // coherent over a partial (incremental) fetch instead of dropping the shown stops' verdicts.
            // determinedLineIds: keep the shown lines' determinations except ones this batch re-queried
            // (which its result replaces — so a line TfL now omits drops out), then add the batch's.
            val determinedLineIds =
                (current.determinedLineIds - batch.attemptedLineIds) + batch.determinedLineIds
            // stopsDisruptionUnknown: the shown stops still on screen plus the batch's failed stops.
            val stopsDisruptionUnknown =
                (current.stopsDisruptionUnknown + batch.stopsDisruptionUnknown)
                    .filterTo(mutableSetOf()) { it in mergedIds }
            val newState = current.copy(
                stops = mergedStops,
                fetchedAt = mergedStops.maxOfOrNull { it.fetchedAt } ?: current.fetchedAt,
                // Recompute incompleteness over the merged set rather than OR-ing the prior flag, so a
                // later reveal that retries and recovers an earlier missing stop CLEARS the "some stops
                // couldn't refresh" banner instead of leaving it stuck until a full refresh. A stop
                // still missing (its fetch failed) or carried stale keeps it set (see [isIncomplete]).
                partialRefresh = isIncomplete(mergedStops),
                // Merge the new batch's line statuses, REPLACING the prior verdict for every line the
                // batch re-queried (attemptedLineIds), not only the ones it definitively determined:
                // a line the batch re-checked but TfL now omits (or whose lookup failed) must drop its
                // stale disrupted entry — it's undetermined now, surfaced via disruptionUnknown — rather
                // than keep flagging a status this fetch couldn't stand behind (Codex, PR #104). Lines
                // the batch didn't touch keep theirs.
                lineStatuses = current.lineStatuses.filterKeys { it !in batch.attemptedLineIds } +
                    batch.lineStatuses,
                // Recompute the screen-wide flag over the merged set and merged provenance (like
                // partialRefresh) rather than OR-ing the prior flag, so a reveal that re-established a
                // previously unknown line/stop CLEARS the "status unknown" banner ([disruptionUnknownOf]).
                disruptionUnknown = disruptionUnknownOf(mergedStops, determinedLineIds, stopsDisruptionUnknown),
                determinedLineIds = determinedLineIds,
                stopsDisruptionUnknown = stopsDisruptionUnknown,
                // Clear a prior total-failure "couldn't refresh" banner once this reveal reaches TfL
                // and gets anything fresh — matching Loaded's contract that the flag clears on the
                // next fetch that gets anything; a reveal that got nothing keeps the prior state.
                refreshFailure = if (batch.anyFreshData) null else current.refreshFailure,
            )
            _state.value = newState
            // Persist when the MERGED set carries fresh arrivals — not only when THIS batch did —
            // matching refresh()'s authoritative rule (it saves a partial that has any fresh stop).
            // Keying on the merged set is what lets a superseding "More" tap that canceled a full
            // refresh's still-in-flight save carry that refresh's just-published fresh stops to disk
            // here, so the save stays CANCELLABLE (the relocation guard keeps working) yet the page
            // is never stranded (Codex, PR #104). A reveal whose merged set is all aged (nothing
            // fresh anywhere) saves nothing and just redraws, as before — the merged stops keep their
            // own arrivalsFresh, so this never rewrites a complete snapshot to stale.
            if (mergedStops.any { it.arrivalsFresh }) {
                try {
                    withContext(io) { snapshotStore.save(DeparturesSnapshot(newState.stops, newState.fetchedAt)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn("snapshot save failed: ${reason(e)}")
                }
            } else {
                // Nothing fresh anywhere in the merged set — like refresh()'s no-save path, poke a
                // best-effort widget redraw so its static RemoteViews recompute staleness from the
                // current clock rather than ageing invisibly past the cutoff (SPEC D4 / principle 2).
                redrawWidgetBestEffort("incremental reveal with no fresh arrivals")
            }

            // Reconcile dismissals for the stops THIS reveal queried too (not just full refreshes):
            // a "More" tap can surface a previously dismissed place whose notice has since resolved,
            // and its stale signature must be pruned like on a full refresh. Provenance is [newStops]
            // (the queried set); the reconcile is scoped per place to the ones whose disruption lookup
            // succeeded, so it never touches the already-shown stops or a newly-fetched failure.
            reconcileDismissals(newStops, mergedStops, newState.lineStatuses, stopsDisruptionUnknown)
        }
        fetchJob = job
        job.invokeOnCompletion { if (fetchJob === job) _refreshing.value = false }
    }

    /**
     * The ids of the routes actually **on screen right now**. A "More" tap uses this so it can tell a
     * farther cluster that adds a new route from one that only repeats a route already shown (which
     * the near-me dedupe would collapse to nothing).
     *
     * Derived from the rendered rows, not the eager stops' *declared* lines: a stop can declare a
     * line it has no current departure (or disruption) for, so that line isn't on screen — counting
     * it would mark a farther stop that does show it as redundant and never reveal it, the dead tap
     * this fixes (Codex, PR #98). Using the renderer's own output ([DepartureRows.across]) rather
     * than re-deriving "what's shown" keeps this from drifting from the UI as its filters evolve.
     */
    private fun shownLineIds(): Set<String> {
        val loaded = _state.value as? DeparturesUiState.Loaded ?: return emptySet()
        // Derive the shown routes from the SAME rows the screen renders — `DepartureRows.across`
        // against the live clock — rather than reconstructing "what's on screen" from the raw
        // snapshot. That way every filter the renderer applies (departed predictions dropped, a
        // disrupted line's status row suppressed on a stale or carried-forward stop, etc.) is
        // inherited for free, instead of this method drifting from the UI one edge case at a time.
        // Stop-status rows carry a blank lineId and drop out; a timed or line-status row's lineId is
        // a genuinely-shown route.
        return DepartureRows.across(loaded.stops, clock(), loaded.lineStatuses)
            .mapNotNullTo(mutableSetOf()) { it.lineId.takeIf(String::isNotBlank) }
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
     * Dismiss [row]'s stop-closure alert (SPEC *Disruptions*) — hide the card until its notice text
     * changes — and persist it. Off the main thread; the [dismissed] flow re-emits from the store,
     * so the card disappears without this touching UI state directly. A no-op for a row that is not
     * a stop-status row (nothing to dismiss). Best-effort: a write failure is logged and the card
     * stays; the widget shows no alerts, so it needs no redraw.
     */
    fun dismissAlert(row: DepartureRow) {
        if (row.stopDisruption == null) return
        viewModelScope.launch {
            try {
                withContext(io) { dismissedStore.dismiss(DismissedAlert.ofStopClosure(row)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("alert dismiss failed: ${reason(e)}")
                // The write didn't take and the store won't re-emit, so the card silently stays —
                // tell the user rather than let the dismiss tap look broken.
                _dismissWriteFailed.value = true
            }
        }
    }

    /**
     * Prune dismissals whose notice is no longer in the feed — so a resolved incident's stale
     * `(place, text)` dismissal can't later suppress a new same-text closure (SPEC principle 2 —
     * never hide a warning). Scoped to places actually checked this cycle: a place is prunable only
     * when every one of its stops was **queried** ([queriedStops], the requested set — not the merged
     * UI set, which drops a stop whose arrivals failed with no prior even when its disruption came
     * back clear) and its disruption lookup succeeded (not in [stopsDisruptionUnknown]). A place not
     * queried this cycle (a different nearby set — the store is shared) or with any unknown member is
     * left alone, so a still-valid dismissal never lapses because its place wasn't looked at.
     *
     * The reconciled set is applied to the in-memory [_dismissed] **before** the persist, so it holds
     * even if the store write fails: an unverified stale signature can't suppress a recurrence this
     * session (the persisted set self-heals on the next successful reconcile; a restart is bounded by
     * the day-expiry follow-up). Best-effort; a no-op write when nothing changed.
     */
    private suspend fun reconcileDismissals(
        queriedStops: List<StopRef>,
        shownStops: List<StopArrivals>,
        lineStatuses: Map<String, LineStatus>,
        stopsDisruptionUnknown: Set<String>,
    ) {
        // Includes each near-me folded card's identity, so its dismissal isn't pruned as not-live.
        val live = DepartureRows.liveStopClosureAlerts(DepartureRows.across(shownStops, clock(), lineStatuses))
        fun placeOf(stop: StopRef) = stopPlaceKey(stop.hubId, stop.clusterId, stop.name, stop.id)
        // A place with any member whose disruption lookup failed this cycle is not fully known, so it
        // is excluded from the checked set and its dismissals are retained.
        val unknownPlaces = queriedStops.asSequence()
            .filter { it.id in stopsDisruptionUnknown }
            .mapTo(mutableSetOf()) { placeOf(it) }
        val checkedPlaces = queriedStops.asSequence()
            .map { placeOf(it) }
            .filterTo(mutableSetOf()) { it !in unknownPlaces }
        // Reconcile the in-memory set first — safe regardless of whether the persist below succeeds.
        val pruned = Dismissed.reconcile(_dismissed.value, live, checkedPlaces)
        if (pruned != _dismissed.value) _dismissed.value = pruned
        try {
            withContext(io) { dismissedStore.reconcile(live, checkedPlaces) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("dismissal reconcile failed: ${reason(e)}")
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

    /** Called by the screen once it has surfaced the dismiss-write failure, so it isn't shown again. */
    fun dismissWriteFailureShown() {
        _dismissWriteFailed.value = false
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
     * The interchange [hubId]'s name + member aliases, from cache or a one-time [TflClient.hubInfo]
     * lookup, so a folded near-me disruption alert titles by the interchange and the strip can drop
     * a redundant leading name in any member spelling (SPEC *Disruptions*).
     *
     * The durable [hubInfoCache] holds successes across refreshes; a failure is never cached, so the
     * next refresh retries rather than the title being permanently blanked. Within one refresh the
     * caller deduplicates hub ids before calling, so a hub shared by several disrupted stops costs
     * at most one call even when it fails, and every member agrees on the result.
     *
     * Best-effort: a failed lookup returns empty and the alert falls back to the stop's own name.
     * Rethrows [CancellationException] first (structured concurrency). The log carries only the hub
     * id — a public TfL place identifier, like a stop id (SPEC *Privacy*).
     */
    private suspend fun resolveHubInfo(hubId: String): HubInfo {
        hubInfoCache[hubId]?.let { return it }
        val info = try {
            withContext(io) { client.hubInfo(hubId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("hub lookup failed for $hubId: ${reason(e)}")
            HubInfo()
        }
        // A success is durable; an empty isn't cached, so the next refresh retries. Written on the
        // main thread (viewModelScope), so parallel lookups don't race on the map.
        if (info.name.isNotBlank()) hubInfoCache[hubId] = info
        return info
    }

    /**
     * Runs one TfL [block], capturing an ordinary failure as a [Result] so a parallel sibling isn't
     * canceled by it. [CancellationException] is rethrown, never captured (structured concurrency).
     */
    private suspend inline fun <T> runCatchingTfl(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun reason(e: Throwable): String =
        (e as? TflException)?.message ?: e::class.simpleName.orEmpty()

    private fun kindOf(e: Throwable?): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}
