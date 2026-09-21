package app.stopcast.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopcast.domain.LocationProvider
import app.stopcast.domain.NearbySelection
import app.stopcast.domain.NearestStops
import app.stopcast.domain.StopFinder
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
 * retry), and [relocate] on a **user-initiated** departures refresh, since the user may have
 * walked since the last fix (SPEC *Finding stops* — a refresh re-locates). The departures
 * state machine's own refreshes — the automatic tick and the foreground-return refresh — are
 * location-free, reusing the nearby set this class last resolved. This keeps location
 * on-demand (SPEC D1); the cancel-on-relocate invariant then stops a location-free refresh
 * from fetching (and persisting) the previous set's departures while a re-locate is in flight.
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
    private val innerRadiusMeters: Int = NearbySelection.INNER_RADIUS_METERS,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    sealed interface State {
        /** The location permission isn't held yet — the screen asks for it. */
        data object PermissionRequired : State

        /** Resolving: a fix and the nearby lookup are in flight — show a placeholder. */
        data object Locating : State

        /**
         * Located, with the nearest stops to hand to the departures view. [distanceMeters]
         * (`stopId` → meters from the fix) lets the departures list collapse a line that
         * several adjacent nearby stops serve down to its nearest stop (SPEC *Finding stops
         * → Near me now*); it stays in memory for that on-demand render and never reaches a
         * log or the persisted snapshot (SPEC *Privacy*).
         */
        data class Ready(
            val stops: List<StopRef>,
            val distanceMeters: Map<String, Double>,
        ) : State

        /** Permission held but no position available (location off, or no fix yet). */
        data object NoLocation : State

        /** The nearby lookup reached TfL and failed — shown by kind, like the departures error. */
        data class Failed(val kind: DeparturesUiState.Error.Kind) : State

        /** Located successfully, but TfL returned no stops within [radiusMeters]. */
        data object Empty : State
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
    fun relocate(onSameSet: () -> Unit = {}) {
        locateJob?.cancel()
        val current = _state.value
        val job = viewModelScope.launch {
            // Force a fresh fix: the user may have walked since the last one, and a cached fix
            // would re-resolve for the previous position (see LocationProvider.current).
            val next = resolveNearby(forceFresh = true)
            // Always emit the fresh outcome. Even when the stop IDs are unchanged, the fresh fix
            // may have moved within the set, so the per-stop distances differ — and the list uses
            // distanceMeters to pick which adjacent stop represents each line and to order rows
            // (SPEC *Finding stops → Near me now*), so the old distances would dedupe/sort by the
            // previous position. Emitting a same-ID Ready does not recreate the departures
            // ViewModel (its store is keyed by stop IDs only), so this is a plain recompose, not a
            // re-fetch. (Updated name/lines still don't reach the already-seeded ViewModel — the
            // deferred same-set-metadata gap, tracked in TODO.md.)
            _state.value = next
            if (
                next is State.Ready && current is State.Ready &&
                next.stops.map { it.id } == current.stops.map { it.id }
            ) {
                // Same stop set: refresh the existing departures ViewModel in place, sequenced
                // after the fix (never fetched in parallel with it).
                onSameSet()
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
            return State.Failed(kindOf(e))
        }
        val nearest = NearbySelection.select(
            found, fix.latitude, fix.longitude, innerRadiusMeters, radiusMeters,
        )
        return if (nearest.isEmpty()) {
            State.Empty
        } else {
            // Distance per stop (in memory only) so the departures list can show a line
            // once, from its nearest stop, rather than once per adjacent stop (SPEC
            // *Finding stops → Near me now*). Never logged or persisted (SPEC *Privacy*).
            val distances = nearest.associate {
                it.id to NearestStops.distanceMeters(
                    fix.latitude, fix.longitude, it.latitude, it.longitude,
                )
            }
            State.Ready(
                stops = nearest.map {
                    StopRef(id = it.id, name = it.name, lines = it.lines, clusterId = it.clusterId)
                },
                distanceMeters = distances,
            )
        }
    }

    private fun kindOf(e: Throwable): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }
}
