package app.stopcast.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line-pill color rules, as pure functions: a tube line resolves to its official
 * TfL color by id, a named Overground line to its own accent (a hollow pill), any bus to
 * London-bus red by mode, everything else to no color (a neutral pill). The filled pill's
 * text flips black/white to stay legible on the fill; the hollow pill's accent is nudged to
 * stay legible on the surface.
 */
class LinePillTest {
    @Test
    fun `tube lines map to their official TfL color`() {
        assertEquals(Color(0xFFE32017), lineFillColor("central", "tube"))
        assertEquals(Color(0xFF000000), lineFillColor("northern", "tube"))
        assertEquals(Color(0xFF003688), lineFillColor("piccadilly", "tube"))
        assertEquals(Color(0xFFF3A9BB), lineFillColor("hammersmith-city", "tube"))
    }

    @Test
    fun `any bus maps to London bus red, whatever its route id`() {
        assertEquals(Color(0xFFDC241F), lineFillColor("24", "bus"))
        assertEquals(Color(0xFFDC241F), lineFillColor("N73", "bus"))
    }

    @Test
    fun `single-color modes resolve by mode, whatever the line id`() {
        assertEquals(Color(0xFF00A4A7), lineFillColor("dlr", "dlr")) // DLR turquoise
        assertEquals(Color(0xFF6950A1), lineFillColor("elizabeth", "elizabeth-line")) // Elizabeth purple
        assertEquals(Color(0xFF84B817), lineFillColor("tram", "tram")) // Trams green
        // An Overground service whose id isn't one of the six named lines (legacy
        // "london-overground") falls back to the single mode orange.
        assertEquals(Color(0xFFEE7C0E), lineFillColor("london-overground", "overground"))
    }

    @Test
    fun `named Overground lines resolve to their own accent and have no solid fill`() {
        // Each named line has its own accent (for the hollow pill) and NO fill, so LinePill
        // renders it hollow rather than as a solid orange.
        assertEquals(Color(0xFFEF9600), overgroundAccentColor("lioness"))
        assertEquals(Color(0xFF2774AE), overgroundAccentColor("mildmay"))
        assertEquals(Color(0xFFD22730), overgroundAccentColor("windrush"))
        assertEquals(Color(0xFF893B67), overgroundAccentColor("weaver"))
        assertEquals(Color(0xFF5BA763), overgroundAccentColor("suffragette"))
        assertEquals(Color(0xFF606667), overgroundAccentColor("liberty"))
        assertNull(lineFillColor("mildmay", "overground"))
        assertNull(lineFillColor("lioness", "overground"))
        // Tube lines and unknown ids are not Overground accents.
        assertNull(overgroundAccentColor("central"))
        assertNull(overgroundAccentColor("london-overground"))
    }

    @Test
    fun `a hollow accent stays legible on both the light and dark surface`() {
        val light = Color.White
        val dark = Color(0xFF141218) // a typical Material 3 dark surface
        // Lioness yellow is the stress case (low contrast on white as-is); Mildmay blue and
        // Liberty gray round it out. The label clears the text floor and the border the
        // (lower) border floor on both surfaces.
        listOf(Color(0xFFEF9600), Color(0xFF2774AE), Color(0xFF606667)).forEach { accent ->
            listOf(light, dark).forEach { surface ->
                val bg = apcaLuminance(surface)
                assertTrue(apcaLc(apcaLuminance(accentInkOn(accent, surface)), bg) >= 60.0)
                assertTrue(apcaLc(apcaLuminance(accentEdgeOn(accent, surface)), bg) >= 30.0)
            }
        }
    }

    @Test
    fun `an unmapped line or mode has no color, so the caller falls back to neutral`() {
        assertNull(lineFillColor("thameslink", "national-rail"))
        assertNull(lineFillColor("", ""))
    }

    @Test
    fun `the halo is the opposite tone of the text, at partial alpha`() {
        // Black text → a translucent white halo; white text → a translucent black halo,
        // so the glow always lifts the label off the fill rather than blending into it.
        val onBlackText = haloFor(Color.Black)
        assertEquals(1f, onBlackText.red, 0f)
        assertEquals(1f, onBlackText.green, 0f)
        assertEquals(1f, onBlackText.blue, 0f)

        val onWhiteText = haloFor(Color.White)
        assertEquals(0f, onWhiteText.red, 0f)
        assertEquals(0f, onWhiteText.green, 0f)
        assertEquals(0f, onWhiteText.blue, 0f)

        // Partial alpha keeps it a lift, not an opaque second outline.
        assertTrue(onBlackText.alpha > 0f && onBlackText.alpha < 1f)
        assertEquals(onBlackText.alpha, onWhiteText.alpha, 0f)
    }

    @Test
    fun `text picks the higher-contrast black or white by APCA`() {
        // Dark fills → white.
        assertEquals(Color.White, textColorOn(Color(0xFF000000))) // Northern black
        assertEquals(Color.White, textColorOn(Color(0xFFE32017))) // Central red
        assertEquals(Color.White, textColorOn(Color(0xFF6950A1))) // Elizabeth purple
        assertEquals(Color.White, textColorOn(Color(0xFF003688))) // Piccadilly navy
        // Pale fills → black.
        assertEquals(Color.Black, textColorOn(Color(0xFFFFD300))) // Circle yellow
        assertEquals(Color.Black, textColorOn(Color(0xFFA0A5A9))) // Jubilee silver
        assertEquals(Color.Black, textColorOn(Color(0xFF84B817))) // Trams green
        assertEquals(Color.Black, textColorOn(Color(0xFFF3A9BB))) // Hammersmith & City pink
        // Saturated mid-tones: WCAG-2's ratio wrongly picks black on these, APCA picks
        // white — the case this switch exists for (Victoria, DLR, Bakerloo, Overground).
        assertEquals(Color.White, textColorOn(Color(0xFF0098D4))) // Victoria blue
        assertEquals(Color.White, textColorOn(Color(0xFF00A4A7))) // DLR turquoise
        assertEquals(Color.White, textColorOn(Color(0xFFB36305))) // Bakerloo brown
        assertEquals(Color.White, textColorOn(Color(0xFFEE7C0E))) // Overground orange
    }
}
