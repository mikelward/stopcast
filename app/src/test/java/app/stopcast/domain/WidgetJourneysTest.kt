package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic stop ids and lines only. */
class WidgetJourneysTest {
    private val hill = JourneyCall("b1", "Hill", null)
    private val dale = JourneyCall("b1", "Dale", null)
    private val town = JourneyCall("b2", "Town", null)

    @Test
    fun `a check that couldn't finish keeps what the journey knew`() {
        val memory = mapOf("j" to WidgetJourney("490000001A", setOf(hill), "j"))
        assertEquals(memory, WidgetJourneys.merge(memory, setOf("j"), emptyList()))
        // Incomplete: confirmed departures add, nothing is dropped.
        val incomplete = WidgetJourneyCheck("j", "490000001A", setOf(town))
        assertEquals(setOf(hill, town), WidgetJourneys.merge(memory, setOf("j"), listOf(incomplete)).getValue("j").calls)
    }

    @Test
    fun `a complete check drops a departure the route now rejects, keeping ones it didn't see`() {
        val memory = mapOf("j" to WidgetJourney("490000001A", setOf(hill, dale), "j"))
        // Dale is at the stop now and was judged not to call; Hill isn't running just now.
        val complete = WidgetJourneyCheck("j", "490000001A", confirmed = setOf(town), checked = setOf(dale, town))
        assertEquals(setOf(hill, town), WidgetJourneys.merge(memory, setOf("j"), listOf(complete)).getValue("j").calls)
    }

    @Test
    fun `a moved origin starts afresh, and an unstarred journey is dropped`() {
        val memory = mapOf(
            "j" to WidgetJourney("490000001A", setOf(hill), "j"),
            "gone" to WidgetJourney("490000002B", setOf(dale), "gone"),
        )
        val flipped = WidgetJourneyCheck("j", "490000003C", setOf(town))
        assertEquals(
            mapOf("j" to WidgetJourney("490000003C", setOf(town), "j")),
            WidgetJourneys.merge(memory, setOf("j"), listOf(flipped)),
        )
    }

    private val now = java.time.Instant.parse("2026-01-01T12:00:00Z")
    private fun stop(id: String, at: java.time.Instant = now) = StopArrivals(id, "Stop", emptyList(), at)

    @Test
    fun `apply keeps a starred pin no check covers, and drops an unstarred one with its journey-only stop`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A"), stop("490000002B")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(hill), "j"), WidgetJourney("490000002B", setOf(dale), "gone")),
            journeyOnlyStopIds = setOf("490000002B"),
        )
        val after = WidgetJourneys.apply(stored, WidgetJourneysReport(setOf("j"), emptyList()), emptyList())!!
        assertEquals(listOf(WidgetJourney("490000001A", setOf(hill), "j")), after.journeys)
        assertEquals(listOf("490000001A"), after.stops.map { it.stopId })
        assertEquals(emptySet<String>(), after.journeyOnlyStopIds)
    }

    @Test
    fun `apply drops a pin for the other direction before any check of the new one`() {
        val stored = DeparturesSnapshot(
            stops = listOf(stop("490000001A")),
            fetchedAt = now,
            journeys = listOf(WidgetJourney("490000001A", setOf(hill), "j", shownFrom = "490000001A")),
        )
        val report = WidgetJourneysReport(setOf("j"), emptyList(), mapOf("j" to "490000003C"))
        assertEquals(emptyList<WidgetJourney>(), WidgetJourneys.apply(stored, report, emptyList())!!.journeys)
    }

    @Test
    fun `apply doesn't pin a journey whose origin it has no copy of`() {
        val report = WidgetJourneysReport(setOf("j"), listOf(WidgetJourneyCheck("j", "490000001A", setOf(hill))))
        assertEquals(null, WidgetJourneys.apply(null, report, emptyList()))
        val stored = DeparturesSnapshot(stops = listOf(stop("490000002B")), fetchedAt = now)
        assertEquals(emptyList<WidgetJourney>(), WidgetJourneys.apply(stored, report, emptyList())!!.journeys)
    }
}
