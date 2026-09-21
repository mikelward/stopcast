package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StopDistanceTest {
    @Test
    fun `meters round to the nearest 10 below a kilometer`() {
        assertEquals("40 m", StopDistance.label(44.0))
        assertEquals("120 m", StopDistance.label(118.0))
        assertEquals("450 m", StopDistance.label(450.0))
        assertEquals("990 m", StopDistance.label(994.0))
    }

    @Test
    fun `a fix on top of the stop reads 10 m, not 0 m`() {
        // Rounding to the nearest 10 would give "0 m" for a fix at the stop; the floor keeps
        // it honest and readable.
        assertEquals("10 m", StopDistance.label(0.0))
        assertEquals("10 m", StopDistance.label(4.0))
    }

    @Test
    fun `a kilometer and up reads in km to one decimal`() {
        // 995 m rounds up into km rather than showing the awkward "1000 m".
        assertEquals("1.0 km", StopDistance.label(995.0))
        assertEquals("1.0 km", StopDistance.label(1000.0))
        assertEquals("1.2 km", StopDistance.label(1200.0))
        // The near-me outer radius is ~1609 m, the far end of what a header shows.
        assertEquals("1.6 km", StopDistance.label(1609.0))
    }
}
