package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Whether the widget would show a starred or picked row: synthetic stops only. */
class DepartureRowsShowsTest {
    private val now: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(seconds: Long, line: String, direction: String, destinationId: String = "", mode: String = "bus") = Departure(
        lineId = line,
        lineName = line,
        direction = direction,
        destination = "Somewhere",
        platform = null,
        expectedArrival = now.plusSeconds(seconds),
        mode = mode,
        destinationId = destinationId,
    )

    private fun stop(departures: List<Departure>, lines: List<LineRef> = emptyList(), nearer: Terminating.Nearer = Terminating.Nearer()) =
        StopArrivals(stopId = "940GA", stopName = "Stop A", departures = departures, fetchedAt = now, lines = lines, nearer = nearer)

    @Test
    fun `a served line shows, even with no predictions right now`() {
        val quiet = stop(emptyList(), lines = listOf(LineRef("73", "73", "bus")))
        assertTrue(DepartureRows.shows(quiet, StarredRow("940GA", "73", "inbound"), emptySet(), now))
        assertFalse("not served", DepartureRows.shows(quiet, StarredRow("940GA", "38", "inbound"), emptySet(), now))
        assertFalse("mode hidden", DepartureRows.shows(quiet, StarredRow("940GA", "73", "inbound"), setOf("bus"), now))
        assertFalse("another stop", DepartureRows.shows(quiet, StarredRow("940GB", "73", "inbound"), emptySet(), now))
    }

    @Test
    fun `a row whose own services all terminate nearer is filtered, before and after they leave`() {
        val nearer = Terminating.Nearer(ids = setOf("940GNEAR"))
        val stop = stop(
            listOf(departure(60, "73", "outbound", destinationId = "940GNEAR"), departure(90, "73", "inbound")),
            nearer = nearer,
        )
        assertFalse(DepartureRows.shows(stop, StarredRow("940GA", "73", "outbound"), emptySet(), now))
        assertFalse(DepartureRows.shows(stop, StarredRow("940GA", "73", "outbound"), emptySet(), now.plusSeconds(200)))
        assertTrue("the other direction is unaffected", DepartureRows.shows(stop, StarredRow("940GA", "73", "inbound"), emptySet(), now))
    }

    @Test
    fun `a blank prediction mode doesn't let a hidden mode's row through`() {
        // The line's first prediction (the other direction) has no mode; the row's own service does.
        val row = StarredRow("940GA", "73", "inbound")
        val named = stop(listOf(departure(60, "73", "outbound", mode = ""), departure(90, "73", "inbound")))
        assertFalse(DepartureRows.shows(named, row, setOf("bus"), now))
        // No prediction names it: the stop's advertised line does.
        val advertised = stop(listOf(departure(90, "73", "inbound", mode = "")), lines = listOf(LineRef("73", "73", "bus")))
        assertFalse(DepartureRows.shows(advertised, row, setOf("bus"), now))
        assertTrue(DepartureRows.shows(advertised, row, setOf("tube"), now))
    }

    @Test
    fun `the widget's row resolves a blank mode the same way, so it's hidden too`() {
        // The soonest prediction omits its mode; a later one names it.
        val stop = stop(listOf(departure(60, "73", "inbound", mode = ""), departure(90, "73", "inbound")))
        val row = DepartureRows.across(listOf(stop), now, splitPlatforms = false).single()
        assertEquals("bus", row.mode)
        assertEquals(emptyList<DepartureRow>(), HiddenModes.rows(listOf(row), setOf("bus")))
        assertFalse(DepartureRows.shows(stop, StarredRow.of(row), setOf("bus"), now))
        // No prediction names it: the stop's advertised line does.
        val advertised = stop(listOf(departure(60, "73", "inbound", mode = "")), lines = listOf(LineRef("73", "73", "bus")))
        assertEquals("bus", DepartureRows.across(listOf(advertised), now, splitPlatforms = false).first { it.lineId == "73" }.mode)
    }

    @Test
    fun `a row whose predictions all omit the mode matches the widget's row, not another direction's`() {
        // No advertised line; the inbound predictions name no mode, the outbound ones say bus.
        val stop = stop(listOf(departure(60, "73", "inbound", mode = ""), departure(90, "73", "outbound")))
        val inbound = DepartureRows.across(listOf(stop), now, splitPlatforms = false).single { it.directionKey == "inbound" }
        val offered = HiddenModes.rows(listOf(inbound), setOf("bus")).isNotEmpty()
        assertEquals(offered, DepartureRows.shows(stop, StarredRow.of(inbound), setOf("bus"), now))
    }
}
