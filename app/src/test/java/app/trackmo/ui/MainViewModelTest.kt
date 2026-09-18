package app.trackmo.ui

import app.trackmo.domain.Departure
import app.trackmo.domain.TflClient
import app.trackmo.domain.TflException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val seeds = listOf(
        StopRef("940GZZLUOXC", "Oxford Circus"),
        StopRef("940GZZLUKSX", "King's Cross St. Pancras"),
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun departure(lineId: String, lineName: String, offsetSeconds: Long) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = "inbound",
            destination = "Brixton",
            platform = null,
            expectedArrival = now.plusSeconds(offsetSeconds),
            mode = "tube",
        )

    private class FakeClient(val byStop: Map<String, Result<List<Departure>>>) : TflClient {
        override suspend fun arrivals(stopId: String): List<Departure> =
            byStop.getValue(stopId).getOrThrow()
    }

    private fun viewModel(client: TflClient, warn: (String) -> Unit = {}) =
        MainViewModel(client, seeds, clock = { now }, io = dispatcher, warn = warn)

    @Test
    fun `every stop succeeding yields one soonest-first Loaded snapshot`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(now, state.fetchedAt)
        assertEquals(false, state.partialRefresh)
        // The snapshot carries both stops as fetched; grouping into the soonest-first
        // list is the screen's job (recomputed from the clock), tested in DepartureRows.
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
    }

    @Test
    fun `one stop failing keeps the others and logs a sanitized reason`() = runTest(dispatcher) {
        val warnings = mutableListOf<String>()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            ),
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        // Only the stop that succeeded is in the snapshot, and it's flagged partial so
        // the screen can say so rather than pass an incomplete list off as complete.
        assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
        assertTrue(state.partialRefresh)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("940GZZLUKSX"))
    }

    @Test
    fun `a total failure after a load keeps the aged last-good snapshot`() = runTest(dispatcher) {
        var failing = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (failing) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }
        }
        val vm = viewModel(client)
        advanceUntilIdle()
        val loaded = vm.state.value
        assertTrue(loaded is DeparturesUiState.Loaded)
        loaded as DeparturesUiState.Loaded

        failing = true
        vm.refresh()
        advanceUntilIdle()

        // The refresh failed outright: the departures the user was reading stay on screen
        // (same aged data), but the failure is carried so the screen can say so rather
        // than pass stale rows off as fresh (SPEC D4 / principle 2).
        val kept = vm.state.value
        assertTrue(kept is DeparturesUiState.Loaded)
        kept as DeparturesUiState.Loaded
        assertEquals(loaded.stops, kept.stops)
        assertEquals(loaded.fetchedAt, kept.fetchedAt)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, kept.refreshFailure)
    }

    @Test
    fun `an empty seed yields an empty Loaded state, not a network error`() = runTest(dispatcher) {
        // No watched stops → nothing is fetched and nothing fails, so the screen shows an
        // empty list, not "Can't reach TfL" (TfL was never contacted).
        val vm = MainViewModel(FakeClient(emptyMap()), emptyList(), clock = { now }, io = dispatcher)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.stops.isEmpty())
        assertEquals(false, state.partialRefresh)
        assertNull(state.refreshFailure)
    }

    @Test
    fun `every stop failing on the first load maps the failure to an Error kind`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.RateLimited(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            DeparturesUiState.Error(DeparturesUiState.Error.Kind.RATE_LIMITED),
            vm.state.value,
        )
    }
}
