package app.stopdash.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.stopdash.domain.Departure
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.LineStatus
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripRoute
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A trip with a change (SPEC *Trips with a change*): TfL's Journey Planner's routes from [fromId] to
 * [toIds], and the live arrivals and line statuses that time them. The plan is held in memory for
 * [PLAN_REUSE] and planned again after that; live times refresh on each [refresh], which the screen
 * calls on the list's own foreground tick, so nothing runs while the trip isn't on screen.
 *
 * A failure keeps what was last known: a failed plan shows its reason over the last plan, a failed
 * stop keeps its last arrivals (aged, so the screen withholds them once stale, SPEC D4).
 */
class TripViewModel(
    private val planner: JourneyPlanner,
    private val client: TflClient,
    val fromId: String,
    // The stops the trip is planned to: one, or one per station of a complex and one of its bus
    // stops (SPEC *Trips with a change*), each asked in parallel and the answers merged.
    val toIds: List<String>,
    private val clock: () -> Instant = Instant::now,
    // Coarse facts only — a stop id, an error kind, never a coordinate (SPEC *Privacy*).
    private val warn: (String) -> Unit = {},
    // Plans kept for the process, so a trip reopened within [PLAN_REUSE] shows its plan at once
    // rather than ask the Planner again. Memory only: never saved, gone with the process.
    private val plans: TripPlans = TripPlans.SHARED,
    // Requests and their decoding run off the main thread, as the other screens' do.
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    /** One boarding stop's last arrivals and when they were fetched; [failed] when the last fetch failed. */
    data class StopLive(val departures: List<Departure>, val fetchedAt: Instant, val failed: Boolean = false)

    data class State(
        val routes: List<TripRoute>? = null,
        val plannedAt: Instant? = null,
        val planning: Boolean = false,
        val planError: DeparturesUiState.Error.Kind? = null,
        val live: Map<String, StopLive> = emptyMap(),
        val statuses: Map<String, LineStatus> = emptyMap(),
        // The last line-status check failed: the statuses shown (if any) are older, so the trip
        // says it couldn't check for disruptions rather than pass its lines off as running normally.
        val statusFailed: Boolean = false,
        // The routes' lines with no status known: TfL left them out of its answer, or the check
        // failed before any was known. They can't be vouched for as running (ranked unchecked).
        val statusUnknown: Set<String> = emptySet(),
        val refreshing: Boolean = false,
        // The last plan reached only some of the trip's stops (a complex's other stations failed):
        // its routes stand, and the trip says it couldn't plan to every station, with a retry.
        val planIncomplete: Boolean = false,
    )

    private val _state = MutableStateFlow(
        plans.get(fromId, toIds)?.let { (routes, at) -> State(routes = routes, plannedAt = at, statusUnknown = linesOf(routes)) } ?: State(),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null

    /** Modes the rider hid: routes riding them are neither shown nor fetched for. Set by the screen. */
    var hiddenModes: Set<String> = emptySet()

    // A refresh asked for while one runs: run once more when it ends, so a re-pick (a new fix, a
    // mode shown again) is never dropped until the next tick.
    private var again = false

    /**
     * Plans if there is no plan under [PLAN_REUSE] old, then refreshes the live times. One at a
     * time; a call during a refresh queues one more after it.
     */
    fun refresh() = start(replan = false)

    // Whether the screen has asked for its first refresh, and the last re-pick it refreshed for.
    private var started = false
    private var seenRepick: Long? = null

    /**
     * The screen's refresh on showing, and on each re-pick of the nearby set ([repickId]): once per
     * model and per new re-pick, so a configuration change that recreates the screen over this
     * retained model doesn't fetch everything again.
     */
    fun refreshFor(repickId: Long?) {
        if (started && repickId == seenRepick) return
        started = true
        seenRepick = repickId
        refresh()
    }

    /** Plans again now, after a failure. During a refresh, plans again once it ends. */
    fun retry() = start(replan = true)

    // A Retry tapped while a refresh runs: plan again when it ends rather than drop the tap.
    private var replanAgain = false

    private fun start(replan: Boolean) {
        if (job?.isActive == true) {
            again = true
            if (replan) replanAgain = true
            return
        }
        job = viewModelScope.launch {
            run(replan)
            while (again) {
                again = false
                val next = replanAgain
                replanAgain = false
                run(next)
            }
        }
    }

    private suspend fun run(replan: Boolean) {
        val plannedAt = _state.value.plannedAt
        val expired = plannedAt == null || Duration.between(plannedAt, clock()) >= PLAN_REUSE
        // After a failed plan only Retry plans again: the tick never retries it in a loop.
        val failed = _state.value.planError != null
        if (replan || (!failed && (expired || _state.value.routes == null))) plan()
        refreshLive()
    }

    private suspend fun plan() {
        _state.update { it.copy(planning = true) }
        // A first plan shows each stop's answer as it lands, so routes appear without waiting on
        // the slowest. A re-plan keeps the last plan until every stop has answered or failed, so
        // routes (and an open one) don't come and go as the answers land.
        val progressive = _state.value.routes == null
        val gathered = mutableListOf<TripRoute>()
        var answered = 0
        var failure: TflException? = null
        try {
            coroutineScope {
                for (toId in toIds) {
                    launch {
                        val routes = try {
                            withContext(io) { planner.journeys(fromId, toId) }
                        } catch (e: TflException) {
                            // Neither end is logged: together they're a trip the rider chose.
                            warn("trip plan failed: ${e::class.simpleName}")
                            failure = failure ?: e
                            return@launch
                        }
                        answered++
                        gathered += routes
                        // Nothing yet from any stop keeps "Planning…" (or the last plan) rather than
                        // say there's no route while others are still answering.
                        if (!progressive || gathered.isEmpty()) return@launch
                        val shown = gathered.toList()
                        // A first answer after a failed plan clears its error: the routes it brings stand.
                        _state.update { it.copy(routes = shown, planError = null, statusUnknown = unknownLines(shown, it)) }
                    }
                }
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(planning = false) }
            throw e
        }
        val failed = failure
        // Failed only when no stop answered: one that answered with no route is still an answer.
        if (answered == 0 && failed != null) {
            _state.update { it.copy(planning = false, planError = errorKindOf(failed)) }
            return
        }
        val routes = gathered.toList()
        val at = clock()
        // Only a whole plan is kept for reuse: a partial one is planned again on the next open.
        if (failed == null) plans.put(fromId, toIds, routes, at)
        // A new plan's lines are unchecked until their status arrives: none passes as running
        // normally meanwhile (its last known status, if held, stands).
        _state.update {
            it.copy(
                routes = routes,
                plannedAt = at,
                planning = false,
                planError = null,
                planIncomplete = failed != null,
                statusUnknown = unknownLines(routes, it),
            )
        }
    }

    private suspend fun refreshLive() {
        // Routes riding a hidden mode aren't shown, so their stops and lines aren't fetched either.
        // Only the routes the screen times (the soonest few of those shown) are fetched for.
        val routes = _state.value.routes
            ?.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, hiddenModes) } }
            ?.let(::bestOf) ?: return
        val stops = routes.flatMap { route -> route.rides.map { it.fromId } }.filter { it.isNotBlank() }.distinct()
        val lines = routes.flatMap { route -> route.rides.map { it.lineId } }.distinct()
        _state.update { it.copy(refreshing = true) }
        try {
            coroutineScope {
                val statuses = async { fetchStatuses(lines) }
                val live = stops.map { id -> async { id to fetchStop(id) } }.awaitAll()
                val fetched = statuses.await()
                _state.update { state ->
                    state.copy(
                        live = state.live + live.associate { (id, stop) -> id to (stop ?: state.live[id]?.copy(failed = true) ?: StopLive(emptyList(), Instant.EPOCH, failed = true)) },
                        statuses = fetched ?: state.statuses,
                        statusFailed = fetched == null,
                        statusUnknown = lines.filterTo(HashSet()) { it !in (fetched ?: state.statuses) },
                    )
                }
            }
        } finally {
            _state.update { it.copy(refreshing = false) }
        }
    }

    // Null on a failure, so the last arrivals stay (aged) rather than blank the leg.
    private suspend fun fetchStop(stopId: String): StopLive? =
        try {
            StopLive(withContext(io) { client.arrivals(stopId) }, clock())
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("trip arrivals failed: ${e::class.simpleName} for stop $stopId")
            null
        }

    // Null on a failure: the last statuses stay rather than pass the lines off as running normally.
    private suspend fun fetchStatuses(lineIds: List<String>): Map<String, LineStatus>? =
        try {
            withContext(io) { client.lineStatuses(lineIds) }.associateBy { it.lineId }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("trip line status failed: ${e::class.simpleName}")
            null
        }

    companion object {
        private fun linesOf(routes: List<TripRoute>): Set<String> =
            routes.flatMapTo(HashSet()) { route -> route.rides.map { it.lineId } }

        private fun unknownLines(routes: List<TripRoute>, state: State): Set<String> =
            linesOf(routes).filterTo(HashSet()) { it !in state.statuses }

        /**
         * The routes worth timing among [routes] (the plan's, less those riding a hidden mode, so a
         * hidden mode's routes never crowd out the rest): the [MAX_ROUTES] that arrive soonest by the
         * Planner's timetable, each with all its timetable variants (a later one can still be caught
         * when an earlier one can't). Each kept route's boarding stops are fetched on every refresh,
         * so the cap bounds the requests a complex's several answers add.
         */
        internal fun bestOf(routes: List<TripRoute>): List<TripRoute> {
            val keys = routes.sortedBy { it.legs.lastOrNull()?.arrival ?: Instant.MAX }
                .map(::routeKey).distinct().take(MAX_ROUTES).toSet()
            return routes.filter { routeKey(it) in keys }
        }

        /** How many distinct routes a trip times at most. */
        const val MAX_ROUTES = 6

        /** How long a plan is reused before the Planner is asked again. */
        val PLAN_REUSE: Duration = Duration.ofMinutes(15)
    }
}

/**
 * The last few trips' plans, in memory for the process (SPEC *Trips with a change*: a plan is reused
 * if the same trip reopens within 15 minutes). Never written to storage; bounded to [MAX] trips.
 */
class TripPlans {
    private val plans = LinkedHashMap<String, Pair<List<TripRoute>, Instant>>()

    @Synchronized
    fun get(fromId: String, toIds: List<String>): Pair<List<TripRoute>, Instant>? = plans[key(fromId, toIds)]

    @Synchronized
    fun put(fromId: String, toIds: List<String>, routes: List<TripRoute>, at: Instant) {
        val key = key(fromId, toIds)
        plans.remove(key)
        plans[key] = routes to at
        while (plans.size > MAX) plans.remove(plans.keys.first())
    }

    private fun key(fromId: String, toIds: List<String>) = "$fromId>${toIds.joinToString(",")}"

    companion object {
        const val MAX = 8
        val SHARED = TripPlans()
    }
}
