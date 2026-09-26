package app.stopdash.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.DestinationAbbreviations
import app.stopdash.R
import app.stopdash.domain.Countdown
import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.DirectTrips
import app.stopdash.domain.HiddenModes
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteStops
import app.stopdash.domain.Staleness
import app.stopdash.domain.StopArrivals
import app.stopdash.domain.StopGrouping
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.toKotlinDuration

/**
 * The live trains [leg] can use (SPEC *Trips with a change*): its line's upcoming trains at its
 * boarding stop whose route calls at its alighting stop ([DirectTrips.filter], so a train for another
 * branch never counts). Null when StopDash can't vouch for them: no arrivals yet, or stale ones (D4).
 * A train whose route is still loading, or can't be followed, is left out rather than guessed.
 */
internal fun legTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure>? {
    if (leg.isWalk) return null
    val stop = state.live[leg.fromId] ?: return null
    if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) return null
    val calling = legFilter(leg, stop, now, sequences)?.stops?.firstOrNull()?.departures.orEmpty()
    // On a loop or a reconverging line both ways can reach the alighting stop: only a train leaving
    // for the leg's next stop takes the Planner's path (and run time).
    return calling.filter { leavesAlongLeg(it, leg, sequences) != false }
}

/**
 * Whether [train] leaves [leg]'s boarding stop for the leg's next stop on its line's route
 * ([sequences]): null when that can't be told (no route, a next stop the route doesn't list, or a
 * path that doesn't resolve).
 */
