package app.stopcast.domain

import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure widget-refresh rebuild (SPEC D5): re-fetch arrivals for exactly the persisted stops,
 * stamp the fresh ones [now], keep an aged last-good for a stop that fails, and treat a cycle
 * where nothing fetched fresh as a no-op (null → nothing saved). No Android, no network — the
 * fetch is an injected lambda.
 */
class WidgetRefreshTest {
    private val t0: Instant = Instant.parse("2026-09-20T08:00:00Z")
    private val t1: Instant = Instant.parse("2026-09-20T08:01:00Z")

    private fun departure(dest: String) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "inbound",
        destination = dest,
        platform = null,
        expectedArrival = t1.plusSeconds(120),
        mode = "tube",
    )

    private fun stop(id: String, deps: List<Departure>, fetchedAt: Instant = t0) =
        StopArrivals(stopId = id, stopName = "Stop $id", departures = deps, fetchedAt = fetchedAt)

    private fun snapshot(vararg stops: StopArrivals) =
        DeparturesSnapshot(stops = stops.toList(), fetchedAt = stops.maxOf { it.fetchedAt })

    @Test
    fun `a stop fetched moments ago is carried over, not fetched again`() = runTest {
        val recent = stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(10))
        val prior = snapshot(recent, stop("B", listOf(departure("Walthamstow"))))
        val fetched = mutableListOf<String>()
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) { id ->
            fetched += id
            listOf(departure("Fresh $id"))
        }
        assertEquals(listOf("B"), fetched)
        assertEquals(recent, refreshed!!.stops.first { it.stopId == "A" })
        assertEquals(t1, refreshed.stops.first { it.stopId == "B" }.fetchedAt)
    }

    @Test
    fun `the widget's journeys ride along with a refresh`() = runTest {
        val prior = snapshot(stop("B", listOf(departure("Walthamstow")))).copy(
            journeys = listOf(WidgetJourney("B", setOf(JourneyCall("victoria", "Fresh B", null)))),
            journeyOnlyStopIds = setOf("B"),
        )
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ZERO) { id ->
            listOf(departure("Fresh $id"))
        }!!
        assertEquals(prior.journeys, refreshed.journeys)
        assertEquals(prior.journeyOnlyStopIds, refreshed.journeyOnlyStopIds)
    }

    @Test
    fun `a cycle where every stop is recent fetches nothing and saves nothing`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(5)))
        var calls = 0
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) {
            calls++
            emptyList()
        }
        assertEquals(0, calls)
        assertNull(refreshed)
    }

    @Test
    fun `a recent stop that isn't fresh is fetched again`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton")), fetchedAt = t1.minusSeconds(5)).copy(arrivalsFresh = false))
        var calls = 0
        WidgetRefresh.refreshedArrivals(prior, t1, reuse = java.time.Duration.ofSeconds(30)) {
            calls++
            emptyList()
        }
        assertEquals(1, calls)
    }

    @Test
    fun `every stop fetching fresh stamps them now`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { id ->
            listOf(departure("Fresh $id"))
        }
        assertEquals(t1, refreshed!!.fetchedAt)
        assertTrue(refreshed.stops.all { it.fetchedAt == t1 && it.arrivalsFresh })
        assertEquals(listOf("Fresh A"), refreshed.stops.first { it.stopId == "A" }.departures.map { it.destination })
    }

    @Test
    fun `a stop whose fetch fails keeps its aged last-good`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { id ->
            if (id == "B") null else listOf(departure("Fresh $id"))
        }
        val a = refreshed!!.stops.first { it.stopId == "A" }
        val b = refreshed.stops.first { it.stopId == "B" }
        assertEquals(t1, a.fetchedAt) // A refreshed
        assertTrue(a.arrivalsFresh)
        assertEquals(t0, b.fetchedAt) // B kept at its old age, so per-row staleness withholds it
        assertFalse(b.arrivalsFresh) // and marked not-fresh so it can't read as fresh in-window
        assertEquals(listOf("Walthamstow"), b.departures.map { it.destination })
        assertEquals(t1, refreshed.fetchedAt) // whole-screen stamp is the freshest stop
    }

    @Test
    fun `a cycle where no stop fetches fresh is a no-op`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))))
        val refreshed = WidgetRefresh.refreshedArrivals(prior, t1) { null }
        assertNull(refreshed)
    }

    @Test
    fun `an empty snapshot has nothing to refresh`() = runTest {
        val refreshed = WidgetRefresh.refreshedArrivals(DeparturesSnapshot(emptyList(), t0), t1) {
            listOf(departure("x"))
        }
        assertNull(refreshed)
    }

    @Test
    fun `fetches every stop at once rather than one after another`() = runTest {
        val prior = snapshot(stop("A", listOf(departure("Brixton"))), stop("B", listOf(departure("Walthamstow"))))
        val gate = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val result = async {
            WidgetRefresh.refreshedArrivals(prior, t1) { id ->
                started += id
                gate.await()
                listOf(departure("Fresh $id"))
            }
        }
        testScheduler.runCurrent()
        // Both fetches are in flight before either answers; a sequential loop would have started one.
        assertEquals(listOf("A", "B"), started)
        gate.complete(Unit)
        val refreshed = result.await()!!
        assertEquals(listOf("A", "B"), refreshed.stops.map { it.stopId })
        assertTrue(refreshed.stops.all { it.arrivalsFresh })
    }
}
