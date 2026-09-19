package app.trackmo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.trackmo.domain.LocationProvider
import app.trackmo.domain.NearestStops
import app.trackmo.domain.StopFinder
import app.trackmo.domain.TflException
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
 * one on-demand action that sends the user's location off the device (via [StopFinder]),
 * while the departures refresh is a background, location-free path — so the two capabilities
 * don't bleed into each other (mirrors the [StopFinder]/[app.trackmo.domain.TflClient] split).
 *
 * Location is resolved **once per screen open** ([locate]), not on every departures refresh:
 * SPEC D1 uses location on demand only, and a fixed nearby set per instance keeps the
 * departures snapshot/persistence assumptions intact. [locate] is called when the coarse-
 * location permission is first held and again after a grant or a retry; moving and re-finding
 * is a later, explicit action.
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
    private val radiusMeters: Int = DEFAULT_RADIUS_METERS,
    private val limit: Int = DEFAULT_LIMIT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val warn: (String) -> Unit = {},
) : ViewModel() {
    sealed interface State {
        /** The coarse-location permission isn't held yet — the screen asks for it. */
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

    // The in-flight resolve, canceled before a new one starts so a superseded lookup can't
    // finish last and overwrite the newer result (e.g. a quick double-tap on Try again).
    private var locateJob: Job? = null

    /**
     * Resolve the nearby stops. Call once the coarse-location permission is held (on open if
     * already granted, or straight after the user grants it), and again for a retry. Safe to
     * call repeatedly — each call cancels any in-flight resolve and supersedes the last state.
     */
    fun locate() {
        locateJob?.cancel()
        _state.value = State.Locating
        locateJob = viewModelScope.launch {
            val fix = try {
                withContext(io) { location.current() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No coordinate in the log — only that a fix couldn't be obtained (SPEC Privacy).
                warn("location fix failed: ${e::class.simpleName}")
                null
            }
            if (fix == null) {
                _state.value = State.NoLocation
                return@launch
            }
            val found = try {
                withContext(io) { finder.nearbyStops(fix.latitude, fix.longitude, radiusMeters) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // TflException.message is the coarse kind (offline / rate-limited / …), never
                // the coordinate that was queried (SPEC Privacy).
                warn("nearby stops lookup failed: ${(e as? TflException)?.message ?: e::class.simpleName}")
                _state.value = State.Failed(kindOf(e))
                return@launch
            }
            val nearest = NearestStops.nearest(found, fix.latitude, fix.longitude, limit)
            _state.value =
                if (nearest.isEmpty()) {
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
                        stops = nearest.map { StopRef(id = it.id, name = it.name, lines = it.lines) },
                        distanceMeters = distances,
                    )
                }
        }
    }

    private fun kindOf(e: Throwable): DeparturesUiState.Error.Kind = when (e) {
        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
        else -> DeparturesUiState.Error.Kind.UNREACHABLE
    }

    companion object {
        /**
         * Search radius for the nearby lookup. 1 km comfortably covers walking distance to a
         * stop in London's dense network without pulling in a sprawl of far-off ones; the
         * ranking then keeps only the nearest [DEFAULT_LIMIT]. Reversible — one constant.
         */
        const val DEFAULT_RADIUS_METERS = 1000

        /** How many nearby stops to watch at once — enough to be useful, few enough to keep
         *  the list scannable and the departures fetch cheap. Reversible — one constant. */
        const val DEFAULT_LIMIT = 5
    }
}
