package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure per-place grouping rule (SPEC D8): one group per place (stops sharing a display
 * name — a junction's poles, a station's platforms), bare-name header, warnings leading their
 * place. The direction/terminus subhead is a follow-up and is not exercised here. Public
 * infrastructure/line names only in the fixtures — no user route data (SPEC *Privacy*).
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
        clusterId: String = "",
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
        clusterId = clusterId,
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
    fun `two poles sharing a cluster merge under one header`() {
        // The junction case: a northbound and a southbound pole are separate TfL stop ids that
        // share a cluster (TfL's stationNaptan), so they read as one place under a single header —
        // the way a Tube station's platforms already did (SPEC *Finding stops*). A third, distinct
        // place makes the merged place's own header show (a lone place would imply itself).
        val rows = listOf(
            row("P1", "Turnpike Lane", clusterId = "490G0TPL", direction = "inbound", destination = "Central"),
            row("P2", "Turnpike Lane", clusterId = "490G0TPL", direction = "outbound", destination = "North"),
            row("A", "Archway", clusterId = "490G0ARW", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Turnpike Lane", "Archway"), groups.map { it.stopName })
        // The merged place carries both poles' rows, from both stop ids, under one group.
        val cluster = groups.first()
        assertEquals(2, cluster.rows.size)
        assertEquals(setOf("P1", "P2"), cluster.rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.all { it.showHeader })
    }

    @Test
    fun `two same-named stops in different clusters stay apart`() {
        // The reason the key is the cluster, not the name: TfL spells one station several ways and
        // gives adjacent stations similar names, so two stops that read alike but are different
        // places (different stationNaptan) must not merge (maintainer, 2026-09-21).
        val rows = listOf(
            row("K1", "King's Cross Station", clusterId = "940GZZLUKSX", destination = "Morden"),
            row("S1", "King's Cross Station", clusterId = "490G000760", destination = "Barnet"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(2, groups.size)
        assertEquals(setOf("K1"), groups[0].rows.mapTo(mutableSetOf()) { it.stopId })
        assertEquals(setOf("S1"), groups[1].rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a disrupted pole stays its own group, not merged with a clear same-named pole`() {
        // One pole of a junction is closed, the other running. Clustering them by name would put
        // the closure (which names no stop — the header does) beside the other pole's catchable
        // departures under one header, leaving a rider unable to tell which pole is closed (SPEC
        // principle 2). So a disrupted stop keeps its own group; only clear poles merge.
        val rows = listOf(
            row("P1", "Turnpike Lane", clusterId = "490G0TPL", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("P2", "Turnpike Lane", clusterId = "490G0TPL", direction = "outbound", destination = "Palmers Green"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // Same cluster, so they would merge — but the closed pole is carved out to its own group.
        assertEquals(2, groups.size)
        // The closed pole leads (warning), on its own; the running pole is a separate group.
        assertEquals(setOf("P1"), groups.first().rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.first().rows.single().stopDisruption != null)
        assertEquals(setOf("P2"), groups.last().rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.all { it.showHeader })
    }

    @Test
    fun `a pole with a suspended-line warning stays its own group`() {
        // Same class as the closure case, one line down: a line-status "No departures" row (a
        // suspended line, no predictions) also names no pole or direction, so merging it beside a
        // same-named pole's live same-line departures would read as "141 suspended" next to live
        // 141 times with nothing to say which pole (SPEC principle 2). Any warning row (no
        // countdown) keeps its stop's group its own.
        val rows = listOf(
            row("P1", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", upcoming = emptyList()),
            row("P2", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "outbound", destination = "Palmers Green"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // Same cluster, so they would merge — but the warned pole is carved out to its own group.
        assertEquals(2, groups.size)
        // The warned pole leads (no countdown), on its own; the running pole is a separate group.
        assertEquals(setOf("P1"), groups.first().rows.mapTo(mutableSetOf()) { it.stopId })
        assertTrue(groups.first().rows.single().upcoming.isEmpty())
        assertEquals(setOf("P2"), groups.last().rows.mapTo(mutableSetOf()) { it.stopId })
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
