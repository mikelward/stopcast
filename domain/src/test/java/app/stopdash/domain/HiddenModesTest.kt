package app.stopdash.domain

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops and lines only. */
class HiddenModesTest {
    private fun stop(id: String, vararg modes: String) = StopLocation(
        id = id,
        name = id,
        latitude = 0.0,
        longitude = 0.0,
        lines = modes.map { LineRef("$it-$id", it, it) },
    )

    @Test
    fun `a stop serving only hidden modes is dropped, a mixed one keeps its other lines`() {
        val kept = HiddenModes.stops(
            listOf(stop("busOnly", "bus"), stop("mixed", "overground", "national-rail"), stop("tube", "tube")),
            setOf("bus", "national-rail"),
        )
        assertEquals(listOf("mixed", "tube"), kept.map { it.id })
        assertEquals(listOf("overground"), kept.first().lines.map { it.mode })
    }

    @Test
    fun `a stop with no lines has no mode to hide, and nothing hidden changes nothing`() {
        val bare = stop("bare")
        assertEquals(listOf(bare), HiddenModes.stops(listOf(bare), setOf("bus")))
        val stops = listOf(stop("a", "bus"))
        assertTrue(HiddenModes.stops(stops, emptySet()) === stops)
    }

    @Test
    fun `a hidden mode's rows go, a stop closure stays, and modes match whatever their case`() {
        val now = Instant.EPOCH
        fun row(mode: String, closure: String? = null) = DepartureRow(
            stopId = "490000001A", stopName = "Example", lineId = mode, lineName = mode, direction = "",
            directionKey = "", destination = "", mode = mode, upcoming = emptyList(), fetchedAt = now,
            stopDisruption = closure,
        )
        val rows = listOf(row("bus"), row("tube"), row("bus", closure = "Stop closed"))
        assertEquals(listOf("tube", "bus"), HiddenModes.rows(rows, setOf("BUS")).map { it.mode })
        assertEquals("Stop closed", HiddenModes.rows(rows, setOf("bus")).last().stopDisruption)
    }
}
