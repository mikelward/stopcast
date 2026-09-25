package app.stopdash.ui

import app.stopdash.domain.DirectTrips
import app.stopdash.domain.LineRef
import app.stopdash.domain.StopArrivals
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

    private fun stop(id: String, mode: String) = StopRef(id, id, lines = listOf(LineRef("l-$id", "L", mode)))

    @Test
    fun `a trip from here starts at the list's stops and any within 0_2 mi`() {
        val tube = stop("TUBE", "tube")
        val pole = stop("POLE", "bus")
        val farBus = stop("FARBUS", "bus")
        val distances = mapOf("TUBE" to 1200.0, "POLE" to 200.0, "FARBUS" to 900.0)
        assertEquals(
            listOf("POLE", "TUBE"),
            hereOriginIds(eager = listOf(tube), nearby = listOf(tube, pole, farBus), distanceMeters = distances, hidden = emptySet()),
        )
    }

    @Test
    fun `a trip from here leaves out hidden modes, and starts nowhere when all are hidden`() {
        val tube = stop("TUBE", "tube")
        val pole = stop("POLE", "bus")
        val distances = mapOf("TUBE" to 1200.0, "POLE" to 200.0)
        assertEquals(listOf("TUBE"), hereOriginIds(listOf(tube, pole), listOf(tube, pole), distances, setOf("bus")))
        assertEquals(emptyList<String>(), hereOriginIds(listOf(tube, pole), listOf(tube, pole), distances, setOf("bus", "tube")))
    }

    @Test
    fun `a stop with no routes is never an origin`() {
        val tube = stop("TUBE", "tube")
        val bare = StopRef("BARE", "BARE")
        val distances = mapOf("TUBE" to 1200.0, "BARE" to 100.0)
        assertEquals(listOf("TUBE"), hereOriginIds(listOf(tube), listOf(tube, bare), distances, emptySet()))
        assertEquals(emptyList<String>(), hereOriginIds(listOf(tube), listOf(tube, bare), distances, setOf("tube")))
    }

    @Test
    fun `a trip's tiers split each cluster into its origins and the places around them`() {
        fun loc(id: String) = app.stopdash.domain.StopLocation(id, id, 0.0, 0.0)
        val junction = app.stopdash.domain.NearbySelection.NearbyCluster("J", listOf(loc("J1"), loc("J2")), 50.0)
        val far = app.stopdash.domain.NearbySelection.NearbyCluster("F", listOf(loc("F1")), 900.0)
        val distances = mapOf("J1" to 50.0, "J2" to 80.0, "F1" to 900.0)
        val tiers = hereTripTiers(listOf(junction, far), setOf("J1"), distances)
        assertEquals(listOf(listOf("J1")), tiers.eager.map { c -> c.stops.map { it.id } })
        assertEquals(listOf(listOf("J2"), listOf("F1")), tiers.more.map { c -> c.stops.map { it.id } })
        assertEquals(listOf("J", "F"), tiers.more.map { it.key })
        assertEquals(distances, tiers.distanceMeters)
    }
}
