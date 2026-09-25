package app.stopdash.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FixRefinementTest {
    // Obviously-synthetic coordinates around the origin, never a real position (SPEC Privacy).
    private val origin = Coordinates(0.0, 0.0)

    // [meters] due north of the origin: one degree of latitude is ~111.2 km.
    private fun north(meters: Double) = Coordinates(meters / 111_195.0, 0.0)

    @Test
    fun `a precise fix that agrees with the coarse one doesn't move the list`() {
        assertFalse(FixRefinement.shouldMove(origin, origin))
        assertFalse(FixRefinement.shouldMove(origin, north(60.0)))
        assertFalse(FixRefinement.shouldMove(origin, north(99.0)))
    }

    @Test
    fun `a precise fix 100 m or more away moves the list`() {
        assertTrue(FixRefinement.shouldMove(origin, north(101.0)))
        assertTrue(FixRefinement.shouldMove(origin, north(380.0)))
    }
}