internal fun leavesAlongLeg(train: Departure, leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean? {
    val next = leg.path.firstOrNull() ?: return null
    val sequence = sequences[leg.lineId]?.callingAt(leg.fromId) ?: return null
    if (sequence.routes.none { route -> route.stopIds.any { isStop(sequence, it, next) } }) return null
    // A bus blind names an area more often than a stop: a bus runs to its route's end (RouteStops.resolve).
    val bus = leg.mode.equals("bus", ignoreCase = true)
    val ahead = RouteStops.ahead(sequence, leg.fromId, train.destination, train.branch, leg.lineId, bus) ?: return null
    return ahead.getOrNull(1)?.let { isStop(sequence, it.id, next) } == true
}

/**
 * Whether [leg]'s times aren't in yet: its boarding stop's arrivals not fetched, or, at a bus stop
 * pair, its route not loaded to say which side the bus uses, or its poles still being looked up.
 */
internal fun legLoading(state: TripViewModel.State, leg: TripLeg, sequences: Map<String, LineSequence?>): Boolean =
    state.live[leg.fromId] == null ||
        (leg.fromArea.isNotEmpty() && (leg.lineId !in sequences || (state.refreshing && leg.fromArea !in state.areaPoles)))

/**
 * [state] with each route's legs [onPoles] — where the trip fetches the chosen pole: one of its
 * pair's looked-up poles ([TripViewModel.State.areaPoles]), read as "Loading" until its arrivals are
 * in. After a failed lookup the trip never fetches it, so the Planner's pole stands (the lookup is
 * asked again on the next refresh).
 */
internal fun onPoles(state: TripViewModel.State, sequences: Map<String, LineSequence?>): TripViewModel.State {
    val routes = state.routes ?: return state
    fun fetched(leg: TripLeg, pole: String) = pole in state.live || pole in state.areaPoles[leg.fromArea].orEmpty()
    val placed = routes.map { route ->
        TripRoute(route.legs.map { leg -> onPoles(leg, sequences).takeIf { it.fromId == leg.fromId || fetched(leg, it.fromId) } ?: leg })
    }
    return if (placed == routes) state else state.copy(routes = placed)
}

/**
 * [leg] boarding and alighting at the poles its bus uses. The Planner names a bus stop by its pair
 * ([TripLeg.fromArea], a road's two poles) and one pole of it, which can be the other side of the
 * road: its buses there run the other way. Of the pair's poles on the line's route ([sequences]),
 * the one the route leaves by way of the leg's next stop, and the first pole of the alighting pair
 * after it — or, where it gets off at a stop in no pair, the first stop of that name. Unchanged for
 * a leg named by no pair, before its route loads (or when it failed), or where the route gives no
 * single answer.
 */
internal fun onPoles(leg: TripLeg, sequences: Map<String, LineSequence?>): TripLeg {
    if (leg.isWalk || (leg.fromArea.isEmpty() && leg.toArea.isEmpty())) return leg
    val sequence = sequences[leg.lineId] ?: return leg
    fun boards(id: String) = id == leg.fromId || (leg.fromArea.isNotEmpty() && sequence.stopAreas[id] == leg.fromArea)
    fun alights(id: String) = id == leg.toId || (leg.toArea.isNotEmpty() && sequence.stopAreas[id] == leg.toArea)
    // A stop in no pair (a bus station's stands, "Archway Station") by its name, only where the route
    // doesn't call at the stop itself: the Planner can name a stand the line doesn't use, and the
    // route's own stand is the only tie between them.
    fun named(id: String) = leg.toArea.isEmpty() && sequence.stopNames[id]?.equals(leg.toName, ignoreCase = true) == true
    val next = leg.path.firstOrNull()
    val ends = sequence.routes.flatMap { route ->
        route.stopIds.indices.filter { boards(route.stopIds[it]) }.flatMap { i ->
            val on = route.stopIds.subList(i + 1, route.stopIds.size)
            // Every stop of the name, so two along the route are no single answer.
            val offs = listOfNotNull(on.indexOfFirst(::alights).takeIf { it >= 0 })
                .ifEmpty { on.indices.filter { named(on[it]) } }
            // The way the Planner rides: by its next stop, before or at where it gets off.
            offs.filter { off -> next == null || on.subList(0, off + 1).any { isStop(sequence, it, next) } }
                .map { off -> route.stopIds[i] to on[off] }
        }
    }.distinct()
    val (from, to) = ends.singleOrNull() ?: return leg
    return if (from == leg.fromId && to == leg.toId) leg else leg.copy(fromId = from, toId = to)
}

// Whether the route's stop [id] is the Planner's [stop]: the stop itself, or, for a bus, the stop
// pair ("490G…") the Planner names its path by, which holds both of a road's poles.
private fun isStop(sequence: LineSequence, id: String, stop: String): Boolean =
    id == stop || sequence.stopAreas[id] == stop

// [leg]'s line's upcoming trains at [stop] judged on their routes; null when the line has none.
private fun legFilter(
    leg: TripLeg,
    stop: TripViewModel.StopLive,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): DirectTrips.Result? {
    val line = Countdown.upcoming(stop.departures.filter { it.lineId == leg.lineId }, now)
    if (line.isEmpty()) return null
    return DirectTrips.filter(
        listOf(StopArrivals(leg.fromId, leg.fromName, line, stop.fetchedAt)),
        listOf(DirectTrips.End(leg.toId, leg.toName)),
        sequences,
    )
}

/**
 * While [leg]'s line's route is still loading (absent from [sequences]), its live trains at the
 * boarding stop as the main screen shows them, so the row isn't bare meanwhile (plain only when
 * [uncheckedPending] can't doubt it, grayed until checked otherwise): those heading for the
 * Planner's terminus and the rest of their direction, or, at a bus pole (one direction by nature),
 * every one. Where TfL gives no direction (National Rail), only those heading for the terminus; at a
 * station none heading there, none (its snapshot may just lack the leg's direction). Shown only: nothing times the route until the route check vouches for a
 * train. Empty once the route has loaded (or failed), or when the arrivals are stale (D4).
 */
internal fun pendingTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure> {
    if (leg.isWalk || leg.lineId in sequences) return emptyList()
    // A bus stop the Planner named by its pair: which side the bus uses isn't known until its route
    // is, and the other side's buses run the other way.
    if (leg.fromArea.isNotEmpty()) return emptyList()
    val stop = state.live[leg.fromId] ?: return emptyList()
    if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) return emptyList()
    // A train with no destination couldn't be labeled but by the Planner's terminus, which the live
    // feed never said it runs to: left out until the route check vouches for it.
    val line = Countdown.upcoming(stop.departures.filter { it.lineId == leg.lineId && it.destination.isNotBlank() }, now)
    val toward = line.filter { train -> towardTerminus(train, leg) }
    // With no train heading for the terminus, only a bus pole (which serves one direction by nature)
    // says which way its trains run; a station's snapshot may simply lack the leg's direction.
    val bus = leg.mode.equals("bus", ignoreCase = true)
    val directions = toward.ifEmpty { if (bus) line else emptyList() }.mapTo(HashSet()) { it.direction }
    // A blank direction (National Rail's boards give none) says nothing about the way a train runs:
    // only the trains heading for the Planner's terminus, never the rest of the board.
    if ("" in directions) return toward
    if (directions.size != 1 && toward.isEmpty()) return emptyList()
    return line.filter { it.direction in directions }
}

// Whether [train] shows the Planner's terminus for [leg], allowing for a place's parenthetical either
// name carries ("Stratford" for a board's "Stratford (London)"), never a longer name ("Stratford
// International" is another station).
private fun towardTerminus(train: Departure, leg: TripLeg): Boolean {
    val destination = train.destination.withoutQualifier()
    return leg.headings.any { it.withoutQualifier().equals(destination, ignoreCase = true) }
}

private val QUALIFIER = Regex("""\s*\([^()]*\)\s*$""")

private fun String.withoutQualifier(): String = replace(QUALIFIER, "").trim()

