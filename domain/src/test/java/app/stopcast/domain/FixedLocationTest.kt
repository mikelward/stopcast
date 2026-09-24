package app.stopcast.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Synthetic places only. */
class FixedLocationTest {
    @Test
    fun `answers the one place, never as a fallback`() = runTest {
        val at = Coordinates(51.5, -0.12)
        val fix = FixedLocation(at).current(forceFresh = true)
        assertEquals(at, fix.coordinates)
        assertFalse(fix.isFallback)
    }

    @Test
    fun `a station's center is the mean of its placed stops`() {
        val stops = listOf(
            StopLocation("A", "A", 51.0, -1.0),
            StopLocation("B", "B", 52.0, 1.0),
            StopLocation("C", "C", 0.0, 0.0),
        )
        assertEquals(Coordinates(51.5, 0.0), FixedLocation.centerOf(stops))
        assertNull(FixedLocation.centerOf(listOf(StopLocation("C", "C", 0.0, 0.0))))
        assertNull(FixedLocation.centerOf(emptyList()))
    }
}
