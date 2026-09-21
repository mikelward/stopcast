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
        override suspend fun current(forceFresh: Boolean): Coordinates? = fix
    }

    /** A location provider whose fix can change between calls — for re-locate on refresh. It
     *  records the last [forceFresh] it was asked for, so a test can assert relocate forces a
     *  fresh fix (a cached one would re-resolve for the previous position). */
    private class MutableLocation(var fix: Coordinates?) : LocationProvider {
        var lastForceFresh: Boolean? = null
        override suspend fun current(forceFresh: Boolean): Coordinates? {
            lastForceFresh = forceFresh
            return fix
        }
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
        // The two nearest bus clusters are eager, nearest-first; the 2 km one is out of range.
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
        // The reported bug: many near bus stops and one Tube station a little farther. The Tube
        // must still be eager — per-mode selection keeps its nearest cluster (SPEC Finding stops).
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"), // the lone tube, within the outer radius
        )
        val model = vm(FakeLocation(origin), FakeFinder { stops })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertTrue("the Tube stop survives the near buses", ready.stops.any { it.id == "tube" })
        assertTrue("the near buses are still shown", ready.stops.any { it.id == "bus1" })
    }

    @Test
    fun `the eager set is capped to the nearest clusters per mode`() = runTest {
        val stops = listOf(
            stop("bus1", 40.0, "bus"),
            stop("bus2", 90.0, "bus"),
            stop("bus3", 150.0, "bus"),
            stop("tube", 700.0, "tube"),
        )
        val model = vm(FakeLocation(origin), FakeFinder { stops })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        // Eager is the two nearest bus clusters plus the lone tube — the third bus is beyond the
        // per-mode cap and is not surfaced (the "More" paging that would reach it is deferred; the
        // eager/`more` split itself is covered by NearbyClustersTest). Distances cover eager only.
        assertEquals(listOf("bus1", "bus2", "tube"), ready.stops.map { it.id })
        assertTrue("bus3" !in ready.stops.map { it.id })
        assertTrue("bus3" !in ready.distanceMeters)
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

    @Test
    fun `relocate swaps in the new nearby set (walked to the next stop)`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val model = vm(MutableLocation(origin), FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("a"), (model.state.value as NearbyStopsViewModel.State.Ready).stops.map { it.id })

        // Refresh after walking on: a different nearby set resolves and replaces the old one.
        stops = listOf(stop("b", 60.0, "tube"))
        model.relocate()
        advanceUntilIdle()

        assertEquals(
            listOf("b"),
            (model.state.value as NearbyStopsViewModel.State.Ready).stops.map { it.id },
        )
    }

    @Test
    fun `relocate surfaces a failed fix honestly, not the stale set`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)

        // A refresh whose fix can't be obtained must not leave the previous location's stops on
        // screen as if current (SPEC principles 1–2) — it reports NoLocation.
        location.fix = null
        model.relocate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.NoLocation, model.state.value)
    }

    @Test
    fun `relocate surfaces an out-of-range result as Empty, not the stale set`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val model = vm(MutableLocation(origin), FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)

        // Walked out of range: the lookup succeeds but finds nothing, so the honest "no stops
        // nearby" replaces the old set rather than showing a previous location's stops.
        stops = emptyList()
        model.relocate()
        advanceUntilIdle()

        assertEquals(NearbyStopsViewModel.State.Empty, model.state.value)
    }

    @Test
    fun `relocating is true while the fresh fix is in flight and false once it resolves`() = runTest {
        val model = vm(MutableLocation(origin), FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        // locate() uses the Locating gate, not the in-place indicator.
        assertEquals(false, model.relocating.value)

        // relocate flips the indicator on synchronously (before the fix runs) so the departures
        // screen's refresh spinner stays up for the whole re-locate, and clears once it resolves.
        model.relocate()
        assertEquals(true, model.relocating.value)
        advanceUntilIdle()
        assertEquals(false, model.relocating.value)
    }

    @Test
    fun `a locate that supersedes a relocate clears the in-flight indicator`() = runTest {
        val model = vm(MutableLocation(origin), FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()

        model.relocate()
        assertEquals(true, model.relocating.value)
        // A locate (e.g. a permission retry) cancels the relocate and shows its own gate, so the
        // in-place indicator must not stay stuck on.
        model.locate()
        assertEquals(false, model.relocating.value)
        advanceUntilIdle()
        assertEquals(false, model.relocating.value)
    }

    @Test
    fun `relocate applies fresh distances when the stop set is unchanged`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val before = model.state.value as NearbyStopsViewModel.State.Ready
        val beforeDistance = before.distanceMeters.getValue("a")

        // The rider moves ~10 m toward the stop; the stop ID is unchanged (same set), but the
        // distance changed — and the list picks each line's nearest stop and orders rows by
        // distanceMeters, so the fresh distance must be applied, not the previous position's.
        location.fix = Coordinates(10.0 / 111_320.0, 0.0)
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        val after = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(listOf("a"), after.stops.map { it.id })
        assertTrue("same set still refreshes departures in place", refreshed)
        assertTrue("the fresh, nearer distance replaces the old one", after.distanceMeters.getValue("a") < beforeDistance)
    }

    @Test
    fun `relocate refreshes the same set in place and forces a fresh fix`() = runTest {
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        // A first open may use the recent-cache fast path.
        assertEquals(false, location.lastForceFresh)
        val before = model.state.value as NearbyStopsViewModel.State.Ready

        // The fix confirms the same stops, so the callback (the departures refresh) fires and
        // the state is not re-emitted — the refresh is sequenced after the fix, not parallel.
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        // A re-locate forces a fresh fix (a cached one could re-resolve for the old position).
        assertEquals(true, location.lastForceFresh)
        assertTrue("same set → departures refreshed in place", refreshed)
        assertEquals(before, model.state.value)
    }

}
