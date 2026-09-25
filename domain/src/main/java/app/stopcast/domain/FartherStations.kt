package app.stopcast.domain

/**
 * The "From …" buttons at the foot of the near-me list (SPEC *Finding stops → Farther
 * stations*): when the list reaches no station of a rail line — a tube line, a National Rail
 * service, an Overground line, the Elizabeth line, the DLR, a tram — the nearest bundled station
 * that does, so a rider far from the rest of the network can look at it in one tap. A National
 * Rail service counts by the **ends** its trains run to from a station, where the index knows its
 * routes: Thameslink to Bedford from one station and to Cambridge from another are two reasons for
 * a button, not one. Worked out
 * on the device from the bundled [StationIndex] (its stations' public positions and lines): no
 * request, and nothing fetched until a button is tapped. Buses are left out: every stop has them.
 */
object FartherStations {
    /** Beyond this a station isn't the rider's in any useful sense: 3 mi. */
    const val MAX_METERS = 4_828.0

    /** At most this many buttons in all, so a far corner of London isn't a wall of them. */
    const val MAX_BUTTONS = 5

    /** Of which at most this many for tube lines. */
    const val MAX_TUBE_BUTTONS = 2

    /** The rail modes whose lines earn a button (buses and boats never do). */
    val MODES: Set<String> = setOf("tube", "overground", "elizabeth-line", "national-rail", "dlr", "tram")

    /** A button: the station to open, and how far it is from the rider. */
    data class Farther(val station: StationMatch, val meters: Double)

    /** A line of a mode (TfL line ids are unique across modes, but a mode is kept to cap the tube). */
    data class Line(val mode: String, val id: String)

    /**
     * One stop the near-me lookup reached: its ids (stop, cluster, interchange — any may be an index
     * station) and the lines it serves as the lookup saw them.
     */
    data class ReachedStop(val ids: Set<String>, val lines: Set<Line>)

    /** What earns a button: a line, or for a National Rail service with route data, one of its ends. */
    private data class Reason(val line: Line, val end: String?)

    /** The reasons [station] stands for: its lines, a National Rail service split by route end. */
    private fun reasonsOf(station: IndexedStation): List<Reason> =
        station.lines.flatMap { (mode, ids) ->
            ids.flatMap { id ->
                val line = Line(mode, id)
                val ends = if (mode == "national-rail") station.routeEnds[id].orEmpty() else emptyList()
                if (ends.isEmpty()) listOf(Reason(line, null)) else ends.map { Reason(line, it) }
            }
        }

    /**
     * The buttons for a rider at [from], nearest first. [reached] are the stops the near-me lookup
     * already reaches (every tier, "More" included): each one's lines, and where its index record
     * knows a National Rail service's routes, just the ends it reaches — no button for any of that;
     * a [hidden] mode's lines get none either. A service at any reached stop whose routes the index
     * doesn't know counts as reached whole, so a gap in the data costs a button, never adds a
     * wrong one. For each unreached line (or National Rail route end)
     * of [MODES], the nearest station serving it, within [MAX_METERS]; then, nearest first, a button
     * for each such station unless a nearer button's place already stands for it — one per place,
     * at most [MAX_TUBE_BUTTONS] of them for tube lines and [MAX_BUTTONS] in all. A station in an interchange stands for the interchange: its
     * button opens the hub (whose page holds all its stations), so Stratford's tube, DLR and rail
     * stations are one "From Stratford…". Unplaced stations are skipped.
     */
    fun pick(
        stations: List<IndexedStation>,
        from: Coordinates,
        reached: List<ReachedStop>,
        hidden: Set<String> = emptySet(),
    ): List<Farther> {
        val byId = stations.associateBy { it.id }
        // Route ends reached from a stop whose record knows them; lines reached whole otherwise.
        val reachedEnds = HashSet<Reason>()
        val reachedWhole = HashSet<Line>()
        for (stop in reached) {
            // The live lookup's lines decide what the stop serves; the index (a weekly snapshot)
            // only adds route detail to them, or stands in when the lookup listed no lines at all.
            val reasons = stop.ids.mapNotNull(byId::get).flatMap(::reasonsOf)
                .filter { stop.lines.isEmpty() || it.line in stop.lines }
            val detailed = reasons.filter { it.end != null }
            reachedEnds += detailed
            // The stop itself is an end its services reach: a terminus nearby makes a farther station
            // whose route ends there add nothing (the index leaves a station out of its own ends).
            for (line in stop.lines) {
                if (line.mode == "national-rail") for (id in stop.ids) reachedEnds += Reason(line, id)
            }
            val known = detailed.mapTo(HashSet()) { it.line }
            reachedWhole += (stop.lines + reasons.map { it.line }).filter { it !in known }
        }
        // A service reached by route somewhere: a farther record of it with no route data can't say
        // it adds an end, so it counts as reached (TfL can list one station twice, one record bare).
        val reachedByRoute = reachedEnds.mapTo(HashSet()) { it.line }
        fun isReached(reason: Reason): Boolean =
            reason.line in reachedWhole || reason in reachedEnds || (reason.end == null && reason.line in reachedByRoute)
        // The place a station stands for: its interchange when it has one, else itself.
        fun placeOf(station: IndexedStation): IndexedStation =
            station.hubId.takeIf { it.isNotBlank() }?.let(byId::get) ?: station
        // A place the list already shows is never a button, whatever its index record claims.
        val shownIds = reached.flatMapTo(HashSet()) { it.ids }
        val nearestByReason = LinkedHashMap<Reason, Pair<IndexedStation, Double>>()
        stations.mapNotNull { station ->
            val lat = station.latitude
            val lon = station.longitude
            if (lat == null || lon == null || station.id.startsWith("HUB", ignoreCase = true)) return@mapNotNull null
            if (station.id in shownIds || placeOf(station).id in shownIds) return@mapNotNull null
            val meters = NearestStops.distanceMeters(from.latitude, from.longitude, lat, lon)
            if (meters > MAX_METERS) null else station to meters
        }.sortedBy { it.second }.forEach { (station, meters) ->
            for (reason in reasonsOf(station)) {
                val mode = reason.line.mode
                if (mode !in MODES || HiddenModes.isHidden(mode, hidden) || isReached(reason)) continue
                nearestByReason.putIfAbsent(reason, station to meters)
            }
        }
        val picked = LinkedHashMap<String, Farther>()
        var tubeButtons = 0
        for ((reason, nearest) in nearestByReason.entries.sortedBy { it.value.second }) {
            if (picked.size >= MAX_BUTTONS) break
            val (station, meters) = nearest
            val place = placeOf(station)
            if (place.id in picked) continue
            if (reason.line.mode == "tube") {
                if (tubeButtons >= MAX_TUBE_BUTTONS) continue
                tubeButtons++
            }
            picked[place.id] = Farther(StationMatch(place.id, place.name, place.modes), meters)
        }
        return picked.values.toList()
    }
}
