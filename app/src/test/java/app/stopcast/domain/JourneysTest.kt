package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stations and positions only (around the obviously-fake (51.5, -0.12)). */
class JourneysTest {
    private val now = Instant.parse("2026-09-23T08:00:00Z")

    // A line with two southbound branches from "Top": via "Mid" to "Bottom A", and via "Side" to
    // "Bottom B". A journey Top ↔ Mid only has the first branch's trains.
    private val sequence = LineSequence(
        routes = listOf(
            LineRoute("Top ↔ Bottom A via Mid", listOf("TOP", "MID", "BOTA")),
            LineRoute("Top ↔ Bottom B via Side", listOf("TOP", "SIDE", "BOTB")),
            LineRoute("Bottom A ↔ Top via Mid", listOf("BOTA", "MID", "TOP")),
        ),
        stopNames = mapOf("TOP" to "Top", "MID" to "Mid", "BOTA" to "Bottom A", "SIDE" to "Side", "BOTB" to "Bottom B"),
    )

    private val journey = StarredJourney(
        from = JourneyEnd("TOP", "Top", 51.51, -0.12),
        to = JourneyEnd("MID", "Mid", 51.49, -0.12),
        lineId = "example",
    )

    private fun departure(destination: String, inSeconds: Long) = Departure(
        lineId = "example",
        lineName = "Example",
        direction = "outbound",
        destination = destination,
        platform = null,
        expectedArrival = now.plusSeconds(inSeconds),
        mode = "tube",
    )

    private fun rowsAt(stopId: String, vararg departures: Departure) = DepartureRows.across(
        listOf(StopArrivals(stopId, stopId, departures.toList(), fetchedAt = now)),
        now,
    )

    @Test
    fun `the nearer end becomes the origin`() {
        assertEquals("TOP", Journeys.oriented(journey, 51.509, -0.12).from.stopId)
        assertEquals("MID", Journeys.oriented(journey, 51.491, -0.12).from.stopId)
    }

    @Test
    fun `without a position the saved direction stands`() {
        assertEquals("TOP", Journeys.oriented(journey, null, null).from.stopId)
        val noCoords = journey.copy(to = journey.to.copy(latitude = null, longitude = null))
        assertEquals("TOP", Journeys.oriented(noCoords, 51.491, -0.12).from.stopId)
    }

    @Test
    fun `only trains that call at the other end are kept`() {
        val rows = rowsAt("TOP", departure("Bottom A", 120), departure("Bottom B", 60), departure("Bottom A", 600))
        val kept = Journeys.rows(journey, rows, sequence)!!
        val times = kept.flatMap { it.upcoming }.map { it.destination }
        assertEquals(listOf("Bottom A", "Bottom A"), times)
    }

    @Test
    fun `reversed, the trains toward the saved origin are shown`() {
        val rows = rowsAt("MID", departure("Top", 90))
        val kept = Journeys.rows(journey.reversed(), rows, sequence)!!
        assertEquals(listOf("Top"), kept.flatMap { it.upcoming }.map { it.destination })
    }

    @Test
    fun `no route data yet says unknown, not no trains`() {
        assertNull(Journeys.rows(journey, rowsAt("TOP", departure("Bottom A", 120)), null))
    }

    @Test
    fun `another line at the origin isn't shown`() {
        val other = departure("Bottom A", 120).copy(lineId = "other")
        assertTrue(Journeys.rows(journey, rowsAt("TOP", other), sequence)!!.isEmpty())
    }

    @Test
    fun `starring either way round toggles the same journey`() {
        val starred = Journeys.toggle(emptyList(), journey)
        assertEquals(1, starred.size)
        assertTrue(Journeys.toggle(starred, journey.reversed()).isEmpty())
    }

    @Test
    fun `a suspended line with no trains keeps its warning`() {
        val suspended = LineStatus("example", 6, "Suspended")
        val rows = DepartureRows.across(
            listOf(StopArrivals("TOP", "Top", emptyList(), fetchedAt = now, lines = listOf(journey.line))),
            now,
            mapOf("example" to suspended),
        )
        val kept = Journeys.rows(journey, rows, sequence)!!
        assertEquals(listOf(suspended), kept.map { it.status })
    }

    @Test
    fun `the journey declares its line for the origin fetch`() {
        val named = journey.copy(lineName = "Example", mode = "tube")
        assertEquals(LineRef("example", "Example", "tube"), named.line)
        assertEquals(named.line, named.reversed().line)
    }

    @Test
    fun `a train whose path can't be resolved is unresolved, not a definite no`() {
        val unknown = rowsAt("TOP", departure("Nowhere", 120))
        assertTrue(Journeys.rows(journey, unknown, sequence)!!.isEmpty())
        assertTrue(Journeys.anyUnresolved(journey, unknown, sequence))
        // A train resolved to another branch is a definite "doesn't call there".
        assertFalse(Journeys.anyUnresolved(journey, rowsAt("TOP", departure("Bottom B", 60)), sequence))
    }
}
