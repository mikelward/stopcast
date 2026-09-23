package app.stopcast.domain

/**
 * One end of a [StarredJourney]: a station the rider boards or alights at, with its published
 * position (TfL's, from the route sequence — never the rider's own fix) so the nearer end can be
 * picked. Position is null when TfL gave none; such a journey keeps its saved direction.
 */
data class JourneyEnd(
    val stopId: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * A starred journey (SPEC *Journeys*): a segment the rider travels, both ways — "Highgate ↔ King's
 * Cross St. Pancras", or two bus stops shared by the 43 and the 134. Direct only: the card shows
 * every train or bus from one end whose route calls at the other, on any line; a journey needing a
 * change is routing, a non-goal for now. [from] → [to] is the saved orientation, starred from
 * [lineId]'s route page; the near-me list shows whichever end is nearer as the origin
 * ([Journeys.oriented]). Stored on the device like the starred rows and, like them, never logged (a
 * pair of stops can reveal home and work).
 */
data class StarredJourney(
    val from: JourneyEnd,
    val to: JourneyEnd,
    // The line it was starred from. Its route places the segment's stops in each direction (a bus
    // stop's way-back pole is across the road), and it is declared on the origin's fetch so its
    // status is checked even when no trains are predicted (a suspension).
    val lineId: String,
    val lineName: String = "",
    val mode: String = "",
) {
    /**
     * Direction- and line-free identity: the same segment starred from either end, or from another
     * line's page, is one journey.
     */
    val key: String get() = listOf(from.stopId, to.stopId).sorted().joinToString("|")

    fun reversed(): StarredJourney = copy(from = to, to = from)

    /** The line as a stop declares it, for fetching the origin. */
    val line: LineRef get() = LineRef(lineId, lineName, mode)

    val bus: Boolean get() = mode.equals("bus", ignoreCase = true)
}

/**
 * Where a journey (already [Journeys.oriented]) boards and alights in the direction it's shown: the
 * [originId] stop to fetch, and the [destinationIds] a train or bus must call at afterwards. For a
 * bus the way back uses other poles, so these can differ from the saved ends' ids.
 */
data class JourneySegment(val originId: String, val destinationIds: Set<String>)

/**
 * The trains or buses a journey card can show ([rows]), plus why an empty list may not mean "none":
 * a line's route still loading ([pending]), or a departure whose path couldn't be resolved or whose
 * route failed to load ([unresolved]) — either could call at the far end (SPEC principle 1).
 */
data class JourneyTrains(
    val rows: List<DepartureRow>,
    val pending: Boolean,
    val unresolved: Boolean,
    // Some line's route failed to load (a cause of [unresolved] a retry can fix).
    val routeFailed: Boolean = false,
)

object Journeys {
    /** How far a way-back stop may be from the end it stands in for, when nothing else matches. */
    const val WAY_BACK_RADIUS_METERS = 400.0

    /** Flip [journey] in or out of [starred], matched by [StarredJourney.key]. */
    fun toggle(starred: List<StarredJourney>, journey: StarredJourney): List<StarredJourney> =
        if (starred.any { it.key == journey.key }) starred.filterNot { it.key == journey.key } else starred + journey

    /**
     * [journey] with the end nearer ([latitude], [longitude]) as its origin, so the list shows the
     * trains the rider can catch from where they are. Without a position (a location-free list) or
     * an end's coordinates, the saved orientation stands.
     */
    fun oriented(journey: StarredJourney, latitude: Double?, longitude: Double?): StarredJourney {
        if (latitude == null || longitude == null) return journey
        val fromMeters = distanceTo(journey.from, latitude, longitude) ?: return journey
        val toMeters = distanceTo(journey.to, latitude, longitude) ?: return journey
        return if (toMeters < fromMeters) journey.reversed() else journey
    }

    private fun distanceTo(end: JourneyEnd, latitude: Double, longitude: Double): Double? {
        val lat = end.latitude ?: return null
        val lon = end.longitude ?: return null
        return NearestStops.distanceMeters(latitude, longitude, lat, lon)
    }

    /**
     * Where [journey] boards and alights on [sequence] (its starred line's route, both directions),
     * or null when the route can't place it unambiguously — the card then says it couldn't check
     * rather than guess a stop (SPEC principle 1).
     *
     * Each end is matched on a route by, in turn: its own stop id (a station, or a bus pole in the
     * saved direction); a stop in the same stop area; a stop of the same name; and, for a stop served
     * one way only, the route's nearest stop within [WAY_BACK_RADIUS_METERS]. The first way that
     * finds the origin before the destination on some route wins; the origin must come out as one
     * stop. The distance fallback applies only to a bus journey on its starred line's own route:
     * another line that merely passes near an end hasn't been shown to serve it.
     */
    fun segment(journey: StarredJourney, sequence: LineSequence, lineId: String = journey.lineId): JourneySegment? {
        for (tier in 0..maxTier(journey, lineId)) {
            val pairs = sequence.routes.flatMap { route ->
                val origins = matches(route, journey.from, sequence, tier)
                val destinations = matches(route, journey.to, sequence, tier)
                origins.flatMap { i -> destinations.filter { it > i }.map { j -> route.stopIds[i] to route.stopIds[j] } }
            }
            if (pairs.isEmpty()) continue
            val origin = pairs.map { it.first }.distinct().singleOrNull() ?: return null
            return JourneySegment(origin, pairs.mapTo(HashSet()) { it.second })
        }
        return null
    }

    /**
     * The indices on [route] that stand for [end] at this matching [tier] or looser (see [segment]):
     * each end finds its best match on its own, so a bus origin found by stop area can pair with a
     * one-way destination found by distance.
     */
    private fun matches(route: LineRoute, end: JourneyEnd, sequence: LineSequence, tier: Int): List<Int> {
        val ids = route.stopIds
        val byId = ids.indices.filter { ids[it] == end.stopId }
        if (byId.isNotEmpty() || tier == 0) return byId
        val area = sequence.stopAreas[end.stopId]?.takeIf { it.isNotBlank() }
        val byArea = if (area == null) emptyList() else ids.indices.filter { sequence.stopAreas[ids[it]] == area }
        if (byArea.isNotEmpty() || tier == 1) return byArea
        val byName = ids.indices.filter { sequence.stopNames[ids[it]]?.equals(end.name, ignoreCase = true) == true }
        if (byName.isNotEmpty() || tier == 2) return byName
        val (lat, lon) = (end.latitude?.let { la -> end.longitude?.let { la to it } })
            ?: sequence.stopPositions[end.stopId] ?: return emptyList()
        return ids.indices
            .mapNotNull { i ->
                sequence.stopPositions[ids[i]]?.let { (la, lo) -> i to NearestStops.distanceMeters(lat, lon, la, lo) }
            }
            .filter { it.second <= WAY_BACK_RADIUS_METERS }
            .minByOrNull { it.second }
            ?.let { listOf(it.first) }
            .orEmpty()
    }

    /**
     * The departures at [segment]'s origin, on any line, that call at one of its destinations —
     * "Bank-branch trains" from Highgate toward King's Cross, or both the 43 and the 134 between two
     * shared stops. Each departure's path comes from its own line's route in [sequences] (null when
     * that route failed to load; absent while loading); one it can't resolve is left out rather than
     * guessed, and flagged. A line's status-only row (a suspension, no predictions) is kept when its
     * route serves the segment, so the card shows the warning rather than a bare "no trains".
     */
    fun trains(
        segment: JourneySegment,
        rows: List<DepartureRow>,
        sequences: Map<String, LineSequence?>,
        // The journey (this way round), so each line can place the far end on its own route — another
        // route may stop at a different pole of the destination's stop area.
        journey: StarredJourney? = null,
    ): JourneyTrains {
        var pending = false
        var unresolved = false
        var routeFailed = false
        val atOrigin = rows.filter { it.stopId == segment.originId && it.stopDisruption == null }
        // A departure TfL gave no line id can't be checked against any route: it may well call there.
        if (atOrigin.any { it.lineId.isBlank() && it.upcoming.isNotEmpty() }) unresolved = true
        val kept = atOrigin
            .filter { it.lineId.isNotBlank() }
            .mapNotNull { row ->
                if (row.lineId !in sequences) {
                    pending = true
                    return@mapNotNull null
                }
                val sequence = sequences[row.lineId]
                if (sequence == null) {
                    // A failed route can't say whether its departures — or its warning, on a
                    // status-only row — belong to this segment: never a silent drop.
                    if (row.upcoming.isNotEmpty() || row.status != null) {
                        unresolved = true
                        routeFailed = true
                    }
                    return@mapNotNull null
                }
                val destinations = segment.destinationIds +
                    (journey?.let { destinationsOn(it.to, segment.originId, sequence, maxTier(it, row.lineId)) }.orEmpty())
                if (row.upcoming.isEmpty()) {
                    return@mapNotNull row.takeIf { it.status != null && serves(sequence, segment.originId, destinations) }
                }
                // The mode from any departure when TfL left it off the soonest one.
                val mode = row.mode.ifBlank { row.upcoming.firstOrNull { it.mode.isNotBlank() }?.mode.orEmpty() }
                val bus = mode.equals("bus", ignoreCase = true)
                val calling = row.upcoming.filter { departure ->
                    val path = RouteStops.ahead(sequence, segment.originId, departure.destination, departure.branch, row.lineId, bus)
                    if (path == null) unresolved = true
                    path?.any { it.id in destinations } == true
                }
                if (calling.isEmpty()) null else row.copy(upcoming = calling, destination = calling.first().destination)
            }
        return JourneyTrains(kept, pending, unresolved, routeFailed)
    }

    /**
     * The loosest match [segment] may use on [lineId]'s route: by distance only for a bus journey on
     * its starred line (a pole served one way only). A station missing from a rail route is a
     * journey that can't be placed, not one a nearby station stands in for.
     */
    private fun maxTier(journey: StarredJourney, lineId: String): Int =
        if (journey.bus && lineId == journey.lineId) 3 else 2

    /** Whether some route of [sequence] calls at [originId] and then one of [destinations]. */
    private fun serves(sequence: LineSequence, originId: String, destinations: Set<String>): Boolean =
        sequence.routes.any { route ->
            val i = route.stopIds.indexOf(originId)
            i >= 0 && route.stopIds.drop(i + 1).any { it in destinations }
        }

    /**
     * The stops on [sequence]'s routes after [originId] that stand for [end], matched the way
     * [segment] matches (id, stop area, name, nearest within [WAY_BACK_RADIUS_METERS] up to
     * [maxTier]), the first way that finds any.
     */
    private fun destinationsOn(end: JourneyEnd, originId: String, sequence: LineSequence, maxTier: Int): Set<String> {
        for (tier in 0..maxTier) {
            val found = sequence.routes.flatMapTo(HashSet()) { route ->
                val i = route.stopIds.indexOf(originId)
                if (i < 0) emptyList() else matches(route, end, sequence, tier).filter { it > i }.map { route.stopIds[it] }
            }
            if (found.isNotEmpty()) return found
        }
        return emptySet()
    }
}

/**
 * Reads and writes the starred journeys (SPEC *Journeys*). A seam so a ViewModel depends on the
 * capability, not DataStore; mirrors [StarredRowsStore]. [journeys] emits the saved list at once
 * and on every change — null when a stored list exists that this build can't read (a newer schema),
 * which the store then preserves rather than overwrite. [toggle] runs off the main thread.
 */
interface StarredJourneysStore {
    fun journeys(): kotlinx.coroutines.flow.Flow<List<StarredJourney>?>

    suspend fun toggle(journey: StarredJourney)

    companion object {
        /** Persists nothing and reads an empty list: tests and an unwired build. */
        val NONE: StarredJourneysStore = object : StarredJourneysStore {
            override fun journeys() = kotlinx.coroutines.flow.flowOf<List<StarredJourney>?>(emptyList())
            override suspend fun toggle(journey: StarredJourney) {}
        }
    }
}