/**
 * Of the [pendingTrains], those that may not call where [leg] gets off, shown grayed and read as still
 * being checked until the route check vouches for them; only a bus to the terminus on no named branch
 * shows plain meanwhile (maintainer, 2026-09-26). A train to another terminus or on a named branch
 * ("via Bank") may take another way; and a rail service to the same terminus may still run fast past
 * the rider's stop, which neither its destination nor its branch tells.
 */
internal fun uncheckedPending(pending: List<Departure>, leg: TripLeg): Set<Departure> =
    pending.filterTo(HashSet()) { train ->
        !leg.mode.equals("bus", ignoreCase = true) || !train.branch.isNullOrBlank() || !towardTerminus(train, leg)
    }

/** A leg card's trains while its route is checked: the [pendingTrains] less those [uncheckedPending]. */
internal fun pendingCardTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): List<Departure> = pendingTrains(state, leg, now, sequences).let { pending -> pending - uncheckedPending(pending, leg) }

/**
 * Whether some listed route's live trains couldn't be checked against where the rider gets off:
 * [TripMessage.CHECKING] while a line's route loads, [TripMessage.INCOMPLETE] once one failed or a
 * train's path couldn't be followed; null when every train was checked. Such a leg falls back to the
 * Planner's time, and this says why, rather than pass the fallback off as "no live train".
 */
internal fun tripCheckState(
    state: TripViewModel.State,
    estimates: List<TripTiming.Estimate>,
    now: Instant,
    sequences: Map<String, LineSequence?>,
): TripMessage? {
    var pending = false
    var unresolved = false
    for (leg in estimates.flatMap { it.route.rides }.distinct()) {
        val stop = state.live[leg.fromId] ?: continue
        if (Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())) continue
        val result = legFilter(leg, stop, now, sequences) ?: continue
        pending = pending || result.pending
        unresolved = unresolved || result.unresolved
    }
    return when {
        unresolved -> TripMessage.INCOMPLETE
        pending -> TripMessage.CHECKING
        else -> null
    }
}

/**
 * The trains a first-leg row times, at most [cap]: the soonest the rider can reach, after as many of
 * those leaving too soon (grayed) as fit, so the train a route is timed from is never cut off.
 */
internal fun shownTrains(
    trains: List<Departure>,
    reachable: Instant,
    cap: Int = SHOWN_TRAINS,
    usable: (Departure) -> Boolean = { true },
): List<Pair<Departure, Boolean>> {
    val sorted = trains.sortedBy { it.expectedArrival }
    fun catchable(d: Departure) = usable(d) && !d.expectedArrival.isBefore(reachable)
    val first = sorted.indexOfFirst(::catchable)
    val shown = if (first < 0) {
        sorted.take(cap)
    } else {
        val before = sorted.subList(0, first).takeLast(cap - 1)
        before + sorted.subList(first, sorted.size).take(cap - before.size)
    }
    return shown.map { it to catchable(it) }
}

/**
 * The trains a leg's cards show: its line's trains at the boarding stop heading the same way as the
 * [usable] ones (so a train for another branch is shown too, never used to time the route). With no
 * usable train to take a direction from, the way is the one whose route ([sequences]) leaves the
 * boarding stop for the leg's next stop, so other-branch trains still show when they're all that's
 * due; with no route to tell by, none.
 */
internal fun lineTrains(
    state: TripViewModel.State,
    leg: TripLeg,
    now: Instant,
    usable: List<Departure>,
    sequences: Map<String, LineSequence?> = emptyMap(),
): List<Departure> {
    val stop = state.live[leg.fromId] ?: return usable
    val line = stop.departures.filter { it.lineId == leg.lineId }
    val directions = if (usable.isNotEmpty()) {
        usable.mapTo(HashSet()) { it.direction }
    } else {
        val next = leg.path.firstOrNull()
        val sequence = sequences[leg.lineId]?.callingAt(leg.fromId)
        if (next == null || sequence == null) return usable
        line.filter { train ->
            leavesAlongLeg(train, leg, sequences)?.let { return@filter it }
            // A bus blind often names an area, not a stop, so no one path resolves. A bus pole serves
            // one direction: the bus goes this way when every route through the pole goes on to the
            // leg's next stop.
            if (!train.mode.equals("bus", ignoreCase = true)) return@filter false
            val onward = sequence.routes.flatMap { route ->
                route.stopIds.indices.filter { route.stopIds[it] == leg.fromId && it < route.stopIds.lastIndex }
                    .map { route.stopIds[it + 1] }
            }
            onward.isNotEmpty() && onward.all { isStop(sequence, it, next) }
        }.mapTo(HashSet()) { it.direction }
    }
    return Countdown.upcoming(line.filter { it.direction in directions }, now)
}

