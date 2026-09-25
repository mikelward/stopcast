package app.stopdash.domain

/**
 * The near-me list's farther bus cards (SPEC *Finding stops → Farther stations*): the bus-stop
 * counterpart of [FartherStations], replacing the "More bus stops" button (maintainer,
 * 2026-09-25). Each card is a farther bus place — a junction's poles, or an interchange's —
 * from the nearby lookup's *more* tier that adds a route the list doesn't already show, so
 * the rider sees which routes a tap would bring in. Offering the cards costs no request: the
 * lookup already listed each stop's routes. A tap fetches the place's poles.
 */
object FartherBuses {
    /** At most this many bus places, so a busy corner isn't a wall of cards. */
    const val MAX_CARDS = 4

    const val MODE = "bus"

    /**
     * A farther bus place: its [key] (the interchange and name its poles share, else the junction),
     * its poles nearest first, how far its nearest pole is, and its bus routes, in the order its
     * poles list them.
     */
    data class Farther(val key: String, val stops: List<StopLocation>, val meters: Double, val lines: List<LineRef>)

    /**
     * The candidate bus places in [more] (the *more* tier, nearest first), nearest first, each with
     * every bus route its poles serve. A route-less stop is no candidate, it has no departures;
     * hidden buses ([hidden]) give none. Clusters sharing an interchange and a name are one place. Which of them become cards, and which routes each names, is decided
     * against the rows the screen actually shows ([CollapsedPlaces.withBusesPicked]), so this never
     * has to guess what the list shows.
     */
    fun candidates(
        more: List<NearbySelection.NearbyCluster>,
        hidden: Set<String> = emptySet(),
    ): List<Farther> {
        if (HiddenModes.isHidden(MODE, hidden)) return emptyList()
        // Places in first-seen order, which is nearest first: [more] is distance-ordered.
        val places = LinkedHashMap<String, MutableList<NearbySelection.NearbyCluster>>()
        for (cluster in more) {
            // TfL's mode ids aren't guaranteed lowercase ([HiddenModes]).
            if (cluster.modes.none { it.equals(MODE, ignoreCase = true) }) continue
            // An interchange's clusters are one place only where they share a name: a card is named
            // after its nearest pole, so a differently named constituent would show its routes under
            // a stop that doesn't serve them.
            val hub = cluster.stops.firstNotNullOfOrNull { it.hubId.takeIf(String::isNotBlank) }
            val key = hub?.let { "hub:$it|${cluster.stops.first().name}" } ?: cluster.key
            places.getOrPut(key) { mutableListOf() } += cluster
        }
        return places.mapNotNull { (key, clusters) ->
            val stops = clusters.flatMap { it.stops }
            val lines = LinkedHashMap<String, LineRef>()
            for (stop in stops) {
                for (line in stop.lines) {
                    if (line.mode.equals(MODE, ignoreCase = true) && line.id.isNotBlank()) lines.putIfAbsent(line.id, line)
                }
            }
            if (lines.isEmpty()) null else Farther(key, stops, clusters.minOf { it.distanceMeters }, lines.values.toList())
        }
    }
}
