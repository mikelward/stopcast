package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [lineCode]: a line's first three letters (uppercased) for a named line, but a
 * number/code-identified route (bus, river bus) kept verbatim so distinct routes don't
 * collapse to the same letters.
 */
class LineCodeTest {
    @Test
    fun `a named line is its first three letters, uppercased`() {
        assertEquals("VIC", lineCode("Victoria", "tube"))
        assertEquals("BAK", lineCode("Bakerloo", "tube"))
        assertEquals("NOR", lineCode("Northern", "tube"))
        // First three letters, not a cleverer abbreviation ("ELZ").
        assertEquals("ELI", lineCode("Elizabeth line", "elizabeth-line"))
        // Non-letters (spaces, ampersands) are skipped before taking three.
        assertEquals("HAM", lineCode("Hammersmith & City", "tube"))
        assertEquals("WAT", lineCode("Waterloo & City", "tube"))
        assertEquals("DLR", lineCode("DLR", "dlr"))
        assertEquals("TRA", lineCode("Tram", "tram"))
        assertEquals("LIB", lineCode("Liberty", "overground"))
    }

    @Test
    fun `a numbered or coded route keeps its identifier verbatim`() {
        // A route number is already the short identity and has no letters to take.
        assertEquals("24", lineCode("24", "bus"))
        assertEquals("N73", lineCode("N73", "bus"))
        // River-bus routes carry a digit, so RB1/RB2/RB6 stay distinct rather than all
        // collapsing to "RB".
        assertEquals("RB1", lineCode("RB1", "river-bus"))
        assertEquals("RB6", lineCode("RB6", "river-bus"))
    }
}
