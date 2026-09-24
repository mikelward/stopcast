package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [WatchedStops]: membership is by stop id — add is idempotent and appends a new stop last,
 * remove drops a stop (and so all its rows) and no-ops on an id that isn't watched.
 */
class WatchedStopsTest {
    private val oxford = WatchedStop("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube")))
    private val kings = WatchedStop("940GZZLUKSX", "King's Cross St. Pancras")

    @Test
    fun `add appends a new stop at the end`() {
        assertEquals(listOf(oxford, kings), WatchedStops.add(listOf(oxford), kings))
    }

    @Test
    fun `add is a no-op when a stop with the same id is already watched`() {
        val current = listOf(oxford, kings)
        // Same id, different metadata: membership is by id, so the existing entry is kept
        // unchanged and in place rather than replaced, duplicated, or moved to the end.
        val readd = oxford.copy(name = "Oxford Circus Underground", lines = emptyList())
        assertEquals(current, WatchedStops.add(current, readd))
    }

    @Test
    fun `remove drops the stop, and with it all of its rows`() {
        assertEquals(listOf(kings), WatchedStops.remove(listOf(oxford, kings), oxford.id))
    }

    @Test
    fun `remove is a no-op for an id that is not watched`() {
        val current = listOf(oxford, kings)
        assertEquals(current, WatchedStops.remove(current, "490000NOTWATCHED"))
    }

    @Test
    fun `add preserves the existing order`() {
        val third = WatchedStop("490000", "A Bus Stop")
        assertEquals(listOf(oxford, kings, third), WatchedStops.add(listOf(oxford, kings), third))
    }
}
