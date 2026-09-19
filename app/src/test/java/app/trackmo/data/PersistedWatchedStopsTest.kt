package app.trackmo.data

import app.trackmo.domain.LineRef
import app.trackmo.domain.WatchedStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedWatchedStopsTest {
    private val sample = listOf(
        WatchedStop(
            id = "940GZZLUOXC",
            name = "Oxford Circus",
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        ),
        WatchedStop(id = "940GZZLUKSX", name = "King's Cross St. Pancras"),
    )

    @Test
    fun `round trips through the persisted form unchanged`() {
        assertEquals(sample, sample.toPersisted().toDomain())
    }

    @Test
    fun `a stop with no served lines round trips`() {
        val busStop = listOf(WatchedStop(id = "490000", name = "A Bus Stop"))
        assertEquals(busStop, busStop.toPersisted().toDomain())
    }

    @Test
    fun `an empty set round trips`() {
        assertEquals(emptyList<WatchedStop>(), emptyList<WatchedStop>().toPersisted().toDomain())
    }

    @Test
    fun `an unknown format version is discarded rather than mis-read`() {
        val fromFuture = sample.toPersisted().copy(version = PersistedWatchedStops.CURRENT_VERSION + 1)
        assertNull(fromFuture.toDomain())
    }
}
