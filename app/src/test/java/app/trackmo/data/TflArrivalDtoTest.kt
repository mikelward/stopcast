package app.trackmo.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `toDeparture`'s field normalization, destination-name cleaning in particular: TfL returns
 * a terminus as e.g. "Brixton Underground Station" / "Lewisham DLR Station", and a departures
 * board reads better as "Brixton" / "Lewisham" — the same station-type-suffix trim applied to
 * stop names ([app.trackmo.domain.cleanStopName]). Public infrastructure names only, no user
 * data (SPEC *Privacy*).
 */
class TflArrivalDtoTest {
    private fun dto(destinationName: String? = null, towards: String? = null) = TflArrivalDto(
        lineId = "victoria",
        lineName = "Victoria",
        destinationName = destinationName,
        towards = towards,
        expectedArrival = "2026-09-18T08:03:00Z",
    )

    @Test
    fun `strips the station-type suffix from destinationName`() {
        assertEquals("Brixton", dto(destinationName = "Brixton Underground Station").toDeparture().destination)
        assertEquals("Lewisham", dto(destinationName = "Lewisham DLR Station").toDeparture().destination)
        assertEquals(
            "Richmond",
            dto(destinationName = "Richmond Rail Station").toDeparture().destination,
        )
    }

    @Test
    fun `cleans the towards fallback when destinationName is absent`() {
        assertEquals("Brixton", dto(towards = "Brixton Underground Station").toDeparture().destination)
    }

    @Test
    fun `leaves a plain destination and a multi-part towards unchanged`() {
        assertEquals("Walthamstow Central", dto(destinationName = "Walthamstow Central").toDeparture().destination)
        // A "towards" can list interchange points; suffix-only cleaning must not touch it.
        assertEquals(
            "Pimlico, Grosvenor Road",
            dto(towards = "Pimlico, Grosvenor Road").toDeparture().destination,
        )
    }

    @Test
    fun `truncates a terminus whose own name ends in Station`() {
        // The bare " Station" catch-all also shortens a proper name like the Northern line's
        // "Battersea Power Station" to "Battersea Power". Accepted over keeping the full name
        // (maintainer, 2026-09-19): the full form runs much longer than every other label and
        // would truncate on the row anyway. Pinned so it isn't "fixed" back.
        assertEquals(
            "Battersea Power",
            dto(destinationName = "Battersea Power Station").toDeparture().destination,
        )
    }

    @Test
    fun `a blank or absent destination resolves to empty`() {
        assertEquals("", dto(destinationName = "", towards = "").toDeparture().destination)
        assertEquals("", dto().toDeparture().destination)
    }
}
