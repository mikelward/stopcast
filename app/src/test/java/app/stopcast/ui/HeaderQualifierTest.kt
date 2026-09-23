package app.stopcast.ui

import app.stopcast.domain.StopQualifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [groupHeaderLabel]/[groupHeaderSpoken]'s mapping from a group's [StopQualifier] to the one line
 * header's title-case qualifier segment and its spoken form, tested apart from the composable. The
 * visible segment is title case with no small-caps and drops the direction/towards parenthetical; the
 * spoken form keeps the direction/towards a screen reader needs.
 */
class HeaderQualifierTest {

    @Test
    fun `a null qualifier has no label and no spoken form`() {
        assertNull(groupHeaderLabel(null))
        assertNull(groupHeaderSpoken(null))
    }

    @Test
    fun `a rail platform is title case and speaks its compass`() {
        assertEquals("Platform 2", groupHeaderLabel(StopQualifier.Platform("2", "Eastbound")))
        assertEquals("Platform 2, Eastbound", groupHeaderSpoken(StopQualifier.Platform("2", "Eastbound")))
    }

    @Test
    fun `a rail platform with no direction has no spoken compass`() {
        assertEquals("Platform 4", groupHeaderLabel(StopQualifier.Platform("4", null)))
        assertEquals("Platform 4", groupHeaderSpoken(StopQualifier.Platform("4", null)))
    }

    @Test
    fun `a bare rail compass is its label both seen and spoken`() {
        assertEquals("Eastbound", groupHeaderLabel(StopQualifier.Compass("Eastbound")))
        assertEquals("Eastbound", groupHeaderSpoken(StopQualifier.Compass("Eastbound")))
    }

    @Test
    fun `a bus stop is title case and speaks its towards`() {
        assertEquals("Stop D", groupHeaderLabel(StopQualifier.BusStop("d", "Archway")))
        assertEquals("Stop D, towards Archway", groupHeaderSpoken(StopQualifier.BusStop("d", "Archway")))
    }

    @Test
    fun `a bus stop with a two-way towards trims at Or to the first destination`() {
        // TfL's Towards is often "Farringdon Or Holborn Circus"; the spoken cue shows just the first
        // so it stays short, not a paragraph.
        assertEquals("Stop G", groupHeaderLabel(StopQualifier.BusStop("g", "Farringdon Or Holborn Circus")))
        assertEquals(
            "Stop G, towards Farringdon",
            groupHeaderSpoken(StopQualifier.BusStop("g", "Farringdon Or Holborn Circus")),
        )
    }

    @Test
    fun `a bus stop with no towards has no spoken direction`() {
        assertEquals("Stop A", groupHeaderLabel(StopQualifier.BusStop("a", null)))
        assertEquals("Stop A", groupHeaderSpoken(StopQualifier.BusStop("a", null)))
    }

    @Test
    fun `a bus bearing reads as a bare direction word, no Stop and no arrow`() {
        // "Stop" is reserved for a literal pole letter; a compass bearing is just the direction word,
        // like the rail compass ("Eastbound") — and the visible form matches the spoken.
        assertEquals("Southwest-bound", groupHeaderLabel(StopQualifier.BusBearing("sw")))
        assertEquals("Southwest-bound", groupHeaderSpoken(StopQualifier.BusBearing("sw")))
        assertEquals("Southbound", groupHeaderLabel(StopQualifier.BusBearing("s")))
    }

    @Test
    fun `a bus terminus renders an arrow and the destination, no Stop`() {
        assertEquals("-> Bank", groupHeaderLabel(StopQualifier.Terminus("Bank")))
        assertEquals("to Bank", groupHeaderSpoken(StopQualifier.Terminus("Bank")))
    }

    @Test
    fun `a bus terminus with a display rename shows the renamed form, matching the card`() {
        // The destination line renames "Battersea Power" → "Battersea" (DepartureLabels); the header
        // must use the same so it doesn't read "-> Battersea Power" above a "Battersea" card.
        assertEquals("-> Battersea", groupHeaderLabel(StopQualifier.Terminus("Battersea Power")))
        assertEquals("to Battersea", groupHeaderSpoken(StopQualifier.Terminus("Battersea Power")))
    }

    @Test
    fun `a bus bearing names its direction, and anything else names none`() {
        assertEquals("Southbound", bearingDirection("s"))
        assertEquals("Northeast-bound", bearingDirection("NE"))
        assertNull(bearingDirection(""))
        assertNull(bearingDirection("X"))
    }
}
