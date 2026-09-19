package app.trackmo.ui

import app.trackmo.domain.Departure
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.LineRef
import app.trackmo.domain.LineStatus
import app.trackmo.domain.SnapshotStore
import app.trackmo.domain.StopArrivals
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

    private fun viewModel(
        client: TflClient,
        store: SnapshotStore = SnapshotStore.NONE,
        warn: (String) -> Unit = {},
    ) = MainViewModel(client, seeds, clock = { now }, io = dispatcher, snapshotStore = store, warn = warn)

    /** An in-memory [SnapshotStore] recording every save, seeded with an optional last-good. */
    private class FakeStore(initial: DeparturesSnapshot? = null) : SnapshotStore {
        var stored: DeparturesSnapshot? = initial
        val saves = mutableListOf<DeparturesSnapshot>()
        override suspend fun load(): DeparturesSnapshot? = stored
        override suspend fun save(snapshot: DeparturesSnapshot) {
            stored = snapshot
            saves += snapshot
        }
    }

    private fun stopArrivals(stopId: String, name: String, offsetSeconds: Long, fetchedAt: Instant) =
        StopArrivals(
            stopId = stopId,
            stopName = name,
            departures = listOf(departure("victoria", "Victoria", offsetSeconds)),
            fetchedAt = fetchedAt,
        )

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
                // Victoria comes back good so the surviving stop's status is fully determined —
                // the only warning is the failed stop's arrivals, which is what this pins.
                statuses = Result.success(listOf(status("victoria", LineStatus.GOOD_SERVICE, "Good Service"))),
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
        val warnings = mutableListOf<String>()
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
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(setOf("victoria"), state.lineStatuses.keys)
        assertTrue(state.disruptionUnknown)
        // The reason is logged and names the undetermined line, so a persistent
        // "couldn't check for disruptions" is diagnosable from logcat.
        assertTrue(
            "the undetermined line is named in the log",
            warnings.any { it.contains("no status") && it.contains("northern") },
        )
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
        val warnings = mutableListOf<String>()
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
            warn = { warnings += it },
        )
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.lineStatuses.isEmpty())
        assertTrue(state.disruptionUnknown)
        // The blank-line-id reason is logged, so this cause is distinguishable in logcat.
        assertTrue(
            "the blank-line-id reason is logged",
            warnings.any { it.contains("no line id") },
        )
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

    @Test
    fun `a successful snapshot is persisted for the next launch and the widget`() =
        runTest(dispatcher) {
            val store = FakeStore()
            val vm = viewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                        "940GZZLUKSX" to Result.success(listOf(departure("northern", "Northern", 120))),
                    ),
                ),
                store = store,
            )
            advanceUntilIdle()

            val saved = store.saves.last()
            assertEquals(listOf("940GZZLUOXC", "940GZZLUKSX"), saved.stops.map { it.stopId })
            assertEquals(now, saved.fetchedAt)
        }

    @Test
    fun `an error result does not clobber a previously saved snapshot`() = runTest(dispatcher) {
        // Nothing is stored, and every stop fails on this first load → an Error state with no
        // content. Persisting that would erase whatever the widget last showed, so it must not.
        val store = FakeStore()
        val vm = viewModel(
            FakeClient(
                mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
                disruptionsByStop = mapOf(
                    "940GZZLUOXC" to Result.failure(TflException.Offline(null)),
                    "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                ),
            ),
            store = store,
        )
        advanceUntilIdle()

        assertTrue(vm.state.value is DeparturesUiState.Error)
        assertTrue(store.saves.isEmpty())
    }

    @Test
    fun `the persisted last-good is restored as the prior a failed refresh falls back to`() =
        runTest(dispatcher) {
            // King's Cross has an aged last-good on disk; Oxford Circus does not. On this
            // launch Oxford Circus refreshes but King's Cross fails outright (arrivals and
            // disruption). Because the restored snapshot is the prior the refresh merges into,
            // King's Cross keeps its aged rows instead of dropping out.
            val aged = now.minusSeconds(120)
            val store = FakeStore(
                DeparturesSnapshot(
                    stops = listOf(stopArrivals("940GZZLUKSX", "King's Cross St. Pancras", 300, aged)),
                    fetchedAt = aged,
                ),
            )
            val vm = viewModel(
                FakeClient(
                    mapOf(
                        "940GZZLUOXC" to Result.success(listOf(departure("victoria", "Victoria", 300))),
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                    disruptionsByStop = mapOf(
                        "940GZZLUKSX" to Result.failure(TflException.Offline(null)),
                    ),
                ),
                store = store,
            )
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(setOf("940GZZLUOXC", "940GZZLUKSX"), state.stops.map { it.stopId }.toSet())
            val ksx = state.stops.single { it.stopId == "940GZZLUKSX" }
            // Kept from the restored snapshot at its aged time, not refreshed away.
            assertEquals(aged, ksx.fetchedAt)
            assertTrue(ksx.departures.isNotEmpty())
            assertTrue(state.partialRefresh)
        }

    @Test
    fun `the restored snapshot is marked disruption-unknown until the refresh checks status`() =
        runTest(dispatcher) {
            val aged = now.minusSeconds(120)
            val store = FakeStore(
                DeparturesSnapshot(
                    stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged)),
                    fetchedAt = aged,
                ),
            )
            // A refresh that never completes, so the state settles at the restored snapshot:
            // the restore has not checked line status, so those departures must not read as
            // verified-clean while the check is pending.
            val hangingClient = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> =
                    kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val vm = viewModel(hangingClient, store = store)
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
            assertTrue(state.disruptionUnknown)
            // The saved snapshot is missing King's Cross (a seed stop), so the restore is
            // flagged partial rather than shown as a complete list.
            assertTrue(state.partialRefresh)
        }

    @Test
    fun `a complete restored snapshot is not flagged partial`() = runTest(dispatcher) {
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, aged) },
                fetchedAt = aged,
            ),
        )
        val hangingClient = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(hangingClient, store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
        assertFalse(state.partialRefresh)
    }

    @Test
    fun `a restored snapshot with a carried-stale stop is flagged partial`() = runTest(dispatcher) {
        // All seed stops are present, but one was carried-forward-stale when saved
        // (arrivalsFresh = false), so the snapshot is mixed-age — flag it partial rather than
        // let the freshest-stop stamp pass it off as uniformly fresh.
        val aged = now.minusSeconds(120)
        val store = FakeStore(
            DeparturesSnapshot(
                stops = listOf(
                    stopArrivals("940GZZLUOXC", "Oxford Circus", 300, aged),
                    stopArrivals("940GZZLUKSX", "King's Cross St. Pancras", 300, aged)
                        .copy(arrivalsFresh = false),
                ),
                fetchedAt = aged,
            ),
        )
        val hangingClient = object : TflClient {
            override suspend fun arrivals(stopId: String): List<Departure> =
                kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()

            override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

            override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
        }
        val vm = viewModel(hangingClient, store = store)
        advanceUntilIdle()

        val state = vm.state.value
        assertTrue(state is DeparturesUiState.Loaded)
        state as DeparturesUiState.Loaded
        assertTrue(state.partialRefresh)
    }

    @Test
    fun `a refresh recovers the last-good from the store when not in a Loaded state`() =
        runTest(dispatcher) {
            // Everything fails (offline). First load with an empty store → Error. Then a
            // snapshot exists on disk (a prior good session), and a later refresh — still
            // offline, still not in a Loaded state — recovers it as the aged last-good rather
            // than staying stuck on the error screen with valid data on disk.
            val store = FakeStore()
            val offline = mapOf(
                "940GZZLUOXC" to Result.failure<List<Departure>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<Departure>>(TflException.Offline(null)),
            )
            val offlineDisruptions = mapOf(
                "940GZZLUOXC" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
            )
            val vm = viewModel(FakeClient(offline, disruptionsByStop = offlineDisruptions), store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            store.stored = DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, now.minusSeconds(120)) },
                fetchedAt = now.minusSeconds(120),
            )
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
            assertEquals(DeparturesUiState.Error.Kind.OFFLINE, state.refreshFailure)
        }

    @Test
    fun `a total-failure refresh from the store keeps the snapshot's incompleteness`() =
        runTest(dispatcher) {
            // First load errors with an empty store. Then an INCOMPLETE snapshot is on disk
            // (King's Cross, a seed stop, is missing — an earlier partial refresh saved only
            // Oxford Circus). A later refresh, still offline, recovers it from the store as
            // the prior — and must carry its incompleteness, so the kept list stays flagged
            // partial rather than passed off as complete (SPEC principle 2). Before the fix
            // the fallback kept only the stops and derived partial from the (Error) previous
            // state, so the warning was dropped.
            val store = FakeStore()
            val offline = mapOf(
                "940GZZLUOXC" to Result.failure<List<Departure>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<Departure>>(TflException.Offline(null)),
            )
            val offlineDisruptions = mapOf(
                "940GZZLUOXC" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
                "940GZZLUKSX" to Result.failure<List<StopDisruption>>(TflException.Offline(null)),
            )
            val vm = viewModel(FakeClient(offline, disruptionsByStop = offlineDisruptions), store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            store.stored = DeparturesSnapshot(
                stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, now.minusSeconds(120))),
                fetchedAt = now.minusSeconds(120),
            )
            vm.refresh()
            advanceUntilIdle()

            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(listOf("940GZZLUOXC"), state.stops.map { it.stopId })
            assertTrue(state.partialRefresh)
            assertEquals(DeparturesUiState.Error.Kind.OFFLINE, state.refreshFailure)
        }

    @Test
    fun `a refresh from a non-Loaded state shows the aged store snapshot before the network returns`() =
        runTest(dispatcher) {
            // First load errors (offline, empty store) → Error. Then a snapshot is on disk and
            // a refresh runs while the network HANGS. The aged last-good must be shown at once
            // rather than the spinner held through the hung fetch — valid data on disk must not
            // be hidden behind a stuck spinner (SPEC principle 5).
            val store = FakeStore()
            var hang = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (hang) return kotlinx.coroutines.CompletableDeferred<List<Departure>>().await()
                    throw TflException.Offline(null)
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String): List<StopDisruption> {
                    if (hang) return kotlinx.coroutines.CompletableDeferred<List<StopDisruption>>().await()
                    throw TflException.Offline(null)
                }
            }
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            assertTrue(vm.state.value is DeparturesUiState.Error)

            val aged = now.minusSeconds(120)
            store.stored = DeparturesSnapshot(
                stops = seeds.map { stopArrivals(it.id, it.name, 300, aged) },
                fetchedAt = aged,
            )
            hang = true
            vm.refresh()
            advanceUntilIdle()

            // The fetch is suspended (hung) mid-flight, so the state settles at the aged
            // last-good published from the store fallback — not stuck on Loading.
            val state = vm.state.value
            assertTrue(state is DeparturesUiState.Loaded)
            state as DeparturesUiState.Loaded
            assertEquals(seeds.map { it.id }.toSet(), state.stops.map { it.stopId }.toSet())
            assertEquals(aged, state.fetchedAt)
            assertTrue(vm.refreshing.value)
        }

    @Test
    fun `a total-failure refresh does not overwrite a complete saved snapshot`() =
        runTest(dispatcher) {
            // A complete good snapshot is saved on the first load. Then a total failure carries
            // the aged rows (arrivalsFresh = false) — but that is not authoritative, so it must
            // NOT be persisted over the complete one, or the next launch would restore it as a
            // partial snapshot despite the good data still being on disk.
            var failing = false
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
            val store = FakeStore()
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            val goodSave = store.saves.last()
            assertEquals(seeds.map { it.id }.toSet(), goodSave.stops.map { it.stopId }.toSet())
            assertTrue(goodSave.stops.all { it.arrivalsFresh })
            val savesBefore = store.saves.size

            failing = true
            vm.refresh()
            advanceUntilIdle()

            // No new save from the failed cycle; the complete snapshot on disk is untouched.
            assertEquals(savesBefore, store.saves.size)
            assertTrue(store.stored!!.stops.all { it.arrivalsFresh })
        }

    @Test
    fun `a cycle with only fresh disruptions and no fresh arrivals does not overwrite the snapshot`() =
        runTest(dispatcher) {
            // A complete good snapshot is saved on the first load. Then arrivals fail for every
            // stop but the disruption calls succeed (empty). anyFreshData would call that
            // authoritative, but there is no fresh ARRIVALS content and disruptions aren't
            // persisted — so saving would only rewrite the snapshot to arrivalsFresh = false
            // and make the next launch restore it as partial. It must be skipped.
            var arrivalsFail = false
            val client = object : TflClient {
                override suspend fun arrivals(stopId: String): List<Departure> {
                    if (arrivalsFail) throw TflException.Offline(null)
                    return listOf(departure("victoria", "Victoria", 300))
                }

                override suspend fun lineStatuses(lineIds: Collection<String>) = emptyList<LineStatus>()

                override suspend fun stopDisruptions(stopId: String) = emptyList<StopDisruption>()
            }
            val store = FakeStore()
            val vm = viewModel(client, store = store)
            advanceUntilIdle()
            val goodSave = store.saves.last()
            assertTrue(goodSave.stops.all { it.arrivalsFresh })
            val savesBefore = store.saves.size

            // Arrivals fail, disruptions still succeed (empty).
            arrivalsFail = true
            vm.refresh()
            advanceUntilIdle()

            // No new save; the complete snapshot on disk is untouched.
            assertEquals(savesBefore, store.saves.size)
            assertTrue(store.stored!!.stops.all { it.arrivalsFresh })
        }

    @Test
    fun `an empty watched list persists an authoritative empty snapshot`() = runTest(dispatcher) {
        // No stops to fetch → an authoritative "no departures", which must overwrite an
        // obsolete saved snapshot rather than leaving removed stops on disk for the next
        // launch (and the widget) to restore.
        val store = FakeStore(
            DeparturesSnapshot(
                stops = listOf(stopArrivals("940GZZLUOXC", "Oxford Circus", 300, now.minusSeconds(600))),
                fetchedAt = now.minusSeconds(600),
            ),
        )
        val emptyClient = FakeClient(emptyMap())
        val vm = MainViewModel(
            emptyClient,
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            snapshotStore = store,
        )
        advanceUntilIdle()

        assertTrue(vm.state.value is DeparturesUiState.Loaded)
        val saved = store.saves.last()
        assertTrue(saved.stops.isEmpty())
    }

    private class FakeStarredStore(
        initial: Set<app.trackmo.domain.StarredRow> = emptySet(),
    ) : app.trackmo.domain.StarredRowsStore {
        private val state =
            kotlinx.coroutines.flow.MutableStateFlow<app.trackmo.domain.StarredRowSet>(
                app.trackmo.domain.StarredRowSet.Loaded(initial),
            )
        override fun starred() = state
        override suspend fun toggle(row: app.trackmo.domain.StarredRow) {
            val current = (state.value as app.trackmo.domain.StarredRowSet.Loaded).starred
            state.value = app.trackmo.domain.StarredRowSet.Loaded(
                app.trackmo.domain.Starred.toggle(current, row),
            )
        }
    }

    private fun row(stopId: String, lineId: String, directionKey: String) = DepartureRow(
        stopId = stopId,
        stopName = "Stop $stopId",
        lineId = lineId,
        lineName = lineId,
        direction = directionKey,
        directionKey = directionKey,
        destination = "Somewhere",
        mode = "tube",
        upcoming = emptyList(),
        fetchedAt = now,
    )

    @Test
    fun `toggleStar stars then unstars a row, reflected in the starred flow`() = runTest(dispatcher) {
        val starredStore = FakeStarredStore()
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = starredStore,
        )
        advanceUntilIdle()
        assertTrue(vm.starred.value.isEmpty())

        val victoria = row("940GZZLUKSX", "victoria", "southbound")
        vm.toggleStar(victoria)
        advanceUntilIdle()
        assertEquals(setOf(app.trackmo.domain.StarredRow.of(victoria)), vm.starred.value)

        vm.toggleStar(victoria)
        advanceUntilIdle()
        assertTrue(vm.starred.value.isEmpty())
    }

    @Test
    fun `an already-starred set is exposed on the starred flow at once`() = runTest(dispatcher) {
        val victoria = app.trackmo.domain.StarredRow("940GZZLUKSX", "victoria", "southbound")
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = FakeStarredStore(setOf(victoria)),
        )
        advanceUntilIdle()
        assertEquals(setOf(victoria), vm.starred.value)
        assertTrue(vm.starringAvailable.value)
    }

    @Test
    fun `an unavailable star set reports starring unavailable, not an empty set`() = runTest(dispatcher) {
        val unavailableStore = object : app.trackmo.domain.StarredRowsStore {
            override fun starred() =
                kotlinx.coroutines.flow.flowOf(app.trackmo.domain.StarredRowSet.Unavailable)
            override suspend fun toggle(row: app.trackmo.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = unavailableStore,
        )
        advanceUntilIdle()
        // No stars to pin (we can't read them), and the flag is false so the screen hides the
        // control rather than showing every row unfilled (a false "nothing starred" claim).
        assertTrue(vm.starred.value.isEmpty())
        assertFalse(vm.starringAvailable.value)
    }

    @Test
    fun `a failed star toggle sets the write-failed flag until it is acknowledged`() = runTest(dispatcher) {
        // A DataStore write failure (storage full, IO error) leaves the star unchanged and the
        // store won't re-emit, so the tap silently no-ops — the ViewModel raises an acknowledged
        // flag the screen turns into a transient message (SPEC principle 2). Acknowledged, not a
        // one-shot event, so it survives a rotation between the failed tap and the message.
        val failingToggle = object : app.trackmo.domain.StarredRowsStore {
            override fun starred() = kotlinx.coroutines.flow.flowOf(
                app.trackmo.domain.StarredRowSet.Loaded(emptySet()),
            )
            override suspend fun toggle(row: app.trackmo.domain.StarredRow) {
                throw java.io.IOException("disk full")
            }
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = failingToggle,
        )
        advanceUntilIdle()
        assertFalse(vm.starWriteFailed.value)
        vm.toggleStar(row("940GZZLUKSX", "victoria", "southbound"))
        advanceUntilIdle()
        assertTrue("a failed write raises the flag", vm.starWriteFailed.value)
        vm.starWriteFailureShown()
        assertFalse("acknowledging clears it", vm.starWriteFailed.value)
    }

    @Test
    fun `starring is unavailable until the store reports a loaded set`() = runTest(dispatcher) {
        // A store whose flow never emits a set (no read has completed) must leave starring
        // unavailable — enabling it early would show a persisted-starred row as unstarred with
        // a "Pin to top" action, and a tap would toggle the real membership off.
        val neverLoads = object : app.trackmo.domain.StarredRowsStore {
            override fun starred() =
                kotlinx.coroutines.flow.emptyFlow<app.trackmo.domain.StarredRowSet>()
            override suspend fun toggle(row: app.trackmo.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = neverLoads,
        )
        advanceUntilIdle()
        assertFalse(vm.starringAvailable.value)
        assertTrue(vm.starred.value.isEmpty())
    }

    @Test
    fun `a failed starred read leaves starring unavailable and is logged`() = runTest(dispatcher) {
        // A DataStore read failure must not escape the collect and crash the departures screen;
        // it leaves starring in an honest unavailable state (control hidden, nothing pinned)
        // and logs a sanitized reason.
        val warnings = mutableListOf<String>()
        val failingStore = object : app.trackmo.domain.StarredRowsStore {
            override fun starred(): kotlinx.coroutines.flow.Flow<app.trackmo.domain.StarredRowSet> =
                kotlinx.coroutines.flow.flow { throw java.io.IOException("disk read failed") }
            override suspend fun toggle(row: app.trackmo.domain.StarredRow) {}
        }
        val vm = MainViewModel(
            FakeClient(emptyMap()),
            seedStops = emptyList(),
            clock = { now },
            io = dispatcher,
            starredStore = failingStore,
            warn = { warnings += it },
        )
        advanceUntilIdle()
        assertFalse(vm.starringAvailable.value)
        assertTrue(vm.starred.value.isEmpty())
        assertTrue(warnings.any { it.contains("starred set read failed") })
    }
}
