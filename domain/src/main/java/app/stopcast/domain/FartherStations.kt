package app.stopcast.domain

/**
 * The "From …" buttons at the foot of the near-me list (SPEC *Finding stops → Farther
 * stations*): when the list reaches no station of a tube line, or of another rail mode, the
 * nearest bundled station that does — "From Crouch Hill…" for the Overground two miles off —
 * so a rider far from the rest of the network can look at it in one tap. Worked out on the
 * device from the bundled [StationIndex] (its stations' public positions and tube lines): no
 * request, and nothing fetched until a button is tapped.
 */
object FartherStations {
    /** Beyond this a station isn't the rider's in any useful sense: 3 mi. */
    const val MAX_METERS = 4_828.0

    /** At most this many tube-line buttons, so a far corner of London isn't a wall of them. */
    const val MAX_TUBE_LINES = 2

    /** The modes named once each (the tube is named per line; buses and boats not at all). */
    val MODES: List<String> = listOf("overground", "elizabeth-line", "national-rail", "dlr", "tram")

    /** A button: the station to open, and how far it is from the rider. */
    data class Farther(val station: StationMatch, val meters: Double)

    /**
     * The buttons for a rider at [from], nearest first. [reachedLines] and [reachedModes] are the
     * tube lines and modes the near-me lookup already reaches (every tier, "More" included) — no
     * button for those; [hidden] modes get none either. For each unreached tube line, the nearest
     * station serving it (at most [MAX_TUBE_LINES] such buttons, nearest lines first), and for each
     * unreached mode of [MODES], the nearest station of that mode — one button per place however
     * many it stands for, and only within [MAX_METERS]. A station in an interchange stands for the
     * interchange: its button opens the hub (whose page holds all its stations), so Stratford's
     * tube, DLR and rail stations are one "From Stratford…". Unplaced stations are skipped.
     */
    fun pick(
        stations: List<IndexedStation>,
        from: Coordinates,
        reachedLines: Set<String>,
        reachedModes: Set<String>,
        hidden: Set<String> = emptySet(),
    ): List<Farther> {
        val placed = stations.mapNotNull { station ->
            val lat = station.latitude
            val lon = station.longitude
            if (lat == null || lon == null || station.id.startsWith("HUB", ignoreCase = true)) return@mapNotNull null
            val meters = NearestStops.distanceMeters(from.latitude, from.longitude, lat, lon)
            if (meters > MAX_METERS) null else station to meters
        }.sortedBy { it.second }
        val byId = stations.associateBy { it.id }
        // The place a station stands for: its interchange when it has one, else itself.
        fun placeOf(station: IndexedStation): IndexedStation =
            station.hubId.takeIf { it.isNotBlank() }?.let(byId::get) ?: station
        val picked = LinkedHashMap<String, Farther>()
        fun take(station: IndexedStation, meters: Double) {
            val place = placeOf(station)
            picked.getOrPut(place.id) { Farther(StationMatch(place.id, place.name, place.modes), meters) }
        }
        if (!HiddenModes.isHidden("tube", hidden)) {
            // Each unreached line's nearest station, nearest lines first; a station serving two of
            // them is one button, and counts once toward the cap.
            val nearestByLine = LinkedHashMap<String, Pair<IndexedStation, Double>>()
            for ((station, meters) in placed) {
                for (line in station.tubeLines) {
                    if (line !in reachedLines) nearestByLine.putIfAbsent(line, station to meters)
                }
            }
            val tubeStations = nearestByLine.values.sortedBy { it.second }.map { it.first to it.second }
                .distinctBy { placeOf(it.first).id }
                .take(MAX_TUBE_LINES)
            for ((station, meters) in tubeStations) take(station, meters)
        }
        for (mode in MODES) {
            if (mode in reachedModes || HiddenModes.isHidden(mode, hidden)) continue
            placed.firstOrNull { (station, _) -> mode in station.modes }?.let { (station, meters) -> take(station, meters) }
        }
        return picked.values.sortedBy { it.meters }
    }
}
