package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure per-stop grouping rule (SPEC D8): one group per stop, bare-name header, warnings
 * leading their stop. The direction/terminus qualifier is a follow-up and is not exercised
 * here. Public infrastructure/line names only in the fixtures — no user route data (SPEC
 * *Privacy*).
 */
class StopGroupingTest {

    private val now: Instant = Instant.parse("2026-09-21T08:00:00Z")

    private fun dep(destination: String, platform: String = "", mode: String = "tube") =
        Departure(
            lineId = "l",
            lineName = "L",
            direction = "",
            destination = destination,
            platform = platform,
            expectedArrival = now.plusSeconds(60),
            mode = mode,
        )

    private fun row(
        stopId: String,
        stopName: String,
        lineId: String = "l",
        direction: String = "",
        destination: String = "Terminus",
        platform: String = "",
        mode: String = "tube",
        upcoming: List<Departure> = listOf(dep(destination, platform, mode)),
        stopDisruption: String? = null,
    ) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        lineId = lineId,
        lineName = lineId.uppercase(),
        direction = direction,
        directionKey = direction.ifBlank { platform.ifBlank { destination } },
        destination = destination,
        mode = mode,
        upcoming = upcoming,
        fetchedAt = now,
        stopDisruption = stopDisruption,
    )

    @Test
    fun `two stops group separately and both show a header`() {
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", destination = "Walthamstow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
        assertTrue(groups.all { it.showHeader })
    }

    @Test
    fun `a station's several directions merge into one group in first-appearance order`() {
        val rows = listOf(
            row("OXC", "Oxford Circus", lineId = "central", destination = "Hainault"),
            row("KSX", "King's Cross", lineId = "victoria", destination = "Brixton"),
            row("OXC", "Oxford Circus", lineId = "bakerloo", destination = "Harrow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // One group per stop, however many directions the station serves.
        assertEquals(listOf("Oxford Circus", "King's Cross"), groups.map { it.stopName })
        assertEquals(3, groups[0].rows.size + groups[1].rows.size)
    }

    @Test
    fun `a single stop hides its header — the stop is implied`() {
        val rows = listOf(row("KSX", "King's Cross", destination = "Brixton"))
        val group = StopGrouping.groupByStop(rows).single()
        assertFalse(group.showHeader)
    }

    @Test
    fun `a closed stop is one group with a header naming it`() {
        val rows = listOf(
            row("OXC", "Oxford Circus", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("OXC", "Oxford Circus", destination = "Hainault"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        // A closed stop always shows its header, since the closure card no longer repeats the
        // name, even though it is the only stop on screen.
        assertTrue(group.showHeader)
        assertTrue(group.rows.any { it.stopDisruption != null })
    }

    @Test
    fun `a stop with a warning leads an ordinary-only stop`() {
        // Grouping must not rely on the caller pre-sorting warnings first: even with the
        // ordinary stop's row first in the input, the closed stop's group leads.
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
        )
        assertEquals(listOf("Brixton", "Archway"), StopGrouping.groupByStop(rows).map { it.stopName })
    }

    @Test
    fun `each stop's rows stay contiguous even when two stops carry warnings`() {
        // Two warned stops: each stays one block led by its warning (option B). The cost the
        // maintainer accepted is that stop A's ordinary rows can sit above stop B's warning,
        // rather than a single global warning band across stops.
        val rows = listOf(
            row("A", "Archway", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("B", "Brixton", destination = "Walthamstow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
        // Each group's own warning row leads its own rows.
        assertTrue(groups.all { it.rows.first().stopDisruption != null })
    }
}
