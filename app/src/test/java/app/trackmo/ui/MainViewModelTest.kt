package app.trackmo.ui

import app.trackmo.domain.Departure
import app.trackmo.domain.LineRef
import app.trackmo.domain.LineStatus
import app.trackmo.domain.StopDisruption
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
import org.junit.Assert.assertFalse
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

    private class FakeClient(
        val byStop: Map<String, Result<List<Departure>>>,
        val statuses: Result<List<LineStatus>> = Result.success(emptyList()),
        val disruptionsByStop: Map<String, Result<List<StopDisruption>>> = emptyMap(),
    ) : TflClient {
        var requestedLineIds: Collection<String>? = null

        override suspend fun arrivals(stopId: String): List<Departure> =
            byStop.getValue(stopId).getOrThrow()

        override suspend fun lineStatuses(lineIds: Collection<String>): List<LineStatus> {
            requestedLineIds = lineIds
            return statuses.getOrThrow()
        }

        override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
            (disruptionsByStop[stopId] ?: Result.success(emptyList())).getOrThrow()
    }

    private fun status(lineId: String, severity: Int, description: String) =
        LineStatus(lineId = lineId, severity = severity, description = description)

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
        // A genuine total failure: BOTH the arrivals and the (now-decoupled) disruption
        // request fail, so nothing fresh comes back at all. If only arrivals failed while
        // disruption succeeded, that would be a partial refresh, not this.
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (failing) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (failing) throw TflException.Offline(null)
                return emptyList()
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
        // (same aged data, at the same age), but they are now flagged not-fresh and the
        // failure is carried so the screen can say so rather than pass stale rows off as
        // fresh (SPEC D4 / principle 2).
        val kept = vm.state.value
        assertTrue(kept is DeparturesUiState.Loaded)
        kept as DeparturesUiState.Loaded
        assertEquals(loaded.stops.map { it.departures }, kept.stops.map { it.departures })
        assertEquals(loaded.fetchedAt, kept.fetchedAt)
        assertTrue(kept.stops.none { it.arrivalsFresh })
        assertEquals(false, kept.partialRefresh)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, kept.refreshFailure)
    }

    @Test
    fun `a disrupted line is carried in the snapshot, a good-service line is not`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Victoria has severe delays; Northern is running well — only the
                // disruption is carried, so a non-null row status always means "flag it".
                statuses = Result.success(
                    listOf(
                        status("victoria", 6, "Severe Delays"),
                        status("northern", LineStatus.GOOD_SERVICE, "Good Service"),
                    ),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), state.lineStatuses.keys)
        assertEquals("Severe Delays", state.lineStatuses.getValue("victoria").description)
        assertEquals(false, state.disruptionUnknown)
    }

    @Test
    fun `a line TfL returned no status for is treated as unknown, not clean`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Victoria came back disrupted; Northern's status is absent (the client
                // drops a line with no entries). That undetermined line must flag the
                // screen "status unknown" rather than let Northern read as verified-clean.
                statuses = Result.success(listOf(status("victoria", 6, "Severe Delays"))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), state.lineStatuses.keys)
        assertTrue(state.disruptionUnknown)
    }

    @Test
    fun `fetches status for declared lines and carries them, so a no-prediction line is known`() =
        runTest(dispatcher) {
            val client = FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    // King's Cross returns no arrivals for its declared Circle line.
                    "940GZZLUKSX" to Result.success(emptyList()),
                ),
                statuses = Result.success(listOf(status("circle", 2, "Suspended"))),
            )
            val vm = MainViewModel(
                client,
                listOf(
                    StopRef("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube"))),
                    StopRef("940GZZLUKSX", "King's Cross St. Pancras", listOf(LineRef("circle", "Circle", "tube"))),
                ),
                clock = { now },
                io = dispatcher,
            )
            advanceUntilIdle()

            // Circle has no prediction, but as a declared line it's still status-checked —
            // that's what lets it surface as a status row rather than vanish.
            assertTrue(client.requestedLineIds!!.contains("circle"))
            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals("Suspended", state.lineStatuses["circle"]?.description)
            // The declared lines travel through to the snapshot for the screen's grouping.
            assertEquals(
                listOf("circle"),
                state.stops.single { it.stopId == "940GZZLUKSX" }.lines.map { it.id },
            )
        }

    @Test
    fun `a departure with a blank line id leaves the disruption state unknown`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    // TfL gave this prediction no line id, so its disruption can't be
                    // checked — it must not read as verified-clean.
                    "940GZZLUKSX" to Result.success(listOf(departure("", "", 120))),
                ),
                // Victoria comes back good, so the only reason for unknown is the blank id.
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.lineStatuses.isEmpty())
        assertTrue(state.disruptionUnknown)
    }

    @Test
    fun `a failed status lookup flags disruptionUnknown but keeps the arrivals`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                statuses = Result.failure(TflException.Offline(null)),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        // Arrivals still shown — a failed disruption check must not blank them — but the
        // screen is told their status is unknown rather than passing them off as clean.
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
        assertTrue(state.lineStatuses.isEmpty())
        assertTrue(state.disruptionUnknown)
    }

    @Test
    fun `carries a stop's own disruptions into the snapshot`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(emptyList()),
                ),
                disruptionsByStop = mapOf(
                    "940GZZLUKSX" to Result.success(listOf(StopDisruption("Station closed until further notice"))),
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(
            listOf("Station closed until further notice"),
            state.stops.single { it.stopId == "940GZZLUKSX" }.disruptions.map { it.description },
        )
    }

    @Test
    fun `a failed disruption refresh drops the prior closure rather than showing it stale`() =
        runTest(dispatcher) {
            var disruptionFails = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String) =
                    listOf(departure("victoria", "Victoria", 300))

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    if (disruptionFails) throw TflException.Offline(null)
                    return if (stopId == "940GZZLUOXC") {
                        listOf(StopDisruption("Station closed until further notice"))
                    } else {
                        emptyList()
                    }
                }
            }
            val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
            advanceUntilIdle()
            val first = vm.state.value
            assertTrue(first is DeparturesUiState.Loaded)
            first as DeparturesUiState.Loaded
            assertEquals(
                listOf("Station closed until further notice"),
                first.stops.single { it.stopId == "940GZZLUOXC" }.disruptions.map { it.description },
            )

            // Next refresh: arrivals still succeed, but the disruption fetch fails. The stale
            // closure is dropped, not shown beside a fresh arrivals stamp; the disruption
            // state is flagged unknown instead (SPEC principle 1).
            disruptionFails = true
            vm.refresh()
            advanceUntilIdle()
            val second = vm.state.value
            assertTrue(second is DeparturesUiState.Loaded)
            second as DeparturesUiState.Loaded
            assertTrue(second.stops.single { it.stopId == "940GZZLUOXC" }.disruptions.isEmpty())
            assertTrue(second.disruptionUnknown)
        }

    @Test
    fun `a failed stop-disruption lookup flags disruptionUnknown, keeping the arrivals`() = runTest(dispatcher) {
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                    "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                ),
                // Oxford Circus's stop-disruption lookup fails — we can't verify it isn't
                // closed, so the departures stay but the state is flagged unknown.
                disruptionsByStop = mapOf("940GZZLUOXC" to Result.failure(TflException.Offline(null))),
            ),
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
        assertTrue(state.disruptionUnknown)
    }

    @Test
    fun `known-empty stops with failed disruption lookups render empty, not an error`() =
        runTest(dispatcher) {
            // Arrivals succeed but return no departures; the disruption lookups fail. TfL was
            // reached and genuinely showed nothing, so this is an honest empty/unknown state,
            // not a whole-screen network error (SPEC principle 1).
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String) = emptyList<Departure>()

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    throw TflException.Offline(null)
                }
            }
            val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId })
            assertTrue(state.disruptionUnknown)
            assertNull(state.refreshFailure)
        }

    @Test
    fun `stamps the snapshot from the start of the fetch, not after the request chain`() =
        runTest(dispatcher) {
            val start = now
            var current = start
            // Each request "takes" time, advancing the clock — as a slow TfL or many
            // stops would. The stamp must reflect the start, or the oldest departures read
            // as just-updated and the stale cutoff slips by the whole chain (SPEC D4).
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    current = current.plusSeconds(30)
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    current = current.plusSeconds(30)
                    return emptyList()
                }
            }
            val vm = MainViewModel(client, seeds, clock = { current }, io = dispatcher)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(start, state.fetchedAt)
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

    @Test
    fun `a stop that fails to refresh keeps its aged rows while a fresh stop updates`() =
        runTest(dispatcher) {
            var current = now
            var failKsx = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (stopId == "940GZZLUKSX" && failKsx) throw TflException.Offline(null)
                    return listOf(departure("victoria", "Victoria", 120))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = MainViewModel(client, seeds, clock = { current }, io = dispatcher)
            advanceUntilIdle()
            val first = vm.state.value
            assertTrue(first is DeparturesUiState.Loaded)
            first as DeparturesUiState.Loaded
            // Both stops fetched at the first load's time.
            assertEquals(setOf(now), first.stops.map { it.fetchedAt }.toSet())

            // Time passes; on the next refresh KSX fails while Oxford Circus succeeds.
            current = now.plusSeconds(120)
            failKsx = true
            vm.refresh()
            advanceUntilIdle()

            val merged = vm.state.value
            assertTrue(merged is DeparturesUiState.Loaded)
            merged as DeparturesUiState.Loaded
            val ageByStop = merged.stops.associate { it.stopId to it.fetchedAt }
            // Oxford Circus refreshed to the new time; King's Cross kept its aged rows at
            // the old time rather than vanishing — the drop-on-partial-refresh class the
            // per-stop snapshot deletes (SPEC D4 / principle 2).
            assertEquals(now.plusSeconds(120), ageByStop.getValue("940GZZLUOXC"))
            assertEquals(now, ageByStop.getValue("940GZZLUKSX"))
            assertTrue(merged.partialRefresh)
            // The whole-screen stamp is the freshest stop's age.
            assertEquals(now.plusSeconds(120), merged.fetchedAt)
        }

    @Test
    fun `every arrivals request failing on first load errors even when stops declare lines`() =
        runTest(dispatcher) {
            // The production seed stops declare lines. If every arrivals request fails on a
            // first load, the stops must NOT be kept on their lines alone and shown as "no
            // departures" — the offline/rate-limit failure has to surface (SPEC principle 1).
            val seedsWithLines = listOf(
                StopRef("940GZZLUOXC", "Oxford Circus", listOf(LineRef("victoria", "Victoria", "tube"))),
                StopRef("940GZZLUKSX", "King's Cross St. Pancras", listOf(LineRef("circle", "Circle", "tube"))),
            )
            val client = FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            )
            val vm = MainViewModel(client, seedsWithLines, clock = { now }, io = dispatcher)
            advanceUntilIdle()

            assertEquals(
                DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE),
                vm.state.value,
            )
        }

    @Test
    fun `a total failure preserves a prior partial-refresh warning`() = runTest(dispatcher) {
        var oxcFails = false
        var allFail = false
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (allFail || (oxcFails && stopId == "940GZZLUOXC")) throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                if (allFail) throw TflException.Offline(null)
                return emptyList()
            }
        }
        // A partial refresh first: Oxford Circus fails, King's Cross succeeds.
        oxcFails = true
        val vm = MainViewModel(client, seeds, clock = { now }, io = dispatcher)
        advanceUntilIdle()
        val partial = vm.state.value
        assertTrue(partial is DeparturesUiState.Loaded)
        partial as DeparturesUiState.Loaded
        assertTrue(partial.partialRefresh)

        // Then a total failure: nothing fresh. The kept snapshot is still incomplete, so the
        // partial warning must persist alongside the refresh-failure one, not be cleared.
        allFail = true
        vm.refresh()
        advanceUntilIdle()
        val kept = vm.state.value
        assertTrue(kept is DeparturesUiState.Loaded)
        kept as DeparturesUiState.Loaded
        assertTrue(kept.partialRefresh)
        assertEquals(DeparturesUiState.Error.Kind.OFFLINE, kept.refreshFailure)
    }

    @Test
    fun `a stop whose arrivals fail still surfaces its fresh disruption`() = runTest(dispatcher) {
        // First load, no prior: King's Cross's arrivals fail but its disruption succeeds
        // with a closure. The decoupled fetch means the closure still surfaces rather than
        // the stop dropping out for want of predictions (the deferred PR #15 finding).
        val client = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> {
                if (stopId == "940GZZLUKSX") throw TflException.Offline(null)
                return listOf(departure("victoria", "Victoria", 300))
            }

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String): List<StopDisruption> =
                if (stopId == "940GZZLUKSX") {
                    listOf(StopDisruption("Station closed until further notice"))
                } else {
                    emptyList()
                }
        }
        val vm = viewModel(client)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        val ksx = state.stops.single { it.stopId == "940GZZLUKSX" }
        // Arrivals failed with no prior, so it has no departures — but it stays in the
        // snapshot on its disruption alone, so the closed stop is flagged, not dropped.
        assertTrue(ksx.departures.isEmpty())
        assertEquals(
            listOf("Station closed until further notice"),
            ksx.disruptions.map { it.description },
        )
        // Its arrivals were never fetched, so it's flagged not-fresh — a status row can't
        // then claim "No departures" for this stop (only the closure shows).
        assertFalse(ksx.arrivalsFresh)
        // Oxford Circus refreshed and King's Cross's arrivals didn't — a partial refresh.
        assertTrue(state.partialRefresh)
    }
}
