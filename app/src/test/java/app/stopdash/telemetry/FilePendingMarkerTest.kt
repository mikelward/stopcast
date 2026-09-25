package app.stopdash.telemetry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FilePendingMarkerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a pending opt-in is kept and cleared`() {
        val marker = FilePendingMarker(File(tmp.root, "pending"))
        assertFalse(marker.read())
        assertTrue(marker.save(true))
        assertTrue(marker.read())
        assertTrue(marker.save(true))
        assertTrue(marker.save(false))
        assertFalse(marker.read())
        assertTrue(marker.save(false))
    }

    @Test
    fun `a marker that can't be written reports it and reads as not pending`() {
        val marker = FilePendingMarker(File(tmp.root, "missing-dir/pending"))
        assertFalse(marker.save(true))
        assertFalse(marker.read())
    }
}
