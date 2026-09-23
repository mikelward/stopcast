package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class LoadStatsTest {
    @Test
    fun `describes the request mix, time, and rate-limit wait`() {
        val line = LoadStats.describe(
            LoadStats.Requests(departures = 9, closures = 3, closureBatches = 1, lineStatus = 1, hubs = 0),
            elapsedMillis = 1234,
            rateWaitMillis = 0,
        )
        assertEquals(
            "departures fetch: 14 requests (9 departures, 3 closure, 1 closure batch, 1 line status, 0 hub) in 1234 ms, 0 ms rate-limited",
            line,
        )
    }

    @Test
    fun `a negative wait reads as none`() {
        assertEquals(
            "departures fetch: 0 requests (0 departures, 0 closure, 0 closure batch, 0 line status, 0 hub) in 5 ms, 0 ms rate-limited",
            LoadStats.describe(LoadStats.Requests(), elapsedMillis = 5, rateWaitMillis = -3),
        )
    }
}
