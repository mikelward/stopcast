package app.stopcast.domain

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
    // Each stop's published (latitude, longitude), where TfL gave one — a starred journey's ends.
    val stopPositions: Map<String, Pair<Double, Double>> = emptyMap(),
    // Each stop's stop area (TfL `stationId`), where given: opposite bus stops often share one.
    val stopAreas: Map<String, String> = emptyMap(),
    // Each stop's interchange (TfL `topMostParentId`, e.g. `HUBKGX`), where it has one other than
    // itself: how a departure listed under one of a station's stop ids finds the sibling id the
    // route calls at (see [callingAt]).
    val stopHubs: Map<String, String> = emptyMap(),
) {
    operator fun plus(other: LineSequence) =
        LineSequence(
            routes + other.routes,
            stopNames + other.stopNames,
            stopLines + other.stopLines,
            stopPositions + other.stopPositions,
            stopAreas + other.stopAreas,
            stopHubs + other.stopHubs,
        )

    /**
     * This sequence as seen from [stopId], a stop departures or a journey end are listed under. TfL can list a station's
     * departures under one stop id and route the line through a sibling: St Pancras's Thameslink
     * trains depart under its domestic-platforms id, while the sequence calls at its main and
     * low-level ids. When no route calls at [stopId], every stop in the same interchange ([hubId])
     * that is the same station by name ([stopName], or it plus a platform qualifier like "LL")
     * becomes [stopId] — so the route page, a journey starred from it, and that journey's trains all
     * work from the id the departures carry. The name keeps another station in the hub (King's Cross
     * beside St Pancras) out, which would otherwise make the path ambiguous or wrong. Unchanged when
     * a route calls at [stopId] or no sibling matches.
     */
    fun callingAt(stopId: String, hubId: String, stopName: String): LineSequence {
        val onRoute = routes.flatMapTo(HashSet()) { it.stopIds }
        if (stopId in onRoute) return this
        val name = cleanStopName(stopName)
        if (hubId.isBlank() || name.isBlank()) return this
        val siblings = onRoute.filterTo(LinkedHashSet()) { id ->
            stopHubs[id] == hubId && sameStation(name, stopNames[id].orEmpty())
        }
        if (siblings.isEmpty()) return this
        fun <T> firstOf(map: Map<String, T>): T? = siblings.firstNotNullOfOrNull { map[it] }
        return copy(
            routes = routes.map { route -> route.copy(stopIds = route.stopIds.map { if (it in siblings) stopId else it }) },
            stopNames = stopNames + (stopId to name),
            stopLines = stopLines + (stopId to siblings.flatMap { stopLines[it].orEmpty() }.distinct()),
            stopPositions = firstOf(stopPositions)?.let { stopPositions + (stopId to it) } ?: stopPositions,
            stopAreas = firstOf(stopAreas)?.let { stopAreas + (stopId to it) } ?: stopAreas,
            stopHubs = stopHubs + (stopId to hubId),
        )
    }
}

