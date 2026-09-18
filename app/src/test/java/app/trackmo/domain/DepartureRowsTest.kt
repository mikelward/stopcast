package app.trackmo.domain

import java.time.Instant
import org.junit.Assert.assertEquals
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
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
    )

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
        )
        val ksx = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(
                departure("northern", "Northern", "southbound", "Morden", 120),
                departure("victoria", "Victoria", "outbound", "Walthamstow Central", 420),
            ),
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
    fun `across drops departed services and can yield an empty list`() {
        val stop = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(departure("victoria", "Victoria", "outbound", "Brixton", -30)),
        )

        assertEquals(emptyList<DepartureRow>(), DepartureRows.across(listOf(stop), now))
    }
}
