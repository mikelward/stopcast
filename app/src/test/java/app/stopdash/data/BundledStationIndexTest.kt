package app.stopdash.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real bundled index (built from TfL by the `station-index` workflow) is present, parses, and
 * finds the stations the abbreviations promise. A missing asset fails rather than skips: the app
 * would fall back to TfL-only search, and the abbreviations would silently stop working.
 */
class BundledStationIndexTest {
    private val asset = File("src/main/assets/stations/station_index.json")

    @Test
    fun `KX, KGX and KC find King's Cross, and CX Charing Cross`() {
        assertTrue("bundled station index is missing; run the station-index workflow", asset.exists())
        val index = StationIndexStore.parse(asset.readText())
        for (query in listOf("kx", "kgx", "kc")) {
            assertEquals(query, "HUBKGX", index.search(query).first().id)
        }
        assertEquals("Charing Cross", index.search("cx").first().name)
    }
}
