package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import app.stopcast.domain.StopQualifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [subHeaderText]'s mapping from a group's [StopQualifier] to its rendered parts, tested apart from
 * the composable. The primary label is uppercased for the small-caps read; the parenthetical keeps
 * its case; the spoken form is what a screen reader hears in place of the glyphs.
 */
class HeaderQualifierTest {

    @Test
    fun `a rail platform leads with the platform number and its compass in parens`() {
        val t = subHeaderText(StopQualifier.Platform("2", "Eastbound"))
        assertEquals("PLATFORM 2", t.primary)
        assertEquals("Eastbound", t.paren)
        assertEquals("Platform 2, Eastbound", t.spoken)
    }

    @Test
    fun `a rail platform with no direction has no parenthetical`() {
        val t = subHeaderText(StopQualifier.Platform("4", null))
        assertEquals("PLATFORM 4", t.primary)
        assertNull(t.paren)
        assertEquals("Platform 4", t.spoken)
    }

    @Test
    fun `a bare rail compass is a single label with no parenthetical`() {
        val t = subHeaderText(StopQualifier.Compass("Eastbound"))
        assertEquals("EASTBOUND", t.primary)
        assertNull(t.paren)
        assertEquals("Eastbound", t.spoken)
    }

    @Test
    fun `a bus stop leads with the stop letter and its towards in parens`() {
        val t = subHeaderText(StopQualifier.BusStop("d", "Archway"))
        assertEquals("STOP D", t.primary)
        assertEquals("towards Archway", t.paren)
        assertEquals("Stop D, towards Archway", t.spoken)
    }

    @Test
    fun `a bus stop with a two-way towards trims at Or to the first destination`() {
        // TfL's Towards is often "Farringdon Or Holborn Circus"; the header shows just the first so it
        // stays a short cue, not a paragraph.
        val t = subHeaderText(StopQualifier.BusStop("g", "Farringdon Or Holborn Circus"))
        assertEquals("STOP G", t.primary)
        assertEquals("towards Farringdon", t.paren)
    }

    @Test
    fun `a bus stop with no towards has no parenthetical`() {
        val t = subHeaderText(StopQualifier.BusStop("a", null))
        assertEquals("STOP A", t.primary)
        assertNull(t.paren)
        assertEquals("Stop A", t.spoken)
    }

    @Test
    fun `a bus bearing renders an arrow glyph and speaks the direction`() {
        val t = subHeaderText(StopQualifier.BusBearing("sw"))
        assertEquals("(→SW)", t.primary)
        assertNull(t.paren)
        assertEquals("Southwest-bound", t.spoken) // the "(→SW)" glyph reads as a direction
    }

    @Test
    fun `a bus terminus renders an arrow and the destination`() {
        val t = subHeaderText(StopQualifier.Terminus("Bank"))
        assertEquals("→ BANK", t.primary)
        assertNull(t.paren)
        assertEquals("to Bank", t.spoken)
    }

    @Test
    fun `a bus terminus with a display rename shows the renamed form, matching the card`() {
        // The destination card renames "Battersea Power" → "Battersea" (DepartureLabels); the header
        // must use the same so it doesn't read "→ BATTERSEA POWER" above a "Battersea" card (Codex
        // P2, PR #116).
        val t = subHeaderText(StopQualifier.Terminus("Battersea Power"))
        assertEquals("→ BATTERSEA", t.primary)
        assertEquals("to Battersea", t.spoken)
    }

    @Test
    fun `the header style bakes in the semi-bold weight it renders`() {
        // The header must measure the same weight it renders, or the width used to size the label
        // under-counts and the text clips (Codex P2, PR #115). This is the one place both the measure
        // and every header Text read.
        val resolved = headerTextStyle(TextStyle(fontWeight = FontWeight.Normal))
        assertEquals(FontWeight.SemiBold, resolved.fontWeight)
    }
}
