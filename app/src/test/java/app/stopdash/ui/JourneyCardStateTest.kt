package app.stopdash.ui

import app.stopdash.domain.Departure
import app.stopdash.domain.DepartureRows
import app.stopdash.domain.JourneyChange
import app.stopdash.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic stops only. */
class JourneyCardStateTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")

    private fun departure(to: String, inSeconds: Long) =
        Departure("example", "Example", "outbound", to, null, now.plusSeconds(inSeconds), "tube")

    @Test
    fun `a row split into direct and change trains is shown, and deduplicated, whole`() {
        val whole = DepartureRows.across(
            listOf(StopArrivals("KING", "King", listOf(departure("West End", 60), departure("North End", 300)), fetchedAt = now)),
            now,
        ).single()
        val direct = whole.copy(upcoming = whole.upcoming.filter { it.destination == "North End" }, destination = "North End")
        val change = whole.copy(upcoming = whole.upcoming.filter { it.destination == "West End" }, destination = "West End")
        val state = JourneyCardState.Trains(listOf(direct), changes = listOf(JourneyChange("FORK", "Fork", change)))
        val shown = state.shownRows.single()
        assertEquals(listOf("West End", "North End"), shown.upcoming.map { it.destination })
        // The near-me copy of that row, every departure on the card, isn't repeated below it.
        assertTrue(DepartureRows.withoutShownAbove(listOf(whole), state.shownRows).isEmpty())
    }
}
