package app.stopcast.domain

/**
 * A stop's identity, position, and served lines — enough to rank it by distance for the
 * in-app "near me now" list (SPEC *Finding stops*) and then watch it. [lines] carries the
 * stop's served lines (a public fact about the stop, like its position) so a watched stop
 * can surface a disrupted line with no predictions as a status row (SPEC *Departures*),
 * without a second lookup. Coordinates are the user's business: they stay in memory for
 * the on-demand ranking and never reach a log or any artifact that leaves the device
 * (SPEC *Privacy*).
 */
data class StopLocation(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lines: List<LineRef> = emptyList(),
)
