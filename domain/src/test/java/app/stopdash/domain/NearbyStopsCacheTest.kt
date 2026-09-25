package app.stopdash.domain

import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic positions only (around the obviously-fake (51.5, -0.12)); never a real fix. */
class NearbyStopsCacheTest {
    private val t0 = Instant.parse("2026-09-23T08:00:00Z")
    private var now = t0
    private val types = StopFinder.DEFAULT_NEARBY_STOP_TYPES
    private val stops = listOf(StopLocation("490000001A", "Example Road", 51.5, -0.12))

    // ~0.001° of latitude is ~111 m.
    private fun cache(store: NearbyStopsStore = NearbyStopsStore.NONE) = NearbyStopsCache(store, clock = { now })

    @Test
    fun `a nearby fix reuses the lookup, a farther one doesn't`() {
        val cache = cache()
        cache.put(51.5, -0.12, 1609, types, stops)
        assertEquals(stops, cache.lookup(51.501, -0.12, 1609, types))
        assertNull(cache.lookup(51.502, -0.12, 1609, types))
    }

    @Test
    fun `a lookup older than a day is asked for again`() {
        val cache = cache()
        cache.put(51.5, -0.12, 1609, types, stops)
        now = t0.plus(Duration.ofHours(24))
        assertNull(cache.lookup(51.5, -0.12, 1609, types))
    }

    @Test
    fun `a clock moved back doesn't pass an old lookup off as fresh`() {
        val cache = cache()
        cache.put(51.5, -0.12, 1609, types, stops)
        now = t0.minusSeconds(60)
        assertNull(cache.lookup(51.5, -0.12, 1609, types))
    }

    @Test
    fun `a different radius or stop-type filter isn't answered from the cache`() {
        val cache = cache()
        cache.put(51.5, -0.12, 1609, types, stops)
        assertNull(cache.lookup(51.5, -0.12, 500, types))
        assertNull(cache.lookup(51.5, -0.12, 1609, listOf("NaptanRailStation")))
    }

    @Test
    fun `an empty result isn't kept`() {
        val cache = cache()
        cache.put(51.5, -0.12, 1609, types, emptyList())
        assertNull(cache.lookup(51.5, -0.12, 1609, types))
    }

    @Test
    fun `only the most recent few places are kept`() {
        val cache = cache()
        repeat(NearbyStopsCache.MAX_AREAS + 1) { i -> cache.put(51.5 + i * 0.01, -0.12, 1609, types, stops) }
        assertNull(cache.lookup(51.5, -0.12, 1609, types))
        assertEquals(stops, cache.lookup(51.5 + NearbyStopsCache.MAX_AREAS * 0.01, -0.12, 1609, types))
    }

    private class MemoryStore(var saved: List<NearbyStopsCache.Entry> = emptyList()) : NearbyStopsStore {
        var loads = 0
        override fun load(): List<NearbyStopsCache.Entry> {
            loads++
            return saved
        }
        override fun save(entries: List<NearbyStopsCache.Entry>) {
            saved = entries
        }
    }

    @Test
    fun `a lookup survives into a new cache through the store`() {
        val store = MemoryStore()
        cache(store).put(51.5, -0.12, 1609, types, stops)
        assertEquals(stops, cache(store).lookup(51.5, -0.12, 1609, types))
    }

    @Test
    fun `the store is read once, and an expired entry is dropped on the next write`() {
        val store = MemoryStore()
        val cache = cache(store)
        cache.put(51.5, -0.12, 1609, types, stops)
        cache.lookup(51.5, -0.12, 1609, types)
        assertEquals(1, store.loads)

        now = t0.plus(Duration.ofHours(25))
        cache.put(51.6, -0.12, 1609, types, stops)
        assertEquals(listOf(51.6), store.saved.map { it.latitude })
    }

    @Test
    fun `an expired entry is removed from the store on the next lookup`() {
        val store = MemoryStore()
        cache(store).put(51.5, -0.12, 1609, types, stops)
        now = t0.plus(Duration.ofHours(25))
        // A fresh process: the stored entry loads, has expired, and leaves the file too.
        assertNull(cache(store).lookup(51.5, -0.12, 1609, types))
        assertTrue(store.saved.isEmpty())
    }

    @Test
    fun `the caching finder asks TfL once for repeat lookups nearby`() = runTest {
        var calls = 0
        val finder = CachingStopFinder(
            object : StopFinder {
                override suspend fun nearbyStops(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation> {
                    calls++
                    return stops
                }
            },
            cache(),
        )
        finder.nearbyStops(51.5, -0.12, 1609)
        finder.nearbyStops(51.5005, -0.12, 1609)
        assertEquals(1, calls)
    }
}
