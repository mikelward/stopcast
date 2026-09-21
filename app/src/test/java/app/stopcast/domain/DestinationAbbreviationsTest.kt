package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DestinationAbbreviationsTest {

    private fun abbrev(name: String) = DestinationAbbreviations.abbreviate(name)

    @Test
    fun `single-letter forms take a period`() {
        assertEquals("N. Greenwich", abbrev("North Greenwich"))
        assertEquals("S. Woodford", abbrev("South Woodford"))
        assertEquals("E. Finchley", abbrev("East Finchley"))
        assertEquals("W. Ruislip", abbrev("West Ruislip"))
        assertEquals("H. Barnet", abbrev("High Barnet"))
        assertEquals("U. Holloway", abbrev("Upper Holloway"))
        assertEquals("L. Sydenham", abbrev("Lower Sydenham"))
    }

    @Test
    fun `multi-letter forms take no period`() {
        assertEquals("Gt Portland St", abbrev("Great Portland Street"))
        assertEquals("Clapham Jct", abbrev("Clapham Junction"))
        assertEquals("Finsbury Pk", abbrev("Finsbury Park"))
        assertEquals("Tottenham Court Rd", abbrev("Tottenham Court Road"))
        assertEquals("Baker St", abbrev("Baker Street"))
        assertEquals("Blackwall Pt", abbrev("Blackwall Point"))
    }

    @Test
    fun `several words in one name are all abbreviated`() {
        assertEquals("H. St Kensington", abbrev("High Street Kensington"))
        assertEquals("N. Pk", abbrev("North Park"))
    }

    @Test
    fun `only whole words are abbreviated, never substrings`() {
        // The trap: a word that starts with a key must not be touched.
        assertEquals("Highgate", abbrev("Highgate"))
        assertEquals("Eastcote", abbrev("Eastcote"))
        assertEquals("Upminster", abbrev("Upminster"))
        assertEquals("Streatham", abbrev("Streatham"))
        assertEquals("Parsons Green", abbrev("Parsons Green"))
        assertEquals("Northwood", abbrev("Northwood"))
        assertEquals("Southgate", abbrev("Southgate"))
    }

    @Test
    fun `a saint prefix is left alone`() {
        // "St" for Saint is a different token from "Street"; only the whole word "Street" maps.
        assertEquals("St Pancras", abbrev("St Pancras"))
        assertEquals("St John's Wood", abbrev("St John's Wood"))
    }

    @Test
    fun `names with nothing to shorten are unchanged`() {
        assertEquals("Morden", abbrev("Morden"))
        assertEquals("Brixton", abbrev("Brixton"))
        assertEquals("Elephant & Castle", abbrev("Elephant & Castle"))
        assertEquals("Heathrow Terminal 4", abbrev("Heathrow Terminal 4"))
    }

    @Test
    fun `abbreviating is idempotent`() {
        val once = abbrev("East Finchley")
        assertEquals(once, abbrev(once))
        val gt = abbrev("Great Portland Street")
        assertEquals(gt, abbrev(gt))
    }
}
