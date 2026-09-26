package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteStopsTest {
    // A synthetic bus route whose last stop TfL names differently from the arrivals' destination.
    private val bus = LineSequence(
        routes = listOf(LineRoute("Hammersmith &harr;  Victoria Station", listOf("A", "B", "C"))),
        stopNames = mapOf("A" to "Hammersmith", "B" to "Hyde Park Corner", "C" to "Victoria Bus"),
    )

    @Test
    fun `a destination spelled differently from the last stop falls back to the route's name`() {
        assertEquals(
            listOf("Hyde Park Corner", "Victoria Bus"),
            RouteStops.ahead(bus, "B", "Victoria Bus", null)?.map { it.name },
        )
        assertEquals(listOf("B", "C"), RouteStops.ahead(bus, "B", "Victoria", null)?.map { it.id })
    }

    @Test
    fun `two downstream stops sharing the destination's name are ambiguous, not cut at the first`() {
        val loop = LineSequence(
            routes = listOf(LineRoute("A &harr; D", listOf("A", "X1", "B", "X2", "D"))),
            stopNames = mapOf("A" to "Start", "X1" to "Market", "B" to "Middle", "X2" to "Market", "D" to "End"),
        )
        assertNull(RouteStops.ahead(loop, "A", "Market", null))
        // Past the first one, only one remains ahead: unambiguous.
        assertEquals(listOf("B", "X2"), RouteStops.ahead(loop, "B", "Market", null)?.map { it.id })
    }

    @Test
    fun `a loop that calls at the boarding stop twice is ambiguous`() {
        val loop = LineSequence(
            routes = listOf(LineRoute("A &harr; E", listOf("A", "L", "M", "L", "E"))),
            stopNames = mapOf("A" to "Start", "L" to "Loop", "M" to "Middle", "E" to "End"),
        )
        // From the first visit the train still runs the loop (L, M, L, E); from the second it
        // doesn't (L, E) — nothing on the arrival says which.
        assertNull(RouteStops.ahead(loop, "L", "End", null))
    }

    @Test
    fun `a variant matching only by its route name still counts against one matching by stop name`() {
        val variants = LineSequence(
            routes = listOf(
                LineRoute("A &harr; Victoria", listOf("A", "B", "V1")),
                LineRoute("A &harr; Victoria Station", listOf("A", "C", "V2")),
            ),
            stopNames = mapOf("A" to "Start", "B" to "Bee", "C" to "Sea", "V1" to "Victoria", "V2" to "Victoria Bus"),
        )
        assertNull(RouteStops.ahead(variants, "A", "Victoria", null))
    }

    // A synthetic bus route whose blind reads a place ("Northtown") that names neither its last
    // stop nor the route — the common shape for London buses, which left most without a list.
    private val labeledBus = LineSequence(
        routes = listOf(LineRoute("Southgate &harr;  Corner Stand", listOf("S", "P", "Q", "N", "E"))),
        stopNames = mapOf(
            "S" to "Southgate", "P" to "Park Road", "Q" to "Queens Avenue",
            "N" to "Northtown High Road", "E" to "Corner Stand",
        ),
    )

    @Test
    fun `a bus whose destination names no stop or route runs to the route's end`() {
        assertEquals(
            listOf("P", "Q", "N", "E"),
            RouteStops.ahead(labeledBus, "P", "Northtown", null, bus = true)?.map { it.id },
        )
    }

    @Test
    fun `rail keeps the strict rule - an unmatched destination has no list`() {
        assertEquals(
            RouteStops.Resolution.NoMatch,
            RouteStops.resolve(labeledBus, "P", "Northtown", null, bus = false),
        )
    }

    @Test
    fun `a bus short-working that names a stop still ends there`() {
        assertEquals(
            listOf("P", "Q"),
            RouteStops.ahead(labeledBus, "P", "Queens Avenue", null, bus = true)?.map { it.id },
        )
    }

    @Test
    fun `a bus with two variants diverging ahead stays ambiguous`() {
        val variants = LineSequence(
            routes = listOf(
                LineRoute("A &harr; X", listOf("A", "B", "X")),
                LineRoute("A &harr; Y", listOf("A", "C", "Y")),
            ),
            stopNames = mapOf("A" to "Start", "B" to "Bee", "C" to "Sea", "X" to "Ex", "Y" to "Why"),
        )
        assertEquals(RouteStops.Resolution.Ambiguous(2), RouteStops.resolve(variants, "A", "Town", null, bus = true))
        // Variants that only differ *behind* the stop agree from here on: one path.
        val behind = LineSequence(
            routes = listOf(
                LineRoute("A &harr; X", listOf("A", "B", "X")),
                LineRoute("G &harr; X", listOf("G", "B", "X")),
            ),
            stopNames = mapOf("A" to "Start", "G" to "Garage", "B" to "Bee", "X" to "Ex"),
        )
        assertEquals(listOf("B", "X"), RouteStops.ahead(behind, "B", "Town", null, bus = true)?.map { it.id })
    }

    @Test
    fun `a stop off every route, or at a route's end, is reported as such`() {
        assertEquals(RouteStops.Resolution.NotOnRoute, RouteStops.resolve(labeledBus, "Z", "Northtown", null, bus = true))
        assertEquals(RouteStops.Resolution.NoMatch, RouteStops.resolve(labeledBus, "E", "Northtown", null, bus = true))
        assertEquals(RouteStops.Resolution.NoDestination, RouteStops.resolve(labeledBus, "P", "", null, bus = true))
    }

    @Test
    fun `an unresolved list is logged with its reason, a resolved one is not`() {
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = labeledBus
            },
            warn = { warnings += it },
        )
        repository.reportUnresolved("43", "P", RouteStops.Resolution.Found(emptyList()))
        repository.reportUnresolved("43", "P", RouteStops.Resolution.Ambiguous(2))
        repository.reportUnresolved("43", "P", RouteStops.Resolution.NoMatch)
        assertEquals(
            listOf(
                "route stops unavailable for line 43 at stop P: 2 possible paths",
                "route stops unavailable for line 43 at stop P: destination matches no route",
            ),
            warnings,
        )
    }

    @Test
    fun `route names parse to their far end`() {
        assertEquals("Edgware", RouteStops.terminusOf("Morden  &harr;  Edgware  via Bank"))
        assertEquals("Archway", RouteStops.terminusOf("Victoria Bus Station &harr;  Archway Station"))
    }

    @Test
    fun `a blank direction fetches both, a known one only its own`() {
        assertEquals(listOf("inbound", "outbound"), RouteStops.directionsFor(""))
        assertEquals(listOf("outbound"), RouteStops.directionsFor("outbound"))
    }

    @Test
    fun `the repository caches per line and direction, and reports failures`() = runTest {
        val calls = mutableListOf<String>()
        var fail = false
        val warnings = mutableListOf<String>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    if (fail) throw TflException.Offline(null)
                    return bus
                }
            },
            warn = { warnings += it },
            clock = { Instant.parse("2026-09-26T08:00:00Z") },
        )
        assertNull(repository.cached("14", "inbound"))
        repository.load("14", "inbound")
        repository.load("14", "inbound")
        assertEquals(listOf("14/inbound"), calls)
        assertEquals(bus, repository.cached("14", "inbound"))
        // A blank direction needs both halves; only the missing one is fetched.
        assertNull(repository.cached("14", ""))
        repository.load("14", "")
        assertEquals(listOf("14/inbound", "14/outbound"), calls)

        fail = true
        val thrown = try {
            repository.load("22", "inbound")
            null
        } catch (e: TflException.Offline) {
            e
        }
        assertTrue("a failed fetch propagates its reason", thrown != null)
        assertNull("and caches nothing", repository.cached("22", "inbound"))
        assertEquals(
            listOf(
                "route sequence fetched for line 14 inbound in 0 ms",
                "route sequence fetched for line 14 outbound in 0 ms",
                "route sequence fetch failed for line 22 inbound after 0 ms: Offline",
            ),
            warnings,
        )
    }

    @Test
    fun `route fetches are bounded so live requests keep free slots`() = runTest {
        var inFlight = 0
        var most = 0
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    inFlight++
                    most = maxOf(most, inFlight)
                    gate.await()
                    inFlight--
                    return bus
                }
            },
        )
        val loads = (1..5).map { line -> async { repository.load("$line", "") } }
        runCurrent()
        assertEquals(RouteStopsRepository.MAX_CONCURRENT_FETCHES, inFlight)
        gate.complete(Unit)
        loads.forEach { it.await() }
        assertEquals(RouteStopsRepository.MAX_CONCURRENT_FETCHES, most)
    }

    @Test
    fun `two loads of one line at once share its requests`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    gate.await()
                    return bus
                }
            },
        )
        // A trip loading the line while its page, opened meanwhile, loads it too.
        val trip = async { repository.load("14", "") }
        val page = async { repository.load("14", "") }
        runCurrent()
        gate.complete(Unit)
        assertEquals(trip.await(), page.await())
        assertEquals(listOf("14/inbound", "14/outbound"), calls)
    }

    @Test
    fun `a load joined by another still finishes when the first is canceled`() = runTest {
        val calls = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    gate.await()
                    return bus
                }
            },
        )
        val first = async { repository.load("14", "inbound") }
        val second = async { repository.load("14", "inbound") }
        runCurrent()
        // The screen that started the request leaves; the one waiting on it asks again itself.
        first.cancel()
        runCurrent()
        gate.complete(Unit)
        assertEquals(bus.routes, second.await().routes)
        assertEquals(listOf("14/inbound", "14/inbound"), calls)
    }

    @Test
    fun `a line's two directions are fetched at once, not in turn`() = runTest {
        val started = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val repository = RouteStopsRepository(
            source = object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    started += direction
                    gate.await()
                    return bus
                }
            },
        )
        val load = async { repository.load("14", "") }
        runCurrent()
        // Both requests are out before either answers.
        assertEquals(listOf("inbound", "outbound"), started)
        gate.complete(Unit)
        load.await()
    }

    private class MemoryStore : RouteStopsStore {
        var contents = RouteStopsStore.Contents()
        var saves = 0
        override fun load() = contents
        override fun save(contents: RouteStopsStore.Contents) {
            this.contents = contents
            saves++
        }
    }

    private val pole = StopLocation("490000001A", "Hill", 51.5, -0.12, listOf(LineRef("43", "43", "bus")), stopLetter = "A")

    private class CountingSource(private val sequence: LineSequence, private val poles: List<StopLocation>) :
        RouteSequenceSource, StopAreaSource {
        val calls = mutableListOf<String>()
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
            calls += "$lineId/$direction"
            return sequence
        }
        override suspend fun stopAreaPoles(areaId: String): List<StopLocation> {
            calls += areaId
            return poles
        }
    }

    @Test
    fun `a route and a stop area fetched by one process are reused by the next for a day`() = runTest {
        val store = MemoryStore()
        var now = Instant.parse("2026-09-24T08:00:00Z")
        val io = StandardTestDispatcher(testScheduler)
        val first = CountingSource(bus, listOf(pole))
        val before = RouteStopsRepository(first, store = store, clock = { now }, io = io)
        before.load("14", "inbound")
        before.loadPoles("490G00000001")
        assertEquals(listOf("14/inbound", "490G00000001"), first.calls)

        // A new process: nothing in memory, the store read once on warm-up, so the first frame
        // has the stops and neither is fetched again.
        now = now.plus(Duration.ofHours(23))
        val second = CountingSource(bus, listOf(pole))
        val after = RouteStopsRepository(second, store = store, clock = { now }, io = io)
        assertNull(after.cached("14", "inbound"))
        after.warm()
        assertEquals(bus, after.cached("14", "inbound"))
        assertEquals(listOf(pole), after.cachedPoles("490G00000001"))
        after.load("14", "inbound")
        after.loadPoles("490G00000001")
        assertEquals(emptyList<String>(), second.calls)

        // A day on, both have expired: not offered to a first frame, fetched again.
        now = now.plus(Duration.ofHours(1))
        assertNull(after.cached("14", "inbound"))
        assertNull(after.cachedPoles("490G00000001"))
        after.load("14", "inbound")
        after.loadPoles("490G00000001")
        assertEquals(listOf("14/inbound", "490G00000001"), second.calls)
    }

    @Test
    fun `entries a day old are dropped from the store when it is read`() = runTest {
        val at = Instant.parse("2026-09-24T08:00:00Z")
        val store = MemoryStore().apply {
            contents = RouteStopsStore.Contents(
                sequences = mapOf(
                    "14/inbound" to RouteStopsStore.Timed(at, bus),
                    "22/inbound" to RouteStopsStore.Timed(at.plus(Duration.ofHours(2)), bus),
                ),
                poles = mapOf("490G00000001" to RouteStopsStore.Timed(at, listOf(pole))),
            )
        }
        val repository = RouteStopsRepository(
            CountingSource(bus, listOf(pole)),
            store = store,
            clock = { at.plus(Duration.ofHours(25)) },
            io = StandardTestDispatcher(testScheduler),
        )
        repository.warm()
        assertEquals(setOf("22/inbound"), store.contents.sequences.keys)
        assertEquals(emptySet<String>(), store.contents.poles.keys)
        assertNull(repository.cached("14", "inbound"))
        assertEquals(bus, repository.cached("22", "inbound"))
    }

    @Test
    fun `an entry stamped in the future, the clock having moved back, is fetched again`() = runTest {
        val now = Instant.parse("2026-09-24T08:00:00Z")
        val store = MemoryStore().apply {
            contents = RouteStopsStore.Contents(sequences = mapOf("14/inbound" to RouteStopsStore.Timed(now.plusSeconds(60), bus)))
        }
        val source = CountingSource(bus, emptyList())
        val repository = RouteStopsRepository(source, store = store, clock = { now }, io = StandardTestDispatcher(testScheduler))
        repository.load("14", "inbound")
        assertEquals(listOf("14/inbound"), source.calls)
        assertEquals(now, store.contents.sequences.getValue("14/inbound").at)
    }
}