/**
 * Each of [state]'s routes timed from [now], best first; null until there is a plan. A route riding
 * a [hidden] mode is left out, as the list leaves out its departures. While the rider's position
 * isn't confirmed ([originUnconfirmed]: a re-locate in flight, failed, or approximate), no route is
 * presented as live-confirmed: each reads "est." at best.
 */
internal fun tripEstimates(
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    hidden: Set<String> = emptySet(),
    originUnconfirmed: Boolean = false,
    // The route open on screen ([routeKey]): kept as its card, even when another way riding the same
    // lines times better, so it isn't swapped out from under the rider.
    openKey: String? = null,
): List<TripTiming.Estimate>? {
    val routes = state.routes
        ?.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, hidden) } }
        ?.let(TripViewModel::bestOf) ?: return null
    val notRunning = TripTiming.notRunning(state.statuses.values)
    // A line with no status known (left out of TfL's answer, or a failed check) can't be vouched
    // for as running.
    // A route shown only now (its mode shown again) waits for its lines' status like a new plan's.
    val unknown = state.statusUnknown +
        routes.flatMap { route -> route.rides.map { it.lineId } }.filterNot { it in state.statuses }
    val estimates = routes.map { route ->
        TripTiming.estimate(route, now, access, { index -> legTrains(state, route.legs[index], now, sequences) }, notRunning, unknown)
            .let { if (originUnconfirmed && it.basis == TripTiming.Basis.LIVE) it.copy(basis = TripTiming.Basis.ESTIMATED) else it }
    }
    // Journeys the Planner times differently but rides alike are one route here (one key in the
    // list): each is timed, since a later timetable slot can still be caught when an earlier one
    // can't, and the best stands for the route.
    val ranked = TripTiming.rank(estimates).distinctBy { routeKey(it.route) }
    // Ways riding the same lines in turn (changing at another stop) read alike, so they're one card:
    // the best of them, or the open one.
    val shown = ranked.groupBy { lineKey(it.route) }
        .mapValues { (_, alike) -> alike.firstOrNull { routeKey(it.route) == openKey } ?: alike.first() }
    return ranked.filter { shown[lineKey(it.route)] === it }
}

// The lines a route rides, in turn: what its card shows.
private fun lineKey(route: TripRoute): String = route.rides.joinToString("|") { "${it.mode}:${it.lineId}" }

/**
 * The lines whose route data a trip loads: [settled] while a first plan's answers are still landing,
 * else [timedLineIds]. A re-plan keeps the last plan until it lands whole, so that plan's lines load
 * at once, even on a screen shown again with nothing settled yet.
 */
internal fun sequenceLineIds(state: TripViewModel.State, hidden: Set<String>, settled: List<String>): List<String> =
    if (state.planning && state.plannedAt == null) settled else timedLineIds(state.routes.orEmpty(), hidden)

/** The lines of the routes a trip times: not riding a [hidden] mode, and within the cap ([TripViewModel.bestOf]). */
internal fun timedLineIds(routes: List<TripRoute>, hidden: Set<String>): List<String> =
    TripViewModel.bestOf(routes.filterNot { route -> route.rides.any { HiddenModes.isHidden(it.mode, hidden) } })
        .flatMap { route -> route.rides.map { it.lineId } }.distinct()

/** A route's identity across refreshes and re-ranking: its lines and stops in order. */
internal fun routeKey(route: TripRoute): String =
    // A bus leg by its stop pairs, so its key holds once its poles are worked out ([onPoles]); one
    // getting off at a stop in no pair by that stop's name, which is how its stop is worked out.
    route.legs.joinToString("|") { leg ->
        val to = leg.toArea.ifEmpty { if (leg.fromArea.isNotEmpty()) leg.toName else leg.toId }
        "${leg.mode}:${leg.lineId}:${leg.fromArea.ifEmpty { leg.fromId }}:$to"
    }

// How many of a first leg's trains its row times.
private const val SHOWN_TRAINS = 3

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val LONDON: ZoneId = ZoneId.of("Europe/London")

/**
 * A trip with a change (SPEC *Trips with a change*): the routes best first, every route alike — its
 * line pills, ⚠ on a disrupted leg, and duration · arrival, over its first leg's live trains — and,
 * once one is tapped, that route leg by leg in the list's own header and route cards. Renders from
 * [state] alone; the caller refreshes it on the list's foreground tick. Its trains are checked
 * against each line's route from [routeStops], which a caller must give: with none, no route would
 * load and every train would stay "checking".
 */
