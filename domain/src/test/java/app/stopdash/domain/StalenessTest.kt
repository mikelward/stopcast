package app.stopdash.domain

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

    @Test
    fun `remaining until stale counts down from the threshold`() {
        // The delay a caller schedules a one-shot staleness redraw after (SPEC D4).
        assertEquals(5.minutes, Staleness.remainingUntilStale(0.seconds))
        assertEquals(1.minutes, Staleness.remainingUntilStale(4.minutes))
        assertEquals(1.seconds, Staleness.remainingUntilStale(4.minutes + 59.seconds))
    }

    @Test
    fun `remaining until stale is zero once at or past the threshold`() {
        // Nothing left to flip, so the caller schedules nothing (or cancels a pending wake).
        assertEquals(kotlin.time.Duration.ZERO, Staleness.remainingUntilStale(5.minutes))
        assertEquals(kotlin.time.Duration.ZERO, Staleness.remainingUntilStale(20.minutes))
    }
}
