package app.stopcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopcast.domain.Coordinates
import app.stopcast.domain.LocationProvider
import app.stopcast.domain.NearbySelection
import app.stopcast.domain.NearestStops
import app.stopcast.domain.StopFinder
import app.stopcast.domain.StopLocation
import app.stopcast.domain.TflException
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
 * The location gate in front of the departures view (SPEC *Finding stops*, D1): it turns
 * "where is the device" into the nearby [StopRef]s [MainViewModel] then shows departures
 * for. Kept separate from the departures state machine on purpose — finding stops is the
 * action that sends the user's location off the device (via [StopFinder]), so it is confined
 * to this class's [locate]/[relocate] and never bleeds into the departures state machine
 * (mirrors the [StopFinder]/[app.stopcast.domain.TflClient] split).
 *
 * Location is taken only on an explicit find: [locate] on screen open (and after a grant or a
 * retry), and [relocate] on a **user-adjacent** departures refresh — the refresh control, a
 * pull-to-refresh, or a return to the foreground — since the user may have walked since the last
 * fix (SPEC *Finding stops*, D1). Only the automatic on-screen tick is **location-free**, reusing
 * the nearby set this class last resolved so an always-open surface keeps countdowns live without
 * a timed location send. This keeps location on-demand and foreground; the cancel-on-relocate
 * invariant then stops a location-free refresh from fetching (and persisting) the previous set's
 * departures while a re-locate is in flight.
 *
 * Every non-happy outcome is a distinct, honest state rather than an empty list (SPEC
 * principles 1–2): the permission isn't held ([State.PermissionRequired]), there's no fix
 * ([State.NoLocation]), TfL couldn't be reached ([State.Failed]), or there simply are no
 * stops in range ([State.Empty]). [io] is injected for a test dispatcher; [warn] is the
 * sanitized failure log — it never carries a coordinate (SPEC *Privacy*).
 */
