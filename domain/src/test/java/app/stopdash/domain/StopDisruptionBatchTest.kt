package app.stopdash.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopDisruptionBatchTest {
    @Test
    fun `a bus pole batches, a stop area or station does not`() {
        assertTrue(StopDisruptionBatch.isPole("490000001A"))
        assertFalse(StopDisruptionBatch.isPole("490G00000001"))
        assertFalse(StopDisruptionBatch.isPole("940GZZLUMRH"))
        assertFalse(StopDisruptionBatch.isPole("910GEXAMPLE"))
        assertFalse(StopDisruptionBatch.isPole("HUBEXA"))
    }
}
