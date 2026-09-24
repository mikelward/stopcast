package app.stopcast.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailStationCodesStoreTest {
    @Test
    fun `the bundled table maps a TfL rail station to its CRS`() {
        // The shipped asset: well-known terminals as examples.
        val codes = RailStationCodesStore.parse(File("src/main/assets/stations/crs_codes.json").readText())
        assertEquals("WAT", codes.crsFor("910GWATRLMN"))
        assertEquals("KGX", codes.crsFor("910GKNGX"))
        assertNull("a tube station has none", codes.crsFor("940GZZLUWLO"))
        // A station TfL lists under two National Rail ids keeps both; an Overground-only
        // twin of a National Rail id is dropped, so its board shows under one stop.
        assertEquals("STP", codes.crsFor("910GSTPX"))
        assertEquals("STP", codes.crsFor("910GSTPADOM"))
        assertEquals("CLJ", codes.crsFor("910GCLPHMJC"))
        assertNull(codes.crsFor("910GCLPHMJ1"))
    }

    @Test
    fun `a table of another version is ignored`() {
        val warnings = mutableListOf<String>()
        val codes = RailStationCodesStore.parse("""{"version":2,"codes":{"EXAMPLE":"EXA"}}""") { warnings += it }
        assertNull(codes.crsFor("910GEXAMPLE"))
        assertEquals(1, warnings.size)
    }
}
