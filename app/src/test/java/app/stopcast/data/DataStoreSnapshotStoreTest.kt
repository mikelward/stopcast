package app.stopcast.data

import androidx.datastore.core.DataStore
import app.stopcast.domain.Departure
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.StopArrivals
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The store wrapper's mapping (persisted ↔ domain), over a fake in-memory [DataStore] so no
 * Android file or Context is needed — the JSON serialization itself is covered by
 * [SnapshotSerializerTest].
 */
class DataStoreSnapshotStoreTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private class FakeDataStore(initial: PersistedSnapshot?) : DataStore<PersistedSnapshot?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedSnapshot?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedSnapshot?) -> PersistedSnapshot?,
        ): PersistedSnapshot? = transform(state.value).also { state.value = it }
    }

    private fun snapshot() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(180), "tube"),
                ),
                fetchedAt = now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `load returns null when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        assertNull(store.load())
    }

    @Test
    fun `save then load returns the same snapshot`() = runTest {
        val backing = FakeDataStore(null)
        val store = DataStoreSnapshotStore(backing)
        store.save(snapshot())
        assertEquals(snapshot(), store.load())
    }

    @Test
    fun `a stored future-version snapshot loads as null`() = runTest {
        val future = snapshot().toPersisted().copy(version = PersistedSnapshot.CURRENT_VERSION + 1)
        val store = DataStoreSnapshotStore(FakeDataStore(future))
        assertNull(store.load())
    }

    /** A different-id snapshot, standing in for "the user relocated" — a new stop set entirely. */
    private fun relocated() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross",
                departures = listOf(
                    Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(120), "tube"),
                ),
                fetchedAt = now,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `saveIfStopsMatch applies and writes when the stored stop set matches`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val refreshed = snapshot().copy(fetchedAt = now.plusSeconds(60))
        val applied = store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC"))
        assertEquals(true, applied)
        assertEquals(refreshed, store.load())
    }

    @Test
    fun `saveIfStopsMatch discards and keeps the stored snapshot when the set differs`() = runTest {
        // The store now holds a different (relocated) set than the one the caller worked from.
        val store = DataStoreSnapshotStore(FakeDataStore(relocated().toPersisted()))
        val staleResult = snapshot().copy(fetchedAt = now.plusSeconds(60))
        val applied = store.saveIfStopsMatch(staleResult, listOf("940GZZLUOXC"))
        assertEquals(false, applied)
        // The newer relocated snapshot is untouched — the stale result was dropped.
        assertEquals(relocated(), store.load())
    }

    @Test
    fun `saveIfStopsMatch discards when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val applied = store.saveIfStopsMatch(snapshot(), listOf("940GZZLUOXC"))
        assertEquals(false, applied)
        assertNull(store.load())
    }

    /** Two stops at different ages — Oxford Circus is the freshest, King's Cross is a minute older. */
    private fun twoStopSnapshot() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure("victoria", "Victoria", "inbound", "Brixton", null, now.plusSeconds(180), "tube"),
                ),
                fetchedAt = now,
            ),
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross",
                departures = listOf(
                    Departure("northern", "Northern", "southbound", "Morden", null, now.plusSeconds(120), "tube"),
                ),
                fetchedAt = now.minusSeconds(60),
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `pruneStops removes the departed stop and re-derives the stamp from the rest`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(twoStopSnapshot().toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        val loaded = store.load()!!
        assertEquals(listOf("940GZZLUKSX"), loaded.stops.map { it.stopId })
        // The whole-snapshot stamp drops to the freshest remaining stop — Oxford Circus was the
        // newest, so removing it ages the snapshot's stamp to King's Cross's.
        assertEquals(now.minusSeconds(60), loaded.fetchedAt)
    }

    @Test
    fun `pruneStops leaves the snapshot untouched when no id is present`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        store.pruneStops(listOf("940GZZLUKSX")) // not in the stored single-stop set
        assertEquals(snapshot(), store.load())
    }

    @Test
    fun `pruneStops is a no-op when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        store.pruneStops(listOf("940GZZLUOXC"))
        assertNull(store.load())
    }
}
