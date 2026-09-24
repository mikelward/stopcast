package app.stopcast.watch

import app.stopcast.data.WatchDecode
import app.stopcast.data.WatchEnvelopes
import app.stopcast.data.WatchPayload
import app.stopcast.domain.Departure
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import java.io.IOException
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone's watch publisher over a fake Data Layer: synthetic stops only, no user data. */
class WatchPublisherTest {
    private val now: Instant = Instant.parse("2026-09-24T08:00:00Z")

    private class FakeChannel(var installed: Boolean = true, var failing: Boolean = false) : WatchChannel {
        val sent = mutableListOf<WatchPayload>()
        override suspend fun watchInstalled(): Boolean = installed
        override suspend fun put(payload: WatchPayload) {
            if (failing) throw IOException("data layer down")
            sent += payload
        }
    }

    private class FakeMarker : PublishMarker {
        var hash: String? = null
        override fun get(): String? = hash
        override fun set(hash: String) {
            this.hash = hash
        }
    }

    private val logged = mutableListOf<String>()

    private fun snapshot(minutes: Long = 3) = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                "940GEXAMPLE1",
                "Example",
                listOf(Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(minutes * 60), "tube")),
                now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `publishes the snapshot to a watch with the app, once per change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet()))
        assertEquals(WatchPublisher.Outcome.Unchanged, publisher.publish(snapshot(), emptySet()))
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(minutes = 4), emptySet()))
        assertEquals(2, channel.sent.size)
        val envelope = (WatchEnvelopes.decode(channel.sent.last().bytes) as WatchDecode.Ok).envelope
        assertEquals(listOf("940GEXAMPLE1"), envelope.stops.map { it.stopId })
    }

    @Test
    fun `a star change is a change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        val star = StarredRow("940GEXAMPLE1", "victoria", "inbound")
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), setOf(star)))
    }

    @Test
    fun `hiding a mode is a change`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet(), hiddenModes = setOf("bus")))
    }

    @Test
    fun `a forced republish goes out even when unchanged`() = runTest {
        val channel = FakeChannel()
        val publisher = WatchPublisher(channel, FakeMarker(), logged::add) { now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet(), force = true))
        assertEquals(2, channel.sent.size)
    }

    @Test
    fun `nothing leaves the phone when no watch has the app`() = runTest {
        val channel = FakeChannel(installed = false)
        val marker = FakeMarker()
        assertEquals(WatchPublisher.Outcome.NoWatch, WatchPublisher(channel, marker, logged::add) { now }.publish(snapshot(), emptySet()))
        assertEquals(0, channel.sent.size)
        assertNull("so a watch that appears later still gets it", marker.hash)
    }

    @Test
    fun `with no watch app, no envelope is built`() = runTest {
        var built = 0
        val publisher = WatchPublisher(FakeChannel(installed = false), FakeMarker(), logged::add) { built++; now }
        publisher.publish(snapshot(), emptySet())
        assertEquals(0, built)
    }

    @Test
    fun `nothing is sent before a snapshot is stored`() = runTest {
        val channel = FakeChannel()
        assertEquals(WatchPublisher.Outcome.NothingStored, WatchPublisher(channel, FakeMarker(), logged::add) { now }.publish(null, emptySet()))
        assertEquals(0, channel.sent.size)
    }

    @Test
    fun `a failed write is logged without user data and retried by the next attempt`() = runTest {
        val channel = FakeChannel(failing = true)
        val marker = FakeMarker()
        val publisher = WatchPublisher(channel, marker, logged::add) { now }
        assertEquals(WatchPublisher.Outcome.Failed, publisher.publish(snapshot(), emptySet()))
        assertNull(marker.hash)
        assertEquals(listOf("watch publish failed: IOException"), logged)
        channel.failing = false
        assertEquals(WatchPublisher.Outcome.Published, publisher.publish(snapshot(), emptySet()))
    }

    @Test
    fun `a publish lost to process death goes out on the next start`() = runTest {
        // The marker records the last *successful* write, so a restart compares against it.
        val marker = FakeMarker()
        WatchPublisher(FakeChannel(), marker, logged::add) { now }.publish(snapshot(), emptySet())
        val afterRestart = FakeChannel()
        assertEquals(WatchPublisher.Outcome.Published, WatchPublisher(afterRestart, marker, logged::add) { now }.publish(snapshot(minutes = 5), emptySet()))
        assertEquals(WatchPublisher.Outcome.Unchanged, WatchPublisher(afterRestart, marker, logged::add) { now }.publish(snapshot(minutes = 5), emptySet()))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a burst of writes becomes one request, the latest`() = runTest {
        val snapshots = MutableStateFlow<DeparturesSnapshot?>(snapshot(minutes = 1))
        val stars = MutableStateFlow<Set<StarredRow>>(emptySet())
        val requests = mutableListOf<Pair<DeparturesSnapshot?, Set<StarredRow>>>()
        val job = launch { WatchPublisher.requests(snapshots, stars, window = 2.seconds).collect { requests += it } }
        runCurrent()
        snapshots.value = snapshot(minutes = 2)
        advanceTimeBy(500)
        snapshots.value = snapshot(minutes = 3)
        advanceTimeBy(500)
        assertEquals(0, requests.size)
        advanceTimeBy(2_001)
        assertEquals(listOf(snapshot(minutes = 3) to emptySet<StarredRow>()), requests)
        job.cancel()
    }
}
