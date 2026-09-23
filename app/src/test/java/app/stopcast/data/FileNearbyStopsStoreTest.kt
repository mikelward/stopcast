package app.stopcast.data

import app.stopcast.domain.LineRef
import app.stopcast.domain.NearbyStopsCache
import app.stopcast.domain.StopLocation
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Synthetic positions only (the obviously-fake (51.5, -0.12)); never a real fix. */
class FileNearbyStopsStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val entry = NearbyStopsCache.Entry(
        latitude = 51.5,
        longitude = -0.12,
        radiusMeters = 1609,
        stopTypes = listOf("NaptanPublicBusCoachTram"),
        at = Instant.parse("2026-09-23T08:00:00Z"),
        stops = listOf(
            StopLocation(
                "490000001A", "Example Road", 51.5, -0.12,
                lines = listOf(LineRef("141", "141", "bus")),
                clusterId = "490G000EXAMPLE", stopLetter = "A", towards = "Example",
            ),
        ),
    )

    @Test
    fun `entries round-trip through the file`() {
        val file = File(tmp.root, "nearby-stops.json")
        FileNearbyStopsStore(file).save(listOf(entry))
        assertEquals(listOf(entry), FileNearbyStopsStore(file).load())
    }

    @Test
    fun `a leftover temp file from an interrupted save is deleted on load`() {
        val file = File(tmp.root, "nearby-stops.json")
        val leftover = File(file.path + ".tmp").apply { writeText("{}") }
        FileNearbyStopsStore(file).load()
        assertFalse(leftover.exists())
    }

    @Test
    fun `a missing file loads as empty`() {
        assertTrue(FileNearbyStopsStore(File(tmp.root, "none.json")).load().isEmpty())
    }

    @Test
    fun `a corrupt file loads as empty, is deleted, and says so`() {
        val file = File(tmp.root, "nearby-stops.json").apply { writeText("{not json") }
        val warnings = mutableListOf<String>()
        assertTrue(FileNearbyStopsStore(file, warn = { warnings += it }).load().isEmpty())
        assertFalse(file.exists())
        assertEquals(1, warnings.size)
    }
}
