package app.stopcast.domain

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException

/**
 * One end-to-end route of a line in one direction, from TfL's `/Line/{id}/Route/Sequence`
 * `orderedLineRoutes`: its [name] as TfL spells it ("Morden ↔ Edgware via Bank") and its
 * [stopIds] in travel order.
 */
data class LineRoute(val name: String, val stopIds: List<String>)

/**
 * A line's routes in one or both directions, plus a display name for each stop id ([stopNames],
 * already [cleanStopName]d) and the lines that serve each stop or its interchange ([stopLines],
 * a line's [LineRef.mode] blank where TfL's data doesn't say). Static network data — no user
 * data, no clock.
 */
data class LineSequence(
    val routes: List<LineRoute>,
    val stopNames: Map<String, String>,
    val stopLines: Map<String, List<LineRef>> = emptyMap(),
) {
    operator fun plus(other: LineSequence) =
        LineSequence(routes + other.routes, stopNames + other.stopNames, stopLines + other.stopLines)
}

/**
 * One station on the route detail's stop list, with the [connections] a rider can change to
 * there — other rail-type lines at the station or its interchange (see [Connections]).
 */
data class RouteStop(val id: String, val name: String, val connections: List<LineRef> = emptyList())

/**
 * Reads a line's route sequence from TfL — the stop list behind the route detail page (SPEC
 * *Route detail*). Separate from [TflClient] so the background refresh path can't reach it: it is
 * fetched only when the user opens a route. Throws a [TflException] on failure, like [TflClient].
 */
interface RouteSequenceSource {
    /** [direction] is TfL's `inbound` or `outbound`. */
    suspend fun routeSequence(lineId: String, direction: String): LineSequence
}

object RouteStops {
    private val DIRECTIONS = listOf("inbound", "outbound")

    /** The TfL directions to fetch for a row: its own when TfL gave one, else both. */
    fun directionsFor(direction: String): List<String> =
        if (direction in DIRECTIONS) listOf(direction) else DIRECTIONS

    /**
     * Why [resolve] could or couldn't produce a stop list — the reason is what the debug log
     * records when the route page says the list is unavailable (SPEC principle 2: never fail
     * silently). Carries only network data (TfL stop and line ids), no user data.
     */
    sealed interface Resolution {
        data class Found(val stops: List<RouteStop>) : Resolution
        /** No destination to follow. */
        data object NoDestination : Resolution
        /** No route in the sequence calls at the boarding stop. */
        data object NotOnRoute : Resolution
        /** The stop is on a route, but nothing ahead of it matches the destination. */
        data object NoMatch : Resolution
        /** More than one distinct path matches; [paths] of them. */
        data class Ambiguous(val paths: Int) : Resolution
    }

    /** The stop list, or null when [resolve] can't say which path the train takes. */
    fun ahead(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        lineId: String = "",
        bus: Boolean = false,
    ): List<RouteStop>? = (resolve(sequence, stopId, destination, branch, lineId, bus) as? Resolution.Found)?.stops

