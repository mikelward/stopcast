package app.stopcast.domain

/**
 * A [LocationProvider] that always answers with one place: a searched station, standing in for the
 * rider's position so its page reads like the near-me list from there (SPEC *Finding stops →
 * From… To…*). A station's public location, not the device's, so it is never a fallback and never
 * moves; a re-locate from it finds the same set.
 */
class FixedLocation(private val at: Coordinates) : LocationProvider {
    override suspend fun current(forceFresh: Boolean): LocationFix = LocationFix(at, isFallback = false)

    companion object {
        /**
         * The middle of [stops] (a station's platforms and entrances): the mean of their positions,
         * leaving out any TfL gave without one (0, 0). Null when none has a position.
         */
        fun centerOf(stops: List<StopLocation>): Coordinates? {
            val placed = stops.filter { it.latitude != 0.0 || it.longitude != 0.0 }
            if (placed.isEmpty()) return null
            return Coordinates(placed.sumOf { it.latitude } / placed.size, placed.sumOf { it.longitude } / placed.size)
        }
    }
}
