package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.JourneyPlanner
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.StopDisruption
import app.stopdash.domain.TflClient
import app.stopdash.domain.TflException
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.domain.TripTiming
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic stops and lines only. */
@OptIn(ExperimentalCoroutinesApi::class)
class TripViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private var now: Instant = Instant.parse("2026-09-26T08:00:00Z")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun at(minutes: Long) = Instant.parse("2026-09-26T08:00:00Z").plus(Duration.ofMinutes(minutes))

    private fun leg(lineId: String, from: String, to: String, departs: Long, arrives: Long) = TripLeg(
        mode = "tube",
        lineId = lineId,
        lineName = lineId,
        fromId = from,
        fromName = from,
        toId = to,
        toName = to,
        departure = at(departs),
        arrival = at(arrives),
        path = listOf(to),
    )

    private val route = TripRoute(listOf(leg("red", "A", "B", 5, 15), leg("blue", "B", "C", 20, 30)))

    private fun train(lineId: String, destination: String, inMinutes: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = at(inMinutes),
        mode = "tube",
    )

    private class FakePlanner(var routes: List<TripRoute>) : JourneyPlanner {
        var calls = 0
        var failWith: TflException? = null
        override suspend fun journeys(fromId: String, toId: String): List<TripRoute> {
            calls++
            failWith?.let { throw it }
            return routes
        }
    }

    private class FakeClient(val arrivals: MutableMap<String, List<Departure>>) : TflClient {
        val asked = mutableListOf<String>()
        var failStops = emptySet<String>()
        var failStatus = false
        override suspend fun arrivals(stopId: String): List<Departure> {
            asked += stopId
            if (stopId in failStops) throw TflException.Offline(null)
            return arrivals[stopId].orEmpty()
        }
        var omitLines = emptySet<String>()
        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            if (failStatus) throw TflException.Offline(null)
            return lineIds.filterNot { it in omitLines }.map { LineStatus(it, LineStatus.GOOD_SERVICE, "Good Service") }
        }
        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
    }

    private fun model(planner: JourneyPlanner, client: TflClient, plans: TripPlans = TripPlans()) =
        TripViewModel(planner, client, "A", "C", clock = { now }, plans = plans, io = dispatcher)

    @Test
    fun `a recreated screen over the same model doesn't fetch again, a new re-pick does`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf())
        val trip = model(planner, client)
        trip.refreshFor(null)
        advanceUntilIdle()
        trip.refreshFor(null)
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        assertEquals(listOf("A", "B"), client.asked.sorted())
        trip.refreshFor(7)
        advanceUntilIdle()
        assertEquals(listOf("A", "A", "B", "B"), client.asked.sorted())
    }

    @Test
    fun `plans, then fetches each boarding stop's arrivals and the lines' status`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(planner, client)
        trip.refresh()
        advanceUntilIdle()
        val state = trip.state.value
        assertEquals(listOf(route), state.routes)
        assertEquals(listOf("A", "B"), client.asked.sorted())
        assertEquals(1, state.live.getValue("A").departures.size)
        assertEquals(setOf("red", "blue"), state.statuses.keys)
    }

    @Test
    fun `reuses a plan for fifteen minutes, then plans again`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        advanceUntilIdle()
        now = now.plus(Duration.ofMinutes(14))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `a refresh asked for during one runs once more after it`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        trip.refresh()
        advanceUntilIdle()
        // Two boarding stops, fetched in each of the two refreshes.
        assertEquals(4, client.asked.size)
    }

    @Test
    fun `a Retry tapped during a refresh plans again after it`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        trip.retry()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
    }

    @Test
    fun `a trip reopened within fifteen minutes reuses its plan`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val plans = TripPlans()
        model(planner, FakeClient(mutableMapOf()), plans).refresh()
        advanceUntilIdle()
        now = now.plus(Duration.ofMinutes(10))
        val reopened = model(planner, FakeClient(mutableMapOf()), plans)
        assertEquals(listOf(route), reopened.state.value.routes)
        reopened.refresh()
        advanceUntilIdle()
        assertEquals(1, planner.calls)
    }

    @Test
    fun `a failed plan says why over the last plan, and Retry plans again`() = runTest(dispatcher) {
        val planner = FakePlanner(listOf(route))
        val trip = model(planner, FakeClient(mutableMapOf()))
        trip.refresh()
        advanceUntilIdle()
        planner.failWith = TflException.Offline(null)
        trip.retry()
        advanceUntilIdle()
        assertEquals(listOf(route), trip.state.value.routes)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, trip.state.value.planError)
        // The tick doesn't retry a failed plan on its own.
        planner.failWith = null
        now = now.plus(Duration.ofMinutes(20))
        trip.refresh()
        advanceUntilIdle()
        assertEquals(2, planner.calls)
        trip.retry()
        advanceUntilIdle()
        assertNull(trip.state.value.planError)
        assertEquals(3, planner.calls)
    }

    @Test
    fun `a failed stop keeps its last arrivals, marked failed`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf("A" to listOf(train("red", "End", 2))))
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        val fetchedAt = now
        client.failStops = setOf("A")
        now = now.plus(Duration.ofMinutes(1))
        trip.refresh()
        advanceUntilIdle()
        val stop = trip.state.value.live.getValue("A")
        assertTrue(stop.failed)
        assertEquals(fetchedAt, stop.fetchedAt)
        assertEquals(1, stop.departures.size)
    }

    @Test
    fun `a failed line-status check keeps the last statuses and says so`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(false, trip.state.value.statusFailed)
        client.failStatus = true
        trip.refresh()
        advanceUntilIdle()
        assertTrue(trip.state.value.statusFailed)
        assertEquals(setOf("red", "blue"), trip.state.value.statuses.keys)
    }

    @Test
    fun `a route riding a hidden mode is left out`() {
        val bus = TripRoute(listOf(leg("red", "A", "C", 2, 40).copy(mode = "bus")))
        val state = TripViewModel.State(routes = listOf(route, bus))
        assertEquals(listOf(route), tripEstimates(state, now, Duration.ZERO, emptyMap(), hidden = setOf("bus"))?.map { it.route })
    }

    @Test
    fun `an unconfirmed position caps every route at estimated`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf(
                "A" to TripViewModel.StopLive(listOf(train("red", "End", 2)), now),
                "B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now),
            ),
        )
        val sequences = mapOf("red" to red, "blue" to blue)
        assertEquals(TripTiming.Basis.LIVE, tripEstimates(state, now, Duration.ZERO, sequences)?.single()?.basis)
        assertEquals(
            TripTiming.Basis.ESTIMATED,
            tripEstimates(state, now, Duration.ZERO, sequences, originUnconfirmed = true)?.single()?.basis,
        )
    }

    @Test
    fun `a new plan's lines are unchecked until their status arrives`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        client.failStatus = true
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("red", "blue"), trip.state.value.statusUnknown)
        client.failStatus = false
        trip.refresh()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), trip.state.value.statusUnknown)
    }

    @Test
    fun `a line TfL leaves out of its status answer is unchecked`() = runTest(dispatcher) {
        val client = FakeClient(mutableMapOf())
        client.omitLines = setOf("blue")
        val trip = model(FakePlanner(listOf(route)), client)
        trip.refresh()
        advanceUntilIdle()
        assertEquals(setOf("blue"), trip.state.value.statusUnknown)
        val estimate = checkNotNull(tripEstimates(trip.state.value, now, Duration.ZERO, emptyMap())).single()
        assertTrue(estimate.unchecked)
    }

    @Test
    fun `a hidden mode's stops aren't fetched`() = runTest(dispatcher) {
        val bus = TripRoute(listOf(leg("red", "X", "C", 2, 40).copy(mode = "bus")))
        val client = FakeClient(mutableMapOf())
        val trip = model(FakePlanner(listOf(route, bus)), client)
        trip.hiddenModes = setOf("bus")
        trip.refresh()
        advanceUntilIdle()
        assertEquals(listOf("A", "B"), client.asked.sorted())
    }

    @Test
    fun `journeys that ride alike are one route`() {
        val later = TripRoute(route.legs.map { it.copy(departure = it.departure.plusSeconds(600), arrival = it.arrival.plusSeconds(600)) })
        val state = TripViewModel.State(routes = listOf(route, later))
        assertEquals(1, tripEstimates(state, now, Duration.ZERO, emptyMap())?.size)
    }

    @Test
    fun `a later timetable slot times a route whose earlier one is missed`() {
        val later = TripRoute(route.legs.map { it.copy(departure = it.departure.plusSeconds(600), arrival = it.arrival.plusSeconds(600)) })
        val state = TripViewModel.State(routes = listOf(route, later))
        // Six minutes from the first stop: the 5-minute slot is gone, the 15-minute one is not.
        val estimate = checkNotNull(tripEstimates(state, now, Duration.ofMinutes(6), emptyMap())).single()
        assertEquals(TripTiming.Basis.ESTIMATED, estimate.basis)
        assertEquals(at(40), estimate.arrival)
    }

    @Test
    fun `several destinations shorten each name alike before eliding`() {
        assertEquals(
            listOf("Crystal Palace, West Croydon", "Crystal Palace, W. Croydon", "Crystal P., W. Croydon"),
            destinationsLadder(listOf("Crystal Palace", "West Croydon")),
        )
    }

    @Test
    fun `a capped first-leg row keeps the first train the rider can reach`() {
        val trains = listOf(train("red", "End", 1), train("red", "End", 2), train("red", "End", 3), train("red", "End", 9))
        val shown = shownTrains(trains, reachable = at(5))
        assertEquals(listOf(at(2) to false, at(3) to false, at(9) to true), shown.map { (d, ok) -> d.expectedArrival to ok })
    }

    @Test
    fun `another branch's train is shown grayed among the usable ones`() {
        val other = train("red", "Elsewhere", 4)
        val trains = listOf(train("red", "End", 2), other, train("red", "End", 6))
        val shown = shownTrains(trains, reachable = at(0), usable = { it != other })
        assertEquals(listOf(at(2) to true, at(4) to false, at(6) to true), shown.map { (d, ok) -> d.expectedArrival to ok })
    }

    @Test
    fun `other-branch trains still show when none is usable`() {
        // Riding blue from B toward C (the fork past B2): only a train for D is due.
        val fork = LineSequence(
            routes = listOf(
                LineRoute("B ↔ C", listOf("B", "B2", "C")),
                LineRoute("B ↔ D", listOf("B", "B2", "D")),
                LineRoute("C ↔ B", listOf("C", "B2", "B")),
            ),
            stopNames = mapOf("B" to "B", "B2" to "B2", "C" to "C", "D" to "D"),
        )
        val leg = TripLeg("tube", "blue", "blue", "B", "B", "C", "C", at(20), at(30), path = listOf("B2", "C"))
        val toD = train("blue", "D", 4)
        val back = train("blue", "B", 5).copy(direction = "inbound")
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(toD, back), now)))
        assertEquals(listOf(toD), lineTrains(state, leg, now, emptyList(), mapOf("blue" to fork)))
        // With no route to tell the way by, none.
        assertEquals(emptyList<Departure>(), lineTrains(state, leg, now, emptyList()))
    }

    @Test
    fun `another variant's bus still shows when none is usable, its blind naming an area`() {
        // Bus 1 from B toward C; the variant to D is due, its blind reading "Town Centre".
        val routes = LineSequence(
            routes = listOf(
                LineRoute("B ↔ C", listOf("B", "B2", "C")),
                LineRoute("B ↔ D", listOf("B", "B2", "D")),
            ),
            stopNames = mapOf("B" to "B", "B2" to "B2", "C" to "C", "D" to "D"),
        )
        val leg = TripLeg("bus", "1", "1", "B", "B", "C", "C", at(20), at(30), path = listOf("B2", "C"))
        val toD = train("1", "Town Centre", 4).copy(mode = "bus")
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(toD), now)))
        assertEquals(listOf(toD), lineTrains(state, leg, now, emptyList(), mapOf("1" to routes)))
    }

    @Test
    fun `lines being checked say so, and only a finished check says it couldn't`() {
        val refreshing = TripViewModel.State(refreshing = true)
        assertEquals(true, statusNote(refreshing, unchecked = true))
        assertNull(statusNote(refreshing, unchecked = false))
        assertEquals(false, statusNote(TripViewModel.State(), unchecked = true))
        assertEquals(false, statusNote(TripViewModel.State(statusFailed = true), unchecked = false))
        assertNull(statusNote(TripViewModel.State(), unchecked = false))
    }

    @Test
    fun `on a loop only a train leaving for the leg's next stop is usable`() {
        // A loop through B, X, C, Y and back to B: riding B to C via X, both ways reach C.
        val loop = LineSequence(
            routes = listOf(
                LineRoute("Clockwise", listOf("B", "X", "C", "Y", "Z")),
                LineRoute("Anticlockwise", listOf("B", "Y", "C", "X", "W")),
            ),
            stopNames = mapOf("B" to "B", "X" to "X", "C" to "C", "Y" to "Y", "Z" to "Z", "W" to "W"),
        )
        val leg = TripLeg("tube", "loop", "loop", "B", "B", "C", "C", at(20), at(30), path = listOf("X", "C"))
        val clockwise = train("loop", "Z", 6)
        val anticlockwise = train("loop", "W", 2)
        val state = TripViewModel.State(live = mapOf("B" to TripViewModel.StopLive(listOf(clockwise, anticlockwise), now)))
        assertEquals(listOf(clockwise), legTrains(state, leg, now, mapOf("loop" to loop)))
    }

    @Test
    fun `a train whose route can't be followed says so rather than pass as no live train`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now)),
        )
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, emptyMap()))
        // The blue line's route is still loading.
        assertEquals(TripMessage.CHECKING, tripCheckState(state, estimates, now, emptyMap()))
        // It failed to load.
        assertEquals(TripMessage.INCOMPLETE, tripCheckState(state, estimates, now, mapOf("blue" to null)))
        assertNull(tripCheckState(state, estimates, now, mapOf("blue" to blue)))
    }

    // A line forking after B: on to C, or to D.
    private val blue = LineSequence(
        routes = listOf(
            LineRoute("B ↔ C", listOf("B", "C")),
            LineRoute("B ↔ D", listOf("B", "D")),
        ),
        stopNames = mapOf("B" to "B", "C" to "C", "D" to "D"),
    )

    private val red = LineSequence(
        routes = listOf(LineRoute("A ↔ End", listOf("A", "B", "End"))),
        stopNames = mapOf("A" to "A", "B" to "B", "End" to "End"),
    )

    @Test
    fun `a leg counts only trains whose route calls where the rider gets off`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("B" to TripViewModel.StopLive(listOf(train("blue", "D", 16), train("blue", "C", 18)), now)),
        )
        val trains = legTrains(state, route.legs[1], now, mapOf("blue" to blue))
        assertEquals(listOf(at(18)), trains?.map { it.expectedArrival })
    }

    @Test
    fun `stale arrivals time nothing`() {
        val state = TripViewModel.State(
            routes = listOf(route),
            live = mapOf("A" to TripViewModel.StopLive(listOf(train("red", "End", 20)), now.minus(Duration.ofMinutes(6)))),
        )
        assertNull(legTrains(state, route.legs[0], now, mapOf("red" to red)))
    }

    @Test
    fun `times each route from live trains and ranks them`() {
        val slow = TripRoute(listOf(leg("red", "A", "C", 2, 40)))
        val state = TripViewModel.State(
            routes = listOf(slow, route),
            live = mapOf(
                "A" to TripViewModel.StopLive(listOf(train("red", "End", 2)), now),
                "B" to TripViewModel.StopLive(listOf(train("blue", "C", 16)), now),
            ),
        )
        val estimates = checkNotNull(tripEstimates(state, now, Duration.ZERO, mapOf("red" to red, "blue" to blue)))
        // The two-leg route is live throughout; the one-leg route's train doesn't reach C, so it's estimated.
        assertEquals(listOf(route, slow), estimates.map { it.route })
        assertEquals(TripTiming.Basis.LIVE, estimates[0].basis)
        assertEquals(at(26), estimates[0].arrival)
    }
}
