package app.trackmo.data

import app.trackmo.domain.DepartureRows
import java.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The mapping→grouping seam: a recorded `/Arrivals` payload decoded exactly as the
 * client decodes it (`ignoreUnknownKeys = true`), mapped with [toDeparture], then
 * grouped by [DepartureRows.forStop]. The direct-`Departure` grouping tests can't
 * catch a drift in how the payload normalizes — an omitted `direction` field, a
 * blank `destinationName` falling back to `towards` — because they never build a
 * `Departure` from TfL's shape. This does (AGENTS.md: product logic tested against
 * recorded TfL fixtures). Public infrastructure/line names only, no user data
 * (SPEC *Privacy*).
 */
class ArrivalsGroupingTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // Two Victoria predictions carry `direction`; the two Northern ones OMIT the
    // field entirely (not blank — absent), so this proves toDeparture normalizes a
    // missing `direction` to "" and the grouping then falls back to platform rather
    // than merging the two branches into one row. One prediction is already in the
    // past, to prove departed services are dropped before grouping.
    private val arrivalsJson =
        """
        [
          {
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "Northbound - Platform 1",
            "direction": "inbound",
            "destinationName": "Walthamstow Central",
            "towards": "Walthamstow Central",
            "expectedArrival": "2026-09-18T08:03:00Z"
          },
          {
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "",
            "direction": "outbound",
            "destinationName": "",
            "towards": "Brixton",
            "expectedArrival": "2026-09-18T08:05:30Z"
          },
          {
            "lineId": "northern",
            "lineName": "Northern",
            "platformName": "Charing Cross - Platform 2",
            "destinationName": "Kennington",
            "towards": "Kennington via Charing Cross",
            "expectedArrival": "2026-09-18T08:02:00Z"
          },
          {
            "lineId": "northern",
            "lineName": "Northern",
            "platformName": "Bank - Platform 4",
            "destinationName": "Morden",
            "towards": "Morden via Bank",
            "expectedArrival": "2026-09-18T08:04:00Z"
          },
          {
            "lineId": "victoria",
            "lineName": "Victoria",
            "platformName": "Northbound - Platform 1",
            "direction": "inbound",
            "destinationName": "Walthamstow Central",
            "towards": "Walthamstow Central",
            "expectedArrival": "2026-09-18T07:59:00Z"
          }
        ]
        """.trimIndent()

    // Decoded exactly as the client decodes it.
    private val json = Json { ignoreUnknownKeys = true }

    private fun rows() =
        DepartureRows.forStop(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures =
                json
                    .decodeFromString(ListSerializer(TflArrivalDto.serializer()), arrivalsJson)
                    .map { it.toDeparture() },
            now = now,
        )

    @Test
    fun `a recorded payload groups into per-direction rows, soonest-first`() {
        val rows = rows()

        // Four groups: victoria inbound, victoria outbound, and the two Northern
        // branches kept apart by platform. The past prediction is dropped, so the
        // second victoria-inbound arrival never adds a row of its own.
        assertEquals(4, rows.size)
        assertEquals(4, rows.sumOf { it.upcoming.size })

        // Soonest-first: Northern/Charing Cross (2 min), Victoria inbound (3),
        // Northern/Bank (4), Victoria outbound (5.5).
        assertEquals(listOf("northern", "victoria", "northern", "victoria"), rows.map { it.lineId })
    }

    @Test
    fun `an omitted direction field normalizes to blank and falls back to platform`() {
        val northern = rows().filter { it.lineId == "northern" }

        // Both Northern predictions omitted `direction`, so it maps to "" — and the
        // grouping keyed on platform instead of merging the branches into one row.
        assertEquals(2, northern.size)
        northern.forEach { assertEquals("", it.direction) }
        assertEquals(
            setOf("Charing Cross - Platform 2", "Bank - Platform 4"),
            northern.map { it.directionKey }.toSet(),
        )
    }

    @Test
    fun `a blank destinationName falls back to towards through the mapping`() {
        val outbound = rows().single { it.lineId == "victoria" && it.direction == "outbound" }

        assertEquals("Brixton", outbound.destination)
        assertEquals(null, outbound.upcoming.single().platform)
    }
}
