package app.stopdash.domain

import java.time.Instant
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import kotlinx.coroutines.runBlocking

class ArrivalsCacheTest {
    private val now = Instant.parse("2026-09-26T08:00:00Z")

    private fun departure(minutes: Long) = Departure(
        lineId = "victoria",
        lineName = "Victoria",
        direction = "outbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(minutes * 60),
        mode = "tube",
    )

    @Test
    fun `a stop's arrivals are handed out with when they were fetched until they're stale`() {
        val cache = ArrivalsCache()
        val departures = listOf(departure(3))
        cache.put("940GZZLUOXC", departures, now)
        assertEquals(ArrivalsCache.Entry(departures, now), cache.get("940GZZLUOXC", now.plusSeconds(90)))
        assertNull(cache.get("940GZZLUOXC", now.plus(java.time.Duration.ofMinutes(5))))
        assertNull(cache.get("940GZZLUVIC", now))
    }

    @Test
    fun `an older fetch landing late doesn't replace a newer one`() {
        val cache = ArrivalsCache()
        val newer = listOf(departure(2))
        cache.put("940GZZLUOXC", newer, now)
        cache.put("940GZZLUOXC", listOf(departure(4)), now.minusSeconds(10))
        assertEquals(newer, cache.get("940GZZLUOXC", now)?.departures)
    }

    @Test
    fun `the least recently fetched stop goes first once full`() {
        val cache = ArrivalsCache()
        repeat(ArrivalsCache.MAX + 1) { i -> cache.put("stop$i", emptyList(), now) }
        assertNull(cache.get("stop0", now))
        assertEquals(emptyList<Departure>(), cache.get("stop${ArrivalsCache.MAX}", now)?.departures)
    }

    @Test
    fun `every arrivals fetch through the client lands in the cache, a failed one doesn't`() = runBlocking {
        val cache = ArrivalsCache()
        val departures = listOf(departure(3))
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                if (stopId == "bad") throw TflException.Offline(null) else departures
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        val client = CachingTflClient(tfl, cache, clock = { now })
        assertEquals(departures, client.arrivals("940GZZLUOXC"))
        assertEquals(ArrivalsCache.Entry(departures, now), cache.get("940GZZLUOXC", now))
        runCatching { client.arrivals("bad") }
        assertNull(cache.get("bad", now))
    }

    @Test
    fun `an entry dated after the clock, set back since, is never handed out`() {
        val cache = ArrivalsCache()
        cache.put("940GZZLUOXC", listOf(departure(3)), now)
        assertNull(cache.get("940GZZLUOXC", now.minusSeconds(30)))
        assertNull(cache.recent("940GZZLUOXC", now.minusSeconds(30)))
    }

    @Test
    fun `a stop another client could answer differently isn't kept`() = runBlocking {
        val cache = ArrivalsCache()
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> = listOf(departure(3))
            override fun shareable(stopId: String) = stopId != "910GEXAMPLE"
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        val client = CachingTflClient(tfl, cache, clock = { now })
        client.arrivals("910GEXAMPLE")
        client.arrivals("940GZZLUOXC")
        assertNull(cache.get("910GEXAMPLE", now))
        assertEquals(1, cache.get("940GZZLUOXC", now)?.departures?.size)
    }

    @Test
    fun `a fetch is kept at when it was asked for, so a slow older one can't pass for newer`() = runBlocking {
        val cache = ArrivalsCache()
        var clock = now
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                // The answer takes 20 s to come back.
                clock = clock.plusSeconds(20)
                return listOf(departure(3))
            }
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        CachingTflClient(tfl, cache, clock = { clock }).arrivals("940GZZLUOXC")
        assertEquals(now, cache.get("940GZZLUOXC", clock)?.fetchedAt)
    }

    @Test
    fun `a fetch asked for before the cache was cleared isn't kept`() {
        val cache = ArrivalsCache()
        val asked = cache.generation
        cache.clear()
        cache.put("940GZZLUOXC", listOf(departure(3)), now, generation = asked)
        assertNull(cache.get("940GZZLUOXC", now))
    }

    @Test
    fun `a fetch whose source changed while it was out isn't kept`() = runBlocking {
        val cache = ArrivalsCache()
        var railKey = false
        val tfl = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                // The National Rail key is added while the request is out.
                railKey = true
                return listOf(departure(3))
            }
            override fun shareable(stopId: String) = !railKey
            override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> = emptyList()
            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> = emptyList()
        }
        CachingTflClient(tfl, cache, clock = { now }).arrivals("910GEXAMPLE")
        assertNull(cache.get("910GEXAMPLE", now))
    }

    @Test
    fun `arrivals from another source aren't handed out, however the key changed`() {
        val cache = ArrivalsCache()
        // Kept without National Rail times (a widget refresh, say); a reader with a key set doesn't take them.
        cache.put("910GEXAMPLE", listOf(departure(3)), now, source = false)
        assertNull(cache.get("910GEXAMPLE", now, source = true))
        assertEquals(1, cache.get("910GEXAMPLE", now, source = false)?.departures?.size)
    }
}
