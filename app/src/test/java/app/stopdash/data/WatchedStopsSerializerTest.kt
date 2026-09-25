package app.stopdash.data

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WatchedStopsSerializerTest {
    private val sample = PersistedWatchedStops(
        stops = listOf(
            PersistedWatchedStop(
                id = "940GZZLUOXC",
                name = "Oxford Circus",
                lines = listOf(PersistedWatchedLine("victoria", "Victoria", "tube")),
            ),
            PersistedWatchedStop(id = "490000", name = "A Bus Stop"),
        ),
    )

    @Test
    fun `write then read round trips`() = runTest {
        val out = ByteArrayOutputStream()
        WatchedStopsSerializer.writeTo(sample, out)
        val read = WatchedStopsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample, read)
    }

    @Test
    fun `empty input reads as nothing saved`() = runTest {
        assertNull(WatchedStopsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corrupt bytes throw so the failure is surfaced, not taken for empty`() {
        val corrupt = "{not valid json".encodeToByteArray()
        assertThrows(CorruptionException::class.java) {
            runBlocking { WatchedStopsSerializer.readFrom(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test
    fun `writing null writes nothing and reads back null`() = runTest {
        val out = ByteArrayOutputStream()
        WatchedStopsSerializer.writeTo(null, out)
        assertEquals(0, out.size())
        assertNull(WatchedStopsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}
