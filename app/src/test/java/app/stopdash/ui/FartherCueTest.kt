package app.stopdash.ui

import app.stopdash.domain.CollapsedPlaces
import org.junit.Assert.assertEquals
import org.junit.Test

class FartherCueTest {
    private val place = CollapsedPlaces.Place("station:FS", "FS", "Farther", 1_600.0, emptyList())
    private val open = FartherLoad.Open(emptyList(), mapOf("A" to 1_600.0, "B" to 1_650.0))

    @Test
    fun `a card reads by where it stands`() {
        assertEquals(FartherCue.TAP_TO_SEE, fartherCue(FartherCard(place), emptySet(), emptySet()))
        assertEquals(FartherCue.LOADING, fartherCue(FartherCard(place, FartherLoad.Loading), emptySet(), emptySet()))
        assertEquals(FartherCue.RETRY, fartherCue(FartherCard(place, FartherLoad.Failed), emptySet(), emptySet()))
    }

    @Test
    fun `an opened card waits, says nothing runs, or offers a retry when every stop failed`() {
        val card = FartherCard(place, open)
        assertEquals(FartherCue.LOADING, fartherCue(card, emptySet(), emptySet()))
        assertEquals("one failed, one still to come", FartherCue.LOADING, fartherCue(card, emptySet(), setOf("A")))
        assertEquals(FartherCue.RETRY, fartherCue(card, emptySet(), setOf("A", "B")))
        assertEquals(FartherCue.NO_DEPARTURES, fartherCue(card, setOf("B"), setOf("A")))
        // A notice in force says a stop is closed: the card says so rather than a dash.
        assertEquals(FartherCue.CLOSED, fartherCue(card, setOf("B"), setOf("A"), closedStopIds = setOf("A")))
    }

    @Test
    fun `only a card that can act is tappable`() {
        assertEquals(listOf(FartherCue.TAP_TO_SEE, FartherCue.RETRY), FartherCue.entries.filter { it.tappable })
    }
}
