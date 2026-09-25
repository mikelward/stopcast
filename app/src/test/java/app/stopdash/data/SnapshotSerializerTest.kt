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

class SnapshotSerializerTest {
    private val sample = PersistedSnapshot(
        stops = listOf(
            PersistedStop(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    PersistedDeparture("victoria", "Victoria", "inbound", "Brixton", "Platform 1", 1_700_000_000_000, "tube"),
                ),
                fetchedAtMillis = 1_700_000_000_000,
                lines = listOf(PersistedLine("victoria", "Victoria", "tube")),
                arrivalsFresh = true,
            ),
        ),
        fetchedAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `write then read round trips`() = runTest {
        val out = ByteArrayOutputStream()
        SnapshotSerializer.writeTo(sample, out)
        val read = SnapshotSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample, read)
    }

    @Test
    fun `empty input reads as no snapshot`() = runTest {
        assertNull(SnapshotSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corrupt bytes throw so the failure is surfaced, not taken for empty`() {
        val corrupt = "{not valid json".encodeToByteArray()
        assertThrows(CorruptionException::class.java) {
            runBlocking { SnapshotSerializer.readFrom(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test
    fun `writing a null snapshot writes nothing and reads back null`() = runTest {
        val out = ByteArrayOutputStream()
        SnapshotSerializer.writeTo(null, out)
        assertEquals(0, out.size())
        assertNull(SnapshotSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}
