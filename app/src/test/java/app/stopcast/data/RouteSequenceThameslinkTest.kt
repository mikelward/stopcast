package app.stopcast.data

import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.JourneyEnd
import app.stopcast.domain.JourneySegment
import app.stopcast.domain.Journeys
import app.stopcast.domain.LineSequence
import app.stopcast.domain.RouteStops
import app.stopcast.domain.StarredJourney
import app.stopcast.domain.StopArrivals
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The route detail's stop list for Thameslink, against a recorded
 * `/Line/thameslink/Route/Sequence/inbound` (three of its routes, trimmed to the fields stopcast
 * reads; public network data only). TfL lists St Pancras's Thameslink departures under the
 * station's domestic-platforms id, which no route calls at: the sequence calls at the low-level
 * (`LL`) id instead, and King's Cross shares the interchange.
 */
class RouteSequenceThameslinkTest {
    private val fixture: String =
        checkNotNull(javaClass.getResource("/fixtures/route_sequence_thameslink_inbound.json")).readText()

    private val thameslink: LineSequence =
        Json { ignoreUnknownKeys = true }.decodeFromString<TflRouteSequenceDto>(fixture).toLineSequence()

    private val now = Instant.parse("2026-09-23T08:00:00Z")

    private fun fromStPancras(name: String = ST_PANCRAS) = thameslink.callingAt(ST_PANCRAS_DOMESTIC, HUB, name)

    @Test
    fun `a train listed under the station's other id boards there, in place of its sibling`() {
        val stops = RouteStops.ahead(fromStPancras(), ST_PANCRAS_DOMESTIC, "Cambridge", null, "thameslink")!!
        assertEquals(ST_PANCRAS_DOMESTIC, stops.first().id)
        assertEquals(ST_PANCRAS, stops.first().name)
        assertEquals("Finsbury Park", stops[1].name)
        assertEquals("Cambridge", stops.last().name)
    }

    @Test
    fun `another station in the interchange is not a sibling`() {
        // King's Cross is in the same hub, and its own route also reaches Cambridge: counting it
        // would leave two paths.
        assertSame(thameslink, fromStPancras("Somewhere Else"))
        val fromKingsCross = RouteStops.ahead(thameslink.callingAt(KINGS_CROSS, HUB, "London King's Cross"), KINGS_CROSS, "Cambridge", null)
        assertEquals(KINGS_CROSS, fromKingsCross?.first()?.id)
    }

    @Test
    fun `without the interchange the stop stays off the route`() {
        assertSame(thameslink, thameslink.callingAt(ST_PANCRAS_DOMESTIC, "", ST_PANCRAS))
        assertEquals(
            RouteStops.Resolution.NotOnRoute,
            RouteStops.resolve(thameslink, ST_PANCRAS_DOMESTIC, "Cambridge", null, "thameslink"),
        )
    }

    @Test
    fun `a journey starred from the station's other id finds its trains`() {
        val departure = Departure(
            lineId = "thameslink",
            lineName = "Thameslink",
            direction = "inbound",
            destination = "Cambridge",
            platform = null,
            expectedArrival = now.plusSeconds(300),
            mode = "national-rail",
        )
        val rows = DepartureRows.across(
            listOf(StopArrivals(ST_PANCRAS_DOMESTIC, ST_PANCRAS, listOf(departure), fetchedAt = now, hubId = HUB)),
            now,
        )
        assertEquals(HUB, rows.single().hubId)
        // As starred from the route page: the origin under the id the departures carry, with its hub.
        val journey = StarredJourney(
            JourneyEnd(ST_PANCRAS_DOMESTIC, ST_PANCRAS, hubId = HUB),
            JourneyEnd(FINSBURY_PARK, "Finsbury Park"),
            "thameslink",
            "Thameslink",
            "national-rail",
        )
        val segment = Journeys.segment(journey, thameslink)
        assertEquals(JourneySegment(ST_PANCRAS_DOMESTIC, setOf(FINSBURY_PARK)), segment)
        val trains = Journeys.trains(segment!!, rows, mapOf("thameslink" to thameslink), journey)
        assertEquals(listOf("Cambridge"), trains.rows.flatMap { row -> row.upcoming.map { it.destination } })
        assertFalse(trains.unresolved)
        // An end saved before its hub was recorded can't be placed: unplaced, never a wrong path.
        assertEquals(null, Journeys.segment(journey.copy(from = journey.from.copy(hubId = "")), thameslink))
    }

    @Test
    fun `the sequence carries each stop's interchange`() {
        assertEquals(HUB, thameslink.stopHubs[ST_PANCRAS_LL])
        assertEquals(HUB, thameslink.stopHubs[KINGS_CROSS])
    }

    private companion object {
        const val HUB = "HUBKGX"
        const val ST_PANCRAS = "London St Pancras International"
        const val ST_PANCRAS_DOMESTIC = "910GSTPADOM"
        const val ST_PANCRAS_LL = "910GSTPXBOX"
        const val KINGS_CROSS = "910GKNGX"
        const val FINSBURY_PARK = "910GFNPK"
    }
}
