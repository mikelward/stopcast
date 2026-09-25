package app.stopcast.data

import app.stopcast.domain.StarredRow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchComplicationRowsTest {
    private val a = StarredRow("940GA", "victoria", "inbound")
    private val b = StarredRow("940GB", "central", "outbound")

    @Test
    fun `rows round-trip, in a stable encoding`() {
        assertEquals(setOf(a, b), WatchComplicationRows.decode(WatchComplicationRows.encode(setOf(a, b))))
        assertArrayEquals(WatchComplicationRows.encode(setOf(a, b)), WatchComplicationRows.encode(setOf(b, a)))
        assertEquals(emptySet<StarredRow>(), WatchComplicationRows.decode(WatchComplicationRows.encode(emptySet())))
    }

    @Test
    fun `an unreadable item is null, never a crash`() {
        assertNull(WatchComplicationRows.decode("not json".encodeToByteArray()))
        assertNull(WatchComplicationRows.decode("{}".encodeToByteArray()))
    }
}
