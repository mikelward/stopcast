package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "near me now" stop selection (SPEC *Finding stops → Near me now*): all stops within
 * the inner ring, plus the nearest stop of each mode within the outer ring, distance-sorted,
 * no count cap. Coordinates are obviously-synthetic offsets from the origin (SPEC *Privacy*).
 */
class NearbySelectionTest {
    // A stop [meters] due north of the origin, serving one line of [mode], so distance is
    // controllable in meters (~111,320 m per degree of latitude).
    private fun stop(id: String, meters: Double, mode: String) = StopLocation(
        id = id,
        name = id,
        latitude = meters / 111_320.0,
        longitude = 0.0,
        lines = listOf(LineRef("$mode-$id", id, mode)),
    )

    private fun select(stops: List<StopLocation>) =
        NearbySelection.select(stops, latitude = 0.0, longitude = 0.0).map { it.id }

    @Test
    fun `includes every stop within the inner ring, distance-sorted`() {
        val stops = listOf(
            stop("b3", 300.0, "bus"),
            stop("b1", 40.0, "bus"),
            stop("b2", 150.0, "bus"),
        )
        assertEquals(listOf("b1", "b2", "b3"), select(stops))
    }

    @Test
    fun `excludes a stop beyond the outer ring`() {
        val stops = listOf(
            stop("near", 100.0, "bus"),
            stop("far", 2000.0, "bus"), // > ~1 mi
        )
        assertEquals(listOf("near"), select(stops))
    }

    @Test
    fun `reserves the nearest stop of a farther mode, so buses don't crowd out the Tube`() {
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"), // outside the inner ring, inside the outer
        )
        val ids = select(stops)
        assertTrue("the Tube is kept", "tube" in ids)
        assertTrue("the inner-ring buses are kept", ids.containsAll(listOf("bus1", "bus2", "bus3")))
        // Distance-sorted: the Tube sits after the near buses at its true distance.
        assertEquals(listOf("bus1", "bus2", "bus3", "tube"), ids)
    }

    @Test
    fun `reserves a river-bus pier as its own mode alongside nearer buses`() {
        // Piers are fetched now (NaptanFerryPort in DEFAULT_NEARBY_STOP_TYPES), and per-mode
        // coverage treats river-bus as any other mode: a pier beyond the inner ring is kept
        // rather than crowded out by nearer bus stops of a different mode.
        val stops = listOf(
            stop("bus1", 60.0, "bus"),
            stop("bus2", 120.0, "bus"),
            stop("pier", 650.0, "river-bus"), // outside the inner ring, inside the outer
        )
        val ids = select(stops)
        assertTrue("the pier is kept", "pier" in ids)
        assertEquals(listOf("bus1", "bus2", "pier"), ids)
    }

    @Test
    fun `does not add a second stop of a mode already covered by the inner ring`() {
        val stops = listOf(
            stop("bus1", 50.0, "bus"),
            stop("bus-far", 800.0, "bus"), // same mode, within outer, but bus is already covered
        )
        assertEquals(listOf("bus1"), select(stops))
    }

    @Test
    fun `expands to the nearest stop when nothing is in the inner ring`() {
        // Nearest is 500 m away — beyond the ~0.2 mi inner ring — so the ring expands to it
        // rather than returning an empty list.
        val stops = listOf(
            stop("a", 500.0, "bus"),
            stop("b", 900.0, "bus"),
        )
        assertEquals(listOf("a"), select(stops))
    }

    @Test
    fun `an interchange stop covers all its modes at once`() {
        val interchange = StopLocation(
            id = "hub", name = "hub", latitude = 40.0 / 111_320.0, longitude = 0.0,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("55", "55", "bus")),
        )
        val stops = listOf(
            interchange,
            stop("tube-far", 900.0, "tube"), // tube already covered by the interchange
            stop("bus-far", 900.0, "bus"), // bus already covered by the interchange
        )
        assertEquals(listOf("hub"), select(stops))
    }

    @Test
    fun `no stops yields an empty selection`() {
        assertEquals(emptyList<String>(), select(emptyList()))
    }
}
