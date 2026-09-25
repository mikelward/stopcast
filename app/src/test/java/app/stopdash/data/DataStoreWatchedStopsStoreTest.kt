package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.domain.LineRef
import app.stopdash.domain.WatchedStop
import app.stopdash.domain.WatchedStopSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The store wrapper's mapping, edits, and version handling over a fake in-memory [DataStore]
 * so no Android file or Context is needed — the JSON serialization itself is covered by
 * [WatchedStopsSerializerTest].
 */
class DataStoreWatchedStopsStoreTest {
    private val oxford = WatchedStop("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube")))
    private val kings = WatchedStop("940GZZLUKSX", "King's Cross St. Pancras")

    private class FakeDataStore(initial: PersistedWatchedStops?) : DataStore<PersistedWatchedStops?> {
        private val state = MutableStateFlow(initial)
        override val data: Flow<PersistedWatchedStops?> = state
        override suspend fun updateData(
            transform: suspend (t: PersistedWatchedStops?) -> PersistedWatchedStops?,
        ): PersistedWatchedStops? = transform(state.value).also { state.value = it }
    }

    @Test
    fun `watched reads an empty set when nothing is stored`() = runTest {
        val store = DataStoreWatchedStopsStore(FakeDataStore(null))
        assertEquals(WatchedStopSet.Loaded(emptyList()), store.watched().first())
    }

    @Test
    fun `add then watched emits the added stop`() = runTest {
        val store = DataStoreWatchedStopsStore(FakeDataStore(null))
        store.add(oxford)
        assertEquals(WatchedStopSet.Loaded(listOf(oxford)), store.watched().first())
    }

    @Test
    fun `add is idempotent by id`() = runTest {
        val store = DataStoreWatchedStopsStore(FakeDataStore(null))
        store.add(oxford)
        store.add(oxford.copy(name = "Oxford Circus Underground"))
        assertEquals(WatchedStopSet.Loaded(listOf(oxford)), store.watched().first())
    }

    @Test
    fun `remove drops the stop`() = runTest {
        val store = DataStoreWatchedStopsStore(FakeDataStore(null))
        store.add(oxford)
        store.add(kings)
        store.remove(oxford.id)
        assertEquals(WatchedStopSet.Loaded(listOf(kings)), store.watched().first())
    }

    @Test
    fun `a newer-version set reads as Unavailable, not as an empty set`() = runTest {
        val future = listOf(oxford).toPersisted().copy(version = PersistedWatchedStops.CURRENT_VERSION + 1)
        val store = DataStoreWatchedStopsStore(FakeDataStore(future))
        assertEquals(WatchedStopSet.Unavailable, store.watched().first())
    }

    @Test
    fun `an edit against a newer-version set preserves it, never overwriting the user's stops`() = runTest {
        val future = listOf(oxford).toPersisted().copy(version = PersistedWatchedStops.CURRENT_VERSION + 1)
        val backing = FakeDataStore(future)
        val store = DataStoreWatchedStopsStore(backing)
        // An add (or remove) must not downgrade the newer file to this build's version and
        // erase the stops in it — the file is kept exactly as written.
        store.add(kings)
        assertEquals(future, backing.data.first())
        assertEquals(WatchedStopSet.Unavailable, store.watched().first())
        store.remove(oxford.id)
        assertEquals(future, backing.data.first())
    }
}
