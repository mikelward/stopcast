package app.stopcast.domain

/**
 * The departures from a searched station that call at another (SPEC *Finding stops → From… To…*):
 * "From… Highgate, To… Euston" keeps the Northern line trains whose path reaches Euston and drops
 * the rest. Direct only — a trip needing a change is journey planning, not yet built. Like a starred
 * journey's card ([Journeys.trains]), a departure is judged by its own line's route ([RouteStops]);
 * one whose route is still loading or failed, or whose path the route can't resolve, is left out
 * and flagged rather than guessed either way (SPEC principle 1).
 */
object DirectTrips {
    /** One of the destination station's stops: its TfL id, name and interchange (blank if none). */
    data class End(val id: String, val name: String, val hubId: String = "")

    /**
     * What a trip filter kept: the [stops] with only the departures that reach the destination (a
     * stop keeps its closure notice with none), and why an empty or short list may not be the whole
     * answer — a line's route still loading ([pending]), or a departure that couldn't be checked
     * ([unresolved]).
     */
    data class Result(val stops: List<StopArrivals>, val pending: Boolean, val unresolved: Boolean)

    /**
     * Keeps the departures in [stops] that call at one of [destination]'s stops after boarding,
     * each judged on its line's route in [sequences] (absent while loading, null when it failed).
     * [hubs] gives each origin stop's interchange, and [destination]'s stops carry theirs, so a
     * station listed under one id and routed through a sibling is matched ([LineSequence.callingAt]).
     * A stop's lines are kept only where their route reaches the destination, so a status row
     * (a suspension) shows for the lines that matter and not for the rest.
     */
    fun filter(
        stops: List<StopArrivals>,
        destination: List<End>,
        sequences: Map<String, LineSequence?>,
        hubs: Map<String, String> = emptyMap(),
    ): Result {
        val destinationIds = destination.mapTo(HashSet()) { it.id }
        var pending = false
        var unresolved = false
        // A line's route as seen from [stop] and the destination's stops, once per stop and line.
        fun sequenceAt(stop: StopArrivals, lineId: String): LineSequence? {
            var sequence = sequences[lineId] ?: return null
            sequence = sequence.knowing(stop.stopId, hubs[stop.stopId] ?: stop.hubId, stop.stopName).callingAt(stop.stopId)
            for (end in destination) sequence = sequence.knowing(end.id, end.hubId, end.name).callingAt(end.id)
            return sequence
        }
        val kept = stops.map { stop ->
            // The origin itself is no destination: From and To the same station is no trip.
            if (stop.stopId in destinationIds) {
                return@map stop.copy(departures = emptyList(), lines = emptyList(), disruptions = emptyList())
            }
            val routes = HashMap<String, LineSequence?>()
            fun routeOf(lineId: String): LineSequence? = routes.getOrPut(lineId) { sequenceAt(stop, lineId) }
            val departures = stop.departures.filter { departure ->
                val lineId = departure.lineId
                when {
                    // No line to follow: it may well call there, so never a silent "no".
                    lineId.isBlank() -> {
                        unresolved = true
                        false
                    }
                    lineId !in sequences -> {
                        pending = true
                        false
                    }
                    else -> {
                        val sequence = routeOf(lineId)
                        val bus = departure.mode.equals("bus", ignoreCase = true)
                        val path = sequence?.let {
                            RouteStops.ahead(it, stop.stopId, departure.destination, departure.branch, lineId, bus)
                        }
                        if (path == null) {
                            unresolved = true
                            false
                        } else {
                            path.drop(1).any { it.id in destinationIds }
                        }
                    }
                }
            }
            val lines = stop.lines.filter { line ->
                if (line.id !in sequences) {
                    pending = true
                    return@filter false
                }
                // A failed route can't say whether this line's status (a suspension) matters here.
                val sequence = routeOf(line.id) ?: run {
                    unresolved = true
                    return@filter false
                }
                reaches(sequence, stop.stopId, destinationIds)
            }
            stop.copy(departures = departures, lines = lines)
        }.filter { it.departures.isNotEmpty() || it.lines.isNotEmpty() || it.disruptions.isNotEmpty() }
        return Result(kept, pending, unresolved)
    }

    /** The lines to load routes for: every line departing from or declared at [stops]. */
    fun lineIds(stops: List<StopArrivals>): List<String> =
        stops.flatMap { stop -> stop.departures.map { it.lineId } + stop.lines.map { it.id } }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * [this] also knowing [stopId]'s interchange and name, where the station index didn't already
     * supply them ([LineSequence.withStations]) — a searched station TfL returned outside the index —
     * so [LineSequence.callingAt] can place it through a sibling id its routes call at.
     */
    private fun LineSequence.knowing(stopId: String, hubId: String, name: String): LineSequence =
        if (hubId.isBlank() || stopHubs.containsKey(stopId)) {
            this
        } else {
            copy(
                stopHubs = stopHubs + (stopId to hubId),
                stopNames = if (stopNames.containsKey(stopId)) stopNames else stopNames + (stopId to cleanStopName(name)),
            )
        }

    /** Whether some route of [sequence] calls at [originId] and later at one of [destinationIds]. */
    private fun reaches(sequence: LineSequence, originId: String, destinationIds: Set<String>): Boolean =
        sequence.routes.any { route ->
            val i = route.stopIds.indexOf(originId)
            i >= 0 && route.stopIds.drop(i + 1).any { it in destinationIds }
        }
}
