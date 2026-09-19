package app.trackmo.data

import app.trackmo.domain.Departure
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.LineRef
import app.trackmo.domain.StopArrivals
import app.trackmo.domain.StopDisruption
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedSnapshotTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun sample() = DeparturesSnapshot(
        stops = listOf(
            StopArrivals(
                stopId = "940GZZLUOXC",
                stopName = "Oxford Circus",
                departures = listOf(
                    Departure(
                        lineId = "victoria",
                        lineName = "Victoria",
                        direction = "inbound",
                        destination = "Brixton",
                        platform = "Platform 1",
                        expectedArrival = now.plusSeconds(180),
                        mode = "tube",
                    ),
                ),
                fetchedAt = now,
                lines = listOf(LineRef("victoria", "Victoria", "tube")),
                arrivalsFresh = true,
            ),
            StopArrivals(
                stopId = "940GZZLUKSX",
                stopName = "King's Cross St. Pancras",
                departures = emptyList(),
                fetchedAt = now.minusSeconds(600),
                lines = emptyList(),
                // A carried-from-prior stop: this flag must survive the round trip, since it
                // gates a status row's "No departures" claim.
                arrivalsFresh = false,
            ),
        ),
        fetchedAt = now,
    )

    @Test
    fun `round trips through the persisted form unchanged`() {
        val snapshot = sample()
        assertEquals(snapshot, snapshot.toPersisted().toDomain())
    }

    @Test
    fun `stop disruptions are not persisted, since a closure cannot be safely aged`() {
        val closed = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "940GZZLUOXC",
                    stopName = "Oxford Circus",
                    departures = emptyList(),
                    fetchedAt = now,
                    disruptions = listOf(StopDisruption("Station closed until further notice")),
                ),
            ),
            fetchedAt = now,
        )
        val restored = closed.toPersisted().toDomain()!!
        // The closure is dropped on the way to disk, so a cleared-since closure can't
        // resurrect on the next launch; everything else survives.
        assertEquals(emptyList<StopDisruption>(), restored.stops.single().disruptions)
        assertEquals(closed.stops.single().copy(disruptions = emptyList()), restored.stops.single())
    }

    @Test
    fun `a null platform survives the round trip`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "490000",
                    stopName = "A Bus Stop",
                    departures = listOf(
                        Departure("73", "73", "outbound", "Victoria", null, now.plusSeconds(60), "bus"),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()
        assertNull(restored!!.stops.single().departures.single().platform)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `the via branch survives the round trip`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(
                StopArrivals(
                    stopId = "940GZZLUEUS",
                    stopName = "Euston",
                    departures = listOf(
                        Departure(
                            "northern", "Northern", "outbound", "Battersea Power",
                            null, now.plusSeconds(120), "tube", branch = "Charing Cross",
                        ),
                    ),
                    fetchedAt = now,
                ),
            ),
            fetchedAt = now,
        )
        val restored = snapshot.toPersisted().toDomain()!!
        assertEquals("Charing Cross", restored.stops.single().departures.single().branch)
        assertEquals(snapshot, restored)
    }

    @Test
    fun `an unknown format version is discarded rather than mis-read`() {
        val fromFuture = sample().toPersisted().copy(version = PersistedSnapshot.CURRENT_VERSION + 1)
        assertNull(fromFuture.toDomain())
    }
}