@Composable
internal fun TripScreen(
    title: String,
    state: TripViewModel.State,
    now: Instant,
    // The rider's walk to the trip's first stop (zero from a From… station).
    access: Duration,
    routeStops: RouteStopsRepository,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    locationBanner: LocationBanner? = null,
    relocating: Boolean = false,
    onRelocate: () -> Unit = {},
    hiddenModes: Set<String> = emptySet(),
    onShowAllModes: () -> Unit = {},
) {
    CompositionLocalProvider(LocalRouteStops provides routeStops) {
        TripContent(
            title, state, now, access, onBack, onRetry, locationBanner, relocating, onRelocate,
            hiddenModes, onShowAllModes,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TripContent(
    title: String,
    planned: TripViewModel.State,
    now: Instant,
    access: Duration,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    // The list's low-confidence location banner, with "Try again" re-locating ([onRelocate]); while
    // it shows, or a re-locate is in flight ([relocating]), no route reads as live-confirmed.
    locationBanner: LocationBanner? = null,
    relocating: Boolean = false,
    onRelocate: () -> Unit = {},
    // Modes the rider hid: routes riding them are left out, with the list's "Show all".
    hiddenModes: Set<String> = emptySet(),
    onShowAllModes: () -> Unit = {},
) {
    // Only the timed routes' lines: a hidden mode's routes, and those past the cap, load no route data.
    // While a plan's answers are still landing, the last settled plan's lines stand, so a passing
    // top six never starts loads a later answer would make pointless.
    val settledLines = remember { arrayOf(emptyList<String>()) }
    val lineIds = remember(planned.routes, hiddenModes, planned.planning) {
        sequenceLineIds(planned, hiddenModes, settledLines[0]).also { settledLines[0] = it }
    }
    val sequences = rememberLineSequences(lineIds, now)
    // Each bus leg at the poles its bus uses, once its route says which (the Planner's may be the
    // other side of the road); everything below reads the trip this way.
    val state = remember(planned, sequences) { onPoles(planned, sequences) }
    val originUnconfirmed = relocating || locationBanner != null
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    val estimates = remember(state, now, access, sequences, hiddenModes, originUnconfirmed, openKey) {
        tripEstimates(state, now, access, sequences, hiddenModes, originUnconfirmed, openKey)
    }
    val open = estimates?.firstOrNull { routeKey(it.route) == openKey }
    BackHandler { if (open != null) openKey = null else onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (open != null) openKey = null else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // With a route open, only its own legs' warnings frame it; the list takes every route's.
            val shown = open?.let { listOf(it) } ?: estimates
            val check = remember(state, shown, now, sequences) { shown?.let { tripCheckState(state, it, now, sequences) } }
            TripBanners(shown, state, check, locationBanner, onRelocate, hiddenModes, onShowAllModes)
            Box(Modifier.fillMaxSize()) {
                when {
                    estimates == null -> TripPlaceholder(state, onRetry)
                    open != null -> RouteLegs(open, state, now, access, sequences, onRetry)
                    else -> RouteList(estimates, state, now, sequences, onRetry, onOpen = { openKey = routeKey(it.route) })
                }
            }
        }
    }
}

/**
 * What frames every route, as the list's banners frame its stops: a location that isn't current,
 * boarding stops whose arrivals couldn't be refreshed (their times age out rather than pass as
 * live), and the modes hidden from the trip.
 */
@Composable
private fun TripBanners(
    estimates: List<TripTiming.Estimate>?,
    state: TripViewModel.State,
    check: TripMessage?,
    locationBanner: LocationBanner?,
    onRelocate: () -> Unit,
    hiddenModes: Set<String>,
    onShowAllModes: () -> Unit,
) {
    locationBanner?.let {
        ActionBanner(
            text = stringResource(
                when (it) {
                    LocationBanner.APPROXIMATE -> R.string.location_approximate
                    LocationBanner.UPDATE_FAILED -> R.string.location_update_failed
                    LocationBanner.COARSE -> R.string.location_coarse
                },
            ),
            onTryAgain = onRelocate,
        )
    }
    val failed = remember(estimates, state.live) {
        estimates.orEmpty().flatMap { it.route.rides }
            .filter { state.live[it.fromId]?.failed == true }
            .map { it.fromName }
            .distinct()
    }
    if (failed.isNotEmpty()) {
        val which = if (failed.size == 1) failed[0] else stringResource(R.string.partial_refresh_more, failed[0], failed.size - 1)
        Banner(stringResource(R.string.partial_refresh_no_reason, which))
    }
    when (check) {
        TripMessage.CHECKING -> Banner(stringResource(R.string.trip_checking))
        TripMessage.INCOMPLETE -> Banner(stringResource(R.string.journey_incomplete))
        else -> Unit
    }
    if (hiddenModes.isNotEmpty()) {
        ActionBanner(
            text = stringResource(R.string.modes_hidden, hiddenGroupsLabel(hiddenModes)),
            actionLabel = stringResource(R.string.modes_show_all),
            onAction = onShowAllModes,
        )
    }
}

@Composable
private fun TripPlaceholder(state: TripViewModel.State, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val error = state.planError
        if (error == null || state.planning) {
            Text(stringResource(R.string.trip_planning), style = MaterialTheme.typography.bodyLarge)
        } else {
            PlanFailure(error, state.planning, onRetry)
        }
    }
}

@Composable
private fun PlanFailure(error: DeparturesUiState.Error.Kind, planning: Boolean, onRetry: () -> Unit) =
    PlanNotice(stringResource(R.string.trip_plan_failed, stringResource(errorMessage(error))), planning, onRetry)

/** A plan that reached only some of a complex's stations: the routes shown may not be the best. */
@Composable
private fun PlanIncomplete(planning: Boolean, onRetry: () -> Unit) =
    PlanNotice(stringResource(R.string.trip_plan_incomplete), planning, onRetry)

@Composable
private fun PlanNotice(text: String, planning: Boolean, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry, enabled = !planning) { Text(stringResource(R.string.try_again)) }
    }
}

