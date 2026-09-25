package app.stopdash.domain

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

    @Test
    fun `a trip from here starts at every shown stop and every stop within 0_2 mi`() {
        val distances = mapOf("SHOWN_FAR" to 1200.0, "CLOSE" to 150.0, "EDGE" to 320.0, "BEYOND" to 330.0)
        assertEquals(
            listOf("CLOSE", "EDGE", "SHOWN_FAR", "NO_DISTANCE"),
            DirectTrips.originIds(listOf("SHOWN_FAR", "NO_DISTANCE"), distances),
        )
    }

    @Test
    fun `with nothing shown or close, a trip from here starts at the nearest stop`() {
        assertEquals(listOf("NEAREST"), DirectTrips.originIds(emptyList(), mapOf("FAR" to 900.0, "NEAREST" to 500.0)))
        assertTrue(DirectTrips.originIds(emptyList(), emptyMap()).isEmpty())
    }

    @Test
    fun `hidden modes are left out unchecked, so they never hold up a shown trip`() {
        val top = stop(
            "TOP", "Top",
            departure("Bottom A", 60),
            departure("Somewhere", 90, lineId = "bus1", mode = "bus"),
            lines = listOf(LineRef("bus2", "Bus 2", "bus")),
        )
        // The bus routes were never loaded: hidden, they're neither pending nor unresolved.
        val result = DirectTrips.filter(listOf(top), listOf(station("BOTA", "Bottom A")), mapOf("rail" to rail), hidden = setOf("bus"))
        assertEquals(listOf("rail"), result.stops.single().departures.map { it.lineId })
        assertTrue(result.stops.single().lines.isEmpty())
        assertFalse(result.pending)
        assertFalse(result.unresolved)
        assertEquals(listOf("rail"), DirectTrips.lineIds(listOf(top), hidden = setOf("bus")))
    }

    @Test
    fun `a destination takes in the stops within 0_2 mi of the station, nearest first`() {
        val center = Coordinates(51.5, -0.12)
        val platform = StopLocation("940GZZLUEXA", "Example", 51.5, -0.12)
        val atDoor = StopLocation("490000000001A", "Example", 51.5005, -0.12) // ~56 m
        val upRoad = StopLocation("490000000002B", "Example / High Road", 51.5025, -0.12) // ~278 m
        val tooFar = StopLocation("490000000003C", "Elsewhere", 51.505, -0.12) // ~556 m
        assertEquals(
            listOf("940GZZLUEXA", "490000000001A", "490000000002B"),
            DirectTrips.destinationStops(listOf(platform), listOf(tooFar, upRoad, platform, atDoor), center).map { it.id },
        )
    }

    @Test
    fun `a bus stop destination takes in only its same-named stands, not every nearby pole`() {
        val bus = listOf(LineRef("1", "1", "bus"))
        val center = Coordinates(51.5, -0.12)
        val stand = StopLocation("490000000001A", "Example", 51.5, -0.12, lines = bus)
        val otherStand = StopLocation("490000000002B", "Example", 51.5010, -0.12, lines = bus) // ~111 m
        val unrelated = StopLocation("490000000003C", "Side Street", 51.5005, -0.12, lines = bus) // ~56 m
        val farStand = StopLocation("490000000004D", "Example", 51.5027, -0.12, lines = bus) // ~300 m
        assertEquals(
            listOf("490000000001A", "490000000002B"),
            DirectTrips.destinationStops(listOf(stand), listOf(unrelated, otherStand, farStand), center).map { it.id },
        )
    }
}
