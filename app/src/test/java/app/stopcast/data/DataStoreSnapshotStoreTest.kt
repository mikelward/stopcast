package app.stopcast.data

import androidx.datastore.core.DataStore
import app.stopcast.domain.Departure
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.JourneyCall
import app.stopcast.domain.WidgetJourney
import app.stopcast.domain.StopArrivals
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store wrapper's mapping (persisted ↔ domain), over a fake in-memory [DataStore] so no
 * Android file or Context is needed — the JSON serialization itself is covered by
 * [SnapshotSerializerTest].
 */
class DataStoreSnapshotStoreTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private class FakeDataStore(initial: PersistedSnapshot?) : DataStore<PersistedSnapshot?> {
        val state = MutableStateFlow(initial)
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
    fun `the widget's journeys and journey-only stops round-trip`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val withJourneys = snapshot().copy(
            journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "940GZZLUOXC|940GZZLUBXN")),
            journeyOnlyStopIds = setOf("940GZZLUOXC"),
        )
        store.save(withJourneys)
        assertEquals(withJourneys, store.load())
        assertEquals(withJourneys, store.loadForWidget())
    }

    @Test
    fun `an unstarred journey is dropped from the stored snapshot, with its journey-only stop`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val kept = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "keep")
        val gone = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "gone")
        val base = snapshot()
        val origin = base.stops.first().copy(stopId = "490000009Z")
        store.save(
            base.copy(
                stops = base.stops + origin,
                journeys = listOf(kept, gone),
                journeyOnlyStopIds = setOf("490000009Z"),
            ),
        )
        store.retainWidgetJourneys(setOf("keep"))
        val after = store.load()!!
        assertEquals(listOf(kept), after.journeys)
        // A flipped journey (same key, another origin) is dropped too.
        store.retainWidgetJourneys(setOf("keep"), mapOf("keep" to "940GZZLUKSX"))
        assertTrue(store.load()!!.journeys.isEmpty())
        assertTrue(after.journeyOnlyStopIds.isEmpty())
        assertEquals(base.stops.map { it.stopId }, after.stops.map { it.stopId })
    }

    @Test
    fun `a version-1 snapshot still loads`() = runTest {
        val v1 = snapshot().toPersisted().copy(version = 1)
        assertEquals(snapshot(), DataStoreSnapshotStore(FakeDataStore(v1)).load())
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
    fun `replaceWidgetJourneys swaps the journeys and leaves the stops`() = runTest {
        val old = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = listOf(old)).toPersisted()))
        val new = old.copy(calls = setOf(JourneyCall("victoria", "Walthamstow Central", null)))
        store.replaceWidgetJourneys(listOf(new, WidgetJourney("940GZZLUKSX", emptySet(), "other")))
        val after = store.load()!!
        assertEquals(listOf(new), after.journeys)
        assertEquals(snapshot().stops, after.stops)
    }

    @Test
    fun `saveKeepingJourneys keeps the stored journeys and their journey-only stops`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin), journeyOnlyStopIds = setOf("490000009Z"))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertTrue(after.stops.any { it.stopId == "490000009Z" })
    }

    @Test
    fun `a pruned stop a pinned journey starts from stays, as journey-only`() = runTest {
        val pin = WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = listOf(pin)).toPersisted()))
        store.pruneStops(listOf("940GZZLUOXC"))
        val after = store.load()!!
        assertEquals(snapshot().stops, after.stops)
        assertEquals(setOf("940GZZLUOXC"), after.journeyOnlyStopIds)
    }

    @Test
    fun `replaceWidgetJourneys adds a supplied origin the store doesn't hold`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        store.replaceWidgetJourneys(listOf(pin), listOf(origin))
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertTrue(origin in after.stops)
    }

    @Test
    fun `replaceWidgetJourneys refreshes a stored origin only with newer arrivals`() = runTest {
        val stored = snapshot().stops.first()
        val pin = WidgetJourney(stored.stopId, setOf(JourneyCall("b1", "Hill", null)), "k")
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().toPersisted()))
        val older = stored.copy(fetchedAt = stored.fetchedAt.minusSeconds(60), departures = emptyList())
        store.replaceWidgetJourneys(listOf(pin), listOf(older))
        assertEquals(listOf(stored), store.load()!!.stops)
        val newer = stored.copy(fetchedAt = stored.fetchedAt.plusSeconds(60), departures = emptyList())
        store.replaceWidgetJourneys(listOf(pin), listOf(newer))
        val after = store.load()!!
        assertEquals(listOf(newer), after.stops)
        assertEquals(newer.fetchedAt, after.fetchedAt)
        // Still a nearby stop, as stored.
        assertEquals(emptySet<String>(), after.journeyOnlyStopIds)
    }

    @Test
    fun `replaceWidgetJourneys starts a snapshot when nothing is stored`() = runTest {
        val store = DataStoreSnapshotStore(FakeDataStore(null))
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        store.replaceWidgetJourneys(listOf(pin), listOf(origin))
        val after = store.load()!!
        assertEquals(listOf(pin), after.journeys)
        assertEquals(listOf(origin), after.stops)
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
        assertEquals(origin.fetchedAt, after.fetchedAt)
        // No pin, nothing stored: still nothing.
        val empty = DataStoreSnapshotStore(FakeDataStore(null))
        empty.replaceWidgetJourneys(emptyList(), emptyList())
        assertNull(empty.load())
    }

    @Test
    fun `journey writes over a version-1 snapshot write version 2`() = runTest {
        val v1 = snapshot().toPersisted().copy(version = 1)
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val replaced = FakeDataStore(v1)
        DataStoreSnapshotStore(replaced).replaceWidgetJourneys(listOf(pin), listOf(origin))
        assertEquals(PersistedSnapshot.CURRENT_VERSION, replaced.state.value!!.version)
        val pinned = snapshot().copy(journeys = listOf(pin.copy(originId = "940GZZLUOXC"))).toPersisted().copy(version = 1)
        val retained = FakeDataStore(pinned)
        DataStoreSnapshotStore(retained).retainWidgetJourneys(emptySet())
        assertEquals(PersistedSnapshot.CURRENT_VERSION, retained.state.value!!.version)
        val pruned = FakeDataStore(pinned)
        DataStoreSnapshotStore(pruned).pruneStops(listOf("940GZZLUOXC"))
        assertEquals(PersistedSnapshot.CURRENT_VERSION, pruned.state.value!!.version)
        assertEquals(listOf("940GZZLUOXC"), pruned.state.value!!.journeyOnlyStopIds)
    }

    @Test
    fun `saveKeepingJourneys keeps an origin saved as nearby`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z")
        // Saved when the origin was nearby: not marked journey-only.
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        val after = store.load()!!
        assertTrue(after.stops.any { it.stopId == "490000009Z" })
        assertEquals(setOf("490000009Z"), after.journeyOnlyStopIds)
    }

    @Test
    fun `saveKeepingJourneys stamps from a carried origin that's newer`() = runTest {
        val pin = WidgetJourney("490000009Z", setOf(JourneyCall("b1", "Hill", null)), "k")
        val origin = snapshot().stops.first().copy(stopId = "490000009Z", fetchedAt = now.plusSeconds(60))
        val stored = snapshot().copy(stops = snapshot().stops + origin, journeys = listOf(pin), journeyOnlyStopIds = setOf("490000009Z"))
        val store = DataStoreSnapshotStore(FakeDataStore(stored.toPersisted()))
        store.saveKeepingJourneys(snapshot())
        assertEquals(now.plusSeconds(60), store.load()!!.fetchedAt)
    }

    @Test
    fun `saveKeepingFresher keeps a stop's newer stored arrivals`() = runTest {
        val newer = snapshot().let { s ->
            s.copy(stops = s.stops.map { it.copy(fetchedAt = now.plusSeconds(120)) }, fetchedAt = now.plusSeconds(120))
        }
        val store = DataStoreSnapshotStore(FakeDataStore(newer.toPersisted()))
        val journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k"))
        // The app's older copy, re-saved with a journey change.
        store.saveKeepingFresher(snapshot().copy(journeys = journeys))
        val after = store.load()!!
        assertEquals(newer.stops, after.stops)
        assertEquals(journeys, after.journeys)
    }

    @Test
    fun `saveKeepingFresher keeps a same-age stop the store marks as failed`() = runTest {
        val failed = snapshot().let { s -> s.copy(stops = s.stops.map { it.copy(arrivalsFresh = false) }) }
        val store = DataStoreSnapshotStore(FakeDataStore(failed.toPersisted()))
        store.saveKeepingFresher(snapshot())
        assertEquals(failed.stops, store.load()!!.stops)
    }

    @Test
    fun `saveIfStopsMatch keeps the stored widget journeys`() = runTest {
        val journeys = listOf(WidgetJourney("940GZZLUOXC", setOf(JourneyCall("victoria", "Brixton", null)), "k"))
        val store = DataStoreSnapshotStore(FakeDataStore(snapshot().copy(journeys = journeys).toPersisted()))
        // A worker that loaded before the journey was pinned writes back no journeys.
        val refreshed = snapshot().copy(fetchedAt = now.plusSeconds(60))
        assertEquals(true, store.saveIfStopsMatch(refreshed, listOf("940GZZLUOXC")))
        assertEquals(refreshed.copy(journeys = journeys), store.load())
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