class NearbyStopsViewModel(
    private val location: LocationProvider,
    private val finder: StopFinder,
    private val radiusMeters: Int = NearbySelection.OUTER_RADIUS_METERS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    sealed interface State {
        /** The location permission isn't held yet — the screen asks for it. */
        data object PermissionRequired : State

        /** Resolving: a fix and the nearby lookup are in flight — show a placeholder. */
        data object Locating : State

        /**
         * Located, with the near-me set as its two tiers (SPEC *Finding stops → Near me now*):
         * [eager] — the nearest [NearbySelection.CLUSTERS_PER_MODE] clusters of each mode, fetched
         * and shown at once — and [more] — the farther clusters, distance-ordered, that a per-mode
         * "More" tap reveals and fetches on demand. Both are carried (not just eager) so the
         * retained departures view can key on the whole nearby set and reconcile a revealed
         * expansion across a relocation, rather than losing it whenever the eager set's identity
         * shifts.
         *
         * [distanceMeters] (`stopId` → meters from the fix) spans **both** tiers, so a revealed
         * stop is collapsed and ordered the same way an eager one is (a line served by several
         * adjacent stops shows once, from its nearest). It stays in memory for that render and
         * never reaches a log or the persisted snapshot (SPEC *Privacy*).
         *
         * [location] is the exact fix these stops and distances were resolved from, kept in
         * memory alongside them so the **consent-gated bug report** can file the coordinate and
         * the distances from one and the same fix (they would otherwise disagree if it re-fetched
         * a fresh position at report time). Like [distanceMeters] it never reaches a log or the
         * persisted snapshot; it leaves the device only inside a report the user has explicitly
         * consented to share (SPEC *Privacy*).
         */
        data class Ready(
            val eager: List<NearbySelection.NearbyCluster>,
            val more: List<NearbySelection.NearbyCluster>,
            val distanceMeters: Map<String, Double>,
            val location: Coordinates,
        ) : State {
            /** The eager tier flattened to the stops shown and fetched at once. */
            val eagerStops: List<StopRef> get() = eager.flatMap { c -> c.stops.map { it.toStopRef() } }

            /**
             * Every resolved nearby stop, both tiers — what [distanceMeters] spans. The bug report
             * uses this rather than just [eagerStops] so a report sent after a "More" reveal still
             * carries the farther stops the user is now looking at (SPEC *Finding stops*).
             */
            val nearbyStops: List<StopRef> get() = (eager + more).flatMap { c -> c.stops.map { it.toStopRef() } }

            /**
             * Order-independent identity of the WHOLE nearby set (both tiers), so a relocation that
             * only reorders the same clusters — or shifts one across the eager/more boundary while
             * every cluster stays in range — is recognized as the same set and keeps a revealed
             * expansion, rather than rebuilding the retained departures view and dropping it.
             */
            val clusterSetKey: String get() = (eager + more).map { it.key }.sorted().joinToString(",")
        }

        /** Permission held but no position available (location off, or no fix yet) — so, unlike
         *  [Empty] and [Failed], there is no fix to carry for a bug report. */
        data object NoLocation : State

        /**
         * The nearby lookup reached TfL and failed — shown by kind, like the departures error.
         * [location] is the fix the failed lookup was made with, retained (in memory) so a bug
         * report sent from this gate can carry where the failure happened — the context a
         * "can't reach TfL" report needs (SPEC *Privacy*: consent-gated report only).
         */
        data class Failed(val kind: DeparturesUiState.Error.Kind, val location: Coordinates) : State

        /**
         * Located successfully, but TfL returned no stops within [radiusMeters]. [location] is the
         * fix that found nothing nearby, retained (in memory) so a bug report from this gate can
         * carry where "no stops nearby" was reported (SPEC *Privacy*: consent-gated report only).
         */
        data class Empty(val location: Coordinates) : State
    }

    private val _state = MutableStateFlow<State>(State.PermissionRequired)
    val state: StateFlow<State> = _state.asStateFlow()

    // True while a background [relocate] is resolving (the departures screen stays up), so the
    // caller can keep its refresh indicator on for the whole re-locate. Without this a slow fix
    // would let pull-to-refresh retract at once and the retained rows read as current even
    // though the set may still change (SPEC principle 2). [locate] doesn't set it — it shows the
    // Locating gate instead.
    private val _relocating = MutableStateFlow(false)
    val relocating: StateFlow<Boolean> = _relocating.asStateFlow()

    // The in-flight resolve, canceled before a new one starts so a superseded lookup can't
    // finish last and overwrite the newer result (e.g. a quick double-tap on Try again).
    private var locateJob: Job? = null

    /**
     * Resolve the nearby stops. Call once the location permission is held (on open if
     * already granted, or straight after the user grants it), and again for a retry. Safe to
     * call repeatedly — each call cancels any in-flight resolve and supersedes the last state.
     */
    fun locate() {
        locateJob?.cancel()
        // The Locating gate is shown instead of an in-place refresh, so clear the re-locate
        // indicator (a relocate this supersedes must not leave it stuck on).
        _relocating.value = false
        _state.value = State.Locating
        locateJob = viewModelScope.launch { _state.value = resolveNearby(forceFresh = false) }
    }

    /**
     * Re-resolve the nearby set for a user-initiated refresh from the departures view (SPEC
     * *Finding stops* — a refresh re-locates). It is [locate] **without the intermediate
     * [State.Locating]**: the departures screen stays up during the (usually instant) resolve
     * instead of flashing back to the gate spinner on every pull-to-refresh.
     *
     * The departures refresh is **sequenced after** the fix, not run in parallel with it, so a
     * refresh never fetches (and stamps a fresh snapshot for) the *previous* location's stops
     * while the user has moved on — which the widget would then present as current. The outcome
     * decides who fetches:
     * - **same set** (the fix confirms the stops already shown): the per-set departures
     *   ViewModel isn't recreated, so [onSameSet] refreshes it in place — the one path that
     *   re-fetches the on-screen set, and only once the fix has confirmed it.
     * - **a new set** (walked to the next station): emitted as [State.Ready]; the caller swaps
     *   in a fresh per-set ViewModel that fetches on its own init, so the old set is never
     *   re-fetched here.
     * - **[State.Empty] / [State.NoLocation] / [State.Failed]**: propagated honestly (SPEC
     *   principles 1–2) — the gate replaces the list rather than leaving a stale set on screen
     *   (cards omit the stop name, so a stale set is indistinguishable from the real one).
     */
    fun relocate(onSameSet: (State.Ready) -> Unit = {}) {
        locateJob?.cancel()
        val current = _state.value
        val job = viewModelScope.launch {
            // Force a fresh fix: the user may have walked since the last one, and a cached fix
            // would re-resolve for the previous position (see LocationProvider.current).
            val next = resolveNearby(forceFresh = true)
            // Always emit the fresh outcome. Even when the cluster set is unchanged, the fresh fix
            // may have moved within it, so the per-stop distances differ — and the list uses
            // distanceMeters to pick which adjacent stop represents each line and to order rows
            // (SPEC *Finding stops → Near me now*), so the old distances would dedupe/sort by the
            // previous position. Emitting a same-set Ready does not recreate the departures
            // ViewModel (its store is keyed on the whole cluster set), so this is a plain
            // recompose plus an in-place reconcile, not a rebuild.
            _state.value = next
            if (
                next is State.Ready && current is State.Ready &&
                next.clusterSetKey == current.clusterSetKey
            ) {
                // Same nearby set (both tiers, order-independent): reconcile the retained
                // departures ViewModel in place — update its tiers, drop a revealed cluster the
                // fresh fix no longer offers, and re-fetch — sequenced after the fix (never
                // fetched in parallel with it), so a revealed expansion survives the relocation.
                onSameSet(next)
            }
        }
        locateJob = job
        _relocating.value = true
        // Clear only when this is still the active resolve: a newer relocate keeps the indicator
        // on (it owns it now), and a locate() that supersedes this already cleared it.
        job.invokeOnCompletion { if (locateJob === job) _relocating.value = false }
    }

    /**
     * Resolve the device's position to the nearby set, as one of the terminal [State]s — the
     * shared body of [locate] (which shows [State.Locating] first) and [relocate] (which does
     * not). Every failure is a distinct honest state, never an empty list (SPEC principles
     * 1–2), and [warn] carries only the coarse reason, never a coordinate (SPEC *Privacy*).
     */
    private suspend fun resolveNearby(forceFresh: Boolean): State {
        val fix = try {
            withContext(io) { location.current(forceFresh) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No coordinate in the log — only that a fix couldn't be obtained (SPEC Privacy).
            warn("location fix failed: ${e::class.simpleName}")
            null
        } ?: return State.NoLocation
        val found = try {
            withContext(io) { finder.nearbyStops(fix.latitude, fix.longitude, radiusMeters) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // TflException.message is the coarse kind (offline / rate-limited / …), never
            // the coordinate that was queried (SPEC Privacy).
            warn("nearby stops lookup failed: ${(e as? TflException)?.message ?: e::class.simpleName}")
            return State.Failed(kindOf(e), location = fix)
        }
        val result = NearbySelection.selectClusters(
            found, fix.latitude, fix.longitude, outerRadiusMeters = radiusMeters,
        )
        // Eager empty means no stop with a route in range (each present mode contributes its
        // nearest; a route-less stop is never eager and has nothing to show) — nothing nearby runs.
        if (result.eager.isEmpty()) return State.Empty(location = fix)
        // Distance per stop, over BOTH tiers (in memory only), so a revealed stop is collapsed and
        // ordered like an eager one — the departures list shows a line once, from its nearest stop
        // (SPEC *Finding stops → Near me now*). Never logged or persisted (SPEC *Privacy*).
        val distances = (result.eager + result.more)
            .flatMap { it.stops }
            .associate { it.id to NearestStops.distanceMeters(fix.latitude, fix.longitude, it.latitude, it.longitude) }
        return State.Ready(
            eager = result.eager,
            more = result.more,
            distanceMeters = distances,
            // The exact fix, retained in memory so the consent-gated bug report files the
            // coordinate and these distances from one and the same fix (SPEC *Privacy*).
            location = fix,
        )
    }

    private fun kindOf(e: Throwable): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}

/** The departures-view [StopRef] a nearby [StopLocation] maps to — the coordinate is dropped
 *  (it stays in the selection/distance math, never reaching the departures VM or a log). */
internal fun StopLocation.toStopRef() =
    StopRef(
        id = id, name = name, lines = lines, clusterId = clusterId, hubId = hubId,
        stopLetter = stopLetter, bearing = bearing, towards = towards,
    )
