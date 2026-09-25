package app.stopdash.data

import androidx.datastore.core.CorruptionException
import app.stopdash.domain.DismissedAlert
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** JSON round-trip and version handling for the dismissed-alerts persistence (mirrors
 *  [StarredRowsSerializerTest]). */
class DismissedAlertsSerializerTest {
    private val sample = PersistedDismissedAlerts(
        alerts = listOf(
            PersistedDismissedAlert("HUBKGX", "No step-free access to the Northern line platforms."),
            PersistedDismissedAlert("490G000A", "Bus Stop Closed"),
        ),
    )

    @Test
    fun `write then read round trips`() = runTest {
        val out = ByteArrayOutputStream()
        DismissedAlertsSerializer.writeTo(sample, out)
        val read = DismissedAlertsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
        assertEquals(sample, read)
    }

    @Test
    fun `empty input reads as nothing saved`() = runTest {
        assertNull(DismissedAlertsSerializer.readFrom(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun `corrupt bytes throw so the failure is surfaced, not taken for empty`() {
        val corrupt = "{not valid json".encodeToByteArray()
        assertThrows(CorruptionException::class.java) {
            runBlocking { DismissedAlertsSerializer.readFrom(ByteArrayInputStream(corrupt)) }
        }
    }

    @Test
    fun `a current-version set maps to the domain, a newer one reads as empty (fails safe)`() {
        assertEquals(
            setOf(DismissedAlert("HUBKGX", "No step-free access to the Northern line platforms."), DismissedAlert("490G000A", "Bus Stop Closed")),
            sample.toDomain(),
        )
        assertNull(sample.copy(version = PersistedDismissedAlerts.CURRENT_VERSION + 1).toDomain())
    }
}
