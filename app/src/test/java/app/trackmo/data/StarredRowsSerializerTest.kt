package app.trackmo.data

import androidx.datastore.core.CorruptionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StarredRowsSerializerTest {
    private val sample = PersistedStarredRows(
        rows = listOf(
            PersistedStarredRow("940GZZLUOXC", "victoria", "southbound"),
            PersistedStarredRow("490000123A", "24", "Pimlico"),
        ),
    )

    @Test
    fun `write then read round trips`() = runTest {
        val out = ByteArrayOutputStream()
        StarredRowsSerializer.writeTo(sample, out)
        val read = StarredRowsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample, read)
    }

    @Test
    fun `empty input reads as nothing saved`() = runTest {
        assertNull(StarredRowsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corrupt bytes throw so the failure is surfaced, not taken for empty`() {
        val corrupt = "{not valid json".encodeToByteArray()
        assertThrows(CorruptionException::class.java) {
            runBlocking { StarredRowsSerializer.readFrom(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test
    fun `writing null writes nothing and reads back null`() = runTest {
        val out = ByteArrayOutputStream()
        StarredRowsSerializer.writeTo(null, out)
        assertEquals(0, out.size())
        assertNull(StarredRowsSerializer.readFrom(ByteArrayInputStream(out.toByteArray())))
    }
}
