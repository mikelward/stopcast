package app.stopcast.domain

/**
 * The route a user tapped on a card — its [destination] and the TfL [branch] of the row's soonest
 * train — so the detail follows that route rather than whichever of the row's trains is soonest (a
 * card shows one route row per destination and branch of a line and direction).
 *
 * The raw branch, not the row's topology merge key, is what's kept: the topology can change while
 * the page is open (it loads asynchronously on a cold start), and a stored key would stop matching.
 * Which trains count as this route is worked out when it's used ([followedDeparture]), under the
 * topology in force then.
 */
data class RouteFocus(val destination: String, val branch: String?) {
    companion object {
        /** The focus for a card's route row. */
        fun of(group: DestinationGroup) = RouteFocus(group.destination, group.times.firstOrNull()?.branch)
    }
}

/**
 * The departure the detail follows: the soonest on the [focus] route — the trains the card groups
 * into that row under [topology] (a merged row can mix branches that share the path from here) —
 * else, once that route has no trains left, the row's soonest. The page's title follows the same
 * train, so the title and the stop list always agree. It never slips to another route row's trains
 * (the same destination on a branch the card shows as its own row).
 */
fun followedDeparture(row: DepartureRow, focus: RouteFocus?, topology: RouteTopology = RouteTopology.EMPTY): Departure? {
    if (focus != null) {
        val route = topology.grouping(row.lineId, row.stopId, focus.destination, focus.branch).mergeKey
        row.upcoming.firstOrNull {
            it.destination == focus.destination &&
                topology.grouping(row.lineId, row.stopId, it.destination, it.branch).mergeKey == route
        }?.let { return it }
    }
    return row.upcoming.firstOrNull()
}
