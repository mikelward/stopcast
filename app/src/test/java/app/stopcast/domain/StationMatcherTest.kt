package app.stopcast.domain

import app.stopcast.domain.StationMatchTier.Anchored
import app.stopcast.domain.StationMatchTier.Fuzzy
import app.stopcast.domain.StationMatchTier.Prefix
import app.stopcast.domain.StationMatchTier.Substring
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The type-to-find tiers, on public station names and ids (no user data). */
class StationMatcherTest {
    private fun tier(query: String, name: String, id: String = "", hub: String = "") =
        StationMatcher.tier(query, name, id, hub)

    @Test
    fun `a name prefix matches, ignoring case, apostrophes and spaces`() {
        assertEquals(Prefix, tier("king", "King's Cross St. Pancras"))
        assertEquals(Prefix, tier("kings", "King's Cross St. Pancras"))
        assertEquals(Prefix, tier("kings cross", "King's Cross St. Pancras"))
    }

    @Test
    fun `word initials anchor`() {
        assertEquals(Anchored, tier("kc", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("ksp", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("tcr", "Tottenham Court Road"))
    }

    @Test
    fun `Cross also reads X, so KX and CX find their stations`() {
        assertEquals(Anchored, tier("kx", "King's Cross St. Pancras"))
        assertEquals(Anchored, tier("cx", "Charing Cross"))
        assertEquals(Prefix, tier("charing x", "Charing Cross"))
    }

    @Test
    fun `a station or hub code matches`() {
        assertEquals(Prefix, tier("kgx", "King's Cross St. Pancras", id = "HUBKGX"))
        assertEquals(Prefix, tier("ksx", "King's Cross St. Pancras", id = "940GZZLUKSX"))
        assertEquals(Prefix, tier("kgx", "King's Cross St. Pancras", id = "940GZZLUKSX", hub = "HUBKGX"))
        assertEquals(Substring, tier("kg", "King's Cross St. Pancras", id = "HUBKGX"))
        assertEquals(Prefix, tier("hubkgx", "King's Cross St. Pancras", id = "HUBKGX"))
    }

    @Test
    fun `a later word's start anchors, a mid-word run is a substring, and letters in order are fuzzy`() {
        // "cross" starts a word, so it anchors there — a better tier than a bare substring.
        assertEquals(Anchored, tier("cross", "Charing Cross"))
        assertEquals(Substring, tier("aring", "Charing Cross"))
        assertEquals(Fuzzy, tier("vctra", "Victoria"))
    }

    @Test
    fun `a lower-case interior letter doesn't start a match`() {
        // "o" is inside "Victoria" and starts no word: no anchored or fuzzy match from it.
        assertNull(tier("oa", "Victoria"))
    }

    @Test
    fun `accents fold and a blank query matches nothing`() {
        assertEquals(Prefix, tier("cafe", "Café Street"))
        assertNull(tier("  ", "Victoria"))
        assertNull(tier("zzz", "Victoria"))
    }

    @Test
    fun `codes drop their fixed prefixes`() {
        assertEquals("KGX", StationMatcher.codeOf("HUBKGX"))
        assertEquals("KSX", StationMatcher.codeOf("940GZZLUKSX"))
        assertEquals("CAW", StationMatcher.codeOf("940GZZDLCAW"))
        assertEquals("490000000001A", StationMatcher.codeOf("490000000001A"))
    }
}
