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
    // The cluster this stop belongs to (SPEC *Finding stops*): TfL's `stationNaptan` — a bus
    // junction's poles and a station's platforms share it — else the display name when TfL gives
    // none, so same-named poles still group. It keys the per-place header (SPEC D8) instead of the
    // name alone, which TfL spells several ways for one station. Blank means "group this stop on
    // its own". Not a coordinate; safe to carry (SPEC *Privacy*).
    val clusterId: String = "",
    // TfL's `hubNaptanCode` for this stop: the interchange it belongs to (`HUBKGX` ties King's
    // Cross and St Pancras together), above `stationNaptan`. Blank for a stop in no hub. Used to
    // fold an interchange's shared disruption into one alert and title it by the interchange, and
    // to keep two genuinely distinct same-named places apart (SPEC *Disruptions*). A public id,
    // not a coordinate; safe to carry (SPEC *Privacy*).
    val hubId: String = "",
)
