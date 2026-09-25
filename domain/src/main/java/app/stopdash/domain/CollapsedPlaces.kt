package app.stopdash.domain

/**
 * The near-me list's collapsed farther places (SPEC *Finding stops → Farther stations*): each
 * station [FartherStations] offers and each bus place [FartherBuses] offers, shown as a card below
 * the loaded places that the rider taps to load and open in place. Worked out on the device from
 * the bundled station list and the nearby lookup already made: no request until a card is tapped.
 */
object CollapsedPlaces {
    /**
     * A collapsed place: its [key] (stable across a relocation that keeps it), the station it opens,
     * the name its card shows, how far it is, and the [lines] it adds that the list doesn't reach.
     * A bus place carries its [stops] already (the nearby lookup found them), so opening it needs no
     * lookup; a station's are looked up by [stationId] when it is tapped.
     */
    data class Place(
        val key: String,
        val stationId: String,
        val name: String,
        val meters: Double,
        val lines: List<LineRef>,
        val stops: List<StopLocation> = emptyList(),
    )

    /**
     * A farther station ([FartherStations.pick]) as a collapsed place, its lines named from
     * [lineNames] (the station list's names as TfL spells them). A line the list has no name for
     * (an older list) is named by its id, which TfL keeps close to the name.
     */
    fun of(farther: FartherStations.Farther, lineNames: Map<String, String> = emptyMap()): Place =
        Place(
            key = "station:${farther.station.id}",
            stationId = farther.station.id,
            name = farther.station.name,
            meters = farther.meters,
            lines = farther.lines.map { LineRef(it.id, lineNames[it.id] ?: it.id, it.mode) },
        )

    /** A farther bus place ([FartherBuses.pick]) as a collapsed place, named by its nearest pole. */
    fun of(farther: FartherBuses.Farther): Place =
        Place(
            key = BUS_KEY_PREFIX + farther.key,
            stationId = "",
            name = farther.stops.first().name,
            meters = farther.meters,
            lines = farther.lines,
            stops = farther.stops,
        )

    /** Whether [place] is a farther bus place ([of] a [FartherBuses.Farther]), not a station. */
    fun isBus(place: Place): Boolean = place.key.startsWith(BUS_KEY_PREFIX)

    private const val BUS_KEY_PREFIX = "bus:"

    /**
     * [places] with their bus places narrowed to the cards to show (SPEC *Finding stops → Farther
     * stations*), decided against [shownLineIds], the bus routes the screen shows: in order (nearest
     * first), each bus place keeps the routes neither shown nor named by an earlier bus card, a place
     * left with none is dropped, and at most [max] bus cards stay. A place in [opened] (tapped, so
     * its departures show under it) always stays, and takes its slot under the cap and its routes
     * before any other card is chosen, wherever it now sits. It still names only the routes not
     * shown, as it did before the tap, so a card that is loading, failed or has nothing running
     * doesn't start advertising a route it adds nothing for; once its own rows show every route it
     * names (it has opened, and its rows replace the card) it keeps them all. Stations pass through
     * unchanged.
     */
    fun withBusesPicked(
        places: List<Place>,
        shownLineIds: Set<String>,
        opened: Set<String> = emptySet(),
        max: Int = FartherBuses.MAX_CARDS,
    ): List<Place> {
        // Opened places are kept first, wherever a relocation has put them: they take their slots
        // under the cap, and their routes, which show under them, before any other card is chosen.
        val kept = places.filter { isBus(it) && it.key in opened }.associate { place ->
            place.key to place.copy(lines = place.lines.filter { it.id !in shownLineIds }.ifEmpty { place.lines })
        }
        val named = HashSet(shownLineIds)
        kept.values.flatMapTo(named) { place -> place.lines.map { it.id } }
        var buses = kept.size
        return places.mapNotNull { place ->
            if (!isBus(place)) return@mapNotNull place
            kept[place.key]?.let { return@mapNotNull it }
            if (buses >= max) return@mapNotNull null
            val lines = place.lines.filter { it.id !in named }
            if (lines.isEmpty()) return@mapNotNull null
            buses++
            named += lines.map { it.id }
            place.copy(lines = lines)
        }
    }

    /**
     * The cards in list order (maintainer, 2026-09-25): the stations within [withinMeters] (the
     * nearby lookup's mile), then the bus places, then the farther stations, each part nearest
     * first. The bus places all lie within that mile; they sit below its stations rather than mix
     * in among them by distance.
     */
    fun ordered(
        stations: List<Place>,
        buses: List<Place>,
        withinMeters: Double = NearbySelection.OUTER_RADIUS_METERS.toDouble(),
    ): List<Place> {
        val (near, far) = stations.partition { it.meters <= withinMeters }
        return near + buses + far
    }
}