@Composable
private fun RouteList(
    estimates: List<TripTiming.Estimate>,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
    onRetry: () -> Unit,
    onOpen: (TripTiming.Estimate) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize().testTag("tripRoutes"),
    ) {
        state.planError?.let { error -> item(key = "error") { PlanFailure(error, state.planning, onRetry) } }
        if (state.planError == null && state.planIncomplete) item(key = "incomplete") { PlanIncomplete(state.planning, onRetry) }
        // A plan past its reuse is being planned again: its routes stay, stamped with their age.
        val plannedAt = state.plannedAt
        if (state.planning && plannedAt != null) {
            item(key = "replanning") {
                Text(
                    stringResource(R.string.trip_replanning, Duration.between(plannedAt, now).toMinutes().toInt()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        // Lines not yet checked rank as unchecked, and say so: checking while a check runs, and
        // only a finished check says it couldn't.
        // A route revealed since the last refresh (a mode shown again) counts by its own lines.
        statusNote(state, state.statusUnknown.isNotEmpty() || estimates.any { it.unchecked })?.let { checking -> item(key = "status") { StatusUnknown(checking) } }
        if (estimates.isEmpty()) {
            item(key = "none") { Text(stringResource(R.string.trip_no_routes), style = MaterialTheme.typography.bodyLarge) }
        }
        items(estimates, key = { routeKey(it.route) }) { estimate ->
            OutlinedCard(onClick = { onOpen(estimate) }, modifier = Modifier.fillMaxWidth()) {
                RouteSummary(estimate, state.statuses, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                val first = estimate.route.legs.indexOfFirst { !it.isWalk }
                if (first >= 0) {
                    HorizontalDivider()
                    FirstLegRow(estimate, first, state, now, sequences)
                }
            }
        }
    }
}

/**
 * A route's top row: its lines' pills in order, a ⚠ beside each disrupted one, then duration ·
 * arrival — which drops below the pills when they leave no room beside them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RouteSummary(estimate: TripTiming.Estimate, statuses: Map<String, LineStatus>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        val rides = estimate.route.rides
        if (rides.isEmpty()) {
            // All walking (two stops close together): no line to show, and no live row below.
            Text(stringResource(R.string.trip_walk_only), style = MaterialTheme.typography.titleSmall)
        }
        rides.forEach { leg ->
            // A disrupted line's ⚠ sits beside its own pill (and wraps with it), so it's clear which
            // leg it qualifies.
            val status = statuses[leg.lineId]?.takeIf { it.disrupted }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LinePill(leg.lineName, leg.lineId, leg.mode)
                if (status != null) DisruptionWarningGlyph(status.description)
            }
        }
        Text(
            text = arrivalText(estimate),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 12.dp),
        )
    }
}

@Composable
private fun arrivalText(estimate: TripTiming.Estimate): String {
    val arrival = estimate.arrival ?: return stringResource(R.string.trip_arrival_unknown)
    val minutes = (estimate.duration ?: Duration.ZERO).toMinutes().toInt()
    val clock = CLOCK.format(arrival.atZone(LONDON))
    return if (estimate.basis == TripTiming.Basis.LIVE) {
        stringResource(R.string.trip_duration_arrival, minutes, clock)
    } else {
        stringResource(R.string.trip_duration_arrival_estimated, minutes, clock)
    }
}

/** The first leg's line and its live trains, those the rider can't reach in time grayed. */
@Composable
private fun FirstLegRow(
    estimate: TripTiming.Estimate,
    index: Int,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
) {
    val leg = estimate.route.legs[index]
    val usable = legTrains(state, leg, now, sequences)
    // The line's trains this way, the other branch's too; those the rider can't use (leaving too
    // soon, or not calling where they get off) are grayed. While the route is still being checked,
    // the line's trains as the main screen shows them, timing nothing.
    val pending = pendingTrains(state, leg, now, sequences)
    val trains = pending.ifEmpty { null } ?: usable?.let { lineTrains(state, leg, now, it, sequences) }
    // A train that may skip the stop the rider gets off at (another terminus, or a named branch) stays
    // grayed, and is read as still being checked, until the route check vouches for it.
    val checking = uncheckedPending(pending, leg)
    val usableSet = usable.orEmpty().toSet() + pending.filterNot { it in checking }
    val reachable = estimate.legs.getOrNull(index)?.board ?: estimate.start
    val shown = shownTrains(trains.orEmpty(), reachable, usable = { it in usableSet })
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        LinePill(leg.lineName, leg.lineId, leg.mode)
        // Every destination the times cover, so a time is never read as another train's; shortened
        // as the list shortens a destination (full, then the standard abbreviations, then its floor)
        // before it would elide. With no live train to show, the terminus of the Planner's service,
        // as the train's front shows it (never the stop the rider gets off at, which read as the
        // line's destination); failing that, where they board.
        val destinations = shown.map { (train, _) -> train.destination }.filter { it.isNotBlank() }.distinct()
        DestinationsLabel(
            names = destinations.ifEmpty { leg.headings }
                .ifEmpty { listOf(stringResource(R.string.trip_first_leg_from, leg.fromName)) },
            modifier = Modifier.weight(1f),
        )
        // Graying is lost on TalkBack: each time is read with its destination, and whether it's usable.
        val description = shown.map { (train, catchable) ->
            val time = Countdown.mergedLabel(listOf(train), now)
            val to = train.destination.ifBlank { leg.toName }
            stringResource(trainDescription(train, catchable, train in checking, reachable), time, to)
        }.joinToString(", ")
        Text(
            // No arrivals yet for the boarding stop (never fetched; a failed fetch is kept as failed).
            text = trainTimes(shown, now, loading = legLoading(state, leg, sequences)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = if (shown.isEmpty()) Modifier else Modifier.semantics { contentDescription = description },
        )
    }
}

/**
 * How TalkBack reads a first-leg train: plain when [catchable]; "can't catch" when it leaves before the
 * rider can board at [reachable], whether or not its route is still [checking]; "checking route" for
 * one the rider could reach whose route isn't vouched for yet; "can't catch" otherwise.
 */
@StringRes
internal fun trainDescription(train: Departure, catchable: Boolean, checking: Boolean, reachable: Instant): Int = when {
    catchable -> R.string.trip_train_description
    checking && !train.expectedArrival.isBefore(reachable) -> R.string.trip_train_checking_description
    else -> R.string.trip_train_unusable_description
}

/**
 * The forms a list of destinations may show in, longest first: each name in full, then each with the
 * standard abbreviations, then each at its floor ("Crystal P., W. Croydon") — every name shortened
 * alike, as the list shortens one destination, before anything elides.
 */
internal fun destinationsLadder(names: List<String>): List<String> = listOf(
    names.joinToString(", "),
    names.joinToString(", ") { DestinationAbbreviations.abbreviate(it) },
    names.joinToString(", ") { DestinationAbbreviations.floor(it) },
).distinct()

/** [names] joined, in the longest form of [destinationsLadder] that fits; "…" only below the floor. */
@Composable
private fun DestinationsLabel(names: List<String>, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.titleMedium
    val ladder = remember(names) { destinationsLadder(names) }
    BoxWithConstraints(modifier = modifier) {
        val measurer = rememberTextMeasurer()
        val fontScale = LocalDensity.current.fontScale
        val max = constraints.maxWidth
        val display = remember(ladder, style, fontScale, max) {
            ladder.firstOrNull { measurer.measure(it, style, maxLines = 1).size.width <= max } ?: ladder.last()
        }
        Text(
            text = display,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (display != ladder.first()) Modifier.semantics { contentDescription = ladder.first() } else Modifier),
        )
    }
}

/**
 * "3 · 11 min" in time order, trains the rider can't use grayed; "Loading" while the boarding stop's
 * arrivals haven't been fetched yet ([loading]); "–" with none StopDash can vouch for.
 */
@Composable
private fun trainTimes(shown: List<Pair<Departure, Boolean>>, now: Instant, loading: Boolean) = buildAnnotatedString {
    if (shown.isEmpty()) {
        append(if (loading) stringResource(R.string.trip_times_loading) else "–")
        return@buildAnnotatedString
    }
    shown.forEachIndexed { i, (train, catchable) ->
        if (i > 0) append(" · ")
        val minutes = Countdown.mergedLabel(listOf(train), now).removeSuffix(" min")
        if (catchable) {
            append(minutes)
        } else {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline, fontWeight = FontWeight.Normal)) { append(minutes) }
        }
    }
    append(" min")
}

/** A tapped route, leg by leg: each ride's header and route card, then how far it rides; walks as a link. */
@Composable
private fun RouteLegs(
    estimate: TripTiming.Estimate,
    state: TripViewModel.State,
    now: Instant,
    access: Duration,
    sequences: Map<String, LineSequence?>,
    onRetry: () -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize().testTag("tripLegs"),
    ) {
        // A re-plan that failed says so over the open route too, with its Retry, as the list does.
        state.planError?.let { error -> item(key = "error") { PlanFailure(error, state.planning, onRetry) } }
        if (state.planError == null && state.planIncomplete) item(key = "incomplete") { PlanIncomplete(state.planning, onRetry) }
        item(key = "summary") { RouteSummary(estimate, state.statuses, Modifier.padding(vertical = 8.dp)) }
        statusNote(state, estimate.unchecked)?.let { checking -> item(key = "status") { StatusUnknown(checking) } }
        val firstStop = estimate.route.legs.firstOrNull()?.fromName
        if (access > Duration.ZERO && firstStop != null) {
            item(key = "access") { WalkLink(stringResource(R.string.trip_walk_first, access.toMinutes().toInt(), firstStop)) }
        }
        estimate.route.legs.forEachIndexed { index, leg ->
            if (leg.isWalk) {
                item(key = "leg$index") { WalkLink(stringResource(R.string.trip_walk, leg.run.toMinutes().toInt(), leg.toName)) }
            } else {
                item(key = "leg$index") { RideLeg(leg, index == 0, state, now, sequences) }
                // A change the Planner allows time for after this ride (not a walk leg of its own):
                // shown, since it decides which next train is in reach.
                if (leg.changeAfter > Duration.ZERO && index < estimate.route.legs.lastIndex) {
                    item(key = "change$index") { WalkLink(stringResource(R.string.trip_change, leg.changeAfter.toMinutes().toInt())) }
                }
            }
        }
    }
}