    /**
     * The stations a train at [stopId] bound for [destination] (cleaned, as on a [Departure]) via
     * [branch] calls at, from the boarding stop through its terminus — or a non-[Resolution.Found]
     * reason when [sequence] can't say which path it takes (SPEC principle 1: no guessed stop list).
     *
     * A route matches when it calls at [stopId] and, later, at a stop named [destination] — so a
     * short-working (a Northern train terminating at Kennington) ends where the train does, not at
     * the line's end. A route with no such stop still matches if its *name* ends at [destination]
     * (a bus destination TfL spells differently from its last stop), running to its end. Where TfL
     * names the branch ("via Bank"), only matching routes count; the answer must then be one
     * unambiguous path. Each stop carries its connections other than [lineId], the line ridden.
     *
     * A [bus] gets one more fallback when neither test matches any route: its route end. A bus
     * arrival's destination is the blind's place label (an area or landmark, not the last stop's
     * name), which usually names no stop and not the route either, so without
     * this nearly every bus had no list. It still has to be a single path from here to the end, and
     * a short-working whose label *does* name a stop still ends there (the tests above run first).
     * Rail keeps the strict rule: its destinations name real stations, so a miss there means a
     * working the sequence doesn't model, and running it to the line's end would be a guess.
     */
    fun resolve(
        sequence: LineSequence,
        stopId: String,
        destination: String,
        branch: String?,
        lineId: String = "",
        bus: Boolean = false,
    ): Resolution {
        if (destination.isBlank()) return Resolution.NoDestination
        if (sequence.routes.none { visits(it, stopId).isNotEmpty() }) return Resolution.NotOnRoute
        // Every visit to [stopId] is a candidate origin and every later stop named [destination] a
        // candidate end: a loop can call here twice, and two stops can share a cleaned name (a
        // loop, a bus route passing a place twice, TfL's line qualifiers that [cleanStopName]
        // drops). Nothing on the arrival says which, so each pairing is its own path, and more
        // than one leaves the answer ambiguous below rather than picking the first.
        // Per route: its stop-name matches, else (none on that route) its route-name terminus — so
        // one variant matching by stop name can't hide another that only matches by its name.
        val matched = sequence.routes.flatMap { route ->
            val byStopName = visits(route, stopId).flatMap { i ->
                (i + 1 until route.stopIds.size).filter { k ->
                    sequence.stopNames[route.stopIds[k]].equals(destination, ignoreCase = true)
                }.map { j -> route to route.stopIds.subList(i, j + 1) }
            }
            byStopName.ifEmpty {
                if (!terminusOf(route.name).equals(destination, ignoreCase = true)) return@ifEmpty emptyList()
                toEnd(route, stopId)
            }
        }
        // A bus whose label matched nothing: every route calling here, run to its end.
        val candidates = if (matched.isEmpty() && bus) sequence.routes.flatMap { toEnd(it, stopId) } else matched
        if (candidates.isEmpty()) return Resolution.NoMatch
        // A branch TfL named narrows to the routes carrying it; if none carry it (an unlabeled
        // Battersea route for a "via CX" train), the branch can't narrow and all candidates stand.
        val onBranch = if (branch == null) {
            candidates
        } else {
            candidates.filter { (route, _) -> branchOf(route.name) == branch }.ifEmpty { candidates }
        }
        val paths = onBranch.map { it.second }.distinct()
        val path = paths.singleOrNull() ?: return Resolution.Ambiguous(paths.size)
        return Resolution.Found(
            path.map { id ->
                RouteStop(id, sequence.stopNames[id].orEmpty(), Connections.of(sequence.stopLines[id].orEmpty(), lineId))
            },
        )
    }

    /** From each visit to [stopId] on [route] (bar its last stop) through the route's end. */
    private fun toEnd(route: LineRoute, stopId: String): List<Pair<LineRoute, List<String>>> =
        visits(route, stopId).filter { it < route.stopIds.lastIndex }
            .map { i -> route to route.stopIds.subList(i, route.stopIds.size) }

    private fun visits(route: LineRoute, stopId: String): List<Int> =
        route.stopIds.indices.filter { route.stopIds[it] == stopId }

    /** The far end of a route name ("Morden ↔ Edgware via Bank" → "Edgware"), cleaned. */
    internal fun terminusOf(routeName: String): String {
        val end = routeName.replace("&harr;", "↔").substringAfterLast("↔")
        return cleanStopName(end.substringBefore(" via ").trim())
    }
}

/**
 * The route detail's stop lists, fetched on demand and held in memory for the process (a line's
 * route barely changes; the next process start refetches). [cached] is the IO-free peek a first
 * frame can use; [load] fetches on a miss. One or two requests per line+direction per process.
 */
class RouteStopsRepository(
    private val source: RouteSequenceSource,
    private val warn: (String) -> Unit = {},
) {
    private val cache = ConcurrentHashMap<String, LineSequence>()

    /**
     * Logs why a fetched sequence gave no stop list for a train on [lineId] at [stopId] — the page
     * shows only "unavailable", so this line is what explains it in a bug report (SPEC principle 2).
     * Ids and the reason only; the destination is TfL's label, kept out as it adds nothing the ids
     * don't.
     */
    fun reportUnresolved(lineId: String, stopId: String, resolution: RouteStops.Resolution) {
        val reason = when (resolution) {
            is RouteStops.Resolution.Found -> return
            RouteStops.Resolution.NoDestination -> "no destination"
            RouteStops.Resolution.NotOnRoute -> "stop not on any route"
            RouteStops.Resolution.NoMatch -> "destination matches no route"
            is RouteStops.Resolution.Ambiguous -> "${resolution.paths} possible paths"
        }
        warn("route stops unavailable for line $lineId at stop $stopId: $reason")
    }

    /** The merged sequence if already fetched, else null. No IO. */
    fun cached(lineId: String, direction: String): LineSequence? {
        val parts = RouteStops.directionsFor(direction).map { cache["$lineId/$it"] ?: return null }
        return parts.reduce(LineSequence::plus)
    }

    /**
     * The sequence for [lineId] in [direction] (both directions when blank), fetched and cached.
     * Throws a [TflException] on failure after logging it (sanitized: line id and error class).
     */
    suspend fun load(lineId: String, direction: String): LineSequence =
        RouteStops.directionsFor(direction).map { dir ->
            cache["$lineId/$dir"] ?: try {
                source.routeSequence(lineId, dir).also { cache["$lineId/$dir"] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("route sequence fetch failed for line $lineId: ${e::class.simpleName}")
                throw e
            }
        }.reduce(LineSequence::plus)
}
