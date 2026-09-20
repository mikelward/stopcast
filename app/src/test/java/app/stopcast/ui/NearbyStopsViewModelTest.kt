package app.stopcast.ui

import app.stopcast.domain.Coordinates
import app.stopcast.domain.LineRef
import app.stopcast.domain.LocationProvider
import app.stopcast.domain.StopFinder
import app.stopcast.domain.StopLocation
import app.stopcast.domain.TflException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NearbyStopsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    // Obviously-synthetic coordinates around the origin — never a real position (SPEC Privacy).
    private val origin = Coordinates(0.0, 0.0)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A location provider that hands back a fixed fix (or null for "no fix"). */
    private class FakeLocation(private val fix: Coordinates?) : LocationProvider {
        override suspend fun current(): Coordinates? = fix
    }

    /** A finder that returns a fixed list, or throws, and records the query it was given. */
    private class FakeFinder(
        private val supply: () -> List<StopLocation>,
    ) : StopFinder {
        var lastLatitude: Double? = null
        var lastLongitude: Double? = null
        var lastRadius: Int? = null

        override suspend fun nearbyStops(
            latitude: Double,
            longitude: Double,
            radiusMeters: Int,
            stopTypes: List<String>,
        ): List<StopLocation> {
            lastLatitude = latitude
            lastLongitude = longitude
            lastRadius = radiusMeters
            return supply()
        }
    }

    private fun vm(location: LocationProvider, finder: StopFinder) =
        NearbyStopsViewModel(location = location, finder = finder, io = dispatcher)

    // A stop [meters] due north of the origin (lon 0), so distance is controllable in meters.
    private fun stop(id: String, meters: Double, mode: String) = StopLocation(
        id = id,
        name = id,
        latitude = meters / 111_320.0, // ~meters per degree of latitude
        longitude = 0.0,
        lines = listOf(LineRef("$mode-$id", id, mode)),
    )

    @Test
    fun `shows the nearby stops within the rings, nearest-first with lines carried through`() = runTest {
        val stops = listOf(
            // Out of order; a bus stop beyond the ~1 mi outer ring is excluded.
            stop("far", 2000.0, "bus"),
            stop("b2", 200.0, "bus"),
            stop("b1", 50.0, "bus"),
        )
        val finder = FakeFinder { stops }
        val model = vm(FakeLocation(origin), finder)

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        // Both inner-ring bus stops, nearest-first; the 2 km one is out of range.
        assertEquals(listOf("b1", "b2"), ready.stops.map { it.id })
        assertEquals(listOf(LineRef("bus-b1", "b1", "bus")), ready.stops.first().lines)
        // Distance from the fix is carried (in memory, from #36) so the departures list can
        // collapse a line served by adjacent stops down to its nearest.
        assertEquals(setOf("b1", "b2"), ready.distanceMeters.keys)
        assertTrue(ready.distanceMeters.getValue("b1") < ready.distanceMeters.getValue("b2"))
        assertEquals(0.0, finder.lastLatitude!!, 0.0)
    }

    @Test
    fun `a farther mode is not crowded out by nearer stops of another mode`() = runTest {
        // The reported bug: many near bus stops and one Tube station a little farther. The
        // Tube must still appear — per-mode coverage reserves it (SPEC Finding stops).
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"), // beyond the inner ring, within the outer
        )
        val model = vm(FakeLocation(origin), FakeFinder { stops })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertTrue("the Tube stop survives the near buses", ready.stops.any { it.id == "tube" })
        assertTrue("the near buses are still shown", ready.stops.any { it.id == "bus1" })
    }

    @Test
    fun `a second locate cancels the first before it reaches TfL`() = runTest {
        // A quick double-tap: two locate() calls before the first resolves. The superseded
        // lookup must be canceled before it hits the finder, so only the live one runs and
        // a stale first result can't finish last and overwrite the newer state.
        var lookups = 0
        val finder = FakeFinder { lookups++; listOf(StopLocation("s", "S", 0.0, 0.0)) }
        val model = vm(FakeLocation(origin), finder)

        model.locate()
        model.locate()
        advanceUntilIdle()

        assertEquals(1, lookups)
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
    }

    @Test
    fun `no fix maps to NoLocation, not an empty list`() = runTest {
        val finder = FakeFinder { error("finder must not run without a fix") }
        val model = vm(FakeLocation(null), finder)

        model.locate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.NoLocation, model.state.value)
        // The lookup — the location-sending call — is never made without a position.
        assertEquals(null, finder.lastLatitude)
    }

    @Test
    fun `located but no stops nearby maps to Empty`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { emptyList() })

        model.locate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.Empty, model.state.value)
    }

    @Test
    fun `a rate-limited lookup fails by kind, not silently`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.RateLimited(null) })

        model.locate()
        advanceUntilIdle()

        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED),
            model.state.value,
        )
    }

    @Test
    fun `an offline lookup maps to the offline kind`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.Offline(null) })

        model.locate()
        advanceUntilIdle()

        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE),
            model.state.value,
        )
    }
}
