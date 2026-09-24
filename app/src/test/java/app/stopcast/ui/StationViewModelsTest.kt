package app.stopcast.ui

import androidx.lifecycle.SavedStateHandle
import app.stopcast.domain.IndexedStation
import app.stopcast.domain.LineRef
import app.stopcast.domain.StationIndex
import app.stopcast.domain.StationFinder
import app.stopcast.domain.StationMatch
import app.stopcast.domain.StopLocation
import app.stopcast.domain.TflException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StationViewModelsTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeFinder(
        val search: suspend (String) -> List<StationMatch> = { emptyList() },
        val stops: suspend (String) -> List<StopLocation> = { emptyList() },
    ) : StationFinder {
        val queries = mutableListOf<String>()
        override suspend fun searchStations(query: String): List<StationMatch> {
            queries += query
            return search(query)
        }
        override suspend fun stationStops(id: String): List<StopLocation> = stops(id)
    }

    private val oxford = StationMatch("940GZZLUOXC", "Oxford Circus", listOf("tube"))

    private fun searchVm(finder: StationFinder, saved: SavedStateHandle = SavedStateHandle()) =
        StationSearchViewModel(finder, saved, io = dispatcher, debounceMillis = 300)

    @Test
    fun `a query restored after process death is kept and searched again`() = runTest {
        val first = SavedStateHandle()
        searchVm(FakeFinder(), first).onQueryChange("oxford")
        val finder = FakeFinder(search = { listOf(oxford) })
        val restored = searchVm(finder, SavedStateHandle(mapOf("query" to first.get<String>("query"))))
        assertEquals("oxford", restored.state.value.query)
        advanceUntilIdle()
        assertEquals(listOf("oxford"), finder.queries)
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), restored.state.value.result)
    }

    @Test
    fun `typing searches once, after the pause, for the trimmed query`() = runTest {
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange("ox")
        advanceTimeBy(100)
        vm.onQueryChange("oxf ")
        advanceTimeBy(299)
        runCurrent()
        assertTrue("no request before the pause", finder.queries.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("oxf"), finder.queries)
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), vm.state.value.result)
        assertFalse(vm.state.value.searching)
    }

    @Test
    fun `closing the search forgets the query and cancels a pending request`() = runTest {
        val saved = SavedStateHandle()
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder, saved)
        vm.onQueryChange("oxford")
        advanceTimeBy(100)
        vm.clear()
        advanceUntilIdle()
        assertTrue("the pending search never ran", finder.queries.isEmpty())
        assertEquals(StationSearchViewModel.State(), vm.state.value)
        assertEquals(null, saved.get<String>("query"))
    }

    private val kingsCross = IndexedStation("HUBKGX", "King's Cross St. Pancras", listOf("tube"))

    @Test
    fun `the bundled index answers at once, before TfL`() = runTest {
        val finder = FakeFinder(search = { listOf(StationMatch("490000000001A", "Kings Road", listOf("bus"))) })
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, debounceMillis = 300)
        vm.onQueryChange("kx")
        runCurrent()
        assertEquals(listOf("HUBKGX"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.id })
        assertTrue("TfL's search is still to come", vm.state.value.searching)
        advanceUntilIdle()
        assertEquals(listOf("kx"), finder.queries)
    }

    @Test
    fun `a failed TfL search keeps the index's matches and says what's missing`() = runTest {
        val finder = FakeFinder(search = { throw TflException.Offline(null) })
        val vm = StationSearchViewModel(finder, loadIndex = { StationIndex(listOf(kingsCross)) }, io = dispatcher, debounceMillis = 300)
        vm.onQueryChange("kings")
        advanceUntilIdle()
        val result = vm.state.value.result as StationSearchViewModel.Result.Matches
        assertEquals(listOf("HUBKGX"), result.matches.map { it.id })
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, result.remoteFailure)
    }

    @Test
    fun `a query under two characters doesn't search`() = runTest {
        val finder = FakeFinder(search = { listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange(" o ")
        advanceUntilIdle()
        assertTrue(finder.queries.isEmpty())
        assertEquals(StationSearchViewModel.Result.Idle, vm.state.value.result)
    }

    @Test
    fun `no matches is its own answer, not an error`() = runTest {
        val vm = searchVm(FakeFinder(search = { emptyList() }))
        vm.onQueryChange("zzz")
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.NoMatches, vm.state.value.result)
    }

    @Test
    fun `a failed search says why, and retry searches again`() = runTest {
        var fail = true
        val finder = FakeFinder(search = { if (fail) throw TflException.Offline(null) else listOf(oxford) })
        val vm = searchVm(finder)
        vm.onQueryChange("oxford")
        advanceUntilIdle()
        assertEquals(
            StationSearchViewModel.Result.Failed(DeparturesUiState.Error.Kind.OFFLINE),
            vm.state.value.result,
        )
        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(StationSearchViewModel.Result.Matches(listOf(oxford)), vm.state.value.result)
    }

    @Test
    fun `the previous matches stay up while the next search runs`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val finder = FakeFinder(search = { q -> if (q == "oxfo") gate.await(); listOf(oxford.copy(name = q)) })
        val vm = searchVm(finder)
        vm.onQueryChange("oxf")
        advanceUntilIdle()
        vm.onQueryChange("oxfo")
        runCurrent()
        assertTrue("marked searching during the typing pause, not only after it", vm.state.value.searching)
        advanceTimeBy(301)
        runCurrent()
        assertTrue(vm.state.value.searching)
        assertEquals(listOf("oxf"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.name })
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("oxfo"), (vm.state.value.result as StationSearchViewModel.Result.Matches).matches.map { it.name })
    }

    @Test
    fun `a station resolves to its stops as departure seeds`() = runTest {
        val stop = StopLocation(
            id = "940GZZLUOXC",
            name = "Oxford Circus",
            latitude = 0.0,
            longitude = 0.0,
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
            clusterId = "940GZZLUOXC",
        )
        val vm = StationStopsViewModel(FakeFinder(stops = { listOf(stop) }), "940GZZLUOXC", io = dispatcher)
        assertEquals(StationStopsViewModel.State.Loading, vm.state.value)
        advanceUntilIdle()
        val ready = vm.state.value as StationStopsViewModel.State.Ready
        assertEquals(listOf("940GZZLUOXC"), ready.stops.map { it.id })
        assertEquals(listOf("victoria"), ready.stops.single().lines.map { it.id })
    }

    @Test
    fun `a station with no departure stops says so, and a failed lookup can retry`() = runTest {
        assertEquals(
            StationStopsViewModel.State.NoStops,
            StationStopsViewModel(FakeFinder(), "HUBEXA", io = dispatcher).also { advanceUntilIdle() }.state.value,
        )
        var fail = true
        val finder = FakeFinder(stops = {
            if (fail) throw TflException.RateLimited(null) else emptyList()
        })
        val vm = StationStopsViewModel(finder, "HUBEXA", io = dispatcher)
        advanceUntilIdle()
        assertEquals(
            StationStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.RATE_LIMITED),
            vm.state.value,
        )
        fail = false
        vm.retry()
        advanceUntilIdle()
        assertEquals(StationStopsViewModel.State.NoStops, vm.state.value)
    }

    @Test
    fun `modes read as a rider says them`() {
        assertEquals("Tube · Elizabeth line · National Rail", modesLabel(listOf("tube", "elizabeth-line", "national-rail")))
        assertEquals("Some mode", modesLabel(listOf("some-mode", "")))
    }
}
