package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class DirectTripsTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    // A line forking at "Mid": via "Side" to "Bottom B", or on to "Bottom A".
    private val rail = LineSequence(
        routes = listOf(
            LineRoute("Top ↔ Bottom A", listOf("TOP", "MID", "BOTA")),
            LineRoute("Top ↔ Bottom B", listOf("TOP", "MID", "SIDE", "BOTB")),
            LineRoute("Bottom A ↔ Top", listOf("BOTA", "MID", "TOP")),
        ),
        stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
    )

    private fun departure(destination: String, inSeconds: Long, lineId: String = "rail", mode: String = "tube") =
        Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "",
            destination = destination,
            platform = null,
            expectedArrival = now.plusSeconds(inSeconds),
            mode = mode,
        )

    private fun stop(id: String, name: String, vararg departures: Departure, lines: List<LineRef> = emptyList()) =
        StopArrivals(id, name, departures.toList(), now, lines)

    private fun station(id: String, name: String, hubId: String = "") = DirectTrips.End(id, name, hubId)

    @Test
    fun `keeps only departures whose path reaches the destination`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60), departure("Bottom B", 120), departure("Bottom A", 300))
        val result = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail))
        assertEquals(listOf(60L, 300L), result.stops.single().departures.map { it.expectedArrival.epochSecond - now.epochSecond })
        assertFalse(result.pending)
        assertFalse(result.unresolved)
    }

    @Test
    fun `a stop both branches share keeps every train`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60), departure("Bottom B", 120))
        val result = DirectTrips.filter(listOf(top), listOf(station("MID", "Mid")), mapOf("rail" to rail))
        assertEquals(2, result.stops.single().departures.size)
    }

    @Test
    fun `a stop with nothing reaching the destination is left out`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60))
        val other = stop("OTHER", "Other", departure("Elsewhere", 60, lineId = "other"))
        val result = DirectTrips.filter(
            listOf(top, other),
            listOf(station("BOTA", "Bottom A")),
            mapOf("rail" to rail, "other" to LineSequence(listOf(LineRoute("x", listOf("OTHER", "ELSE"))), mapOf("ELSE" to "Elsewhere"))),
        )
        assertEquals(listOf("TOP"), result.stops.map { it.stopId })
    }

    @Test
    fun `a line still loading is pending, not a no`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60))
        val result = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), emptyMap())
        assertTrue(result.stops.isEmpty())
        assertTrue(result.pending)
    }

    @Test
    fun `a failed route or an unresolvable path is flagged`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60))
        val failed = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), mapOf("rail" to null))
        assertTrue(failed.unresolved)
        assertFalse(failed.pending)
        val unknownDestination = stop("TOP", "Top", departure("Nowhere", 60))
        assertTrue(DirectTrips.filter(listOf(unknownDestination), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail)).unresolved)
        val noLine = stop("TOP", "Top", departure("Bottom A", 60, lineId = ""))
        assertTrue(DirectTrips.filter(listOf(noLine), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail)).unresolved)
    }

    @Test
    fun `the way back doesn't count`() {
        // At Mid, a train to Top has Bottom A behind it, not ahead.
        val mid = stop("MID", "Mid", departure("Top", 60))
        val result = DirectTrips.filter(listOf(mid), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail))
        assertTrue(result.stops.isEmpty())
    }

    @Test
    fun `the same station is no trip`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60))
        assertTrue(DirectTrips.filter(listOf(top), listOf(station("TOP", "Top")), mapOf("rail" to rail)).stops.isEmpty())
        // Even with a closure there: the page says there's no trip, not the origin's own alert.
        val closed = top.copy(disruptions = listOf(StopDisruption("Top station is closed")))
        assertTrue(DirectTrips.filter(listOf(closed), listOf(station("TOP", "Top")), mapOf("rail" to rail)).stops.isEmpty())
    }

    @Test
    fun `a destination listed under a sibling id in its hub still matches`() {
        // The route calls at MID; the searched station comes back as MIDX in the same interchange.
        val hubbed = rail.copy(stopHubs = mapOf("MID" to "HUBMID"))
        val top = stop("TOP", "Top", departure("Bottom A", 60))
        val result = DirectTrips.filter(listOf(top), listOf(station("MIDX", "Mid", hubId = "HUBMID")), mapOf("rail" to hubbed))
        assertEquals(1, result.stops.single().departures.size)
    }

    @Test
    fun `status rows are kept only for lines that reach the destination`() {
        val lines = listOf(LineRef("rail", "Rail", "tube"), LineRef("other", "Other", "tube"))
        // A suspension: no predictions, only the declared lines.
        val top = stop("TOP", "Top", lines = lines)
        val other = LineSequence(listOf(LineRoute("x", listOf("TOP", "ELSE"))), mapOf("ELSE" to "Elsewhere"))
        val result = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail, "other" to other))
        assertEquals(listOf("rail"), result.stops.single().lines.map { it.id })
    }

    @Test
    fun `a status-only line whose route failed is flagged, not a quiet no`() {
        val top = stop("TOP", "Top", lines = listOf(LineRef("rail", "Rail", "tube")))
        val result = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), mapOf("rail" to null))
        assertTrue(result.stops.isEmpty())
        assertTrue(result.unresolved)
    }

    @Test
    fun `line ids to load come from departures and declared lines`() {
        val top = stop("TOP", "Top", departure("Bottom A", 60), departure("X", 60, lineId = ""), lines = listOf(LineRef("other", "Other", "tube")))
        assertEquals(listOf("rail", "other"), DirectTrips.lineIds(listOf(top)))
    }
}
