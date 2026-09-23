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
}
