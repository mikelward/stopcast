package app.stopdash.data

import app.stopdash.data.WatchRefreshOutcome.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchRefreshOutcomeTest {
    @Test
    fun `each refresh result maps to the outcome the watch shows`() {
        assertEquals(WatchRefreshOutcome.NO_STOPS, WatchRefreshOutcome.of(0, 0, 0, emptyList()))
        assertEquals(WatchRefreshOutcome.REFRESHED, WatchRefreshOutcome.of(3, 3, 0, emptyList()))
        assertEquals(WatchRefreshOutcome.REFRESHED, WatchRefreshOutcome.of(3, 1, 2, emptyList()))
        assertEquals(WatchRefreshOutcome.PARTLY_REFRESHED, WatchRefreshOutcome.of(3, 2, 0, listOf(Failure.UNREACHABLE)))
        assertEquals(WatchRefreshOutcome.DEBOUNCED, WatchRefreshOutcome.of(3, 0, 3, emptyList()))
        assertEquals(
            WatchRefreshOutcome.RATE_LIMITED,
            WatchRefreshOutcome.of(3, 0, 0, listOf(Failure.UNREACHABLE, Failure.RATE_LIMITED, Failure.UNREACHABLE)),
        )
        assertEquals(WatchRefreshOutcome.UNREACHABLE, WatchRefreshOutcome.of(2, 0, 1, listOf(Failure.UNREACHABLE)))
    }

    @Test
    fun `the outcome round-trips, and an unknown one reads as null`() {
        for (outcome in WatchRefreshOutcome.entries) {
            val reply = WatchRefreshReply(-42L, outcome)
            assertEquals(reply, WatchRefreshReply.decode(reply.encode()))
        }
        assertNull(WatchRefreshReply.decode("7:SOMETHING_NEWER".encodeToByteArray()))
        assertNull(WatchRefreshReply.decode("REFRESHED".encodeToByteArray()))
        assertEquals(7L, WatchRefreshReply.decodeRequest(WatchRefreshReply.encodeRequest(7L)))
        assertNull(WatchRefreshReply.decodeRequest(ByteArray(0)))
    }

    @Test
    fun `a recent failure answers again, a success or an old failure doesn't`() {
        val window = java.time.Duration.ofSeconds(30)
        val soon = java.time.Duration.ofSeconds(5)
        for (failed in listOf(WatchRefreshOutcome.RATE_LIMITED, WatchRefreshOutcome.UNREACHABLE)) {
            assertTrue(WatchRefreshOutcome.answersAgain(failed, soon, window))
            assertFalse(WatchRefreshOutcome.answersAgain(failed, window, window))
            assertFalse(WatchRefreshOutcome.answersAgain(failed, soon.negated(), window))
        }
        for (ok in listOf(WatchRefreshOutcome.REFRESHED, WatchRefreshOutcome.PARTLY_REFRESHED, WatchRefreshOutcome.DEBOUNCED, WatchRefreshOutcome.NO_STOPS)) {
            assertFalse(WatchRefreshOutcome.answersAgain(ok, soon, window))
        }
    }
}
