package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a started trip, on synthetic stops A–E and example vehicle ids. */
class OnTheWayTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "C", "C", "D", "D", at(15), at(20))
    private val second = TripLeg("tube", "blue", "Blue", "D", "D", "E", "E", at(22), at(30), path = listOf("E"))
    private val trip = ActiveTrip(TripRoute(listOf(ride, walk, second)), "E", startedAt = t0)

    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))
    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)

    @Test
    fun `Start follows the next train the rider can catch`() {
        val trains = listOf(train("7", 2), train("", 4), train("9", 6), train("8", 5))
        // Ready at 4 min: the 2-minute train is gone, the 4-minute one has no id to follow.
        assertEquals("8", OnTheWay.pickTrain(trains, at(4))?.vehicleId)
        assertNull(OnTheWay.pickTrain(trains, at(7)))
    }

    @Test
    fun `waits while the train is still due at the boarding stop`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("A", 5), call("B", 9), call("C", 14)), at(3))
        assertEquals(TripProgress.Waiting(ride, at(5)), progress)
        assertFalse(next.boarded)
    }

    @Test
    fun `once the train has left the boarding stop the rider is on it`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 14)), at(6))
        assertTrue(next.boarded)
        progress as TripProgress.Riding
        assertEquals("B", progress.nextStop)
        assertEquals(2, progress.stopsLeft)
        assertEquals(at(14), progress.getOffAt)
        assertFalse(progress.getOffSoon)
    }

    @Test
    fun `get off soon from one stop out, or two minutes, and said once`() {
        val following = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        val (oneStop, lastStop) = OnTheWay.advance(following, listOf(call("C", 14)), at(10))
        assertTrue((lastStop as TripProgress.Riding).getOffSoon)
        val (_, twoMinutes) = OnTheWay.advance(following, listOf(call("B", 12), call("C", 13)), at(11))
        assertTrue((twoMinutes as TripProgress.Riding).getOffSoon)
        assertTrue(OnTheWay.shouldWarn(oneStop, lastStop))
        assertFalse(OnTheWay.shouldWarn(OnTheWay.warned(oneStop), lastStop))
    }

    @Test
    fun `off the train, the trip walks on, then waits for the next leg's train`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        // Its calls no longer include C: the rider got off there.
        val (walking, progress) = OnTheWay.advance(onBoard, listOf(call("X", 16)), at(15))
        assertEquals(1, walking.legIndex)
        assertEquals(TripProgress.Walking(walk, at(20)), progress)
        assertEquals("", walking.vehicleId)
        // The walk has its time: the next leg, waiting for a train to be picked.
        val (changed, waiting) = OnTheWay.advance(walking, null, at(20))
        assertEquals(2, changed.legIndex)
        assertEquals(TripProgress.Waiting(second, null), waiting)
    }

    @Test
    fun `a followed train that never calls where the rider gets off is lost, not ridden`() {
        val following = OnTheWay.follow(trip, train("8", 5))
        // Past the boarding stop and not calling at C: the wrong train (or TfL lost it).
        val (next, progress) = OnTheWay.advance(following, listOf(call("Y", 9)), at(6))
        assertEquals(TripProgress.Lost(ride), progress)
        assertEquals(0, next.legIndex)
    }

    @Test
    fun `calls that couldn't be fetched claim nothing`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(onBoard, null, at(8)).second)
        assertEquals(TripProgress.Waiting(ride, null), OnTheWay.advance(trip, null, at(1)).second)
    }

    @Test
    fun `past the last leg the trip has arrived`() {
        val last = trip.copy(legIndex = 2, vehicleId = "5", boarded = true, dueOffAt = at(30))
        val (done, progress) = OnTheWay.advance(last, emptyList(), at(30))
        assertEquals(TripProgress.Arrived, progress)
        assertNull(done.leg)
    }

    @Test
    fun `a stop named by another of its ids is the same place`() {
        val station = ride.copy(toId = "940GZZLUXXX", toName = "Stratford")
        val following = trip.copy(route = TripRoute(listOf(station)), vehicleId = "8")
        val calls = listOf(VehicleCall("B", "B", null, at(9)), VehicleCall("910GSTFD", "Stratford", null, at(14)))
        assertEquals(2, (OnTheWay.advance(following, calls, at(6)).second as TripProgress.Riding).stopsLeft)
    }

    @Test
    fun `a leg's candidate trains are its line's, named, reachable and once each`() {
        val other = Departure("blue", "Blue", "outbound", "C", null, at(5), "tube", vehicleId = "4")
        val trains = listOf(train("9", 8), train("8", 5), other, train("", 6), train("8", 5), train("7", 1))
        assertEquals(listOf("8", "9"), OnTheWay.candidates(trains, ride, at(3)).map { it.vehicleId })
    }

    @Test
    fun `only a train calling at the boarding stop and then where the rider gets off takes the leg`() {
        assertTrue(OnTheWay.runsAlong(ride, listOf(call("A", 5), call("B", 9), call("C", 14))))
        // The other way: C before A.
        assertFalse(OnTheWay.runsAlong(ride, listOf(call("C", 5), call("B", 9), call("A", 14))))
        // Another branch: never reaches C.
        assertFalse(OnTheWay.runsAlong(ride, listOf(call("A", 5), call("Z", 9))))
    }

    @Test
    fun `a ride followed by another ride picks its train after the change time`() {
        val first = ride.copy(changeAfter = Duration.ofMinutes(4))
        val direct = ActiveTrip(TripRoute(listOf(first, second)), "E", startedAt = t0, vehicleId = "8", boarded = true)
        val (next, _) = OnTheWay.advance(direct, listOf(call("X", 16)), at(15))
        assertEquals(1, next.legIndex)
        assertEquals(at(19), next.legStartedAt)
    }

    // A long ride: TfL predicts only so far ahead, so the calls can end before F.
    private val long = TripLeg("tube", "red", "Red", "A", "A", "F", "F", at(5), at(45), path = listOf("B", "C", "D", "E", "F"))

    @Test
    fun `a train whose predictions end short of the stop runs along the leg while it keeps to its path`() {
        assertTrue(OnTheWay.runsAlong(long, listOf(call("A", 5), call("B", 9), call("C", 12))))
        // Leaves the path (another branch) before the predictions end.
        assertFalse(OnTheWay.runsAlong(long, listOf(call("A", 5), call("B", 9), call("Z", 12))))
        // Nothing predicted past the boarding stop: can't tell yet.
        assertFalse(OnTheWay.runsAlong(long, listOf(call("A", 5))))
    }

    @Test
    fun `riding beyond the predictions counts the stops left, claiming no time`() {
        val following = ActiveTrip(TripRoute(listOf(long)), "F", startedAt = t0, vehicleId = "8")
        val (next, progress) = OnTheWay.advance(following, listOf(call("B", 9), call("C", 12)), at(6))
        assertTrue(next.boarded)
        assertEquals(TripProgress.Riding(long, "B", 5, null, false), progress)
        assertEquals(0, next.legIndex)
    }

    @Test
    fun `an empty prediction list ends a ride only once the rider was due off`() {
        val onBoard = OnTheWay.follow(trip, train("8", 5)).copy(boarded = true)
        // Never seen due off: an empty list may be TfL's gap, not the end of the ride.
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(onBoard, emptyList(), at(8)).second)
        // Seen due at C at 14: not yet at 12, then off at 15.
        val (seen, _) = OnTheWay.advance(onBoard, listOf(call("B", 9), call("C", 14)), at(6))
        assertEquals(at(14), seen.dueOffAt)
        assertEquals(TripProgress.Lost(ride), OnTheWay.advance(seen, emptyList(), at(12)).second)
        val (walking, progress) = OnTheWay.advance(seen, emptyList(), at(15))
        assertEquals(1, walking.legIndex)
        assertNull(walking.dueOffAt)
        assertEquals(TripProgress.Walking(walk, at(20)), progress)
    }
}
