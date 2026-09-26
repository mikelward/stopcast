package app.stopdash.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosedNoticeTest {
    @Test
    fun `a notice closing the stop or station says closed`() {
        assertTrue(ClosedNotice.saysClosed("Bus Stop Closed"))
        assertTrue(ClosedNotice.saysClosed("Euston Square Underground Station: Station closed due to strike action."))
        assertTrue(ClosedNotice.saysClosed("The station is closed until further notice"))
        assertTrue(ClosedNotice.saysClosed("This stop will be closed from 22:00"))
    }

    @Test
    fun `a narrower notice doesn't`() {
        assertFalse(ClosedNotice.saysClosed("The lift to platform 2 is out of order"))
        assertFalse(ClosedNotice.saysClosed("Station entrance closed; use the Euston Road exit"))
        assertFalse(ClosedNotice.saysClosed("Northern line platforms closed"))
        assertFalse(ClosedNotice.saysClosed("Stop moved to Euston Road, outside the station"))
        assertFalse(ClosedNotice.saysClosed("Part closure of the station forecourt"))
    }
}
