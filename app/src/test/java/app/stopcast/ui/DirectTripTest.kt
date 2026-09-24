package app.stopcast.ui

import app.stopcast.domain.DirectTrips
import app.stopcast.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/** The To… page's state narrowing (SPEC *Finding stops → From… To…*), on synthetic stops. */
class DirectTripTest {
    private val now = Instant.parse("2026-09-24T08:00:00Z")
    private val fresh = StopArrivals("FRESH", "Fresh", emptyList(), now.minusSeconds(30))
    private val aged = StopArrivals("AGED", "Aged", emptyList(), now.minusSeconds(3600))
    private val loaded = DeparturesUiState.Loaded(listOf(fresh, aged), fetchedAt = fresh.fetchedAt)

    @Test
    fun `an empty trip takes the oldest source stop's age`() {
        assertEquals(aged.fetchedAt, tripLoaded(loaded, emptyList()).fetchedAt)
    }

    @Test
    fun `a trip's stamp is its freshest kept stop's`() {
        val trip = tripLoaded(loaded, listOf(fresh))
        assertEquals(fresh.fetchedAt, trip.fetchedAt)
        assertEquals(listOf("FRESH"), trip.stops.map { it.stopId })
        // The fresh stop filtered out: the page is as old as what it shows.
        assertEquals(aged.fetchedAt, tripLoaded(loaded, listOf(aged)).fetchedAt)
    }

    @Test
    fun `an empty trip says no trips only when everything was checked`() {
        assertEquals(TripMessages(null, TripMessage.NONE), tripMessages(DirectTrips.Result(emptyList(), pending = false, unresolved = false)))
        assertEquals(TripMessages(null, TripMessage.CHECKING), tripMessages(DirectTrips.Result(emptyList(), pending = true, unresolved = true)))
        assertEquals(TripMessages(null, TripMessage.INCOMPLETE), tripMessages(DirectTrips.Result(emptyList(), pending = false, unresolved = true)))
    }

    @Test
    fun `a trip with stops carries its caveat as a notice`() {
        assertEquals(TripMessages(null, TripMessage.NONE), tripMessages(DirectTrips.Result(listOf(fresh), pending = false, unresolved = false)))
        assertEquals(TripMessage.CHECKING, tripMessages(DirectTrips.Result(listOf(fresh), pending = true, unresolved = false)).notice)
        assertEquals(TripMessage.INCOMPLETE, tripMessages(DirectTrips.Result(listOf(fresh), pending = false, unresolved = true)).notice)
    }
}
