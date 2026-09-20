package app.trackmo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun `leaves a plain destination unchanged`() {
        assertEquals("Walthamstow Central", dto(destinationName = "Walthamstow Central").toDeparture().destination)
    }

    @Test
    fun `drops a bus towards' comma tail as noise`() {
        // A London bus `towards` lists an interchange point after the terminus
        // ("Pimlico, Grosvenor Road"); the tail is noise on a departures board, so the
        // destination is just the terminus (maintainer, 2026-09-19).
        assertEquals("Pimlico", dto(towards = "Pimlico, Grosvenor Road").toDeparture().destination)
    }

    @Test
    fun `splits the via branch off the destination`() {
        // TfL names the Northern line's central trunk in `towards`; the destination is the
        // terminus only, the branch is carried separately (shown parenthesized on the card).
        val withName = dto(
            destinationName = "Battersea Power Station",
            towards = "Battersea Power Station via Charing Cross",
        ).toDeparture()
        assertEquals("Battersea Power Station", withName.destination)
        // branchOf folds TfL's inconsistent trunk spellings to one short board label.
        assertEquals("Charing X", withName.branch)

        // No destinationName: the terminus is `towards` before " via ", the branch after it.
        val fromTowards = dto(towards = "Edgware via Bank").toDeparture()
        assertEquals("Edgware", fromTowards.destination)
        assertEquals("Bank", fromTowards.branch)
    }

    @Test
    fun `a towards with no via has a null branch`() {
        assertNull(dto(destinationName = "Brixton Underground Station").toDeparture().branch)
        assertNull(dto(towards = "Pimlico, Grosvenor Road").toDeparture().branch)
        assertNull(dto().toDeparture().branch)
    }

    @Test
    fun `keeps the full name of a terminus whose own name ends in Station`() {
        // "Power Station" is a landmark compound, not a transit-type tag, so the Northern line's
        // "Battersea Power Station" is kept in full and the card ellipsizes it if it can't fit,
        // rather than being pre-shortened to the fragment "Battersea Power" (maintainer,
        // 2026-09-20, reversing the 2026-09-19 catch-all). Pinned so it isn't "fixed" back.
        assertEquals(
            "Battersea Power Station",
            dto(destinationName = "Battersea Power Station").toDeparture().destination,
        )
    }

    @Test
    fun `a blank or absent destination resolves to empty`() {
        assertEquals("", dto(destinationName = "", towards = "").toDeparture().destination)
        assertEquals("", dto().toDeparture().destination)
    }
}
