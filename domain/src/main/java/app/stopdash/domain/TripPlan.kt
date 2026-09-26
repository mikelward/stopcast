package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * One leg of a planned trip (SPEC *Trips with a change*): a ride on one line from [fromId] to [toId],
 * or a walk between them ([isWalk]). The times are TfL Journey Planner's timetable view — the lines
 * and changes are its, the live times are StopDash's own ([TripTiming]).
 *
 * [path] is the stop ids the leg calls at after boarding, through [toId]; [changeAfter] is the time
 * the Planner allows to change to the next leg.
 */
data class TripLeg(
    val mode: String,
    val lineId: String,
    val lineName: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val toName: String,
    val departure: Instant,
    val arrival: Instant,
    val path: List<String> = emptyList(),
    val changeAfter: Duration = Duration.ZERO,
) {
    val isWalk: Boolean get() = mode.equals(WALKING, ignoreCase = true)

    /** The Planner's time on board (or on foot). */
    val run: Duration get() = Duration.between(departure, arrival).coerceAtLeast(Duration.ZERO)

    /** How many stops the leg rides before getting off ("6 stops to Whitechapel"). */
    val stops: Int get() = path.size

    companion object {
        const val WALKING = "walking"
    }
}

/** One route the Planner offered: its [legs] in order. */
data class TripRoute(val legs: List<TripLeg>) {
    /** The legs ridden, walks left out: the line pills a route shows. */
    val rides: List<TripLeg> get() = legs.filterNot { it.isWalk }
}

/**
 * Plans a trip between two stops by TfL id, behind a domain interface so the trip's logic is tested
 * against recorded fixtures (SPEC *Testing*). Returns the Planner's routes in its own order; throws
 * a [TflException] on a transport or decode failure, as [TflClient] does.
 */
interface JourneyPlanner {
    suspend fun journeys(fromId: String, toId: String): List<TripRoute>
}
