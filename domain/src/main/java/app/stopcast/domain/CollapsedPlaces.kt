package app.stopcast.domain

/**
 * The near-me list's collapsed farther places (SPEC *Finding stops → Farther stations*): each
 * station [FartherStations] offers, shown as a card below the loaded places that the rider taps to
 * load and open in place. Worked out on the device from the bundled station list: no request until
 * a card is tapped.
 */
object CollapsedPlaces {
    /**
     * A collapsed place: its [key] (stable across a relocation that keeps it), the station it opens,
     * the name its card shows, how far it is, and the [lines] it adds that the list doesn't reach.
     */
    data class Place(
        val key: String,
        val stationId: String,
        val name: String,
        val meters: Double,
        val lines: List<LineRef>,
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
}