/**
 * Whether a line shown without ⚠ may still be disrupted, and why: true while a check for [unchecked]
 * lines runs or is about to (a plan still landing), false once the check failed or left them
 * unchecked, null when every line was checked.
 */
internal fun statusNote(state: TripViewModel.State, unchecked: Boolean): Boolean? = when {
    // A plan still landing checks its lines when it settles: checking, not failed.
    state.refreshing || state.planning -> if (unchecked) true else null
    state.statusFailed || unchecked -> false
    else -> null
}

/** A line shown without ⚠ may still be disrupted: its status is being checked, or couldn't be. */
@Composable
private fun StatusUnknown(checking: Boolean) {
    Text(
        stringResource(if (checking) R.string.disruptions_checking else R.string.disruptions_unknown),
        style = MaterialTheme.typography.bodyMedium,
        color = if (checking) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun WalkLink(text: String) {
    Text(
        text = "┊  $text",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

@Composable
private fun RideLeg(
    leg: TripLeg,
    first: Boolean,
    state: TripViewModel.State,
    now: Instant,
    sequences: Map<String, LineSequence?>,
) {
    val stop = state.live[leg.fromId]
    // The card shows each destination on its own row, so it shows the leg's line's other-branch
    // trains too (the way the leg goes), as the list would; only the usable ones time the route.
    // Stale arrivals show none (D4).
    // While the route is checked, the line's trains as the main screen shows them, less those on a
    // named branch that may skip the stop the rider gets off at (they join once the check vouches).
    val trains = pendingCardTrains(state, leg, now, sequences)
        .ifEmpty { legTrains(state, leg, now, sequences)?.let { lineTrains(state, leg, now, it, sequences) }.orEmpty() }
    val groups = remember(leg, trains, stop?.fetchedAt, state.statuses) {
        StopGrouping.groupByStop(
            DepartureRows.forStop(
                leg.fromId,
                leg.fromName,
                trains,
                now,
                lineStatuses = state.statuses,
                fetchedAt = stop?.fetchedAt ?: now,
            ),
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (groups.isEmpty()) {
            StopGroupHeader(leg.fromName, qualifier = null, distanceLabel = null, firstOnScreen = first)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    LinePill(leg.lineName, leg.lineId, leg.mode)
                    state.statuses[leg.lineId]?.takeIf { it.disrupted }?.let { DisruptionChip(it.description) }
                    Box(Modifier.weight(1f))
                    // "Loading" while its times aren't in yet, as the first-leg row says; "–" once none can be shown.
                    Text(
                        if (legLoading(state, leg, sequences)) stringResource(R.string.trip_times_loading) else "–",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        } else {
            groups.forEachIndexed { index, group ->
                StopGroupHeader(group.stopName, group.qualifier, distanceLabel = null, firstOnScreen = first && index == 0)
                StopGroupCard(
                    group,
                    now,
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                    onOpenDetail = { _, _ -> },
                )
            }
        }
        Text(
            text = pluralStringResource(R.plurals.trip_stops_to, leg.stops, leg.stops, leg.toName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
