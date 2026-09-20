package app.stopcast.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureRowsTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(
        lineId: String,
        lineName: String,
        direction: String,
        destination: String,
        offsetSeconds: Long,
        platform: String? = null,
        mode: String = "tube",
        branch: String? = null,
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = mode,
        branch = branch,
    )

    private fun rowWith(vararg upcoming: Departure): DepartureRow {
        val soonest = upcoming.first()
        return DepartureRow(
            stopId = "940GZZLUEUS",
            stopName = "Euston",
            lineId = soonest.lineId,
            lineName = soonest.lineName,
            direction = soonest.direction,
            directionKey = soonest.direction,
            destination = soonest.destination,
            mode = soonest.mode,
            upcoming = upcoming.toList(),
            fetchedAt = now,
        )
    }

    @Test
    fun `destinationLines yields one group for a non-branching row`() {
        val row = rowWith(
            departure("victoria", "Victoria", "outbound", "Brixton", 120),
            departure("victoria", "Victoria", "outbound", "Brixton", 360),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(1, lines.size)
        assertEquals("Brixton", lines[0].destination)
        assertNull(lines[0].branch)
        assertEquals(2, lines[0].times.size)
    }

    @Test
    fun `destinationLines splits a branching direction, soonest group first`() {
        // Same line and direction, two destinations — the soonest (Brixton) leads, the
        // divergent one (Walthamstow) keeps its own group and its own times (SPEC D8).
        val row = rowWith(
            departure("victoria", "Victoria", "outbound", "Brixton", 120),
            departure("victoria", "Victoria", "outbound", "Walthamstow Central", 300),
            departure("victoria", "Victoria", "outbound", "Brixton", 480),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(listOf("Brixton", "Walthamstow Central"), lines.map { it.destination })
        assertEquals(2, lines[0].times.size)
        assertEquals(1, lines[1].times.size)
    }

    @Test
    fun `destinationLines splits one terminus reached via two branches`() {
        // The P1 case: same terminus, different trunks. Each branch is its own group with its
        // own countdown, so a divergent train's time never sits under the wrong branch.
        val row = rowWith(
            departure("northern", "Northern", "northbound", "Edgware", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "Edgware", 540, branch = "Charing Cross"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing Cross"), lines.map { it.branch })
        lines.forEach { assertEquals("Edgware", it.destination) }
    }

    @Test
    fun `destinationLines caps the countdowns within each group`() {
        val many = (1..8).map { departure("victoria", "Victoria", "outbound", "Brixton", it * 60L) }
        val lines = DepartureRows.destinationLines(rowWith(*many.toTypedArray()), maxTimes = 3)
        assertEquals(1, lines.size)
        assertEquals(3, lines[0].times.size)
    }

    @Test
    fun `destinationLines keeps a divergent destination whose soonest train is past the cap`() {
        // Three imminent Morden trains fill the first maxTimes, then a Battersea train. Capping
        // the flat list before grouping would drop Battersea's line entirely; grouping first
        // keeps it, with its own (single) countdown (SPEC D8).
        val row = rowWith(
            departure("northern", "Northern", "southbound", "Morden", 60),
            departure("northern", "Northern", "southbound", "Morden", 120),
            departure("northern", "Northern", "southbound", "Morden", 180),
            departure("northern", "Northern", "southbound", "Battersea Power Station", 240),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(listOf("Morden", "Battersea Power Station"), lines.map { it.destination })
        assertEquals(3, lines[0].times.size)
        assertEquals(1, lines[1].times.size)
        assertEquals(240L, lines[1].times.single().expectedArrival.epochSecond - now.epochSecond)
    }

    @Test
    fun `a two-way line at a stop becomes one row per direction`() {
        val southbound1 = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val southbound2 = departure("victoria", "Victoria", "outbound", "Brixton", 360)
        val northbound = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 240)

        val rows = DepartureRows.forStop(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(southbound1, northbound, southbound2),
            now = now,
        )

        assertEquals(2, rows.size)
        // Ordered by soonest departure: southbound (120s) before northbound (240s).
        assertEquals("outbound", rows[0].direction)
        assertEquals("Brixton", rows[0].destination)
        assertEquals(listOf(southbound1, southbound2), rows[0].upcoming)
        assertEquals("inbound", rows[1].direction)
        assertEquals("Walthamstow Central", rows[1].destination)
        assertEquals(listOf(northbound), rows[1].upcoming)
    }

    @Test
    fun `carries the caller's stop identity onto every row`() {
        val rows = DepartureRows.forStop(
            stopId = "490000123A",
            stopName = "High Street",
            departures = listOf(departure("24", "24", "", "Pimlico", 90)),
            now = now,
        )

        assertEquals(1, rows.size)
        assertEquals("490000123A", rows[0].stopId)
        assertEquals("High Street", rows[0].stopName)
        assertEquals("", rows[0].direction)
    }

    @Test
    fun `drops departed services and omits a group with nothing upcoming`() {
        val gone = departure("bakerloo", "Bakerloo", "inbound", "Harrow & Wealdstone", -30)
        val liveSoon = departure("central", "Central", "outbound", "Ealing Broadway", 60)
        val liveLater = departure("central", "Central", "outbound", "Ealing Broadway", 300)

        val rows = DepartureRows.forStop(
            stopId = "940GZZLUOXC",
            stopName = "Oxford Circus",
            departures = listOf(gone, liveLater, liveSoon),
            now = now,
        )

        // The Bakerloo group had only a departed service, so it yields no row.
        assertEquals(1, rows.size)
        assertEquals("central", rows[0].lineId)
        assertEquals(listOf(liveSoon, liveLater), rows[0].upcoming)
    }

    @Test
    fun `headline destination is the soonest departure's, even when a direction branches`() {
        // A branching line: same (line, direction), different destinations.
        val soonerBranch = departure("london-overground", "Mildmay line", "outbound", "Richmond", 180)
        val laterBranch = departure("london-overground", "Mildmay line", "outbound", "Clapham Junction", 300)

        val rows = DepartureRows.forStop(
            stopId = "910GWLWICH",
            stopName = "Willesden Junction",
            departures = listOf(laterBranch, soonerBranch),
            now = now,
        )

        assertEquals(1, rows.size)
        assertEquals("Richmond", rows[0].destination)
        assertEquals(listOf(soonerBranch, laterBranch), rows[0].upcoming)
    }

    @Test
    fun `rows are ordered by soonest departure, ties broken by line then direction`() {
        val busSoon = departure("29", "29", "", "Trafalgar Square", 120)
        val tubeOutbound = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val tubeInbound = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120)

        val rows = DepartureRows.forStop(
            stopId = "seed",
            stopName = "Seed",
            departures = listOf(tubeOutbound, busSoon, tubeInbound),
            now = now,
        )

        // All three share 120s, so the tie-break decides: line "29" < "Victoria",
        // then Victoria's "inbound" < "outbound".
        assertEquals(listOf("29", "Victoria", "Victoria"), rows.map { it.lineName })
        assertEquals(listOf("", "inbound", "outbound"), rows.map { it.direction })
    }

    @Test
    fun `blank direction does not merge opposite directions, falling back to platform`() {
        // TfL omitted direction, but the platform names it — the two must stay apart.
        val north = departure("northern", "Northern", "", "High Barnet", 120, platform = "Northbound - Platform 1")
        val south = departure("northern", "Northern", "", "Morden", 240, platform = "Southbound - Platform 4")

        val rows = DepartureRows.forStop("940GZZLUEUS", "Euston", listOf(south, north), now)

        assertEquals(2, rows.size)
        assertEquals(listOf("High Barnet", "Morden"), rows.map { it.destination })
    }

    @Test
    fun `blank direction and no platform falls back to destination`() {
        val toPimlico = departure("24", "24", "", "Pimlico", 90)
        val toHampstead = departure("24", "24", "", "Hampstead Heath", 150)

        val rows = DepartureRows.forStop("490000123A", "Trafalgar Square", listOf(toHampstead, toPimlico), now)

        assertEquals(2, rows.size)
        assertEquals(listOf("Pimlico", "Hampstead Heath"), rows.map { it.destination })
    }

    @Test
    fun `two platforms of the same present direction still share one row`() {
        // Direction is present, so platform is ignored: one northbound row, both platforms.
        val p3 = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120, platform = "Platform 3")
        val p4 = departure("victoria", "Victoria", "inbound", "Walthamstow Central", 300, platform = "Platform 4")

        val rows = DepartureRows.forStop("940GZZLUVIC", "Victoria", listOf(p4, p3), now)

        assertEquals(1, rows.size)
        assertEquals(listOf(p3, p4), rows[0].upcoming)
    }

    @Test
    fun `each row carries a stable directionKey identity`() {
        // Present direction → directionKey is that direction.
        val present = DepartureRows.forStop(
            "s", "S",
            listOf(departure("victoria", "Victoria", "inbound", "Walthamstow Central", 120)),
            now,
        ).single()
        assertEquals("inbound", present.directionKey)
        assertEquals("inbound", present.direction)

        // Blank direction split by platform → each row's directionKey is its platform,
        // so (stopId, lineId, directionKey) tells the two rows apart even though both
        // carry a blank `direction` — the identity a persisted star keys on.
        val north = departure("northern", "Northern", "", "High Barnet", 120, platform = "Northbound - Platform 1")
        val south = departure("northern", "Northern", "", "Morden", 240, platform = "Southbound - Platform 4")
        val split = DepartureRows.forStop("940GZZLUEUS", "Euston", listOf(south, north), now)

        assertEquals(listOf("", ""), split.map { it.direction })
        assertEquals(
            listOf("Northbound - Platform 1", "Southbound - Platform 4"),
            split.map { it.directionKey },
        )
        assertEquals(2, split.map { it.directionKey }.toSet().size)
    }

    @Test
    fun `stamps a disrupted line's rows with its status, leaving clean lines null`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val northern = departure("northern", "Northern", "southbound", "Morden", 180)
        val statuses = mapOf(
            "victoria" to LineStatus("victoria", 6, "Severe Delays"),
            "northern" to LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"),
        )

        val rows = DepartureRows.forStop(
            "940GZZLUVIC", "Victoria", listOf(victoria, northern), now, statuses,
        ).associateBy { it.lineId }

        assertEquals("Severe Delays", rows.getValue("victoria").status?.description)
        // A good-service line still stamps null — a non-null row status always means
        // "disrupted", decoupled from whatever the map happens to carry.
        assertNull(rows.getValue("northern").status)

        // across() threads the same map through to every stop's rows.
        val acrossRows = DepartureRows.across(
            listOf(StopArrivals("940GZZLUVIC", "Victoria", listOf(victoria, northern), fetchedAt = now)),
            now,
            statuses,
        ).associateBy { it.lineId }
        assertEquals("Severe Delays", acrossRows.getValue("victoria").status?.description)
        assertNull(acrossRows.getValue("northern").status)
    }

    @Test
    fun `a disrupted declared line with no predictions becomes a status row, sorted first`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(victoria),
            fetchedAt = now,
            lines = listOf(
                LineRef("victoria", "Victoria", "tube"), // has a prediction → no status row
                LineRef("circle", "Circle", "tube"), // disrupted, no prediction → status row
                LineRef("northern", "Northern", "tube"), // good service → no row at all
            ),
        )
        val statuses = mapOf(
            "circle" to LineStatus("circle", 2, "Suspended"),
            "victoria" to LineStatus("victoria", 6, "Severe Delays"),
            "northern" to LineStatus("northern", LineStatus.GOOD_SERVICE, "Good Service"),
        )

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(2, rows.size)
        // The Circle status row sorts first — no countdown, direction-independent.
        val statusRow = rows[0]
        assertEquals("circle", statusRow.lineId)
        assertEquals(STATUS_DIRECTION_KEY, statusRow.directionKey)
        assertEquals("", statusRow.direction)
        assertTrue(statusRow.upcoming.isEmpty())
        assertEquals("Suspended", statusRow.status?.description)
        // Victoria has predictions, so it's a timed row marked with its status — not a
        // second status row. Northern is good service with no predictions → no row.
        assertEquals("victoria", rows[1].lineId)
        assertEquals(listOf(victoria), rows[1].upcoming)
        assertEquals("Severe Delays", rows[1].status?.description)
    }

    @Test
    fun `a stale stop's disrupted line does not synthesize a No-departures status row`() {
        // Arrivals are stale (aged past the threshold) and the line's predictions have all
        // expired. "No departures" would be a categorical claim the stale data can't back —
        // a delayed line's predictions may have merely expired, not stopped — so no status
        // row is synthesized; the screen's stale empty-state prompt covers the stop instead.
        val old = now.minusSeconds(600)
        val expired = departure("victoria", "Victoria", "outbound", "Brixton", -60)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(expired),
            fetchedAt = old,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        assertEquals(emptyList<DepartureRow>(), DepartureRows.across(listOf(stop), now, statuses))
    }

    @Test
    fun `a fresh stop's disrupted line with no predictions still synthesizes a status row`() {
        // The same shape, but the stop is fresh, so "no departures" is trustworthy and the
        // status row is synthesized — guarding the suppression above from over-reaching.
        val fresh = now.minusSeconds(30)
        val expired = departure("victoria", "Victoria", "outbound", "Brixton", -60)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(expired),
            fetchedAt = fresh,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(1, rows.size)
        assertEquals("victoria", rows[0].lineId)
        assertTrue(rows[0].upcoming.isEmpty())
        assertEquals("Severe Delays", rows[0].status?.description)
    }

    @Test
    fun `a disruption-only stop shows its closure but no No-departures status row`() {
        // Arrivals were never fetched (arrivalsFresh = false), though the stop is stamped
        // fresh from its disruption. Its disrupted declared line must NOT synthesize a
        // "No departures" row (we don't know its departures), but the closure still shows.
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = emptyList(),
            fetchedAt = now,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
            disruptions = listOf(StopDisruption("Stop moved to Pancras Road")),
            arrivalsFresh = false,
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        assertEquals(1, rows.size)
        assertEquals("Stop moved to Pancras Road", rows[0].stopDisruption)
    }

    @Test
    fun `a stop disruption becomes a stop-status row, sorted above line and timed rows`() {
        val victoria = departure("victoria", "Victoria", "outbound", "Brixton", 120)
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            departures = listOf(victoria),
            fetchedAt = now,
            lines = listOf(LineRef("circle", "Circle", "tube")), // suspended, no prediction
            disruptions = listOf(StopDisruption("Station closed until further notice")),
        )
        val statuses = mapOf("circle" to LineStatus("circle", 2, "Suspended"))

        val rows = DepartureRows.across(listOf(stop), now, statuses)

        // Rank order: stop-status (whole stop) first, then the Circle line-status row,
        // then the Victoria timed row.
        assertEquals(3, rows.size)
        val stopStatus = rows[0]
        assertEquals("Station closed until further notice", stopStatus.stopDisruption)
        assertEquals("", stopStatus.lineId)
        assertEquals(STOP_STATUS_DIRECTION_KEY, stopStatus.directionKey)
        assertTrue(stopStatus.upcoming.isEmpty())
        assertNull(stopStatus.status)
        assertEquals("circle", rows[1].lineId)
        assertTrue(rows[1].upcoming.isEmpty())
        assertEquals("victoria", rows[2].lineId)
        assertEquals(listOf(victoria), rows[2].upcoming)
    }

    @Test
    fun `no upcoming departures yields no rows`() {
        val gone = departure("victoria", "Victoria", "outbound", "Brixton", -60)

        val rows = DepartureRows.forStop("s", "S", listOf(gone), now)

        assertEquals(emptyList<DepartureRow>(), rows)
    }

    @Test
    fun `across merges several stops into one soonest-first list`() {
        val oxc = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "inbound", "Brixton", 300)),
            fetchedAt = now,
        )
        val ksx = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(
                departure("northern", "Northern", "southbound", "Morden", 120),
                departure("victoria", "Victoria", "outbound", "Walthamstow Central", 420),
            ),
            fetchedAt = now,
        )

        val rows = DepartureRows.across(listOf(oxc, ksx), now)

        // Soonest-first across both stops: KSX Northern (120s), OXC Victoria (300s),
        // KSX Victoria (420s) — each row stamped with the stop it came from.
        assertEquals(3, rows.size)
        assertEquals(
            listOf("King's Cross St. Pancras", "Oxford Circus", "King's Cross St. Pancras"),
            rows.map { it.stopName },
        )
        assertEquals(listOf("northern", "victoria", "victoria"), rows.map { it.lineId })
    }

    @Test
    fun `across stamps each row with its own stop's fetch age`() {
        val fresh = now.minusSeconds(30)
        val old = now.minusSeconds(600)
        val oxc = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "inbound", "Brixton", 300)),
            fetchedAt = fresh,
        )
        val ksx = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(departure("northern", "Northern", "southbound", "Morden", 120)),
            fetchedAt = old,
            disruptions = listOf(StopDisruption("Station closed until further notice")),
        )

        val ageByStop = DepartureRows.across(listOf(oxc, ksx), now).associate { it.stopId to it.fetchedAt }

        // Each stop's rows (its timed rows and its stop-status row) carry that stop's own
        // age, so the screen withholds a stale stop's countdowns while a fresh one stays
        // live rather than one screen-wide flag deciding for both (SPEC D4).
        assertEquals(fresh, ageByStop.getValue("940GZZLUOXC"))
        assertEquals(old, ageByStop.getValue("940GZZLUKSX"))
    }

    @Test
    fun `across drops departed services and can yield an empty list`() {
        val stop = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "outbound", "Brixton", -30)),
            fetchedAt = now,
        )

        assertEquals(emptyList<DepartureRow>(), DepartureRows.across(listOf(stop), now))
    }

    // --- nearbyDeduped: the "near me now" collapse (SPEC Finding stops → Near me now) ---

    private fun rowsFor(stopId: String, stopName: String, vararg departures: Departure): List<DepartureRow> =
        DepartureRows.forStop(stopId, stopName, departures.toList(), now)

    private fun stopStatusRow(stopId: String, stopName: String) = DepartureRow(
        stopId = stopId,
        stopName = stopName,
        lineId = "",
        lineName = "",
        direction = "",
        directionKey = STOP_STATUS_DIRECTION_KEY,
        destination = "",
        mode = "",
        upcoming = emptyList(),
        fetchedAt = now,
        stopDisruption = "Stop closed",
    )

    @Test
    fun `nearbyDeduped collapses a line across adjacent stops to the nearest`() {
        // The same bus route, same direction, at three stops within the radius. The nearest
        // wins even though a farther stop's departure is sooner — you'd walk to the nearest.
        val near = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 120, mode = "bus"))
        val mid = rowsFor("B", "Stop B", departure("55", "55", "outbound", "Bakerloo", 60, mode = "bus"))
        val far = rowsFor("C", "Stop C", departure("55", "55", "outbound", "Bakerloo", 30, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(
            near + mid + far,
            stopDistanceMeters = mapOf("A" to 100.0, "B" to 300.0, "C" to 500.0),
        )

        assertEquals(1, deduped.size)
        assertEquals("A", deduped[0].stopId)
    }

    @Test
    fun `nearbyDeduped keeps both directions of a line`() {
        val out = rowsFor("A", "Stop A", departure("55", "55", "outbound", "Bakerloo", 120, mode = "bus"))
        val inbound = rowsFor("B", "Stop B", departure("55", "55", "inbound", "Walthamstow", 90, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(out + inbound, mapOf("A" to 100.0, "B" to 120.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("outbound", "inbound"), deduped.map { it.direction }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps fully unresolved opposite directions at separate stops`() {
        // TfL gave neither direction nor destination; only the (stop-local) platform told the
        // two apart. Keyed on a blank cross-stop direction they would collapse and drop the
        // farther direction — so a fully-unresolved row stays stop-specific and both survive.
        val a = rowsFor("A", "Stop A", departure("55", "55", "", "", 120, platform = "Stop A", mode = "bus"))
        val b = rowsFor("B", "Stop B", departure("55", "55", "", "", 60, platform = "Stop B", mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(a + b, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped never crowds out a lone farther mode`() {
        // Many nearby bus lines and one farther Tube line. With no count cap, every distinct
        // line survives — the Tube is not evicted for being farther than the buses.
        val bus1 = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 60, mode = "bus"))
        val bus2 = rowsFor("A2", "Stop A2", departure("12", "12", "outbound", "Y", 90, mode = "bus"))
        val bus3 = rowsFor("A3", "Stop A3", departure("36", "36", "outbound", "Z", 120, mode = "bus"))
        val tube = rowsFor("T", "Tube", departure("victoria", "Victoria", "inbound", "Brixton", 300, mode = "tube"))

        val deduped = DepartureRows.nearbyDeduped(
            bus1 + bus2 + bus3 + tube,
            mapOf("A" to 100.0, "A2" to 150.0, "A3" to 200.0, "T" to 800.0),
        )

        assertEquals(4, deduped.size)
        assertTrue(deduped.any { it.lineId == "victoria" })
    }

    @Test
    fun `nearbyDeduped leaves stop-status rows untouched`() {
        // Two different closed stops: each closure is about its own stop, so both are kept —
        // collapsing them by their blank line id would drop a real warning.
        val deduped = DepartureRows.nearbyDeduped(
            listOf(stopStatusRow("A", "Stop A"), stopStatusRow("B", "Stop B")),
            mapOf("A" to 100.0, "B" to 200.0),
        )

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps blank-direction rows stop-specific even with a shared destination`() {
        // TfL omits `direction`, so there is no cross-stop direction identity. Two opposite
        // one-way stops of a line can share a destination, and `destination` can't reconstruct
        // a direction (SPEC), so blank-`direction` rows are never merged across stops — the
        // safe over-show (a same-direction service may then show at both adjacent stops)
        // against ever collapsing two opposite directions into one row (maintainer). Both
        // stops' rows survive; the earlier destination-based collapse is gone.
        val near = rowsFor("A", "Stop A", departure("55", "55", "", "Bakerloo", 120, platform = "Stop A / 1", mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("55", "55", "", "Bakerloo", 60, platform = "Stop B / 2", mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps blank-lineId rows stop-specific even with a shared direction`() {
        // TfL omits `lineId` on some predictions; `lineName` alone can name a different route.
        // Two unrelated routes at adjacent stops that share a nonblank `direction` must not
        // collapse onto one `(blank lineId, direction)` key and drop the farther one — a blank
        // line has no cross-stop identity, so the row stays stop-specific (Codex).
        val near = rowsFor("A", "Stop A", departure("", "N55", "inbound", "Aldwych", 120, mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("", "24", "inbound", "Pimlico", 60, mode = "bus"))

        val deduped = DepartureRows.nearbyDeduped(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("A", "B"), deduped.map { it.stopId }.toSet())
    }

    @Test
    fun `nearbyDeduped keeps both platforms of one service at a single stop`() {
        // Two platforms of the same line and destination at ONE stop are distinct rows there;
        // the collapse is across stops, so it must not drop the second platform's departures.
        val rows = DepartureRows.forStop(
            "A", "Stop A",
            listOf(
                departure("55", "55", "", "Bakerloo", 120, platform = "Platform 1", mode = "bus"),
                departure("55", "55", "", "Bakerloo", 60, platform = "Platform 2", mode = "bus"),
            ),
            now,
        )
        assertEquals("the stop itself has two platform rows", 2, rows.size)

        val deduped = DepartureRows.nearbyDeduped(rows, mapOf("A" to 100.0))

        assertEquals(2, deduped.size)
        assertEquals(setOf("Platform 1", "Platform 2"), deduped.map { it.directionKey }.toSet())
    }

    @Test
    fun `nearbyDeduped treats a stop missing from the distance map as farthest`() {
        val known = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val unknown = rowsFor("B", "Stop B", departure("55", "55", "outbound", "X", 60, mode = "bus"))

        // B is absent from the map, so it sorts behind A's known distance rather than winning.
        val deduped = DepartureRows.nearbyDeduped(known + unknown, mapOf("A" to 500.0))

        assertEquals(1, deduped.size)
        assertEquals("A", deduped[0].stopId)
    }

    // --- byStopDistance: the near-me display order (closest stop first, soonest same-stop tie) ---

    @Test
    fun `byStopDistance puts the closest stop first even when a farther stop leaves sooner`() {
        // Stop A is nearer; stop B is farther but its bus leaves sooner. Closest-first means the
        // near stop leads — you'd walk to the one at your feet, not chase the far sooner one.
        val near = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 600, mode = "bus"))
        val far = rowsFor("B", "Stop B", departure("134", "134", "outbound", "Y", 60, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(near + far, mapOf("A" to 100.0, "B" to 400.0))

        assertEquals(listOf("A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance breaks a same-stop tie soonest-first`() {
        // Two lines at ONE stop (identical distance): distance can't order them, so the sooner
        // service leads. Order them into the list farther-first to prove the sort, not input order.
        val laterAtStop = rowsFor("A", "Stop A", departure("134", "134", "outbound", "Y", 540, mode = "bus"))
        val soonerAtStop = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(laterAtStop + soonerAtStop, mapOf("A" to 100.0))

        assertEquals(listOf("55", "134"), ordered.map { it.lineId })
    }

    @Test
    fun `byStopDistance sorts a stop missing from the distance map last`() {
        val known = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 600, mode = "bus"))
        val unknown = rowsFor("B", "Stop B", departure("134", "134", "outbound", "Y", 60, mode = "bus"))

        // B is absent from the map → farthest, so it trails A despite leaving sooner.
        val ordered = DepartureRows.byStopDistance(known + unknown, mapOf("A" to 500.0))

        assertEquals(listOf("A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance keeps equidistant distinct stops grouped, not time-interleaved`() {
        // Two distinct stops that compute the same distance (e.g. StopPoints sharing
        // coordinates). Stop A has a soon and a late departure; stop B one in between. By
        // distance→time alone the order would interleave A(soon), B(mid), A(late); grouping by
        // stop id first keeps each stop's rows together (soonest is a same-stop tiebreak only).
        val aSoon = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 60, mode = "bus"))
        val aLate = rowsFor("A", "Stop A", departure("134", "134", "outbound", "Y", 600, mode = "bus"))
        val bMid = rowsFor("B", "Stop B", departure("43", "43", "outbound", "Z", 120, mode = "bus"))

        val ordered = DepartureRows.byStopDistance(aSoon + aLate + bMid, mapOf("A" to 100.0, "B" to 100.0))

        // A's two rows are adjacent (grouped), not split by B's row.
        assertEquals(listOf("A", "A", "B"), ordered.map { it.stopId })
    }

    @Test
    fun `byStopDistance keeps a warning leading above a nearer timed row`() {
        // A closure at a FAR stop must still lead above a NEAR stop's departures — a warning the
        // user must see isn't buried under closer catchable rows (SPEC principle 2).
        val nearTimed = rowsFor("A", "Stop A", departure("55", "55", "outbound", "X", 120, mode = "bus"))
        val farClosure = listOf(stopStatusRow("Z", "Stop Z"))

        val ordered = DepartureRows.byStopDistance(nearTimed + farClosure, mapOf("A" to 100.0, "Z" to 900.0))

        assertEquals("Z", ordered.first().stopId)
        assertTrue("the closure leads", ordered.first().stopDisruption != null)
    }

    // A minimal Northern-line topology: the two central trunks share the northern leg
    // (High Barnet → Camden) and the southern leg (Kennington → Morden), and differ only
    // between — Mornington Crescent (MTC) on Charing X, Bank (BNK) on Bank.
    private val northernTopology = RouteTopology(
        mapOf(
            "northern" to listOf(
                RoutePattern("Bank", listOf(HBT, HGT, CTN, EUS, BNK, KNG, MDN), "High Barnet", "Morden"),
                RoutePattern("Charing X", listOf(HBT, HGT, CTN, MTC, EUS, CHX, KNG, MDN), "High Barnet", "Morden"),
            ),
        ),
    )

    private fun rowAt(stopId: String, vararg upcoming: Departure): DepartureRow {
        val soonest = upcoming.first()
        return DepartureRow(
            stopId = stopId,
            stopName = stopId,
            lineId = soonest.lineId,
            lineName = soonest.lineName,
            direction = soonest.direction,
            directionKey = soonest.direction,
            destination = soonest.destination,
            mode = soonest.mode,
            upcoming = upcoming.toList(),
            fetchedAt = now,
        )
    }

    @Test
    fun `destinationLines merges two branches past the junction, dropping the label`() {
        // Highgate → High Barnet (north of Camden Town): the trunks have physically joined, so the
        // two are the same service from here — one merged line, no branch label (the maintainer's ask).
        val row = rowAt(
            HGT,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(1, lines.size)
        assertEquals("High Barnet", lines[0].destination)
        assertNull(lines[0].branch)
        assertEquals(2, lines[0].times.size)
    }

    @Test
    fun `destinationLines keeps both branches where the trunk is a choice ahead`() {
        // Southbound at Camden Town toward Morden: the trunks haven't split behind you — they
        // diverge ahead (different central stations), so both lines stay, each labeled.
        val row = rowAt(
            CTN,
            departure("northern", "Northern", "southbound", "Morden", 120, branch = "Bank"),
            departure("northern", "Northern", "southbound", "Morden", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
        lines.forEach { assertEquals("Morden", it.destination) }
    }

    @Test
    fun `destinationLines keeps the branch at Euston toward High Barnet (Mornington Crescent)`() {
        // The edge case: at Euston (on both trunks) a High Barnet train's branch still tells the
        // rider whether it stops at Mornington Crescent, so it is a genuine choice and stays.
        val row = rowAt(
            EUS,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3, topology = northernTopology)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
    }

    @Test
    fun `destinationLines with the empty topology keeps every raw branch, merging nothing`() {
        // The default: no topology, so the pre-topology behavior — each branch is its own line.
        val row = rowAt(
            CTN,
            departure("northern", "Northern", "northbound", "High Barnet", 120, branch = "Bank"),
            departure("northern", "Northern", "northbound", "High Barnet", 300, branch = "Charing X"),
        )
        val lines = DepartureRows.destinationLines(row, maxTimes = 3)
        assertEquals(2, lines.size)
        assertEquals(listOf("Bank", "Charing X"), lines.map { it.branch })
    }

    private companion object {
        const val HBT = "940GZZLUHBT"
        const val HGT = "940GZZLUHGT"
        const val CTN = "940GZZLUCTN"
        const val MTC = "940GZZLUMTC"
        const val EUS = "940GZZLUEUS"
        const val BNK = "940GZZLUBNK"
        const val CHX = "940GZZLUCHX"
        const val KNG = "940GZZLUKNG"
        const val MDN = "940GZZLUMDN"
    }
}
