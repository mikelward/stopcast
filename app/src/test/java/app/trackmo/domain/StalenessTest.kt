package app.trackmo.domain

import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StalenessTest {
    @Test
    fun `there is exactly one threshold, pinned here`() {
        // If this value changes, change it deliberately: it is the single policy every
        // surface shares (SPEC D4). The test pins it so a drive-by tweak is caught.
        assertEquals(5.minutes, Staleness.THRESHOLD)
    }

    @Test
    fun `data younger than the threshold is not stale`() {
        assertFalse(Staleness.isStale(0.seconds))
        assertFalse(Staleness.isStale(4.minutes + 59.seconds))
    }

    @Test
    fun `data at or past the threshold is stale`() {
        assertTrue(Staleness.isStale(5.minutes))
        assertTrue(Staleness.isStale(10.minutes))
    }
}
