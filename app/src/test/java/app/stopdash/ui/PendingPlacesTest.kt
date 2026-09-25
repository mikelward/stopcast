package app.stopdash.ui

import app.stopdash.domain.LineRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Where a cold load's still-loading stops show ([pendingPlaces], [pendingSlots]). */
class PendingPlacesTest {
    private val circle = LineRef("circle", "Circle", "tube")
    private val metropolitan = LineRef("metropolitan", "Metropolitan", "tube")

    @Test
    fun `a station's loading platforms make one card with every line`() {
        val places = pendingPlaces(
            listOf(
                StopRef("940GZZLUESQ1", "Euston Square", listOf(circle), clusterId = "940GZZLUESQ"),
                StopRef("940GZZLUESQ2", "Euston Square", listOf(circle, metropolitan), clusterId = "940GZZLUESQ"),
            ),
            shownPlaces = emptySet(),
            stopDistanceMeters = mapOf("940GZZLUESQ1" to 90.0, "940GZZLUESQ2" to 80.0),
        )
        assertEquals(1, places.size)
        assertEquals(80.0, places.single().place.meters, 0.0)
        assertEquals(listOf("circle", "metropolitan"), places.single().place.lines.map { it.id })
    }

    @Test
    fun `a place already on screen gets no loading card`() {
        val places = pendingPlaces(
            listOf(StopRef("940GZZLUESQ2", "Euston Square", clusterId = "940GZZLUESQ")),
            shownPlaces = setOf("940GZZLUESQ"),
            stopDistanceMeters = mapOf("940GZZLUESQ2" to 80.0),
        )
        assertEquals(emptyList<PendingPlace>(), places)
    }

    @Test
    fun `the near-me list leaves out a stop that isn't nearby`() {
        // A journey's farther origin is fetched with the list but shown on its card, not here.
        val places = pendingPlaces(
            listOf(StopRef("940GZZLUESQ", "Euston Square"), StopRef("940GZZLUWHM", "West Ham")),
            shownPlaces = emptySet(),
            stopDistanceMeters = mapOf("940GZZLUESQ" to 80.0),
        )
        assertEquals(listOf("Euston Square"), places.map { it.place.name })
    }

    @Test
    fun `on the watched list a loading card has no distance and goes to the foot`() {
        val places = pendingPlaces(listOf(StopRef("940GZZLUESQ", "Euston Square")), emptySet(), emptyMap())
        assertFalse(places.single().distanced)
        assertEquals(listOf(2), pendingSlots(listOf(null, null), listOf(false, false), places))
    }

    @Test
    fun `a loading card goes where its distance puts it, below the starred band`() {
        fun at(meters: Double) = pendingPlaces(listOf(StopRef("s$meters", "Stop")), emptySet(), mapOf("s$meters" to meters)).single()
        // A starred place far off leads; then places at 100 m and 300 m.
        val meters = listOf(900.0, 100.0, 300.0)
        val pinned = listOf(true, false, false)
        assertEquals(
            listOf(1, 2, 3),
            pendingSlots(meters, pinned, listOf(at(50.0), at(200.0), at(1_000.0))),
        )
    }

    @Test
    fun `a loading card never splits a starred station's groups`() {
        val near = pendingPlaces(listOf(StopRef("s", "Stop")), emptySet(), mapOf("s" to 50.0)).single()
        // A starred station's two platform groups, both in the starred band, then a place at 300 m.
        assertEquals(listOf(2), pendingSlots(listOf(900.0, 900.0, 300.0), listOf(true, true, false), listOf(near)))
    }

    @Test
    fun `a hidden mode leaves the loading cards too`() {
        val bus = LineRef("73", "73", "bus")
        val shown = visiblePending(
            listOf(
                StopRef("490000001A", "Example Road", listOf(bus)),
                StopRef("940GZZLUESQ", "Euston Square", listOf(circle, bus)),
            ),
            setOf("bus"),
        )
        assertEquals(listOf("Euston Square"), shown.map { it.name })
        assertEquals(listOf("circle"), shown.single().lines.map { it.id })
    }

    @Test
    fun `a stop still loading counts as reached for the farther cards`() {
        val partial = DeparturesUiState.Loaded(
            stops = listOf(app.stopdash.domain.StopArrivals("A", "A", emptyList(), java.time.Instant.EPOCH)),
            fetchedAt = java.time.Instant.EPOCH,
            pendingStops = listOf(StopRef("B", "B")),
            statusPending = true,
        )
        assertEquals(setOf("A", "B"), reachedStopIds(partial))
        // Once the batch is whole, only what came back counts: a failed stop's lines keep their cards.
        assertEquals(setOf("A"), reachedStopIds(partial.copy(pendingStops = emptyList(), statusPending = false)))
        assertEquals(null, reachedStopIds(DeparturesUiState.Loading))
    }
}
