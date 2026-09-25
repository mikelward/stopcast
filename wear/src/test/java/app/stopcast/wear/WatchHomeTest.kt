package app.stopcast.wear

import app.stopcast.data.PersistedStop
import app.stopcast.data.WatchEnvelope
import app.stopcast.data.WatchEnvelopes
import java.io.File
import java.nio.file.Files
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The watch's envelope store: synthetic stops only, no user data. */
class WatchHomeTest {
    private val at = Instant.parse("2026-09-24T08:00:00Z")

    @Test
    fun `the store keeps the last good envelope and refuses one it can't read`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val file = File(dir, "envelope.json")
            val logged = mutableListOf<String>()
            val store = WatchEnvelopeStore(file, now = { at }, log = logged::add)
            store.load()
            assertEquals(WatchReceived.NeverSynced, store.state.value)

            val good = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example")))
            assertTrue(store.ingest(WatchEnvelopes.encode(good)))
            assertEquals(WatchReceived.Received(good, at), store.state.value)

            val newer = """{"version":${WatchEnvelope.CURRENT_VERSION + 1}}""".encodeToByteArray()
            assertFalse(store.ingest(newer))
            assertEquals(listOf("envelope refused: version ${WatchEnvelope.CURRENT_VERSION + 1}"), logged)
            assertEquals(WatchReceived.Received(good, at), store.state.value)

            // A fresh process reads the stored one back.
            val reopened = WatchEnvelopeStore(file, now = { at }, log = logged::add)
            reopened.load()
            assertEquals(good, (reopened.state.value as WatchReceived.Received).envelope)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a startup lookup can't replace an envelope the listener stored while it read`() {
        val dir = Files.createTempDirectory("watch").toFile()
        try {
            val store = WatchEnvelopeStore(File(dir, "envelope.json"), now = { at }, log = {})
            val older = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE1", "Example")))
            val newer = WatchEnvelope(stops = listOf(PersistedStop("940GEXAMPLE2", "Other")))
            val since = store.ingestCount()
            assertTrue(store.ingest(WatchEnvelopes.encode(newer)))
            assertFalse(store.ingest(WatchEnvelopes.encode(older), ifNoneSince = since))
            assertEquals(newer, (store.state.value as WatchReceived.Received).envelope)
            // With nothing in between, the lookup's envelope is stored.
            assertTrue(store.ingest(WatchEnvelopes.encode(older), ifNoneSince = store.ingestCount()))
        } finally {
            dir.deleteRecursively()
        }
    }
}
