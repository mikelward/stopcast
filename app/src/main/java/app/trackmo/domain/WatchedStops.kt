package app.trackmo.domain

/**
 * Pure add/remove rules for the watched-stop set, kept out of the store so the membership
 * logic is JVM-testable without DataStore or Android. The store applies these inside its
 * atomic update; the order the list keeps here is the order surfaces show the stops in
 * before soonest-first ranking and starring reorder them (SPEC D8).
 */
object WatchedStops {
    /**
     * [current] with [stop] watched. Membership is by [WatchedStop.id], so adding a stop that
     * is already watched is a **no-op** — the existing entry is kept unchanged, in place,
     * rather than duplicated or moved to the end. (Refreshing a watched stop's name/lines is
     * the departures path's job, which carries fresh values per stop; this function only
     * decides membership.) A genuinely new stop is appended, so the newest addition sits last
     * until ranking reorders the list.
     */
    fun add(current: List<WatchedStop>, stop: WatchedStop): List<WatchedStop> =
        if (current.any { it.id == stop.id }) current else current + stop

    /**
     * [current] with the stop identified by [stopId] removed — and with it all of that stop's
     * rows, since a row belongs to a stop (SPEC *Watched stops*). Removing an id that isn't
     * watched is a no-op.
     */
    fun remove(current: List<WatchedStop>, stopId: String): List<WatchedStop> =
        current.filterNot { it.id == stopId }
}
