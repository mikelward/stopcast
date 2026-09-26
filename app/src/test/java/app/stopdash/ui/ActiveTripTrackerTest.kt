package app.stopdash.ui

import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.Departure
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.domain.VehicleCall
import app.stopdash.domain.VehicleSource
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Following a started trip, on synthetic stops A–E and example vehicle ids. */
class ActiveTripTrackerTest {
    private val t0 = Instant.parse("2026-09-26T08:00:00Z")
    private fun at(minutes: Long) = t0.plus(Duration.ofMinutes(minutes))
    private var now = t0

    private val ride = TripLeg("tube", "red", "Red", "A", "A", "C", "C", at(5), at(15), path = listOf("B", "C"))
    private val route = TripRoute(listOf(ride))

    private fun train(vehicle: String, minutes: Long) =
        Departure("red", "Red", "outbound", "C", null, at(minutes), "tube", vehicleId = vehicle)
    private fun call(id: String, minutes: Long) = VehicleCall(id, id, null, at(minutes))

    private val departures = mutableMapOf<String, List<Departure>>()
    private val trains = mutableMapOf<String, List<VehicleCall>>()
    private var failing = false
    private val asked = mutableListOf<String>()
    private var kept: ActiveTrip? = null
    private val warned = mutableListOf<TripProgress.Riding>()

    private fun tracker(dispatcher: kotlinx.coroutines.CoroutineDispatcher, load: () -> ActiveTrip? = { null }) = ActiveTripTracker(
        load = load,
        save = { kept = it },
        arrivals = { stop -> departures[stop].orEmpty() },
        vehicles = object : VehicleSource {
            override suspend fun vehicleCalls(vehicleId: String, lineId: String): List<VehicleCall> {
                if (failing) throw TflException.Offline(null)
                asked += vehicleId
                return trains[vehicleId].orEmpty()
            }
        },
        clock = { now },
        io = dispatcher,
        onGetOffSoon = { _, riding -> warned += riding },
    )

    @Test
    fun `Start follows the soonest train that runs where the rider is going`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        // The 3-minute train is too soon to reach; the 4-minute one runs the other way.
        departures["A"] = listOf(train("1", 3), train("2", 4), train("3", 6))
        trains["2"] = listOf(call("C", 2), call("A", 4))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(4))
        tracker.refresh()
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertEquals(TripProgress.Waiting(ride, at(6)), tracker.progress.value)
        assertEquals(listOf("2", "3"), asked)
        assertEquals(tracker.trip.value, kept)
    }

    @Test
    fun `on board, get off soon is said once`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        now = at(12)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        tracker.refresh()
        assertEquals(1, warned.size)
        assertTrue((tracker.progress.value as TripProgress.Riding).getOffSoon)
    }

    @Test
    fun `a failed lookup says so and claims nothing new`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        failing = true
        tracker.refresh()
        assertTrue(tracker.failed.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
    }

    @Test
    fun `arriving forgets the trip, and says it arrived`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6))
        trains["3"] = listOf(call("A", 6), call("B", 9), call("C", 14))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        now = at(10)
        trains["3"] = listOf(call("C", 14))
        tracker.refresh()
        now = at(15)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Arrived, tracker.progress.value)
        assertNull(tracker.trip.value)
        assertNull(kept)
    }

    @Test
    fun `on board, an empty answer keeps following the train rather than pick another`() = runTest {
        val tracker = tracker(StandardTestDispatcher(testScheduler))
        departures["A"] = listOf(train("3", 6), train("4", 8))
        trains["3"] = listOf(call("A", 6), call("B", 9))
        trains["4"] = listOf(call("A", 8), call("B", 11), call("C", 16))
        tracker.start(route, "C", readyAt = at(1))
        tracker.refresh()
        now = at(7)
        trains["3"] = listOf(call("B", 9))
        tracker.refresh()
        // TfL's predictions go quiet before C was ever predicted: not arrived, still on train 3.
        now = at(10)
        trains["3"] = emptyList()
        tracker.refresh()
        assertEquals(TripProgress.Lost(ride), tracker.progress.value)
        assertEquals("3", tracker.trip.value?.vehicleId)
        assertTrue(checkNotNull(tracker.trip.value).boarded)
    }

    @Test
    fun `a kept trip comes back after the app was closed`() = runTest {
        val saved = ActiveTrip(route, "C", startedAt = t0, vehicleId = "3", boarded = true)
        val tracker = tracker(StandardTestDispatcher(testScheduler), load = { saved })
        tracker.restore()
        assertEquals(saved, tracker.trip.value)
        tracker.end()
        assertNull(tracker.trip.value)
        assertNull(kept)
    }
}
