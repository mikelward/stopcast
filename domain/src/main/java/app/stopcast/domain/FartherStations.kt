package app.stopcast.domain

/**
 * The "From …" buttons at the foot of the near-me list (SPEC *Finding stops → Farther
 * stations*): when the list reaches no station of a rail line — a tube line, a National Rail
 * service, an Overground line, the Elizabeth line, the DLR, a tram — the nearest bundled station
 * that does, so a rider far from the rest of the network can look at it in one tap. Worked out
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
     * The buttons for a rider at [from], nearest first. [reached] are the lines the near-me lookup
     * already reaches (every tier, "More" included) — no button for those; a [hidden] mode's lines
     * get none either. For each unreached line of [MODES], the nearest station serving it, within
     * [MAX_METERS]; then, nearest first, a button for each such station unless a nearer button's
     * place already stands for it — one per place, at most [MAX_TUBE_BUTTONS] of them for tube
     * lines and [MAX_BUTTONS] in all. A station in an interchange stands for the interchange: its
     * button opens the hub (whose page holds all its stations), so Stratford's tube, DLR and rail
     * stations are one "From Stratford…". Unplaced stations are skipped.
     */
    fun pick(
        stations: List<IndexedStation>,
        from: Coordinates,
        reached: Set<Line>,
        hidden: Set<String> = emptySet(),
    ): List<Farther> {
        val byId = stations.associateBy { it.id }
        // The place a station stands for: its interchange when it has one, else itself.
        fun placeOf(station: IndexedStation): IndexedStation =
            station.hubId.takeIf { it.isNotBlank() }?.let(byId::get) ?: station
        val nearestByLine = LinkedHashMap<Line, Pair<IndexedStation, Double>>()
        stations.mapNotNull { station ->
            val lat = station.latitude
            val lon = station.longitude
            if (lat == null || lon == null || station.id.startsWith("HUB", ignoreCase = true)) return@mapNotNull null
            val meters = NearestStops.distanceMeters(from.latitude, from.longitude, lat, lon)
            if (meters > MAX_METERS) null else station to meters
        }.sortedBy { it.second }.forEach { (station, meters) ->
            for ((mode, ids) in station.lines) {
                if (mode !in MODES || HiddenModes.isHidden(mode, hidden)) continue
                for (id in ids) {
                    val line = Line(mode, id)
                    if (line !in reached) nearestByLine.putIfAbsent(line, station to meters)
                }
            }
        }
        val picked = LinkedHashMap<String, Farther>()
        var tubeButtons = 0
        for ((line, nearest) in nearestByLine.entries.sortedBy { it.value.second }) {
            if (picked.size >= MAX_BUTTONS) break
            val (station, meters) = nearest
            val place = placeOf(station)
            if (place.id in picked) continue
            if (line.mode == "tube") {
                if (tubeButtons >= MAX_TUBE_BUTTONS) continue
                tubeButtons++
            }
            picked[place.id] = Farther(StationMatch(place.id, place.name, place.modes), meters)
        }
        return picked.values.toList()
    }
}
