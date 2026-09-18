package app.trackmo.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The line-pill color rules, as pure functions: a tube line resolves to its official
 * TfL color by id, any bus to London-bus red by mode, everything else to no color (a
 * neutral pill), and the text flips black/white to stay legible on the fill.
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
    fun `an unmapped line or mode has no color, so the caller falls back to neutral`() {
        assertNull(lineFillColor("elizabeth", "elizabeth-line"))
        assertNull(lineFillColor("", ""))
    }

    @Test
    fun `text picks the higher-contrast black or white for the fill`() {
        assertEquals(Color.White, textColorOn(Color(0xFFE32017))) // Central red (dark)
        assertEquals(Color.White, textColorOn(Color(0xFF000000))) // Northern black
        assertEquals(Color.Black, textColorOn(Color(0xFFFFD300))) // Circle yellow (pale)
        // Mid-luminance fills take black, where a 0.5 split would wrongly pick white.
        assertEquals(Color.Black, textColorOn(Color(0xFF0098D4))) // Victoria blue
        assertEquals(Color.Black, textColorOn(Color(0xFFA0A5A9))) // Jubilee gray
    }
}
