package app.stopcast.domain

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
        assertEquals(listOf("route sequence fetch failed for line 22: Offline"), warnings)
    }
}
