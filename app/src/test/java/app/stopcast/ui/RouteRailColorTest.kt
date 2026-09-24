package app.stopcast.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route rail takes its line pill's color (the pure pill rules are in `:shared`'s LineColorsTest). */
class RouteRailColorTest {
    @Test
    fun `the route rail takes the pill's color, including a rail operator's and an Overground accent`() {
        assertEquals(Color(0xFFE32017), lineAccentColor("central", "tube", "Central"))
        assertEquals(Color(0xFF8CC63E), lineAccentColor("southern", "national-rail", "Southern"))
        assertEquals(overgroundAccentColor("mildmay"), lineAccentColor("mildmay", "overground", "Mildmay"))
        assertNull(lineAccentColor("unknown", "national-rail", "Unknown Trains"))
    }

    @Test
    fun `a black line's rail is lifted off a dark surface`() {
        val black = lineAccentColor("northern", "tube", "Northern")!!
        val dark = Color(0xFF121212)
        assertTrue(accentEdgeOn(black, dark) != black)
        assertEquals(black, accentEdgeOn(black, Color.White))
    }
}
