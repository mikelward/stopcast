package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * A trip the rider has started (SPEC *On the way*): the planned [route] to [destinationName], the
 * leg they're on ([legIndex], into [TripRoute.legs]) since [legStartedAt] (for a ride, when the rider
 * can be at its boarding stop, from which its train is picked), and the train followed on
 * it ([vehicleId], blank until one is picked). [boarded] once that train has left the boarding stop
 * (the rider is taken to be on it); [dueOffAt] when that train was last seen due where the rider gets
 * off (null until it's predicted that far); [warnedLeg] is the leg whose "get off soon" has been said,
 * so it's said once. Kept on the device only: where a rider is going is theirs (SPEC *Privacy*).
 */
data class ActiveTrip(
    val route: TripRoute,
    val destinationName: String,
    val startedAt: Instant,
    val legIndex: Int = 0,
    val legStartedAt: Instant = startedAt,
    val vehicleId: String = "",
    val boarded: Boolean = false,
    val dueOffAt: Instant? = null,
    val warnedLeg: Int = -1,
) {
    /** The leg the rider is on, or null once they've arrived. */
    val leg: TripLeg? get() = route.legs.getOrNull(legIndex)
}

/** Where a started trip stands, for its screen, banner and notification (SPEC *On the way*). */
sealed interface TripProgress {
    /** Waiting at [leg]'s boarding stop for the train followed, due at [due] (null: none followed yet). */
    data class Waiting(val leg: TripLeg, val due: Instant?) : TripProgress

    /**
     * On [leg]'s train: next at [nextStop], getting off in [stopsLeft] stops (counting the stop
     * itself), expected there at [getOffAt] — null while that stop is beyond TfL's predictions, when
     * only the stops are counted. [getOffSoon] from one stop, or two minutes, out.
     */
    data class Riding(
        val leg: TripLeg,
        val nextStop: String,
        val stopsLeft: Int,
        val getOffAt: Instant?,
        val getOffSoon: Boolean,
    ) : TripProgress

    /** On foot along [leg] (a walk to a change or the destination), until about [until]. */
    data class Walking(val leg: TripLeg, val until: Instant) : TripProgress

    /**
     * The train followed can't be placed on [leg] — it doesn't call at the boarding stop or where
     * the rider gets off (the wrong train, or TfL lost it) — so another is to be picked; nothing is
     * claimed meanwhile (SPEC principle 1).
     */
    data class Lost(val leg: TripLeg) : TripProgress

    data object Arrived : TripProgress
}

/**
 * Follows a started trip from its train's calls (SPEC *On the way*). Pure: the caller fetches the
 * followed train's calls ([VehicleSource]) and the boarding stop's departures, and keeps the
 * [ActiveTrip] this returns.
 */
object OnTheWay {
    /** "Get off soon" from this many stops out, the stop itself counted as one. */
    const val GET_OFF_SOON_STOPS = 1

    /** …or from this long before the train is due where the rider gets off. */
    val GET_OFF_SOON_TIME: Duration = Duration.ofMinutes(2)

    /**
     * The train to follow for a leg: the soonest of its [trains] (the leg's line, heading its way,
     * as the trip lists them) that TfL names and the rider can reach by [readyAt]. The maintainer's
     * rule (2026-09-26): assume the next catchable train, and switch once another is seen to be the
     * one they're on.
     */
    fun pickTrain(trains: List<Departure>, readyAt: Instant): Departure? =
        trains.filter { it.vehicleId.isNotBlank() && !it.expectedArrival.isBefore(readyAt) }.minByOrNull { it.expectedArrival }

    /**
     * The trains [leg] could be followed on, soonest first: its line's departures at its boarding
     * stop that TfL names and the rider can reach by [readyAt], each once. Which of them runs where
     * the rider is going is for their calls to say ([runsAlong]).
     */
    fun candidates(departures: List<Departure>, leg: TripLeg, readyAt: Instant): List<Departure> =
        departures.filter { it.lineId == leg.lineId && it.vehicleId.isNotBlank() && !it.expectedArrival.isBefore(readyAt) }
            .sortedBy { it.expectedArrival }
            .distinctBy { it.vehicleId }

    /**
     * Whether a train with [calls] ahead of it takes [leg]: it calls at the boarding stop, and later
     * where the rider gets off — not a train the other way, or to another branch. TfL predicts only
     * so far ahead ([VehicleSource]), so a train whose predictions end first takes the leg when every
     * stop predicted after boarding is on the leg's path.
     */
    fun runsAlong(leg: TripLeg, calls: List<VehicleCall>): Boolean {
        val on = calls.indexOfFirst { calls(it, leg.fromId, leg.fromName) }
        if (on < 0) return false
        val ahead = calls.drop(on + 1)
        return ahead.any { calls(it, leg.toId, leg.toName) } || (ahead.isNotEmpty() && ahead.all { onPath(leg, it) >= 0 })
    }

    /** [trip] following [train] on its current leg, not yet on board. */
    fun follow(trip: ActiveTrip, train: Departure): ActiveTrip = trip.copy(vehicleId = train.vehicleId, boarded = false)

    /**
     * [trip] brought up to date at [now] from its followed train's [calls] (null when they couldn't
     * be fetched, or no train is followed): the trip as it now stands — moved on to the next leg once
     * the rider has got off, or a walk has had its time — and where it stands. Moves at most as far
     * as the calls show; a leg reached here waits for its own train to be picked. The calls are a
     * prediction window ([VehicleSource]): the stop missing from them is not yet predicted, not
     * passed, while the train's next stop is still on the leg; and an empty list ends a ride only once
     * the rider was seen due off.
     */
    fun advance(trip: ActiveTrip, calls: List<VehicleCall>?, now: Instant): Pair<ActiveTrip, TripProgress> {
        val leg = trip.leg ?: return trip to TripProgress.Arrived
        if (leg.isWalk) {
            val until = trip.legStartedAt.plus(leg.run)
            return if (now.isBefore(until)) trip to TripProgress.Walking(leg, until) else nextLeg(trip, now)
        }
        if (trip.vehicleId.isBlank() || calls == null) {
            return trip to if (trip.boarded) TripProgress.Lost(leg) else TripProgress.Waiting(leg, null)
        }
        val boardingCall = calls.firstOrNull { calls(it, leg.fromId, leg.fromName) }
        if (boardingCall != null) return trip.copy(boarded = false) to TripProgress.Waiting(leg, boardingCall.expected)
        val off = calls.indexOfFirst { calls(it, leg.toId, leg.toName) }
        if (off < 0) {
            val next = calls.firstOrNull()
            val along = next?.let { onPath(leg, it) } ?: -1
            if (along >= 0) {
                // Still on the leg, with the stop beyond the predictions: count the stops, claim no time.
                val stopsLeft = (leg.path.indexOf(leg.toId).takeIf { it >= 0 } ?: leg.path.lastIndex) - along + 1
                val soon = stopsLeft <= GET_OFF_SOON_STOPS
                return trip.copy(boarded = true) to TripProgress.Riding(leg, next!!.stopName, stopsLeft, null, soon)
            }
            val dueOff = trip.dueOffAt
            val gotOff = if (calls.isEmpty()) dueOff != null && !now.isBefore(dueOff) else trip.boarded
            // Past the stop: the rider got off there. Otherwise the train was never theirs, or TfL has
            // stopped predicting it before the rider was due off — nothing is claimed.
            return if (gotOff) nextLeg(trip, now) else trip to TripProgress.Lost(leg)
        }
        val getOffAt = calls[off].expected
        val stopsLeft = off + 1
        val soon = stopsLeft <= GET_OFF_SOON_STOPS || !now.plus(GET_OFF_SOON_TIME).isBefore(getOffAt)
        val riding = trip.copy(boarded = true, dueOffAt = getOffAt)
        return riding to TripProgress.Riding(leg, calls.first().stopName, stopsLeft, getOffAt, soon)
    }

    /** [trip] with its "get off soon" said for the leg it's on, so it isn't said again. */
    fun warned(trip: ActiveTrip): ActiveTrip = trip.copy(warnedLeg = trip.legIndex)

    /** Whether [progress] calls for "get off soon" not yet said on [trip]. */
    fun shouldWarn(trip: ActiveTrip, progress: TripProgress): Boolean =
        progress is TripProgress.Riding && progress.getOffSoon && trip.warnedLeg != trip.legIndex

    // The next leg, from [now] plus the change the Planner allows after this one (a change with no
    // walk leg of its own): a walk's time runs from there, and a ride's train is picked from there.
    private fun nextLeg(trip: ActiveTrip, now: Instant): Pair<ActiveTrip, TripProgress> {
        val from = now.plus(trip.leg?.changeAfter ?: Duration.ZERO)
        val next = trip.copy(legIndex = trip.legIndex + 1, legStartedAt = from, vehicleId = "", boarded = false, dueOffAt = null)
        val leg = next.leg ?: return next to TripProgress.Arrived
        return next to if (leg.isWalk) TripProgress.Walking(leg, from.plus(leg.run)) else TripProgress.Waiting(leg, null)
    }

    // Where [call] is along [leg]'s path (its stops after boarding), or -1 when it isn't.
    private fun onPath(leg: TripLeg, call: VehicleCall): Int = leg.path.indexOf(call.stopId)

    // Whether [call] is at the stop [id] (or, by name, the same place: TfL's arrivals can name a
    // station by another of its ids).
    private fun calls(call: VehicleCall, id: String, name: String): Boolean =
        call.stopId == id || (name.isNotBlank() && call.stopName.equals(cleanStopName(name), ignoreCase = true))
}
