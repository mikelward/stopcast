package app.trackmo.ui

import app.trackmo.domain.Coordinates
import app.trackmo.domain.LineRef
import app.trackmo.domain.LocationProvider
import app.trackmo.domain.StopFinder
import app.trackmo.domain.StopLocation
import app.trackmo.domain.TflException
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

    private fun vm(location: LocationProvider, finder: StopFinder, limit: Int = 5) =
        NearbyStopsViewModel(location = location, finder = finder, limit = limit, io = dispatcher)

    @Test
    fun `resolves the nearest stops, ranked and mapped to StopRefs`() = runTest {
        val stops = listOf(
            // Deliberately out of distance order; the closest is the last one.
            StopLocation("far", "Far", 0.0, 0.02),
            StopLocation("mid", "Mid", 0.0, 0.01),
            StopLocation("near", "Near", 0.0, 0.0, lines = listOf(LineRef("victoria", "Victoria", "tube"))),
        )
        val finder = FakeFinder { stops }
        val model = vm(FakeLocation(origin), finder)

        model.locate()
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state is NearbyStopsViewModel.State.Ready)
        val ready = state as NearbyStopsViewModel.State.Ready
        // Ranked nearest-first from the fix, not left in input order.
        assertEquals(listOf("near", "mid", "far"), ready.stops.map { it.id })
        // The stop's served lines are carried through so a suspended line still surfaces.
        assertEquals(listOf(LineRef("victoria", "Victoria", "tube")), ready.stops.first().lines)
        // Distance from the fix is carried (in memory) so the departures list can collapse a
        // line served by adjacent stops down to its nearest: the stop at the fix is ~0 m, the
        // others farther, in the same nearest-first order.
        assertEquals(setOf("near", "mid", "far"), ready.distanceMeters.keys)
        assertEquals(0.0, ready.distanceMeters.getValue("near"), 1.0)
        assertTrue(ready.distanceMeters.getValue("near") < ready.distanceMeters.getValue("mid"))
        assertTrue(ready.distanceMeters.getValue("mid") < ready.distanceMeters.getValue("far"))
        // The device fix was the query point.
        assertEquals(0.0, finder.lastLatitude!!, 0.0)
    }

    @Test
    fun `caps the result at the limit`() = runTest {
        val stops = (1..8).map { StopLocation("s$it", "Stop $it", 0.0, it * 0.001) }
        val model = vm(FakeLocation(origin), FakeFinder { stops }, limit = 3)

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(3, ready.stops.size)
        assertEquals(listOf("s1", "s2", "s3"), ready.stops.map { it.id })
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