// "St Pancras International" and "St Pancras International LL" are one station; "King's Cross" is
// not. Either name may carry the qualifier, as TfL's ids don't say which one is the main.
private fun sameStation(a: String, b: String): Boolean {
    if (a.isBlank() || b.isBlank()) return false
    val (short, long) = if (a.length <= b.length) a to b else b to a
    return long.equals(short, ignoreCase = true) || long.startsWith("$short ", ignoreCase = true)
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

/**
 * Reads a stop area's poles from TfL (`/StopPoint/{areaId}`): each pole's id, letter and lines —
 * how a starred bus journey finds the poles beside its origin that other lines board from (SPEC
 * *Journeys*). On demand like [RouteSequenceSource], never on the refresh path. Throws a
 * [TflException] on failure.
 */
interface StopAreaSource {
    suspend fun stopAreaPoles(areaId: String): List<StopLocation>
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
 * Where [RouteStopsRepository] keeps what it fetched between processes: each line+direction's
 * sequence (keyed `"$lineId/$direction"`) and each stop area's poles, with when each was fetched.
 * Blocking; the repository calls it off the main thread. [load] never throws — an unreadable store
 * loads as empty.
 */
interface RouteStopsStore {
    fun load(): Contents
    fun save(contents: Contents)

    data class Timed<T>(val at: Instant, val value: T)

    data class Contents(
        val sequences: Map<String, Timed<LineSequence>> = emptyMap(),
        val poles: Map<String, Timed<List<StopLocation>>> = emptyMap(),
    )

    companion object {
        /** Keeps nothing: the repository holds its entries in memory only, as in a test. */
        val NONE: RouteStopsStore = object : RouteStopsStore {
            override fun load() = Contents()
            override fun save(contents: Contents) = Unit
        }
    }
}

/**
 * The route detail's stop lists and stop areas' poles, fetched on demand and kept for up to
 * [maxAge] (a day: a line's route and a stop area's poles barely change), in memory and through
 * [store] so a reopen after the process was killed still has them. [cached] is the IO-free peek a
 * first frame can use; [load] reads [store] once (see [warm]) and fetches on a miss or an expired
 * entry. One or two requests per line+direction per day.
 */
class RouteStopsRepository(
    private val source: RouteSequenceSource,
    private val warn: (String) -> Unit = {},
    // A stop area's poles, for a bus journey's origin (null: none looked up, as in a test).
    private val areas: StopAreaSource? = source as? StopAreaSource,
    private val store: RouteStopsStore = RouteStopsStore.NONE,
    private val clock: () -> Instant = Instant::now,
    private val maxAge: Duration = MAX_AGE,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val cache = ConcurrentHashMap<String, RouteStopsStore.Timed<LineSequence>>()
    private val areaCache = ConcurrentHashMap<String, RouteStopsStore.Timed<List<StopLocation>>>()
    private val storeLock = Mutex()
    // Nothing to read from a store that keeps nothing, so no IO hop (a test's store is NONE).
    @Volatile private var storeRead = store === RouteStopsStore.NONE

    /**
     * Reads [store] into memory if not yet read, dropping (and deleting from it) anything older
     * than [maxAge]. [load] and [loadPoles] do this themselves; calling it early, off the render
     * path, lets [cached] answer a first frame from what an earlier process fetched.
     */
    suspend fun warm() {
        if (storeRead) return
        storeLock.withLock {
            if (storeRead) return
            val contents = withContext(io) { store.load() }
            val now = clock()
            contents.sequences.forEach { (key, entry) -> if (fresh(entry, now)) cache.putIfAbsent(key, entry) }
            contents.poles.forEach { (key, entry) -> if (fresh(entry, now)) areaCache.putIfAbsent(key, entry) }
            storeRead = true
            val expired = contents.sequences.size + contents.poles.size -
                contents.sequences.values.count { fresh(it, now) } - contents.poles.values.count { fresh(it, now) }
            if (expired > 0) saveLocked()
        }
    }

    // A negative age (the clock moved back) is never fresh: fetch again rather than trust it.
    private fun fresh(entry: RouteStopsStore.Timed<*>, now: Instant): Boolean {
        val age = Duration.between(entry.at, now)
        return !age.isNegative && age < maxAge
    }

    private fun <T> Map<String, RouteStopsStore.Timed<T>>.freshValue(key: String): T? =
        this[key]?.takeIf { fresh(it, clock()) }?.value

    /** Writes the fresh entries to [store], so an expired one leaves it too. */
    private suspend fun save() {
        if (store === RouteStopsStore.NONE) return
        storeLock.withLock { saveLocked() }
    }

    private suspend fun saveLocked() {
        val now = clock()
        cache.entries.removeIf { !fresh(it.value, now) }
        areaCache.entries.removeIf { !fresh(it.value, now) }
        val contents = RouteStopsStore.Contents(cache.toMap(), areaCache.toMap())
        withContext(io) { store.save(contents) }
    }

    /** The poles of stop area [areaId] if already fetched (and not expired), else null. No IO. */
    fun cachedPoles(areaId: String): List<StopLocation>? = areaCache.freshValue(areaId)

    /**
     * The poles of stop area [areaId], fetched once a day and cached (a stop area's poles barely
     * change). Empty when no area source is wired. Throws a [TflException] on failure after logging
     * it (sanitized: the area id and error class).
     */
    suspend fun loadPoles(areaId: String): List<StopLocation> {
        warm()
        areaCache.freshValue(areaId)?.let { return it }
        val areas = areas ?: return emptyList()
        val poles = try {
            areas.stopAreaPoles(areaId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: TflException) {
            warn("stop area fetch failed for $areaId: ${e::class.simpleName}")
            throw e
        }
        areaCache[areaId] = RouteStopsStore.Timed(clock(), poles)
        save()
        return poles
    }

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

    /** The merged sequence if already fetched (and not expired), else null. No IO. */
    fun cached(lineId: String, direction: String): LineSequence? {
        val parts = RouteStops.directionsFor(direction).map { cache.freshValue("$lineId/$it") ?: return null }
        return parts.reduce(LineSequence::plus)
    }

    /**
     * The sequence for [lineId] in [direction] (both directions when blank), fetched and cached.
     * Throws a [TflException] on failure after logging it (sanitized: line id and error class).
     */
    suspend fun load(lineId: String, direction: String): LineSequence {
        warm()
        var fetched = false
        val sequence = RouteStops.directionsFor(direction).map { dir ->
            cache.freshValue("$lineId/$dir") ?: try {
                source.routeSequence(lineId, dir).also {
                    cache["$lineId/$dir"] = RouteStopsStore.Timed(clock(), it)
                    fetched = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                warn("route sequence fetch failed for line $lineId: ${e::class.simpleName}")
                // A direction fetched before the other failed is still kept.
                if (fetched) save()
                throw e
            }
        }.reduce(LineSequence::plus)
        if (fetched) save()
        return sequence
    }

    companion object {
        /** How long a fetched route or stop area's poles is reused (maintainer, 2026-09-24). */
        val MAX_AGE: Duration = Duration.ofHours(24)
    }
}
