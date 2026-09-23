package app.stopcast.ui

import app.stopcast.domain.Coordinates
import app.stopcast.domain.LineRef
import app.stopcast.domain.LocationFix
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

    // The fresh (non-fallback) fix the fakes hand back for `origin`, with no accuracy/provider/age
    // supplied — what the state retains for a bug report. Matches FakeLocation/MutableLocation's
    // default (isFallback = false, the rest null).
    private val originFix = LocationFix(origin, isFallback = false)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A location provider that hands back a fixed fix (or null for "no fix"); [isFallback] marks
     *  it a low-confidence last-known fix (a fresh fix failed). */
    private class FakeLocation(
        private val fix: Coordinates?,
        private val isFallback: Boolean = false,
    ) : LocationProvider {
        override suspend fun current(forceFresh: Boolean): LocationFix? =
            fix?.let { LocationFix(it, isFallback = isFallback) }
    }

    /** A location provider whose fix (and its confidence) can change between calls — for re-locate
     *  on refresh. It records the last [forceFresh] it was asked for, so a test can assert relocate
     *  forces a fresh fix (a cached one would re-resolve for the previous position). */
    private class MutableLocation(var fix: Coordinates?, var isFallback: Boolean = false) : LocationProvider {
        var lastForceFresh: Boolean? = null
        override suspend fun current(forceFresh: Boolean): LocationFix? {
            lastForceFresh = forceFresh
            return fix?.let { LocationFix(it, isFallback = isFallback) }
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
        assertEquals(listOf("b1", "b2"), ready.eagerStops.map { it.id })
        assertEquals(listOf(LineRef("bus-b1", "b1", "bus")), ready.eagerStops.first().lines)
        // Distance from the fix is carried (in memory, from #36) so the departures list can
        // collapse a line served by adjacent stops down to its nearest.
        assertEquals(setOf("b1", "b2"), ready.distanceMeters.keys)
        assertTrue(ready.distanceMeters.getValue("b1") < ready.distanceMeters.getValue("b2"))
        assertEquals(0.0, finder.lastLatitude!!, 0.0)
        // The exact fix is retained alongside the distances (in memory) so the consent-gated bug
        // report files the coordinate and the distances from one and the same fix.
        assertEquals(originFix, ready.location)
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
        assertTrue("the Tube stop survives the near buses", ready.eagerStops.any { it.id == "tube" })
        assertTrue("the near buses are still shown", ready.eagerStops.any { it.id == "bus1" })
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
        // per-mode cap, so it sits in the `more` tier (paged in by a "More" tap), not eager. (The
        // eager/`more` split itself is covered by NearbyClustersTest.)
        assertEquals(listOf("bus1", "bus2", "tube"), ready.eagerStops.map { it.id })
        assertTrue("bus3" !in ready.eagerStops.map { it.id })
        assertEquals(listOf("bus3"), ready.more.flatMap { c -> c.stops.map { it.id } })
        // Distances span both tiers, so a revealed `more` stop is collapsed and ordered like an eager one.
        assertTrue("bus3" in ready.distanceMeters)
    }

    @Test
    fun `a second locate cancels the first before it reaches TfL`() = runTest {
        // A quick double-tap: two locate() calls before the first resolves. The superseded
        // lookup must be canceled before it hits the finder, so only the live one runs and
        // a stale first result can't finish last and overwrite the newer state.
        var lookups = 0
        val finder = FakeFinder { lookups++; listOf(stop("s", 0.0, "bus")) }
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

        // Empty carries the fix it found nothing near, so a bug report from that gate can say where.
        assertEquals(NearbyStopsViewModel.State.Empty(originFix), model.state.value)
    }

    @Test
    fun `a rate-limited lookup fails by kind, not silently`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.RateLimited(null) })

        model.locate()
        advanceUntilIdle()

        // Failed carries the fix the lookup was made with (for the bug report), alongside the kind.
        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED, originFix),
            model.state.value,
        )
    }

    @Test
    fun `an offline lookup maps to the offline kind`() = runTest {
        val model = vm(FakeLocation(origin), FakeFinder { throw TflException.Offline(null) })

        model.locate()
        advanceUntilIdle()

        assertEquals(
            NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE, originFix),
            model.state.value,
        )
    }

    @Test
    fun `relocate swaps in the new nearby set (walked to the next stop)`() = runTest {
        var stops = listOf(stop("a", 50.0, "bus"))
        val model = vm(MutableLocation(origin), FakeFinder { stops })
        model.locate()
        advanceUntilIdle()
        assertEquals(listOf("a"), (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id })

        // Refresh after walking on: a different nearby set resolves and replaces the old one.
        stops = listOf(stop("b", 60.0, "tube"))
        model.relocate()
        advanceUntilIdle()

        assertEquals(
            listOf("b"),
            (model.state.value as NearbyStopsViewModel.State.Ready).eagerStops.map { it.id },
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

        assertEquals(NearbyStopsViewModel.State.Empty(originFix), model.state.value)
    }

    @Test
    fun `the resolved state retains the fix's confidence signals for a bug report`() = runTest {
        // The plumbing this adds: provider/accuracy/age travel with the fix into the state, so the
        // consent-gated bug report says how the on-screen fix was obtained (the input a future
        // accuracy/age gate needs — TODO). The state carries the fix whole, not just its coordinate.
        val fix = LocationFix(origin, isFallback = false, accuracyMeters = 42f, provider = "network", ageMillis = 0L)
        val location = object : LocationProvider {
            override suspend fun current(forceFresh: Boolean) = fix
        }
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })

        model.locate()
        advanceUntilIdle()

        val ready = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(fix, ready.location)
        assertEquals("network", ready.location.provider)
    }

    @Test
    fun `locate from a fallback fix shows the stops but flags the location approximate`() = runTest {
        // Cold start with only a stale last-known fix (a fresh fix failed — the Underground case):
        // the stops resolve, but the banner says the position is approximate (SPEC principle 2).
        val model = vm(FakeLocation(origin, isFallback = true), FakeFinder { listOf(stop("a", 50.0, "bus")) })

        model.locate()
        advanceUntilIdle()

        assertTrue(model.state.value is NearbyStopsViewModel.State.Ready)
        assertEquals(LocationBanner.APPROXIMATE, model.locationBanner.value)
    }

    @Test
    fun `relocate keeps the current set and flags update-failed on a fallback fix`() = runTest {
        // A good set is shown, then a refresh can only get a low-confidence fix: don't jump to it —
        // keep the set already shown and say the location couldn't update (SPEC *Finding stops*).
        val location = MutableLocation(origin)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        val shown = model.state.value as NearbyStopsViewModel.State.Ready
        assertEquals(null, model.locationBanner.value)

        location.isFallback = true
        var refreshed = false
        model.relocate(onSameSet = { refreshed = true })
        advanceUntilIdle()

        // The set is unchanged (not re-resolved to the stale fix), and the banner explains why.
        assertEquals(shown.clusterSetKey, (model.state.value as NearbyStopsViewModel.State.Ready).clusterSetKey)
        assertEquals(LocationBanner.UPDATE_FAILED, model.locationBanner.value)
        // The retained set's departures fetch still restarts in place: the caller canceled it before
        // re-locating, so keeping the set without re-fetching would hang a mid-load screen forever.
        assertTrue("keeping the set still re-fetches its departures", refreshed)
    }

    @Test
    fun `a fresh relocate clears the location banner`() = runTest {
        val location = MutableLocation(origin, isFallback = true)
        val model = vm(location, FakeFinder { listOf(stop("a", 50.0, "bus")) })
        model.locate()
        advanceUntilIdle()
        assertEquals(LocationBanner.APPROXIMATE, model.locationBanner.value)

        // A later refresh gets a fresh fix — the banner clears.
        location.isFallback = false
        model.relocate()
        advanceUntilIdle()

        assertEquals(null, model.locationBanner.value)
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
        assertEquals(listOf("a"), after.eagerStops.map { it.id })
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
