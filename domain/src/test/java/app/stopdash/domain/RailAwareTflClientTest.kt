package app.stopdash.domain

import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stop ids and codes only. */
class RailAwareTflClientTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")
    private fun departure(line: String, mode: String) =
        Departure(line, line, "", "Example", null, now.plusSeconds(60), mode)

    private val tfl = object : TflClient {
        val asked = mutableListOf<String>()
        override suspend fun arrivals(stopId: String): List<Departure> {
            asked += stopId
            return listOf(departure("overground-example", "overground"))
        }
        override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()
        override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
    }

    private class Board(var key: Boolean = true, var fail: Boolean = false) : RailBoardSource {
        val asked = mutableListOf<String>()
        override val available get() = key
        override suspend fun departures(crs: String): List<Departure> {
            asked += crs
            if (fail) throw TflException.Unreachable("HTTP 401", null)
            return listOf(Departure("great-example", "Great Example", "", "Far", "Platform 1", Instant.EPOCH, "national-rail"))
        }
    }

    private val codes = RailStationCodes(mapOf("EXAMPLE" to "EXA"))

    @Test
    fun `a rail station's National Rail departures join TfL's`() = runTest {
        val board = Board()
        val client = RailAwareTflClient(tfl, board, { codes })
        val lines = client.arrivals("910GEXAMPLE").map { it.lineId }
        assertEquals(listOf("overground-example", "great-example"), lines)
        assertEquals(listOf("EXA"), board.asked)
    }

    @Test
    fun `no key, no code, or not a rail station asks TfL alone`() = runTest {
        val board = Board(key = false)
        RailAwareTflClient(tfl, board, { codes }).arrivals("910GEXAMPLE")
        board.key = true
        RailAwareTflClient(tfl, board, { codes }).arrivals("910GUNKNOWN")
        RailAwareTflClient(tfl, board, { codes }).arrivals("940GZZLUEXA")
        assertTrue(board.asked.isEmpty())
    }

    @Test
    fun `a failed board is logged and the stop keeps its TfL departures`() = runTest {
        val warnings = mutableListOf<String>()
        val client = RailAwareTflClient(tfl, Board(fail = true), { codes }, warn = { warnings += it })
        assertEquals(listOf("overground-example"), client.arrivals("910GEXAMPLE").map { it.lineId })
        assertEquals(listOf("national rail board failed for stop 910GEXAMPLE: HTTP 401"), warnings)
    }

    @Test
    fun `two stops sharing a station code show its board under one, which keeps it while it asks`() = runTest {
        val board = Board()
        var clock = 0L
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val client = RailAwareTflClient(tfl, board, { shared }, elapsedMillis = { clock })
        fun List<Departure>.rail() = count { it.mode == "national-rail" }
        assertEquals(1, client.arrivals("910GTWINA").rail())
        assertEquals("the twin gets TfL's rows only, and asks for no board", 0, client.arrivals("910GTWINB").rail())
        assertEquals(listOf("TWN"), board.asked)
        // A later refresh: the owner asks again and keeps it; the board is fetched afresh.
        clock += 60_000L
        assertEquals(1, client.arrivals("910GTWINA").rail())
        assertEquals(0, client.arrivals("910GTWINB").rail())
        assertEquals(listOf("TWN", "TWN"), board.asked)
        // Once the owner has stopped asking, the twin takes over.
        clock += RailAwareTflClient.OWNER_IDLE_MILLIS
        assertEquals(1, client.arrivals("910GTWINB").rail())
    }

    @Test
    fun `an owner whose own TfL fetch fails lets its twin show the board`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        val failing = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> =
                if (stopId == "910GTWINA") throw TflException.Unreachable("HTTP 500", null) else tfl.arrivals(stopId)
        }
        val client = RailAwareTflClient(failing, board, { shared }, elapsedMillis = { 0L })
        assertTrue(runCatching { client.arrivals("910GTWINA") }.isFailure)
        assertEquals(1, client.arrivals("910GTWINB").count { it.mode == "national-rail" })
    }

    @Test
    fun `of two twins refreshed together, the one whose TfL fetch works shows the board`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // TWINA starts first and fails; TWINB's fetch works.
        val failing = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "910GTWINA") {
                    delay(20)
                    throw TflException.Unreachable("HTTP 500", null)
                }
                delay(10)
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(failing, board, { shared }, elapsedMillis = { 0L })
        suspend fun refresh(): Int = coroutineScope {
            val a = async { runCatching { client.arrivals("910GTWINA") } }
            val b = async { client.arrivals("910GTWINB") }
            assertTrue(a.await().isFailure)
            b.await().count { it.mode == "national-rail" }
        }
        assertEquals(1, refresh())
        assertEquals(1, refresh())
    }

    @Test
    fun `a twin that finishes first takes the board over when the standing owner's fetch fails`() = runTest {
        val board = Board()
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        var ownerFails = false
        val tflClient = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "910GTWINA") {
                    delay(20)
                    if (ownerFails) throw TflException.Unreachable("HTTP 500", null)
                } else {
                    delay(10)
                }
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(tflClient, board, { shared }, elapsedMillis = { 0L })
        suspend fun refresh(): Pair<Int?, Int> = coroutineScope {
            val a = async { runCatching { client.arrivals("910GTWINA") } }
            val b = async { client.arrivals("910GTWINB") }
            a.await().getOrNull()?.count { it.mode == "national-rail" } to b.await().count { it.mode == "national-rail" }
        }
        // TWINA becomes the owner.
        assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
        // Both refreshed together, the owner fetching slower: it keeps the board while its fetch works.
        assertEquals(1 to 0, refresh())
        // Its fetch fails: the twin, done first, waits to hear so and shows the board.
        ownerFails = true
        assertEquals(null to 1, refresh())
    }

    @Test
    fun `an idle owner asking again keeps its board, fetched once, when its twin asks too`() = runTest {
        val board = Board()
        var clock = 0L
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // The owner's TfL fetch is the slower of the two.
        val slowOwner = object : TflClient by tfl {
            override suspend fun arrivals(stopId: String): List<Departure> {
                delay(if (stopId == "910GTWINA") 20 else 10)
                return tfl.arrivals(stopId)
            }
        }
        val client = RailAwareTflClient(slowOwner, board, { shared }, elapsedMillis = { clock })
        assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
        clock += RailAwareTflClient.OWNER_IDLE_MILLIS
        val (a, b) = coroutineScope {
            val a = async { client.arrivals("910GTWINA") }
            val b = async { client.arrivals("910GTWINB") }
            a.await().count { it.mode == "national-rail" } to b.await().count { it.mode == "national-rail" }
        }
        assertEquals(1 to 0, a to b)
        assertEquals("one board request per refresh", listOf("TWN", "TWN"), board.asked)
    }

    @Test
    fun `a twin taking over from a failed owner shows the board the owner already asked for`() = runTest {
        val shared = RailStationCodes(mapOf("TWINA" to "TWN", "TWINB" to "TWN"))
        // The owner fails either after its twin's fetch is in, or before.
        for (ownerDelay in listOf(20L, 5L)) {
            val board = Board()
            var ownerFails = false
            val tflClient = object : TflClient by tfl {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (stopId == "910GTWINA") {
                        delay(ownerDelay)
                        if (ownerFails) throw TflException.Unreachable("HTTP 500", null)
                    } else {
                        delay(10)
                    }
                    return tfl.arrivals(stopId)
                }
            }
            val client = RailAwareTflClient(tflClient, board, { shared }, elapsedMillis = { 0L })
            assertEquals(1, client.arrivals("910GTWINA").count { it.mode == "national-rail" })
            ownerFails = true
            val b = coroutineScope {
                val a = async { runCatching { client.arrivals("910GTWINA") } }
                val b = async { client.arrivals("910GTWINB") }
                assertTrue(a.await().isFailure)
                b.await().count { it.mode == "national-rail" }
            }
            assertEquals("owner delay $ownerDelay", 1, b)
            assertEquals("owner delay $ownerDelay: one request per refresh", listOf("TWN", "TWN"), board.asked)
        }
    }

    @Test
    fun `each stop says why it has no National Rail times`() = runTest {
        val board = Board(key = false)
        val client = RailAwareTflClient(tfl, board, { codes })
        client.arrivals("910GEXAMPLE")
        assertEquals("no key: a key would give times", RailFeed.NO_KEY, client.railFeed("910GEXAMPLE"))
        client.arrivals("910GUNKNOWN")
        assertEquals("no code: a key would give nothing", null, client.railFeed("910GUNKNOWN"))
        client.arrivals("940GZZLUEXA")
        assertEquals(null, client.railFeed("940GZZLUEXA"))
        board.key = true
        board.fail = true
        client.arrivals("910GEXAMPLE")
        assertEquals("the board failed", RailFeed.UNAVAILABLE, client.railFeed("910GEXAMPLE"))
        board.fail = false
        client.arrivals("910GEXAMPLE")
        assertEquals("the board came back", RailFeed.LIVE, client.railFeed("910GEXAMPLE"))
    }

    @Test
    fun `an operator's name makes TfL's line id`() {
        assertEquals("great-northern", railLineId("Great Northern"))
        assertEquals("c2c", railLineId("c2c"))
        assertEquals("london-north-eastern-railway", railLineId("London North Eastern Railway"))
    }
}
