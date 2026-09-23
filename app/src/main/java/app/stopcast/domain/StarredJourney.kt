package app.stopcast.domain

/**
 * One end of a [StarredJourney]: a station the rider boards or alights at, with its published
 * position (TfL's, from the route sequence — never the rider's own fix) so the nearer end can be
 * picked. Position is null when TfL gave none; such a journey keeps its saved direction.
 */
data class JourneyEnd(
    val stopId: String,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * A starred journey (SPEC *Journeys*): two stations on one line the rider travels between, both
 * ways — "Highgate ↔ King's Cross St. Pancras" on the Northern line. Direct only: the trains shown
 * are [lineId]'s from one end that call at the other; a journey needing a change is routing, a
 * non-goal for now. [from] → [to] is the saved orientation; the near-me list shows whichever end
 * is nearer as the origin ([Journeys.oriented]). Stored on the device like the starred rows and,
 * like them, never logged (a pair of stations can reveal home and work).
 */
data class StarredJourney(
    val from: JourneyEnd,
    val to: JourneyEnd,
    val lineId: String,
    // The line's display name and TfL mode, so a farther origin's fetch declares the line and its
    // status is checked even when no trains are predicted (a suspension). Not part of [key].
    val lineName: String = "",
    val mode: String = "",
) {
    /** Direction-free identity: the same journey starred from either end is one journey. */
    val key: String get() = listOf(from.stopId, to.stopId).sorted().joinToString("|") + "|" + lineId

    fun reversed(): StarredJourney = copy(from = to, to = from)

    /** The line as a stop declares it, for fetching the origin. */
    val line: LineRef get() = LineRef(lineId, lineName, mode)
}

object Journeys {
    /** Flip [journey] in or out of [starred], matched by [StarredJourney.key]. */
    fun toggle(starred: List<StarredJourney>, journey: StarredJourney): List<StarredJourney> =
        if (starred.any { it.key == journey.key }) starred.filterNot { it.key == journey.key } else starred + journey

    /**
     * [journey] with the end nearer ([latitude], [longitude]) as its origin, so the list shows the
     * trains the rider can catch from where they are. Without a position (a location-free list) or
     * an end's coordinates, the saved orientation stands.
     */
    fun oriented(journey: StarredJourney, latitude: Double?, longitude: Double?): StarredJourney {
        if (latitude == null || longitude == null) return journey
        val fromMeters = distanceTo(journey.from, latitude, longitude) ?: return journey
        val toMeters = distanceTo(journey.to, latitude, longitude) ?: return journey
        return if (toMeters < fromMeters) journey.reversed() else journey
    }

    private fun distanceTo(end: JourneyEnd, latitude: Double, longitude: Double): Double? {
        val lat = end.latitude ?: return null
        val lon = end.longitude ?: return null
        return NearestStops.distanceMeters(latitude, longitude, lat, lon)
    }

    /**
     * The rows for [journey] (already [oriented]): [rows] at its origin on its line, each keeping
     * only the departures that call at its destination — "Bank-branch trains" from Highgate toward
     * King's Cross, not the Charing Cross ones. Whether a train calls there comes from the line's
     * route [sequence] ([RouteStops.ahead]); a train whose path can't be resolved is left out
     * rather than guessed (SPEC principle 1). Null when [sequence] isn't loaded yet, so the card can
     * say it's checking rather than claim there are no trains.
     */
    fun rows(journey: StarredJourney, rows: List<DepartureRow>, sequence: LineSequence?): List<DepartureRow>? {
        sequence ?: return null
        return rows
            .filter { it.stopId == journey.from.stopId && it.lineId == journey.lineId && it.stopDisruption == null }
            .mapNotNull { row ->
                // A disrupted line with no predictions (a suspension) is a status-only row: kept, so
                // the card shows the warning rather than a bare "no trains" (SPEC principle 1).
                if (row.upcoming.isEmpty()) return@mapNotNull row.takeIf { it.status != null }
                val calling = row.upcoming.filter { departure ->
                    RouteStops.ahead(
                        sequence,
                        journey.from.stopId,
                        departure.destination,
                        departure.branch,
                        journey.lineId,
                    )?.any { it.id == journey.to.stopId } == true
                }
                if (calling.isEmpty()) null else row.copy(upcoming = calling, destination = calling.first().destination)
            }
    }

    /**
     * Whether any of the origin's trains on [journey]'s line has a path [sequence] can't resolve
     * (an unknown or ambiguous destination) — such a train is left out of [rows] but may call at the
     * far end, so an empty result isn't a "no trains" the card can claim (SPEC principle 1).
     */
    fun anyUnresolved(journey: StarredJourney, rows: List<DepartureRow>, sequence: LineSequence): Boolean =
        rows.any { row ->
            row.stopId == journey.from.stopId && row.lineId == journey.lineId && row.stopDisruption == null &&
                row.upcoming.any { departure ->
                    RouteStops.ahead(sequence, journey.from.stopId, departure.destination, departure.branch, journey.lineId) == null
                }
        }
}

/**
 * Reads and writes the starred journeys (SPEC *Journeys*). A seam so a ViewModel depends on the
 * capability, not DataStore; mirrors [StarredRowsStore]. [journeys] emits the saved list at once
 * and on every change — null when a stored list exists that this build can't read (a newer schema),
 * which the store then preserves rather than overwrite. [toggle] runs off the main thread.
 */
interface StarredJourneysStore {
    fun journeys(): kotlinx.coroutines.flow.Flow<List<StarredJourney>?>

    suspend fun toggle(journey: StarredJourney)

    companion object {
        /** Persists nothing and reads an empty list: tests and an unwired build. */
        val NONE: StarredJourneysStore = object : StarredJourneysStore {
            override fun journeys() = kotlinx.coroutines.flow.flowOf<List<StarredJourney>?>(emptyList())
            override suspend fun toggle(journey: StarredJourney) {}
        }
    }
}
