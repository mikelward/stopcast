package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `a station splits into one header per compass direction`() {
        // King's Cross: a rail platform's compass ("Northbound - Platform 1") splits a station's
        // cards into one header per direction, so a busy interchange isn't a wall of cards under
        // one bare name (SPEC D8). Public line names/termini only (SPEC *Privacy*).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 3"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // One group per compass direction, in first-appearance (soonest-first) order.
        assertEquals(listOf("Northbound", "Eastbound", "Southbound"), groups.map { it.directionLabel })
        assertTrue(groups.all { it.stopName == "King's Cross" && it.showHeader })
    }

    @Test
    fun `a station's direction blocks stay adjacent when another place interleaves by time`() {
        // SPEC D8: a place's cards stay adjacent. A station split into direction blocks must not
        // have a nearer bus stop's block wedged between its directions just because that bus leaves
        // sooner than the station's second direction. Rows arrive soonest-first (KX north, bus, KX
        // east); the station's two blocks must still come out adjacent, the bus after both.
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("BUS", "York Way", clusterId = "490G0YRK", lineId = "390", direction = "outbound", destination = "Archway", platform = "", mode = "bus"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 3"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(
            listOf("King's Cross" to "Northbound", "King's Cross" to "Eastbound", "York Way" to null),
            groups.map { it.stopName to it.directionLabel },
        )
    }

    @Test
    fun `a cluster's direction groups share one canonical place name`() {
        // Members of one cluster can carry different cleaned names (the reason to group by
        // stationNaptan, not by name). Every direction group of the place must show the same name,
        // never "King's Cross – Eastbound" beside "King's Cross St. Pancras – Westbound" (Codex P2,
        // PR #109). The first row's name for the place wins.
        val rows = listOf(
            row("A", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("B", "King's Cross St. Pancras", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Northbound", "Southbound"), groups.map { it.directionLabel })
        assertEquals(listOf("King's Cross", "King's Cross"), groups.map { it.stopName })
    }

    @Test
    fun `compass groups by platform, not TfL inbound-outbound`() {
        // The reason the key is the compass, not TfL's `direction`: at King's Cross TfL tags the
        // same Eastbound platform `inbound` for the Circle and `outbound` for the Hammersmith &
        // City (confirmed against live data, 2026-09-22), so grouping on inbound/outbound would
        // split one platform's trains into two headers. The compass keeps them together.
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Eastbound - Platform 2"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "hammersmith-city", direction = "outbound", destination = "Barking", platform = "Eastbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(1, groups.size)
        assertEquals("Eastbound", groups.single().directionLabel)
        assertEquals(setOf("circle", "hammersmith-city"), groups.single().rows.mapTo(mutableSetOf()) { it.lineId })
        assertTrue(groups.single().showHeader)
    }

    @Test
    fun `a row resolves its direction from a later prediction when the soonest has no platform`() {
        // A row is one (line, direction); TfL can leave the platform blank on the soonest
        // prediction while a later one in the same row carries the compass. The row must still
        // resolve to its direction header, not drop to the bare header until the blank prediction
        // departs (Codex P2, PR #109).
        val rows = listOf(
            row(
                "KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria",
                direction = "outbound", destination = "Walthamstow Central",
                upcoming = listOf(
                    dep("Walthamstow Central", platform = ""),
                    dep("Walthamstow Central", platform = "Northbound - Platform 1"),
                ),
            ),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertEquals("Northbound", group.directionLabel)
    }

    @Test
    fun `mixed-case compass labels group into one direction block`() {
        // TfL supplying "Northbound" and "NORTHBOUND" must not split into two identical-looking
        // sections — the canonical casing keeps them one block (Codex P2, PR #109).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "northern", direction = "outbound", destination = "High Barnet", platform = "NORTHBOUND - Platform 5"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertEquals("Northbound", group.directionLabel)
        assertEquals(2, group.rows.size)
    }

    @Test
    fun `one compass direction spanning two platforms is one group`() {
        // Eastbound spans Platform 2 (sub-surface lines) and Platform 6 (Piccadilly) at King's
        // Cross, so the compass is correctly coarser than the platform number — one Eastbound group.
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", direction = "inbound", destination = "Edgware Road", platform = "Eastbound - Platform 2"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "piccadilly", direction = "outbound", destination = "Cockfosters", platform = "Eastbound - Platform 6"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(1, groups.size)
        assertEquals("Eastbound", groups.single().directionLabel)
    }

    @Test
    fun `a bus pole with no platform keeps the bare place header`() {
        // Rail-first: a bus arrival carries no platform (its bearing is in stop metadata, not the
        // feed), so it resolves no compass and falls to the bare per-place header — buses are not
        // regressed by the rail split. Public route/place names only (SPEC *Privacy*).
        val rows = listOf(
            row("P1", "Cranley Gardens", clusterId = "490G00005712", lineId = "43", direction = "inbound", destination = "London Bridge", platform = "", mode = "bus"),
            row("A", "Archway", clusterId = "490G0ARW", destination = "Morden"),
        )
        val cranley = StopGrouping.groupByStop(rows).first { it.stopName == "Cranley Gardens" }
        assertNull(cranley.directionLabel)
    }

    @Test
    fun `bus poles with stop-letter platforms stay merged under one bare header`() {
        // A bus prediction can carry a stop-local `platform` (the stop letter). It must not be read
        // as a direction, or one cluster's poles would split into "Stop A"/"Stop B" groups instead
        // of merging under one bare header (Codex P2, PR #109).
        val rows = listOf(
            row("PA", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "inbound", destination = "London Bridge", platform = "Stop A", mode = "bus"),
            row("PB", "Turnpike Lane", clusterId = "490G0TPL", lineId = "141", direction = "outbound", destination = "Palmers Green", platform = "Stop B", mode = "bus"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertNull(group.directionLabel)
        assertEquals(setOf("PA", "PB"), group.rows.mapTo(mutableSetOf()) { it.stopId })
    }

    @Test
    fun `a suspended line at a station does not collapse the live direction groups`() {
        // One stop id with a suspended declared line (a directionless "No departures" status row)
        // plus live rows carrying compass platforms: the live rows must still split by direction,
        // not all collapse to one bare group because the stop is "warned" (Codex P2, PR #109).
        val rows = listOf(
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "circle", destination = "", upcoming = emptyList()),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "outbound", destination = "Walthamstow Central", platform = "Northbound - Platform 1"),
            row("KSX", "King's Cross", clusterId = "940GZZLUKSX", lineId = "victoria", direction = "inbound", destination = "Brixton", platform = "Southbound - Platform 2"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // The live rows keep their compass groups.
        assertEquals(setOf("Northbound", "Southbound"), groups.mapNotNull { it.directionLabel }.toSet())
        // The suspended line is its own directionless group (a warning), not merged into a block.
        assertTrue(groups.any { it.directionLabel == null && it.rows.all { r -> r.upcoming.isEmpty() } })
    }

    @Test
    fun `a single stop hides its header — the stop is implied`() {
        val rows = listOf(row("KSX", "King's Cross", destination = "Brixton"))
        val group = StopGrouping.groupByStop(rows).single()
        assertFalse(group.showHeader)
    }

    @Test
    fun `a closure-only stop counts as a place, so a lone departures stop keeps its header`() {
        // Codex P1 (PR #91): on a location-free list, one stop's arrivals failed leaving only a
        // closure (rendered header-less, outside grouping) while another stop has departures. The
        // closure's stop is still a place, so the departures stop must show its name header rather
        // than read as a single-place list and drop it.
        val rows = listOf(
            row("A", "Archway", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("B", "Brixton", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        // Only the departures stop is grouped; the closure forms no group.
        assertEquals(listOf("Brixton"), groups.map { it.stopName })
        // It still shows its header, because Archway (the closure) is a second place on screen.
        assertTrue(groups.single().showHeader)
    }

    @Test
    fun `a lone stop with both a closure and departures still implies its place`() {
        // The single-place case is preserved: one stop carries both a closure (header-less) and its
        // own departures. One place, so the departures header stays hidden.
        val rows = listOf(
            row("A", "Archway", destination = "", stopDisruption = "Closed", upcoming = emptyList()),
            row("A", "Archway", destination = "Morden"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway"), groups.map { it.stopName })
        assertFalse(groups.single().showHeader)
    }

    @Test
    fun `a stop with a warning leads an ordinary-only stop`() {
        // Grouping must not rely on the caller pre-sorting warnings first: even with the ordinary
        // stop's row first in the input, the warned stop's group leads. Stop closures render
        // outside grouping now, so a line-status "No departures" warning stands for the class here.
        val rows = listOf(
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", lineId = "vic", upcoming = emptyList()),
        )
        assertEquals(listOf("Brixton", "Archway"), StopGrouping.groupByStop(rows).map { it.stopName })
    }

    @Test
    fun `each stop's rows stay contiguous even when two stops carry warnings`() {
        // Two warned stops: each stays one block led by its warning (option B). The cost the
        // maintainer accepted is that stop A's ordinary rows can sit above stop B's warning,
        // rather than a single global warning band across stops. (Line-status warnings — closures
        // no longer group here.)
        val rows = listOf(
            row("A", "Archway", lineId = "vic", upcoming = emptyList()),
            row("A", "Archway", destination = "Morden"),
            row("B", "Brixton", lineId = "vic", upcoming = emptyList()),
            row("B", "Brixton", destination = "Walthamstow"),
        )
        val groups = StopGrouping.groupByStop(rows)
        assertEquals(listOf("Archway", "Brixton"), groups.map { it.stopName })
        // Each group's own warning row (no countdown) leads its own rows.
        assertTrue(groups.all { it.rows.first().upcoming.isEmpty() })
    }

    @Test
    fun `a bus stop whose routes all head one way takes the shared terminus`() {
        // The bus analog of the rail compass: a compass-less bus place where every route heads to
        // one terminus is qualified "→ Bank", and the terminus forces its header even as a lone place.
        val rows = listOf(
            row("BP", "Turnpike Lane", lineId = "141", destination = "Bank", mode = "bus"),
            row("BP", "Turnpike Lane", lineId = "341", destination = "Bank", mode = "bus"),
        )
        val group = StopGrouping.groupByStop(rows).single()
        assertEquals("Bank", group.terminusLabel)
        assertNull(group.directionLabel)
        assertTrue(group.showHeader)
    }

    @Test
    fun `a bus stop serving diverging termini stays bare`() {
        // Routes at the pole head different ways, so there is no one terminus to stand behind — the
        // header stays the bare name rather than claim a direction (SPEC principle 1).
        val rows = listOf(
            row("BP", "Wood Green", lineId = "141", destination = "Bank", mode = "bus"),
            row("BP", "Wood Green", lineId = "341", destination = "Waterloo", mode = "bus"),
        )
        assertNull(StopGrouping.groupByStop(rows).single().terminusLabel)
    }

    @Test
    fun `a short-working within one route blocks the shared terminus`() {
        // One (line, direction) row can carry departures to more than one terminus (a short-working
        // among the through buses); the row headline names only the soonest. The terminus must be a
        // consensus of every upcoming departure, not the headline — else "→ Bank" would show while a
        // card below still lists a Waterloo departure (Codex P1, PR #116).
        val rows = listOf(
            row(
                "BP", "Wood Green", lineId = "141", mode = "bus", destination = "Bank",
                upcoming = listOf(dep("Bank", "", "bus"), dep("Waterloo", "", "bus")),
            ),
        )
        assertNull(StopGrouping.groupByStop(rows).single().terminusLabel)
    }

    @Test
    fun `a blank destination among the bus routes blocks the shared terminus`() {
        // TfL omitted a terminus, so a shared one can't be confirmed — bail rather than guess.
        val rows = listOf(
            row("BP", "Wood Green", lineId = "141", destination = "Bank", mode = "bus"),
            row("BP", "Wood Green", lineId = "341", destination = "", mode = "bus"),
        )
        assertNull(StopGrouping.groupByStop(rows).single().terminusLabel)
    }

    @Test
    fun `a suspended bus route blocks the shared terminus`() {
        // A live route to Bank plus a suspended bus route (no predictions) at the same stop: the
        // suspended route's card rides in the group but its destination is unknown, so the header
        // can't claim "→ Bank" over it (Codex P2, PR #116) — every route must name the terminus.
        val rows = listOf(
            row("BP", "Turnpike Lane", lineId = "141", mode = "bus", destination = "Bank"),
            row("BP", "Turnpike Lane", lineId = "341", mode = "bus", upcoming = emptyList()),
        )
        assertNull(StopGrouping.groupByStop(rows).single().terminusLabel)
    }

    @Test
    fun `a rail place with a compass takes no terminus`() {
        // Rail's direction is the compass; the terminus is the bus fallback, so a compass group never
        // carries one even when its trains share a destination.
        val rows = listOf(
            row("KSX", "King's Cross", platform = "Northbound - Platform 1", destination = "Walthamstow"),
            row("A", "Archway", destination = "Morden"),
        )
        val kings = StopGrouping.groupByStop(rows).first { it.stopName == "King's Cross" }
        assertEquals("Northbound", kings.directionLabel)
        assertNull(kings.terminusLabel)
    }

    @Test
    fun `a compass-less non-bus place takes no terminus`() {
        // The terminus is bus-only for now (the qualifier chain is mode-aware): a bare-platform tube
        // group sharing one destination still shows the bare name, not "→ Terminus".
        val rows = listOf(
            row("X", "Some Depot", mode = "tube", destination = "Aldgate"),
            row("A", "Archway", mode = "tube", destination = "Morden"),
        )
        val depot = StopGrouping.groupByStop(rows).first { it.stopName == "Some Depot" }
        assertNull(depot.terminusLabel)
        assertNull(depot.directionLabel)
    }
}
