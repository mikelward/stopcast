package app.stopcast.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.semantics.SemanticsProperties
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopcast.domain.StationMatch
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.JourneyEnd
import app.stopcast.domain.DismissedAlert
import app.stopcast.domain.LineRef
import app.stopcast.domain.RailFeed
import app.stopcast.domain.StopAreaSource
import app.stopcast.domain.StopLocation
import app.stopcast.domain.LineStatus
import app.stopcast.domain.RoutePattern
import app.stopcast.domain.RouteTopology
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.TflException
import app.stopcast.domain.StarredJourney
import app.stopcast.domain.RouteStopsRepository
import app.stopcast.domain.RouteSequenceSource
import app.stopcast.domain.LineSequence
import app.stopcast.domain.LineRoute
import app.stopcast.domain.StopDisruption
import app.stopcast.ui.theme.StopCastTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowToast
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `MainScreen` in each state it can be in, light and dark. The states are the point:
 * a fresh snapshot, a stale one (countdowns withheld), a partial refresh (some stops
 * failed), nothing upcoming, and an honest error (SPEC principles 1–2 / D4) — each
 * renders from fixture state alone, which the screen's pure `state`-in signature makes
 * possible.
 *
 * Public infrastructure/line names only in the fixtures — no user route data (SPEC
 * *Privacy*). Dynamic color is off so the baseline schemes render deterministically
 * rather than varying with the host's wallpaper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun dep(
        lineId: String,
        lineName: String,
        direction: String,
        destination: String,
        offsetSeconds: Long,
        platform: String,
        mode: String = "tube",
        branch: String? = null,
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = mode,
        branch = branch,
    )

    // Each stop is stamped independently: the two default to the same age, but a caller can
    // age one and not the other to render a mixed-age snapshot (see `mixed age`).
    private fun stops(
        ksxFetchedAt: Instant,
        oxcFetchedAt: Instant = ksxFetchedAt,
    ): List<StopArrivals> = listOf(
        StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(
                dep("victoria", "Victoria", "southbound", "Brixton", 40, "Platform 1"),
                dep("victoria", "Victoria", "southbound", "Brixton", 240, "Platform 1"),
                // A bus, to show the red pill and the mode-based fallback.
                dep("73", "73", "inbound", "Victoria", 150, "", mode = "bus"),
            ),
            fetchedAt = ksxFetchedAt,
            // Declared lines: Circle is served here but returns no arrivals — a suspended
            // line, so it surfaces as a status row (see statuses()).
            lines = listOf(
                LineRef("victoria", "Victoria", "tube"),
                LineRef("circle", "Circle", "tube"),
            ),
        ),
        StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(
                // A branching direction: same line and direction, different destinations —
                // the second train names its own destination rather than "Hainault".
                dep("central", "Central", "eastbound", "Hainault", 180, "Platform 3"),
                dep("central", "Central", "eastbound", "Woodford", 600, "Platform 3"),
                dep("bakerloo", "Bakerloo", "northbound", "Harrow & Wealdstone", 300, "Platform 2"),
            ),
            fetchedAt = oxcFetchedAt,
            // A stop-level disruption: the station is flagged (a stop-status row), while
            // its departures still show below (marked, not suppressed).
            disruptions = listOf(StopDisruption("Station closed until further notice")),
        ),
    )

    // Victoria is disrupted (its rows carry the chip); Circle is suspended and returns no
    // arrivals (a status row). Other lines are clean (absent from the map). Canned line +
    // status wording only (SPEC *Privacy*).
    private fun statuses(): Map<String, LineStatus> =
        mapOf(
            "victoria" to LineStatus("victoria", severity = 6, description = "Severe Delays"),
            "circle" to LineStatus("circle", severity = 2, description = "Suspended"),
        )

    @Test
    fun `loaded, light`() {
        capture("main-loaded.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(120)), now.minusSeconds(120), lineStatuses = statuses()),
                now,
                {},
            )
        }
        composeRule.onNodeWithText("Brixton").assertExists()
        // The two Brixton times merge onto one line, the unit written once (SPEC D8).
        composeRule.onNodeWithText("0 · 4 min").assertExists()
        // A branching direction (Central eastbound) keeps its headline destination and its
        // divergent one apart, so neither countdown sits under the wrong destination.
        composeRule.onNodeWithText("Hainault").assertExists()
        composeRule.onNodeWithText("Woodford").assertExists()
        // The disrupted Victoria line's timed rows carry the inline ⚠ (SPEC D3) — the full status
        // wording is the glyph's content description, no longer a visible chip on a timed row.
        composeRule.onNodeWithContentDescription("Severe Delays").assertExists()
        // Circle is suspended and TfL answered with no Circle trains, so it surfaces as a status row
        // with a dash where a countdown would sit, heard as "No departures" (the "Suspended" chip
        // carries the reason).
        composeRule.onNodeWithText("Suspended").assertExists()
        composeRule.onNodeWithContentDescription("No departures").assertExists()
        composeRule.onNodeWithText("No data").assertDoesNotExist()
        // Oxford Circus has a stop-level disruption, shown as a stop-status row.
        composeRule.onNodeWithText("Station closed until further notice").assertExists()
        // Each group gets one combined title-case header, the place name repeated per platform (SPEC
        // D8). King's Cross splits into a Platform 1 group and a bare bus/status group; Oxford Circus
        // into its two platforms.
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().assertExists()
        composeRule.onAllNodesWithText("Oxford Circus").onFirst().assertExists()
    }

    @Test
    fun `with every mode nearby hidden the list says so rather than that nothing runs`() {
        val busOnly = StopArrivals(
            "490000001A",
            "Example Road",
            listOf(dep("73", "73", "inbound", "Victoria", 150, "", mode = "bus")),
            now.minusSeconds(30),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                MainScreen(DeparturesUiState.Loaded(listOf(busOnly), now.minusSeconds(30)), now, {}, hiddenModes = setOf("bus"))
            }
        }
        composeRule.onNodeWithText("Nothing else to show with Bus hidden").assertExists()
    }

    @Test
    fun `a station's To… page is titled by the trip and says what it couldn't check`() {
        var planned = false
        capture("main-station-trip.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(30)), now.minusSeconds(30), lineStatuses = statuses()),
                now,
                {},
                stationTitle = "Oxford Circus ➔ Brixton",
                onPlanTo = { planned = true },
                tripNotice = "Some routes couldn't be checked",
            )
        }
        composeRule.onNodeWithText("Oxford Circus ➔ Brixton").assertExists()
        composeRule.onNodeWithText("Some routes couldn't be checked").assertExists()
        composeRule.onNodeWithText("To…").performClick()
        assertTrue(planned)
    }

    @Test
    fun `an empty To… page says nothing goes there directly`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                MainScreen(
                    DeparturesUiState.Loaded(emptyList(), now.minusSeconds(30)),
                    now,
                    {},
                    stationTitle = "Oxford Circus ➔ Brixton",
                    emptyMessage = "No direct trips to Brixton soon",
                )
            }
        }
        composeRule.onNodeWithText("No direct trips to Brixton soon").assertExists()
        // No To… action without a handler.
        composeRule.onNodeWithText("To…").assertDoesNotExist()
    }

    @Test
    fun `a hidden mode's rows are left out under a banner that shows them again`() {
        var shownAll = false
        capture("main-modes-hidden.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(30)), now.minusSeconds(30), lineStatuses = statuses()),
                now,
                {},
                hiddenModes = setOf("bus"),
                onShowAllModes = { shownAll = true },
            )
        }
        composeRule.onNodeWithText("Bus hidden").assertExists()
        // The 73 bus is left out; the Victoria line still shows.
        composeRule.onNodeWithText("Brixton").assertExists()
        composeRule.onNodeWithText("73").assertDoesNotExist()
        composeRule.onNodeWithText("Show all").performClick()
        assertTrue(shownAll)
    }

    @Test
    fun `a National Rail line with no key says so and opens Settings`() {
        val stop = StopArrivals(
            "910GEXAMPLE",
            "Example",
            departures = emptyList(),
            fetchedAt = now.minusSeconds(30),
            lines = listOf(LineRef("great-northern", "Great Northern", "national-rail")),
            railFeed = RailFeed.NO_KEY,
        )
        val statuses = mapOf("great-northern" to LineStatus("great-northern", severity = 6, description = "Severe Delays"))
        var openedSettings = false
        capture("main-rail-no-key.png") {
            MainScreen(
                DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(30), lineStatuses = statuses),
                now,
                {},
                onOpenSettings = { openedSettings = true },
            )
        }
        composeRule.onNodeWithText("No data").assertDoesNotExist()
        composeRule.onNodeWithText("No key").performClick()
        assertTrue(openedSettings)
    }

    // The busiest interchange on the network: King's Cross St. Pancras, six Underground lines both
    // ways. Real line ids, termini, and TfL's own `direction` values — recorded from the live
    // `/StopPoint/940GZZLUKSX/Arrivals` feed (2026-09-22). The headers group on the **platform**
    // (SPEC D8), with the platform's compass in parens; TfL's `direction` is deliberately faithful,
    // so this fixture proves the platform is the key and inbound/outbound is not: the three
    // sub-surface lines (Circle, Hammersmith & City, Metropolitan) share one eastbound platform yet
    // TfL tags it `inbound` for the Circle and `outbound` for the other two, and leaves a Westbound
    // train's direction blank — all three still land under one "PLATFORM 7" header. The deep-tube and
    // sub-surface platforms carry distinct numbers (the tube 1–6, the sub-surface 7–8), as they do on
    // the real station, so a platform header names one physical platform. Public infrastructure/line
    // names only (SPEC *Privacy*).
    private fun kingsCrossStPancras() = StopArrivals(
        "940GZZLUKSX",
        "King's Cross St. Pancras",
        listOf(
            dep("victoria", "Victoria", "outbound", "Walthamstow Central", 60, "Northbound - Platform 1"),
            dep("victoria", "Victoria", "inbound", "Brixton", 210, "Southbound - Platform 2"),
            dep("piccadilly", "Piccadilly", "outbound", "Cockfosters", 120, "Eastbound - Platform 3"),
            dep("piccadilly", "Piccadilly", "inbound", "Heathrow Terminal 5", 330, "Westbound - Platform 4"),
            dep("northern", "Northern", "outbound", "High Barnet", 90, "Northbound - Platform 5", branch = "Bank"),
            dep("northern", "Northern", "inbound", "Morden", 240, "Southbound - Platform 6", branch = "Bank"),
            // Circle, Hammersmith & City and Metropolitan share the sub-surface platforms here, so
            // all three run eastbound (Platform 7) / westbound (Platform 8) at King's Cross. TfL's
            // inbound/outbound for one platform disagrees across these lines (Circle Eastbound is
            // `inbound`, the others `outbound`) and a Westbound train can carry no direction at all —
            // the platform is what keeps their trains together, one header for all three.
            dep("circle", "Circle", "inbound", "Edgware Road", 300, "Eastbound - Platform 7"),
            dep("circle", "Circle", "outbound", "Hammersmith", 390, "Westbound - Platform 8"),
            dep("metropolitan", "Metropolitan", "outbound", "Aldgate", 180, "Eastbound - Platform 7"),
            dep("metropolitan", "Metropolitan", "inbound", "Uxbridge", 270, "Westbound - Platform 8"),
            dep("hammersmith-city", "Hammersmith & City", "outbound", "Barking", 150, "Eastbound - Platform 7"),
            dep("hammersmith-city", "Hammersmith & City", "", "Hammersmith", 420, "Westbound - Platform 8"),
        ),
        fetchedAt = now.minusSeconds(60),
    )

    // A bus stop on the same street — a realistic local watched set is a station plus the bus
    // stops around it, not two far-apart interchanges. Public route/place names only.
    private fun kingsCrossBusStop() = StopArrivals(
        "490000077E",
        "King's Cross Station",
        listOf(
            dep("73", "73", "outbound", "Stoke Newington", 120, "", mode = "bus"),
            dep("91", "91", "outbound", "Crouch End", 300, "", mode = "bus"),
            dep("259", "259", "outbound", "Edmonton Green", 480, "", mode = "bus"),
        ),
        fetchedAt = now.minusSeconds(60),
    )

    @Test
    fun `the most connected station splits into per-platform headers under one place name`() {
        // King's Cross' six lines both ways now group by platform, two levels: the place name once
        // ("KING'S CROSS ST. PANCRAS") over a sub-header per platform ("PLATFORM 1 (Northbound)"), so
        // a busy interchange reads as platform blocks rather than a wall of cards under one bare name
        // (SPEC D8). A nearby bus stop (no platform in the feed) sits below under the same place-name
        // level. This is the "after" for the grain change; the fixture carries TfL's real,
        // inconsistent inbound/outbound so the capture proves the platform is the key.
        // Wire the bundled branch topology the way MainActivity does, so the capture is the real
        // production "before" and not the unwired RouteTopology.EMPTY fallback. King's Cross is on
        // the Northern line's Bank (City) branch alone — the Charing Cross branch runs Camden Town →
        // Mornington Crescent → Warren Street → Charing Cross, not via King's Cross — so only one
        // trunk serves this stop and the topology drops the redundant "/Bank" cue here (High Barnet,
        // Morden render bare). EMPTY would instead keep "/Bank", which production never shows.
        val topology = RouteTopology(
            mapOf(
                "northern" to listOf(
                    RoutePattern(
                        "Bank",
                        listOf(
                            "940GZZLUHBT", "940GZZLUHGT", "940GZZLUCTN", "940GZZLUEUS",
                            "940GZZLUKSX", "940GZZLUBNK", "940GZZLUKNG", "940GZZLUMDN",
                        ),
                        "High Barnet",
                        "Morden",
                    ),
                    RoutePattern(
                        "Charing X",
                        listOf(
                            "940GZZLUHBT", "940GZZLUHGT", "940GZZLUCTN", "940GZZLUMTC",
                            "940GZZLUEUS", "940GZZLUCHX", "940GZZLUKNG", "940GZZLUMDN",
                        ),
                        "High Barnet",
                        "Morden",
                    ),
                ),
            ),
        )
        capture("main-connected-station.png") {
            CompositionLocalProvider(LocalRouteTopology provides topology) {
                MainScreen(
                    DeparturesUiState.Loaded(listOf(kingsCrossStPancras(), kingsCrossBusStop()), now.minusSeconds(60)),
                    now,
                    {},
                )
            }
        }
        // The platform blocks lead soonest-first (Platform 1 at 60s, Platform 5 at 90s, Platform 3
        // at 120s); the lower platforms and the bus stop are below the fold (off-screen, not in the
        // semantics tree). The place name shows once as the top level; each platform is a sub-header
        // with its compass in parens.
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().assertExists()
        composeRule.onNodeWithText("– Platform 1", substring = true).assertExists()
        composeRule.onNodeWithText("– Platform 3", substring = true).assertExists()
        // The compass moved off the visible one-line header (title case, no parenthetical) into the
        // spoken label a screen reader hears — several platforms share a compass (Platform 1 and 5 both
        // Northbound), so assert it shows, not that it's unique.
        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().assertExists()
        composeRule.onAllNodesWithContentDescription("Eastbound", substring = true).onFirst().assertExists()
        // Platform 1 leads with the Victoria; Platform 5 the Northern; Platform 3 the Piccadilly.
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("High Barnet").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertExists()
        // The Northern rows here are Bank-branch-only at King's Cross, so the topology drops the
        // "/Bank" cue — the production render, not the RouteTopology.EMPTY fallback.
        composeRule.onNodeWithText("/Bank").assertDoesNotExist()
    }

    // Two poles of one place: a northbound and a southbound bus stop that share a display name are
    // separate TfL stop ids, so grouping by name reads them as one boarding location under a single
    // header — the junction case behind the large-station grain work (SPEC *Finding stops*).
    // Public route/place names only (SPEC *Privacy*).
    private fun turnpikeLaneNorth() = StopArrivals(
        "490009TPL1",
        "Turnpike Lane",
        listOf(dep("141", "141", "outbound", "Palmers Green", 120, "", mode = "bus")),
        fetchedAt = now.minusSeconds(60),
        clusterId = "490G0TPL",
    )

    private fun turnpikeLaneSouth() = StopArrivals(
        "490009TPL2",
        "Turnpike Lane",
        listOf(dep("141", "141", "inbound", "London Bridge", 180, "", mode = "bus")),
        fetchedAt = now.minusSeconds(60),
        clusterId = "490G0TPL",
    )

    private fun manorHouse() = StopArrivals(
        "940GZZLUMRH",
        "Manor House",
        listOf(dep("piccadilly", "Piccadilly", "westbound", "Cockfosters", 240, "Westbound - Platform 2")),
        fetchedAt = now.minusSeconds(60),
    )

    @Test
    fun `two poles of one place render under a single cluster header`() {
        // The junction's north and south poles are distinct stop ids sharing one cluster
        // (TfL's stationNaptan); grouped by it they sit under one header (not two identical ones), with both directions'
        // cards beneath it. Manor House is the second place that makes the merged header show (a
        // lone place implies itself).
        capture("main-cluster-header.png") {
            MainScreen(
                DeparturesUiState.Loaded(
                    listOf(turnpikeLaneNorth(), turnpikeLaneSouth(), manorHouse()),
                    now.minusSeconds(60),
                ),
                now,
                {},
            )
        }
        // One "Turnpike Lane" header, not two — the two poles (buses, no compass in the feed) merged
        // into one place under a bare name (no qualifier segment).
        composeRule.onAllNodesWithText("Turnpike Lane").assertCountEquals(1)
        // Manor House is a rail stop, so its one-line header carries the platform, its compass moved to
        // the spoken label.
        composeRule.onNodeWithText("Manor House", substring = true).assertExists()
        composeRule.onNodeWithText("– Platform 2", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Platform 2, Westbound", substring = true).assertExists()
        // Both poles' cards render under that one header.
        composeRule.onNodeWithText("Palmers Green").assertExists()
        composeRule.onNodeWithText("London Bridge").assertExists()
    }

    @Test
    fun `the list keeps its scroll position across a route page and an overlay`() {
        // Enough places to scroll. Synthetic stops and lines; no user data.
        val stops = (1..12).map { i ->
            StopArrivals(
                "S$i", "Stop $i",
                listOf(dep("l$i", "L$i", "outbound", "Dest $i", 60L + i * 10, "", mode = "bus")),
                fetchedAt = now.minusSeconds(60),
            )
        }
        // The list state held above the screen, as MainActivity does, and a stand-in for the
        // Settings overlay that takes the screen out of composition.
        lateinit var listState: LazyListState
        var overlayOpen by mutableStateOf(false)
        composeRule.setContent {
            listState = rememberLazyListState()
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (!overlayOpen) {
                        MainScreen(DeparturesUiState.Loaded(stops, now.minusSeconds(60)), now, {}, listState = listState)
                    }
                }
            }
        }
        composeRule.onNode(hasScrollToIndexAction()).performScrollToIndex(12)
        composeRule.waitForIdle()
        val scrolled = composeRule.runOnIdle { listState.firstVisibleItemIndex }
        assertTrue(scrolled > 0)

        // Out to a route page and back.
        composeRule.onNodeWithText("Dest 9").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(scrolled, listState.firstVisibleItemIndex) }

        // Out to an overlay (the screen leaves composition) and back.
        overlayOpen = true
        composeRule.waitForIdle()
        overlayOpen = false
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(scrolled, listState.firstVisibleItemIndex) }
    }

    @Test
    fun `a platform view keeps its scroll position across a rotation`() {
        // One platform with enough lines to scroll (synthetic lines; a public station as the example).
        val station = StopArrivals(
            "940GZZLUMRH", "Manor House",
            (1..30).map { i -> dep("l$i", "L$i", "westbound", "Dest $i", 60L + i * 10, "Westbound - Platform 2") },
            fetchedAt = now.minusSeconds(60),
        )
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loaded(listOf(station, turnpikeLaneSouth()), now.minusSeconds(60)), now, {})
                }
            }
        }
        composeRule.onNodeWithContentDescription("Platform 2, Westbound", substring = true).performClick()
        composeRule.waitForIdle()
        // The one platform is one tall card, so scroll by a drag rather than to an item.
        composeRule.onNode(hasScrollToIndexAction()).performTouchInput { swipeUp() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Dest 1").assertIsNotDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()
        // Still the platform view, still scrolled past the first line.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Dest 1").assertIsNotDisplayed()
    }

    @Test
    fun `tapping a platform header shows just that platform, and back returns to the list`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(
                            listOf(turnpikeLaneNorth(), turnpikeLaneSouth(), manorHouse()),
                            now.minusSeconds(60),
                        ),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Platform 2, Westbound", substring = true).performClick()
        composeRule.waitForIdle()
        captureSnapshot("main-platform-view.png")
        // A back arrow replaces the app's mark, the app bar names the platform, and only its
        // departures remain.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Manor House – Platform 2").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertExists()
        composeRule.onNodeWithText("Palmers Green").assertDoesNotExist()
        composeRule.onNodeWithText("London Bridge").assertDoesNotExist()

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Palmers Green").assertExists()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a station's platform view shows that platform only, though all share one stop`() {
        // King's Cross' platforms all come from one TfL stop id, so the drill-down must keep the
        // tapped platform's group, not every row of the stop.
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(kingsCrossStPancras()), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cockfosters").assertExists()

        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertDoesNotExist()
        composeRule.onNodeWithText("High Barnet").assertDoesNotExist()
    }

    @Test
    fun `a platform view holds its platform when a suspended line appears at the stop`() {
        // A line-status row switches the stop's grouping place to a per-stop key; the drill-down
        // matches on the platform itself, so it keeps showing Platform 1's departures.
        // A real StopArea cluster, so the clear stop groups under it and the warned one under its own id.
        val station = kingsCrossStPancras().copy(clusterId = "940GZZLUKSX")
        val clear = DeparturesUiState.Loaded(listOf(station), now.minusSeconds(60))
        val warned = DeparturesUiState.Loaded(
            listOf(station.copy(lines = station.lines + LineRef("jubilee", "Jubilee", "tube"))),
            now.minusSeconds(60),
            lineStatuses = mapOf("jubilee" to LineStatus("jubilee", 2, "Suspended")),
        )
        var state by mutableStateOf<DeparturesUiState>(clear)
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(state, now, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()

        state = warned
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertDoesNotExist()
        // The suspension names no platform, so the platform view shows it rather than hide it.
        composeRule.onAllNodesWithText("Suspended", substring = true).onFirst().assertExists()
    }

    @Test
    fun `a platform view closes when the feed drops its platform number, rather than claim no departures`() {
        val station = kingsCrossStPancras()
        var state by mutableStateOf<DeparturesUiState>(DeparturesUiState.Loaded(listOf(station), now.minusSeconds(60)))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(state, now, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists()

        // The same trains, but TfL now omits every platform number.
        state = DeparturesUiState.Loaded(
            listOf(station.copy(departures = station.departures.map { it.copy(platform = null) })),
            now.minusSeconds(60),
        )
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
    }

    @Test
    fun `a platform view shows its place's closure card, and a dismissal there hides it everywhere`() {
        val dismissed = mutableStateOf(emptySet<DismissedAlert>())
        val closed = manorHouse().copy(disruptions = listOf(StopDisruption("Station closed until further notice")))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(turnpikeLaneNorth(), closed), now.minusSeconds(60)),
                        now,
                        {},
                        dismissed = dismissed.value,
                        onDismissAlert = { row -> dismissed.value = dismissed.value + DismissedAlert.ofStopClosure(row) },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Platform 2, Westbound", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Station closed", substring = true).assertExists()

        composeRule.onNodeWithContentDescription("Dismiss alert").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Station closed", substring = true).assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Station closed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping a suspended line's header opens the whole stop, not just the warning`() {
        val station = kingsCrossStPancras().let { it.copy(lines = it.lines + LineRef("jubilee", "Jubilee", "tube")) }
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(
                            listOf(station),
                            now.minusSeconds(60),
                            lineStatuses = mapOf("jubilee" to LineStatus("jubilee", 2, "Suspended")),
                        ),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // The suspension groups apart under the bare station header (no platform in its label).
        composeRule.onNodeWithContentDescription("King's Cross St. Pancras").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onAllNodesWithText("Suspended", substring = true).onFirst().assertExists()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        // The whole station is titled by its bare name, not by its first platform.
        composeRule.onNodeWithText("King's Cross St. Pancras – Platform 1").assertDoesNotExist()
    }

    @Test
    fun `a platform view's title follows the pole's current terminus`() {
        // A letterless, bearingless bus pole is headed by its shared terminus, which comes from the
        // departures — so after a refresh changes it, the title must not keep claiming the old one.
        fun pole(destination: String) = StopArrivals(
            "490000001A",
            "Example Road",
            listOf(dep("141", "141", "outbound", destination, 120, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60),
        )
        var state by mutableStateOf<DeparturesUiState>(
            DeparturesUiState.Loaded(listOf(pole("Palmers Green")), now.minusSeconds(60)),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(state, now, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Example Road", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists()

        state = DeparturesUiState.Loaded(listOf(pole("Wood Green")), now.minusSeconds(60))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Palmers Green", substring = true).assertCountEquals(0)
    }

    @Test
    fun `tapping the station name shows the whole station, the rest of the header one platform`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(kingsCrossStPancras(), manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().performClick()
        composeRule.waitForIdle()
        // Every King's Cross platform, and nothing from Manor House.
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertExists()
        composeRule.onAllNodesWithText("Manor House", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a platform tapped in a station view shows just that platform, and back returns to the station`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(kingsCrossStPancras(), manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cockfosters").assertExists()

        // In the station view, a platform header narrows to that platform.
        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertDoesNotExist()

        // Back steps out to the whole station, not past it to the full list.
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Cockfosters").assertExists()
        composeRule.onAllNodesWithText("Manor House", substring = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Back").assertExists()

        // And back again returns to the full list (Manor House sits below King's Cross's rows, off
        // the lazy list's first screen, so the missing back arrow is what marks the full list).
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().assertExists()
    }

    @Test
    fun `a refresh dropping a platform and its station closes to the full list and stays there`() {
        val both = DeparturesUiState.Loaded(listOf(kingsCrossStPancras(), manorHouse()), now.minusSeconds(60))
        var state by mutableStateOf<DeparturesUiState>(both)
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(state, now, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription("Platform 1, Northbound", substring = true).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()

        // Both the platform and its station leave the snapshot: close past the station too.
        state = DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()

        // The station coming back doesn't reopen a view the user was already taken out of.
        state = both
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `tapping a header's distance shows that stop on a map, not the platform`() {
        var opened: Pair<String, String>? = null
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 400.0),
                        onOpenStopMap = { stopId, name -> opened = stopId to name },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("(400 m)", substring = true, useUnmergedTree = true).onFirst().performClick()
        composeRule.waitForIdle()
        assertEquals("940GZZLUMRH" to "Manor House", opened)
        composeRule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `a whole-station view keeps a platform the near-me list folded away, titled by the station`() {
        // Two poles of one cluster: the farther pole's only line is folded to the nearer pole, so it
        // has no group on the near-me list — but it's still part of the station.
        val north = turnpikeLaneNorth()
        val farPole = StopArrivals(
            "490009TPL3",
            "Turnpike Lane",
            listOf(dep("141", "141", "outbound", "Palmers Green", 400, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60),
            clusterId = "490G0TPL",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(north, farPole, manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("490009TPL1" to 50.0, "490009TPL3" to 90.0, "940GZZLUMRH" to 400.0),
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Turnpike Lane").onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists()
        // The folded pole's 141 is back, and the title is the bare station name.
        composeRule.onAllNodesWithText("Palmers Green").assertCountEquals(2)
        composeRule.onAllNodesWithText("Turnpike Lane", substring = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Turnpike Lane –", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a whole-station view follows a pole that joins its cluster on a refresh`() {
        val joining = StopArrivals(
            "490009TPL4",
            "Turnpike Lane",
            listOf(dep("29", "29", "outbound", "Wood Green", 300, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60),
            clusterId = "490G0TPL",
        )
        var state by mutableStateOf<DeparturesUiState>(
            DeparturesUiState.Loaded(listOf(turnpikeLaneNorth(), manorHouse()), now.minusSeconds(60)),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { MainScreen(state, now, {}) }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Turnpike Lane").onFirst().performClick()
        composeRule.waitForIdle()

        state = DeparturesUiState.Loaded(listOf(turnpikeLaneNorth(), joining, manorHouse()), now.minusSeconds(60))
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithText("Wood Green").assertExists()
    }

    @Test
    fun `a platform view shows services the near-me list folded to a nearer stop`() {
        // Near me, a line shows once, from its nearest stop — so a farther stop's card drops the 141
        // that a nearer pole also serves. Drilling into that farther stop shows everything it serves.
        val nearer = turnpikeLaneNorth()
        val farther = StopArrivals(
            "490000001A",
            "Example Road",
            listOf(
                dep("141", "141", "outbound", "Palmers Green", 300, "", mode = "bus"),
                dep("29", "29", "outbound", "Wood Green", 360, "", mode = "bus"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(nearer, farther), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("490009TPL1" to 50.0, "490000001A" to 300.0),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Palmers Green").assertCountEquals(1)

        composeRule.onNodeWithContentDescription("Example Road", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Wood Green").assertExists()
        composeRule.onNodeWithText("Palmers Green").assertExists()
    }

    @Test
    fun `near-me headers show each stop's distance`() {
        // On the near-me list (distances present) each stop's header carries its own distance in
        // parens after the name, so a rider can judge which nearby stop to walk to; the watched
        // list (no distances) shows none (D1). Captured as a baseline so the near-me header
        // layout is covered visually (Codex, PR #82), not only by the assertions below. Synthetic
        // distances and public stop ids/names only (SPEC *Privacy*).
        capture("main-near-me.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(60)), now.minusSeconds(60), lineStatuses = statuses()),
                now,
                {},
                stopDistanceMeters = mapOf(
                    "940GZZLUKSX" to 120.0,
                    "940GZZLUOXC" to 1200.0,
                ),
            )
        }
        // Meters below a kilometer, km above — each stop's own distance, not one shared value. The
        // distance is a reserved dimmed node at the end of the one-line header; each stop splits into
        // several groups, so its distance repeats on each of that place's group headers.
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().assertExists()
        composeRule.onAllNodesWithText("(120 m)", substring = true).onFirst().assertExists()
        composeRule.onAllNodesWithText("Oxford Circus").onFirst().assertExists()
        composeRule.onAllNodesWithText("(1.2 km)", substring = true).onFirst().assertExists()
    }

    @Test
    fun `a hub-wide alert repeated across an interchange shows once, titled by the interchange`() {
        // TfL reports a hub-wide notice (a lift outage) against every stop point in an
        // interchange, so the near-me set carries the identical text once per member. Those
        // members share one hubNaptanCode, so the notice folds by hub identity to a single card,
        // headed by the interchange name; tapping expands the body to the full text. Synthetic
        // accessibility copy + public station ids/names only (SPEC *Privacy*).
        val notice =
            "No step-free access — the lifts to the Thameslink platforms are out of service. " +
                "Step-free interchange is not available; please use an alternative accessible route."
        val hubName = "King's Cross & St Pancras International"
        fun member(id: String, name: String) = StopArrivals(
            id, name, emptyList(),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption(notice)),
            arrivalsFresh = false,
            hubId = "HUBKGX",
            hubName = hubName,
        )
        val members = listOf(
            member("910GSTPX", "London St Pancras International"),
            member("910GSTPXBOX", "London St Pancras International"),
            member("940GZZLUKSX", "King's Cross St. Pancras"),
        )
        val distances = mapOf("910GSTPX" to 120.0, "910GSTPXBOX" to 150.0, "940GZZLUKSX" to 370.0)
        capture("main-near-me-hub-alert.png") {
            MainScreen(
                DeparturesUiState.Loaded(members, now.minusSeconds(60)),
                now,
                {},
                stopDistanceMeters = distances,
            )
        }
        // Deduped to one card — no per-member group header (caps), and the notice shows once.
        composeRule.onAllNodesWithText(notice).assertCountEquals(1)
        composeRule.onNodeWithText("London St Pancras International", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("King's Cross St. Pancras", substring = true).assertDoesNotExist()
        // The interchange name heads the card even collapsed, so the alert always says which place.
        composeRule.onNodeWithText(hubName).assertExists()
        // Tapping the collapsed alert expands its body in place to the full notice text.
        composeRule.onNodeWithText(notice).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(hubName).assertExists()
        captureSnapshot("main-near-me-hub-alert-expanded.png")
    }

    @Test
    fun `a web link in a closure notice is a tappable link to that page`() {
        // Generic notice text; a TfL-style bare domain + path gets https added (SPEC *Disruptions*).
        val notice = "Station closed until further notice. Visit tfl.gov.uk/status-updates for more."
        val closed = manorHouse().copy(disruptions = listOf(StopDisruption(notice)))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loaded(listOf(closed), now.minusSeconds(60)), now, {})
                }
            }
        }
        composeRule.waitForIdle()
        val text = composeRule.onNodeWithText("tfl.gov.uk/status-updates", substring = true).fetchSemanticsNode()
            // The card merges its title and body into one node; take the body's text.
            .config[SemanticsProperties.Text].first { "tfl.gov.uk/status-updates" in it }
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals("https://tfl.gov.uk/status-updates", (link.item as LinkAnnotation.Url).url)
        assertEquals("tfl.gov.uk/status-updates", text.substring(link.start, link.end))

        // With no app to open it (no browser), the tap says so rather than crashing.
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val url = link.item as LinkAnnotation.Url
        composeRule.runOnUiThread { url.linkInteractionListener!!.onClick(url) }
        assertEquals("No app to open this link", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `a closed bus stop reported per pole folds to one card with the stop name and real newlines`() {
        // TfL reports a bus-stop closure against each pole of the junction, with a body that names
        // no stop and carries literal backslash-n escapes and indent runs. The poles share no hub
        // but do share a cluster, so the notice folds to one card; the stop name heads it (the body
        // never names it), and the escapes render as real line breaks, not visible "\n". Generic
        // notice + stand-in stop name and example bus-stop ids only (SPEC *Privacy*).
        val notice = "Bus Stop Closed\\n    Please use the next stop\\n    or the previous stop \\n    to catch your bus"
        fun pole(id: String) = StopArrivals(
            id, "Example Road", emptyList(),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption(notice)),
            arrivalsFresh = false,
            clusterId = "490G000EXAMPLE",
        )
        val poles = listOf(pole("490000001E"), pole("490000001W"), pole("490000001N"))
        val distances = mapOf("490000001E" to 40.0, "490000001W" to 55.0, "490000001N" to 60.0)
        capture("main-near-me-bus-closure.png") {
            MainScreen(
                DeparturesUiState.Loaded(poles, now.minusSeconds(60)),
                now,
                {},
                stopDistanceMeters = distances,
            )
        }
        // Folded to one card: the closure's first line shows exactly once, not once per pole.
        composeRule.onAllNodesWithText("Bus Stop Closed", substring = true).assertCountEquals(1)
        // The stop name heads the card even collapsed, since the body never names the stop.
        composeRule.onNodeWithText("Example Road").assertExists()
        // The literal backslash-n escapes are gone — the body carries real line breaks instead.
        composeRule.onNodeWithText("\\n", substring = true).assertDoesNotExist()
        // Expanding shows the full multi-line notice.
        composeRule.onNodeWithText("Bus Stop Closed", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("to catch your bus", substring = true).assertExists()
        captureSnapshot("main-near-me-bus-closure-expanded.png")
    }

    @Test
    fun `a stop-closure alert offers a dismiss control that reports the row`() {
        val stop = StopArrivals(
            "490000001A", "Example Road", emptyList(),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
            arrivalsFresh = false,
            clusterId = "490G000EXAMPLE",
        )
        var dismissedRow: DepartureRow? = null
        capture("main-near-me-alert-dismiss.png") {
            MainScreen(
                DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                now,
                {},
                stopDistanceMeters = mapOf("490000001A" to 40.0),
                onDismissAlert = { dismissedRow = it },
            )
        }
        // The closure card offers a dismiss (×) control.
        composeRule.onNodeWithContentDescription("Dismiss alert").assertExists()
        // Tapping it reports the closure's row to the host, which persists the dismissal.
        composeRule.onNodeWithContentDescription("Dismiss alert").performClick()
        composeRule.waitForIdle()
        assertEquals("490000001A", dismissedRow?.stopId)
    }

    @Test
    fun `a tap on the dismiss control's leading edge dismisses rather than expanding`() {
        // The × sits flush against the chevron with the visual gap inside its own 48dp target, so a
        // near miss just left of the glyph must still dismiss — not fall through to the card's
        // expand/collapse. Tapping the node's left edge (not its center) exercises that hit area.
        val stop = StopArrivals(
            "490000001A", "Example Road", emptyList(),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
            arrivalsFresh = false,
            clusterId = "490G000EXAMPLE",
        )
        var dismissedRow: DepartureRow? = null
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                MainScreen(
                    DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                    now,
                    {},
                    stopDistanceMeters = mapOf("490000001A" to 40.0),
                    onDismissAlert = { dismissedRow = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Dismiss alert").performTouchInput { click(centerLeft) }
        composeRule.waitForIdle()
        assertEquals("490000001A", dismissedRow?.stopId)
    }

    @Test
    fun `a dismissed stop-closure alert is hidden`() {
        // Rendered with the alert already in the dismissed set, its card does not show — the same
        // notice at the same place, once reworded, would no longer match and would return.
        val stop = StopArrivals(
            "490000001A", "Example Road", emptyList(),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption("Bus Stop Closed")),
            arrivalsFresh = false,
            clusterId = "490G000EXAMPLE",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("490000001A" to 40.0),
                        dismissed = setOf(DismissedAlert("490G000EXAMPLE", "Bus Stop Closed")),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Bus Stop Closed", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the near-me list shows per-mode More controls`() {
        // At a dense corner the farther clusters wait behind a per-mode "More" control at the foot
        // of the list (SPEC *Finding stops → Near me now*) — one per mode still holding an
        // unrevealed cluster. Captured as a baseline so the footer layout is covered visually; a
        // short (one-stop) list keeps the footer on screen. Public names, synthetic distance.
        capture("main-more-controls.png") {
            MainScreen(
                DeparturesUiState.Loaded(listOf(oneStarrableStop()), now.minusSeconds(60)),
                now,
                {},
                stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                revealableModes = setOf("bus", "tube"),
            )
        }
        composeRule.onNodeWithText("More bus stops").assertExists()
        composeRule.onNodeWithText("More Tube stations").assertExists()
    }

    @Test
    fun `More stays reachable when the near-me list is empty`() {
        // When the nearest clusters return nothing, the farther ones are most useful — the "More"
        // controls render in the empty loaded state too (SPEC principle 2). Logic-only, no baseline.
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(emptyList(), now.minusSeconds(30)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                        revealableModes = setOf("bus"),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("No upcoming departures").assertExists()
        composeRule.onNodeWithText("More bus stops").assertExists()
    }

    @Test
    fun `tapping a More control reveals that mode`() {
        // The footer button hands its mode to onReveal, so the ViewModel pages that mode's clusters.
        var revealed: String? = null
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(oneStarrableStop()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                        revealableModes = setOf("bus"),
                        onReveal = { revealed = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("More bus stops").performClick()
        composeRule.runOnIdle { assertEquals("bus", revealed) }
    }

    @Test
    fun `the near-me list names farther stations of the lines and modes it doesn't reach`() {
        // Past "More", a "From ‹station›…" button each for the nearest station of a tube line or rail
        // mode nothing nearby reaches (SPEC *Finding stops → Farther stations*). Public station names
        // as stand-ins, not anyone's surroundings.
        capture("main-farther-stations.png") {
            MainScreen(
                DeparturesUiState.Loaded(listOf(oneStarrableStop()), now.minusSeconds(60)),
                now,
                {},
                stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                revealableModes = setOf("bus"),
                farther = listOf(
                    StationMatch("910GSTFD", "Stratford", listOf("overground")),
                    StationMatch("910GCLPHMJC", "Clapham Junction", listOf("national-rail")),
                ),
            )
        }
        composeRule.onNodeWithText("From Stratford…").assertExists()
        composeRule.onNodeWithText("From Clapham Junction…").assertExists()
    }

    @Test
    fun `tapping a farther station opens it`() {
        var opened: StationMatch? = null
        val station = StationMatch("910GSTFD", "Stratford", listOf("overground"))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(oneStarrableStop()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                        farther = listOf(station),
                        onOpenFarther = { opened = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("From Stratford…").performClick()
        composeRule.runOnIdle { assertEquals(station, opened) }
    }

    @Test
    fun `a starred journey shows only the trains that reach its far end, atop the near-me list`() {
        // Victoria line from Victoria: northbound trains reach Warren Street, southbound ones don't.
        // Public station ids/names as examples; the journey origin isn't one of the near-me stops.
        val sequence = LineSequence(
            routes = listOf(
                LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUWRR", "940GZZLUWWL")),
                LineRoute("Walthamstow Central ↔ Brixton", listOf("940GZZLUWWL", "940GZZLUWRR", "940GZZLUVIC", "940GZZLUBXN")),
            ),
            stopNames = mapOf(
                "940GZZLUVIC" to "Victoria",
                "940GZZLUWRR" to "Warren Street",
                "940GZZLUWWL" to "Walthamstow Central",
                "940GZZLUBXN" to "Brixton",
            ),
        )
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String) = sequence
            },
        )
        val victoria = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(
                dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5"),
                dep("victoria", "Victoria", "southbound", "Brixton", 60, "Southbound - Platform 4"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        val journey = StarredJourney(
            JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria",
        )
        var flipped: StarredJourney? = null
        // The saved journeys, reloadable: after a rotation they re-read from disk (unknown, empty).
        var saved by mutableStateOf(listOf(journey))
        var savedKnown by mutableStateOf(true)
        var savedLoading by mutableStateOf(false)
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalRouteStops provides repository) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), victoria), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = saved,
                            journeysKnown = savedKnown,
                            journeysLoading = savedLoading,
                            onFlipJourney = { flipped = it },
                            onToggleJourney = {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Victoria ➔ Warren Street").assertExists()
        composeRule.onNodeWithText("Walthamstow Central", substring = true).assertExists()
        // The southbound train doesn't call at Warren Street, and the origin isn't a near-me stop.
        composeRule.onAllNodesWithText("Brixton", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Manor House", substring = true).assertExists()
        captureSnapshot("main-journey-card.png")

        // The ⇄ at the end of the heading swaps the direction in place.
        composeRule.onNodeWithContentDescription("Swap direction").performClick()
        assertEquals(journey, flipped)

        // A tap on the heading opens the journey's own view: its trains headed by where they board,
        // under Swap and Unstar, with the near-me list gone.
        composeRule.onNodeWithText("Victoria ➔ Warren Street").performClick()
        composeRule.onNodeWithText("Unstar journey").assertExists()
        composeRule.onNodeWithText("Platform 5", substring = true).assertExists()
        composeRule.onAllNodesWithText("Manor House", substring = true).assertCountEquals(0)
        captureSnapshot("main-journey-view.png")
        flipped = null
        composeRule.onNodeWithText("Swap direction").performClick()
        assertEquals(journey, flipped)

        // While the saved journeys reload, the view holds (no flash to the near-me list) and comes
        // back once they're known again (Codex).
        saved = emptyList()
        savedKnown = false
        savedLoading = true
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Manor House", substring = true).assertCountEquals(0)
        saved = listOf(journey)
        savedKnown = true
        savedLoading = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unstar journey").assertExists()

        // A read that failed isn't loading: the view closes to the list rather than spin forever.
        saved = emptyList()
        savedKnown = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Manor House", substring = true).assertExists()
    }

    private val victoriaLine = LineSequence(
        routes = listOf(LineRoute("Brixton ↔ Walthamstow Central", listOf("940GZZLUVIC", "940GZZLUWRR", "940GZZLUWWL"))),
        stopNames = mapOf("940GZZLUVIC" to "Victoria", "940GZZLUWRR" to "Warren Street", "940GZZLUWWL" to "Walthamstow Central"),
    )
    private val victoriaToWarrenStreet = StarredJourney(
        JourneyEnd("940GZZLUVIC", "Victoria"), JourneyEnd("940GZZLUWRR", "Warren Street"), "victoria",
    )

    @Test
    fun `a far journey waits behind a button at the foot, unfetched, and revealing it loads it`() {
        // More than a mile from both ends (the distance is the caller's; synthetic here).
        val origins = mutableListOf<List<StopRef>>()
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                            farJourneyMeters = mapOf(victoriaToWarrenStreet.key to 2400.0),
                            onToggleJourney = {},
                            onJourneyOrigins = { origins += it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()

        // Collapsed to a "Faraway favorites" button at the foot: no heading, no card, origin not fetched.
        composeRule.onNodeWithText("Faraway favorites").assertExists()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertDoesNotExist()
        assertTrue(origins.all { refs -> refs.none { it.id == "940GZZLUVIC" } })
        captureSnapshot("main-journey-far.png")

        // Revealing it shows its card, headed with its distance, and fetches its origin.
        composeRule.onNodeWithText("Faraway favorites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertExists()
        composeRule.onNodeWithText("(2.4 km)", substring = true).assertExists()
        assertTrue(origins.last().any { it.id == "940GZZLUVIC" })
        captureSnapshot("main-journey-far-revealed.png")

        // Its heading still opens its own view.
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Unstar journey").assertExists()
    }

    @Test
    fun `revealed faraway favorites are held back again after leaving their nearby set`() {
        var nearbyKey by mutableStateOf("940GZZLUMRH")
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                        journeys = listOf(victoriaToWarrenStreet),
                        farJourneyMeters = mapOf(victoriaToWarrenStreet.key to 2400.0),
                        nearbyKey = nearbyKey,
                        onToggleJourney = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Faraway favorites").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertExists()

        nearbyKey = "940GZZLUSVS"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Faraway favorites").assertExists()
        // Back to the first set: the tap was forgotten on leaving it.
        nearbyKey = "940GZZLUMRH"
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Faraway favorites").assertExists()
    }

    @Test
    fun `a faraway reveal restored after process death holds only for the set it was tapped for`() {
        // Read once per (re)created screen, so a change reaches only the restored one — as when the
        // rider moves while the process is dead — never the live screen before its state is saved.
        var restoreKey = "940GZZLUMRH"
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            val nearbyKey = remember { restoreKey }
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                        journeys = listOf(victoriaToWarrenStreet),
                        farJourneyMeters = mapOf(victoriaToWarrenStreet.key to 2400.0),
                        nearbyKey = nearbyKey,
                        onToggleJourney = {},
                    )
                }
            }
        }
        composeRule.onNodeWithText("Faraway favorites").performClick()
        composeRule.waitForIdle()

        // Same set: the reveal survives.
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertExists()

        // Restored somewhere else.
        restoreKey = "940GZZLUSVS"
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Faraway favorites").assertExists()
    }

    @Test
    fun `a faraway reveal held above the screen survives an overlay taking the list away`() {
        var listShown by mutableStateOf(true)
        composeRule.setContent {
            // Hoisted as MainActivity does, above the Settings/Licenses/search switch.
            val farReveal = rememberFarReveal("940GZZLUMRH")
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (listShown) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                            farJourneyMeters = mapOf(victoriaToWarrenStreet.key to 2400.0),
                            nearbyKey = "940GZZLUMRH",
                            farReveal = farReveal,
                            onToggleJourney = {},
                        )
                    }
                }
            }
        }
        composeRule.onNodeWithText("Faraway favorites").performClick()
        composeRule.waitForIdle()

        // An overlay opens and closes again.
        listShown = false
        composeRule.waitForIdle()
        listShown = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street", substring = true).assertExists()
    }

    private fun journeyScreen(
        origin: StopArrivals,
        source: RouteSequenceSource,
        journey: StarredJourney = victoriaToWarrenStreet,
    ) {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalRouteStops provides RouteStopsRepository(source)) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(journey),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // A line forking past a shared trunk (synthetic stops): from King both branches run via Fork,
    // then one to West End and the other via North Park to North End. The journey ends at North Park,
    // short of its branch's terminus, so the heading's "(for …)" is the journey's end, not the route's.
    private val forkedLine = LineSequence(
        routes = listOf(
            LineRoute("King ↔ West End", listOf("KING", "MID", "FORK", "WEST")),
            LineRoute("King ↔ North End", listOf("KING", "MID", "FORK", "NPARK", "NORTH")),
        ),
        stopNames = mapOf(
            "KING" to "King", "MID" to "Mid", "FORK" to "Fork", "WEST" to "West End", "NPARK" to "North Park",
            "NORTH" to "North End",
        ),
    )
    private val kingToNorthEnd = StarredJourney(JourneyEnd("KING", "King"), JourneyEnd("NPARK", "North Park"), "northern")

    private fun forkedOrigin(vararg departures: Pair<String, Long>) = StopArrivals(
        "KING", "King",
        departures.map { (to, inSeconds) ->
            Departure("northern", "Northern", "outbound", to, null, now.plusSeconds(inSeconds), "tube")
        },
        fetchedAt = now.minusSeconds(60),
    )

    private val forkedSource = object : RouteSequenceSource {
        override suspend fun routeSequence(lineId: String, direction: String) = forkedLine
    }

    @Test
    fun `with a direct train due, a train on the other branch isn't offered`() {
        journeyScreen(forkedOrigin("West End" to 60, "North End" to 600), forkedSource, kingToNorthEnd)
        composeRule.onNodeWithText("King ➔ North Park").assertExists()
        composeRule.onNodeWithText("North End").assertExists()
        composeRule.onAllNodesWithText("(for North Park)", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("West End").assertCountEquals(0)
    }

    @Test
    fun `with no direct train soon the card says so above the trains to change from`() {
        journeyScreen(forkedOrigin("West End" to 60, "West End" to 480), forkedSource, kingToNorthEnd)
        composeRule.onNodeWithText("No direct trains to North Park soon").assertExists()
        // The journey's own end in the brackets, not the branch's terminus.
        composeRule.onNodeWithText("King ➔ Fork (for North Park)").assertExists()
        // A screen reader hears "to", not the drawn arrow.
        composeRule.onNodeWithContentDescription("King to Fork, for North Park").assertExists()
        composeRule.onNodeWithText("1 · 8 min").assertExists()
        captureSnapshot("main-journey-card-change.png")
    }

    @Test
    fun `a tapped train to change from opens its own route`() {
        journeyScreen(forkedOrigin("West End" to 60), forkedSource, kingToNorthEnd)
        composeRule.onNodeWithText("West End").performClick()
        composeRule.waitForIdle()
        // The West End train's page: its stops run to West End, and nothing names the north branch
        // (the page's "Northern line" heading is the line, not the branch).
        composeRule.onAllNodesWithText("West End", substring = true).assertCountEquals(2)
        composeRule.onAllNodesWithText("North End", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("North Park", substring = true).assertCountEquals(0)
    }

    @Test
    fun `with a train that couldn't be checked the card doesn't claim there's no direct one`() {
        // "Nowhere" matches no stop or route end, so that train might yet be a direct one.
        journeyScreen(forkedOrigin("West End" to 60, "Nowhere" to 120), forkedSource, kingToNorthEnd)
        composeRule.onNodeWithText("King ➔ Fork (for North Park)").assertExists()
        composeRule.onNodeWithText("Some routes couldn't be checked").assertExists()
        composeRule.onAllNodesWithText("No direct trains", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a journey card refetches its route once a day old, keeping the old one if that fails`() {
        val calls = mutableListOf<String>()
        var fail = false
        var clock = now
        val repository = RouteStopsRepository(
            object : RouteSequenceSource {
                override suspend fun routeSequence(lineId: String, direction: String): LineSequence {
                    calls += "$lineId/$direction"
                    if (fail) throw TflException.Offline(null)
                    return victoriaLine
                }
            },
            clock = { clock },
        )
        val origin = StopArrivals(
            "940GZZLUVIC", "Victoria",
            listOf(Departure("victoria", "Victoria", "outbound", "Walthamstow Central", null, now.plusSeconds(120), "tube")),
            fetchedAt = now.minusSeconds(60),
        )
        var screenNow by mutableStateOf(now)
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalRouteStops provides repository) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            screenNow,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("victoria/inbound", "victoria/outbound"), calls)

        // An hour on, the route is still fresh: the recheck asks for nothing.
        clock = now.plus(Duration.ofHours(1))
        screenNow = clock
        composeRule.waitForIdle()
        assertEquals(2, calls.size)

        // A day on, with the screen still up, it's fetched again.
        clock = now.plus(Duration.ofHours(25))
        screenNow = clock
        composeRule.waitForIdle()
        assertEquals(4, calls.size)

        // Another day, with TfL down: the card keeps the route it has rather than lose it.
        fail = true
        clock = now.plus(Duration.ofHours(50))
        screenNow = clock
        composeRule.waitForIdle()
        assertEquals(5, calls.size)
        composeRule.onAllNodesWithText("Couldn't load the route").assertCountEquals(0)
    }

    @Test
    fun `a nearby row a journey card already shows isn't repeated below it`() {
        // The journey's origin is also a nearby stop: its train shows on the card, once.
        val origin = StopArrivals(
            "940GZZLUVIC", "Victoria",
            listOf(Departure("victoria", "Victoria", "outbound", "Walthamstow Central", null, now.plusSeconds(120), "tube")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0, "940GZZLUVIC" to 100.0),
                            journeys = listOf(victoriaToWarrenStreet),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Walthamstow Central", substring = true).assertCountEquals(1)
        // The rest of the nearby list is untouched.
        composeRule.onNodeWithText("Cockfosters", substring = true).assertExists()
    }

    @Test
    fun `a journey card shows a closure at its destination`() {
        val origin = StopArrivals(
            "940GZZLUVIC", "Victoria",
            listOf(Departure("victoria", "Victoria", "outbound", "Walthamstow Central", null, now.plusSeconds(120), "tube")),
            fetchedAt = now.minusSeconds(60),
        )
        val closed = StopArrivals(
            "940GZZLUWRR", "Warren Street", emptyList(), now.minusSeconds(60),
            disruptions = listOf(StopDisruption("Station Closed")),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                            journeyDestinationStops = listOf(closed),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Station Closed", substring = true).assertExists()
        captureSnapshot("main-journey-card-destination-closed.png")
    }

    @Test
    fun `journey cards still show when nothing nearby has departures`() {
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(origin), now.minusSeconds(60)),
                            now,
                            {},
                            // The nearby stop was fetched with nothing to show; the origin isn't nearby.
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street").assertExists()
        composeRule.onNodeWithText("Walthamstow Central", substring = true).assertExists()
        composeRule.onNodeWithText("No departures nearby").assertExists()
    }

    @Test
    fun `under journey cards, an empty near-me list with modes hidden says so`() {
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                            hiddenModes = setOf("bus"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Victoria ➔ Warren Street").assertExists()
        composeRule.onNodeWithText("Nothing else to show with Bus hidden").assertExists()
        composeRule.onNodeWithText("No departures nearby").assertDoesNotExist()
    }

    @Test
    fun `a dismissed line alert is gone from the journey card too`() {
        val suspended = LineStatus("victoria", 2, "Suspended")
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            emptyList(),
            fetchedAt = now.minusSeconds(60),
            lines = listOf(LineRef("victoria", "Victoria", "tube")),
        )
        var dismissed by mutableStateOf(emptySet<DismissedAlert>())
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(
                                listOf(manorHouse(), origin),
                                now.minusSeconds(60),
                                lineStatuses = mapOf("victoria" to suspended),
                            ),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(victoriaToWarrenStreet),
                            dismissed = dismissed,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The suspension shows on the card while no trains are predicted.
        composeRule.onAllNodesWithText("Suspended", substring = true).onFirst().assertExists()

        dismissed = setOf(DismissedAlert.ofLineStatus(suspended))
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Suspended", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a bus journey shown the other way is fetched from the stop across the road`() {
        // Synthetic poles: each stop has one per direction in a shared stop area.
        val route = LineSequence(
            routes = listOf(
                LineRoute("Park ↔ Hill", listOf("490000001N", "490000002N")),
                LineRoute("Hill ↔ Park", listOf("490000002S", "490000001S")),
            ),
            stopNames = mapOf(
                "490000001N" to "Park", "490000001S" to "Park", "490000002N" to "Hill", "490000002S" to "Hill",
            ),
            stopAreas = mapOf(
                "490000001N" to "490G1", "490000001S" to "490G1", "490000002N" to "490G2", "490000002S" to "490G2",
            ),
            // The way-back pole's routes: another bus, and an interchange line of no single mode.
            stopLines = mapOf("490000002S" to listOf(LineRef("b2", "B2", "bus"), LineRef("hubline", "Hub", ""))),
        )
        val parkToHill = StarredJourney(
            JourneyEnd("490000001N", "Park"), JourneyEnd("490000002N", "Hill"), "b1", "B1", "bus",
        )
        var origins: List<StopRef> = emptyList()
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = route
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(parkToHill.reversed()),
                            onJourneyOrigins = { origins = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(listOf("490000002S"), origins.map { it.id })
        // The starred line and the other bus there; not the interchange's modeless line.
        assertEquals(listOf("b1", "b2"), origins.single().lines.map { it.id })
        // A bus journey's card talks about buses.
        composeRule.onNodeWithText("Checking buses…").assertExists()
    }

    @Test
    fun `a bus journey shows buses from the stop beside its origin under that stop's letter`() {
        // Synthetic ids: the journey boards b1 at stop L; b3 leaves stop K beside it, for Hill too.
        val b1 = LineSequence(
            routes = listOf(LineRoute("Park ↔ Hill", listOf("490000001L", "490000002N"))),
            stopNames = mapOf("490000001L" to "Park", "490000002N" to "Hill"),
            stopAreas = mapOf("490000001L" to "490G1"),
        )
        val b3 = LineSequence(
            routes = listOf(LineRoute("Park ↔ Hill", listOf("490000001K", "490000002N"))),
            stopNames = mapOf("490000001K" to "Park", "490000002N" to "Hill"),
        )
        val source = object : RouteSequenceSource, StopAreaSource {
            override suspend fun routeSequence(lineId: String, direction: String) = if (lineId == "b3") b3 else b1
            override suspend fun stopAreaPoles(areaId: String) = listOf(
                StopLocation("490000001L", "Park", 51.5, -0.12, listOf(LineRef("b1", "B1", "bus")), "490G1", stopLetter = "L"),
                StopLocation("490000001K", "Park", 51.5, -0.12, listOf(LineRef("b3", "B3", "bus")), "490G1", stopLetter = "K"),
            )
        }
        fun pole(id: String, letter: String, line: String, inSeconds: Long) = StopArrivals(
            id, "Park",
            listOf(Departure(line, line.uppercase(), "outbound", "Hill", null, now.plusSeconds(inSeconds), "bus")),
            fetchedAt = now.minusSeconds(60), clusterId = "490G1", stopLetter = letter,
        )
        val parkToHill = StarredJourney(JourneyEnd("490000001L", "Park"), JourneyEnd("490000002N", "Hill"), "b1", "B1", "bus")
        var origins: List<StopRef> = emptyList()
        var stops by mutableStateOf(listOf(manorHouse(), pole("490000001L", "L", "b1", 120), pole("490000001K", "K", "b3", 240)))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalRouteStops provides RouteStopsRepository(source)) {
                        MainScreen(
                            DeparturesUiState.Loaded(stops, now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(parkToHill),
                            onJourneyOrigins = { origins = it },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The stop beside the origin is fetched too, and its bus shows under its own letter.
        assertEquals(setOf("490000001L", "490000001K"), origins.mapTo(HashSet()) { it.id })
        composeRule.onNodeWithText("Stop K", substring = true).assertExists()
        composeRule.onNodeWithText("Stop L", substring = true).assertExists()
        captureSnapshot("main-journey-card-sibling-pole.png")

        // Only the neighboring stop has a bus: it still shows under its own letter.
        stops = listOf(manorHouse(), pole("490000001L", "L", "b1", 120).copy(departures = emptyList()), pole("490000001K", "K", "b3", 240))
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Stop K", substring = true).assertExists()
    }

    @Test
    fun `a journey card says when one of its routes couldn't be checked`() {
        val route = LineSequence(
            routes = listOf(LineRoute("Park ↔ Hill", listOf("490000001N", "490000002N"))),
            stopNames = mapOf("490000001N" to "Park", "490000002N" to "Hill"),
        )
        val origin = StopArrivals(
            "490000001N",
            "Park",
            listOf(
                Departure("b1", "B1", "outbound", "Hill", null, now.plusSeconds(120), "bus"),
                Departure("b2", "B2", "outbound", "Hill", null, now.plusSeconds(240), "bus"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                                    if (lineId == "b2") throw TflException.Offline(null) else route
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = listOf(
                                StarredJourney(JourneyEnd("490000001N", "Park"), JourneyEnd("490000002N", "Hill"), "b1", "B1", "bus"),
                            ),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Park ➔ Hill").assertExists()
        composeRule.onNodeWithText("Some routes couldn't be checked").assertExists()
        // The failed route can be retried from the card.
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun `tapping a journey card's train opens its route page`() {
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
        )
        journeyScreen(origin, object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
        })
        // The origin isn't a near-me stop, so the page must resolve from the journey card's rows.
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
        composeRule.onNodeWithText("Walthamstow Central", substring = true).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pin to top").assertExists()
    }

    @Test
    fun `a route page opened from a journey stays open when the journey is unstarred`() {
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
        )
        var journeys by mutableStateOf(listOf(victoriaToWarrenStreet))
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        LocalRouteStops provides RouteStopsRepository(
                            object : RouteSequenceSource {
                                override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
                            },
                        ),
                    ) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(manorHouse(), origin), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                            journeys = journeys,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Walthamstow Central", substring = true).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pin to top").assertExists()

        journeys = emptyList()
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pin to top").assertExists()
    }

    @Test
    fun `a journey whose origin failed to refresh says it couldn't check, not that there are no trains`() {
        // The origin's last refresh failed: kept aged, with nothing current left to show.
        val aged = StopArrivals("940GZZLUVIC", "Victoria", emptyList(), fetchedAt = now.minusSeconds(600), arrivalsFresh = false)
        journeyScreen(aged, object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
        })
        composeRule.onNodeWithText("Couldn't check trains").assertExists()
        composeRule.onAllNodesWithText("No trains to", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a journey whose origin couldn't be fetched says so, not that it's checking`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(
                            listOf(manorHouse()),
                            now.minusSeconds(60),
                            unavailableStopIds = setOf("940GZZLUVIC"),
                        ),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                        journeys = listOf(victoriaToWarrenStreet),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Couldn't check trains").assertExists()
        composeRule.onAllNodesWithText("Checking trains", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a journey whose route failed to load says so and offers a retry`() {
        val origin = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
        )
        journeyScreen(origin, object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
                throw TflException.Offline(null)
        })
        composeRule.onNodeWithText("Couldn't load the route").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun `a journey's origin closure shows on its card`() {
        val closed = StopArrivals(
            "940GZZLUVIC",
            "Victoria",
            listOf(dep("victoria", "Victoria", "northbound", "Walthamstow Central", 180, "Northbound - Platform 5")),
            fetchedAt = now.minusSeconds(60),
            disruptions = listOf(StopDisruption("Station closed until further notice.")),
        )
        journeyScreen(closed, object : RouteSequenceSource {
            override suspend fun routeSequence(lineId: String, direction: String) = victoriaLine
        })
        composeRule.onNodeWithText("Station closed until further notice.", substring = true).assertExists()
    }

    @Test
    fun `a journey whose route isn't known yet says it's checking, not that there are no trains`() {
        val journey = StarredJourney(
            JourneyEnd("940GZZLUMRH", "Manor House"), JourneyEnd("940GZZLUKSX", "King's Cross St. Pancras"), "piccadilly",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(manorHouse()), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUMRH" to 300.0),
                        journeys = listOf(journey),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Checking trains…").assertExists()
    }

    @Test
    fun `a lone near-me stop still shows its name and distance`() {
        // A single nearby stop suppresses the watched-list header (StopGroup.showHeader is false
        // for a lone non-closed stop), but on the near-me path the name and distance must still
        // show — otherwise a one-stop result (nothing inside the inner radius, or dedup
        // collapsing to one group) drops both (Codex, PR #82). Logic-only — no baseline.
        // Synthetic distance and public stop id/name only (SPEC *Privacy*).
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(dep("victoria", "Victoria", "southbound", "Brixton", 120, "Platform 1")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                        now,
                        {},
                        stopDistanceMeters = mapOf("940GZZLUKSX" to 300.0),
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("King's Cross St. Pancras", substring = true).assertExists()
        composeRule.onNodeWithText("(300 m)", substring = true).assertExists()
    }

    @Test
    fun `the near-me distance stays visible when a long stop name clips`() {
        // A long stop name at a large font on a narrow row must not push the distance off the
        // end: the name clips, the distance is reserved and stays within the row (Codex, PR #82) —
        // the same reserved-trailing-element discipline as the departure row's countdown.
        // Logic-only — no baseline. Public station name + synthetic distance only (SPEC *Privacy*).
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras International",
            listOf(dep("victoria", "Victoria", "southbound", "Brixton", 120, "Platform 1")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUKSX" to 120.0),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The distance keeps real width and stays within the row rather than clipping off the end
        // behind the long name (which itself clips, as the reserved element is measured first).
        val bounds = composeRule.onNodeWithText("(120 m)", substring = true).getUnclippedBoundsInRoot()
        assertTrue("distance should keep width, was ${bounds.right - bounds.left}", bounds.right - bounds.left > 0.dp)
        assertTrue("distance should stay within the row, right was ${bounds.right}", bounds.right <= 412.dp)
    }

    @Test
    fun `a near-me rail place renders its one-line header with the platform and distance at a large scale`() {
        // The one-line header (SPEC D8 redesign): place name, then " – Platform 2", then the dimmed
        // distance, on a single title-case line. The place name clips (ellipsis) first while the
        // qualifier and distance are reserved; the compass moves into the spoken label ("Platform 2,
        // Southbound"), off the visible line. Logic-only — no baseline. Public station name +
        // synthetic distance only (SPEC *Privacy*).
        val stop = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras International",
            listOf(dep("victoria", "Victoria", "inbound", "Brixton", 120, "Southbound - Platform 2")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                            now,
                            {},
                            stopDistanceMeters = mapOf("940GZZLUKSX" to 1200.0),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The reserved distance keeps width and stays within the header row while the long name clips
        // — the reserved-trailing-element guard.
        val dist = composeRule.onNodeWithText("(1.2 km)", substring = true).getUnclippedBoundsInRoot()
        assertTrue("distance should keep width, was ${dist.right - dist.left}", dist.right - dist.left > 0.dp)
        assertTrue("distance should stay within the row, right was ${dist.right}", dist.right <= 412.dp)
        // The platform sits on the one line, its compass in the spoken label the header announces.
        composeRule.onNodeWithText("– Platform 2", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Platform 2, Southbound", substring = true).assertExists()
    }

    @Test
    fun `two platforms of one cluster each get a one-line header with the place name and platform`() {
        // Two members of one cluster (TfL's stationNaptan), each a different platform: each platform is
        // its own combined one-line header, the place name repeated on each ("King's Cross – Platform
        // 2", "King's Cross – Platform 1"), the compass in the spoken label. Logic-only — no baseline.
        // Public station name only (SPEC *Privacy*).
        val stop = StopArrivals(
            "940GZZLUKSX", "King's Cross",
            listOf(dep("circle", "Circle", "inbound", "Edgware Road", 120, "Eastbound - Platform 2")),
            fetchedAt = now.minusSeconds(60), clusterId = "940GZZLUKSX",
        )
        val other = StopArrivals(
            "940GZZLUKSX-W", "King's Cross",
            listOf(dep("circle", "Circle", "outbound", "Aldgate", 180, "Westbound - Platform 1")),
            fetchedAt = now.minusSeconds(60), clusterId = "940GZZLUKSX",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(stop, other), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // The place name repeats — one combined header per platform.
        composeRule.onAllNodesWithText("King's Cross").assertCountEquals(2)
        composeRule.onNodeWithText("– Platform 2", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Platform 2, Eastbound", substring = true).assertExists()
        composeRule.onNodeWithText("– Platform 1", substring = true).assertExists()
        composeRule.onNodeWithContentDescription("Platform 1, Westbound", substring = true).assertExists()
    }

    @Test
    fun `a bus stop whose routes all head one way shows the terminus`() {
        // The bus analog of the rail compass: a compass-less bus place where every route heads one
        // way is qualified "➔ BANK", so a rider reads the stop's direction off the header. Logic-only —
        // no baseline. Public route/place names only (SPEC *Privacy*).
        val stop = StopArrivals(
            "490G00TPL", "Turnpike Lane",
            listOf(
                dep("141", "141", "outbound", "Bank", 120, "", mode = "bus"),
                dep("341", "341", "outbound", "Bank", 300, "", mode = "bus"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Turnpike Lane", substring = true).assertExists()
        composeRule.onNodeWithText("➔ Bank", substring = true).assertExists()
    }

    @Test
    fun `a bus place with lettered poles splits into one sub-header per pole`() {
        // The bus analog of the rail platform split: two poles of one bus place (same display name)
        // carry stop letters, so under the one place name the place reads as "STOP D" and "STOP E"
        // sub-headers rather than a wall of cards (SPEC D8). Logic-only — no baseline. Public
        // route/place names only (SPEC *Privacy*).
        val poleD = StopArrivals(
            "490000129D", "King's Cross Station",
            listOf(dep("17", "17", "outbound", "Farringdon", 120, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60), stopLetter = "D", clusterId = "490G00247",
        )
        val poleE = StopArrivals(
            "490000129E", "King's Cross Station",
            listOf(dep("30", "30", "outbound", "Angel", 180, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60), stopLetter = "E", clusterId = "490G00247",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                    MainScreen(
                        DeparturesUiState.Loaded(listOf(poleD, poleE), now.minusSeconds(60)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        // One combined header per pole, the place name repeated, the letter as the qualifier segment.
        composeRule.onAllNodesWithText("King's Cross Station", substring = true).assertCountEquals(2)
        composeRule.onNodeWithText("– Stop D", substring = true).assertExists()
        composeRule.onNodeWithText("– Stop E", substring = true).assertExists()
        // The letter still announces its pole to a screen reader, in the header's spoken label.
        composeRule.onNodeWithContentDescription("Stop D", substring = true).assertExists()
    }

    @Test
    fun `a long bus terminus shares the header with the place name, neither crowded out`() {
        // On the one-line header the place name and the "➔ Terminus" qualifier SHARE the row (each
        // weighted), so a long terminus at a large font can't consume the whole line and crowd the
        // place name to zero — both keep at least their half and clip within it (Codex P1, PR #122).
        // The header announces "to Finsbury Park Interchange" to a screen reader. Logic-only — no
        // baseline. Public route/place names only.
        val stop = StopArrivals(
            "490G00TPL", "Turnpike Lane",
            listOf(dep("W3", "W3", "outbound", "Finsbury Park Interchange", 120, "", mode = "bus")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2.5f),
                ) {
                    Surface(modifier = Modifier.requiredWidth(360.dp).fillMaxHeight()) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                            now,
                            {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The place name keeps a positive width — not crowded to zero by the long terminus (the #2
        // guarantee): name and qualifier share the row.
        val nameBounds = composeRule.onNodeWithText("Turnpike Lane", substring = true).getUnclippedBoundsInRoot()
        assertTrue("place name should keep width, was ${nameBounds.right - nameBounds.left}", nameBounds.right - nameBounds.left > 0.dp)
        // The terminus segment is present and starts within the row (the cue survives, clipped if need be).
        val terminus = composeRule.onNodeWithText("➔ Finsbury", substring = true).getUnclippedBoundsInRoot()
        assertTrue("terminus should start within the row, left was ${terminus.left}", terminus.left < 360.dp)
        // The full terminus is the header's spoken label.
        composeRule.onNodeWithContentDescription("to Finsbury Park Interchange", substring = true).assertExists()
    }

    @Test
    fun `a station's platform headers each show the nearest member distance, never the farther one`() {
        // A clustered station whose member stop ids sit at different distances, split into platforms:
        // the one-line header repeats the place's NEAREST member distance on each platform header —
        // never the farther member's (Codex P2, PR #109). Logic-only — no baseline. Public station data
        // + synthetic distances only.
        val north = StopArrivals(
            "940GZZLUKSX-N", "King's Cross St. Pancras",
            listOf(dep("victoria", "Victoria", "outbound", "Walthamstow Central", 60, "Northbound - Platform 1")),
            fetchedAt = now.minusSeconds(60), clusterId = "940GZZLUKSX",
        )
        val south = StopArrivals(
            "940GZZLUKSX-S", "King's Cross St. Pancras",
            listOf(dep("victoria", "Victoria", "inbound", "Brixton", 120, "Southbound - Platform 2")),
            fetchedAt = now.minusSeconds(60), clusterId = "940GZZLUKSX",
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                MainScreen(
                    DeparturesUiState.Loaded(listOf(north, south), now.minusSeconds(60)),
                    now,
                    {},
                    stopDistanceMeters = mapOf("940GZZLUKSX-N" to 120.0, "940GZZLUKSX-S" to 300.0),
                )
            }
        }
        composeRule.waitForIdle()
        // Each platform header carries the nearest member's distance (repeated per platform), and the
        // farther member's distance never appears.
        composeRule.onAllNodesWithText("(120 m)", substring = true).assertCountEquals(2)
        composeRule.onNodeWithText("(300 m)", substring = true).assertDoesNotExist()
        // Both platforms render as their own combined headers.
        composeRule.onNodeWithText("– Platform 1", substring = true).assertExists()
        composeRule.onNodeWithText("– Platform 2", substring = true).assertExists()
    }

    @Test
    fun `via branch joined to the terminus with a slash`() {
        // The Northern line's branch (TfL's `towards` "via Charing Cross") joins the terminus
        // with a slash ("Battersea/Charing X", "Morden/Bank"), so a rider can pick the train by
        // its central trunk (SPEC destination-label). Canned public line/place names only (SPEC
        // *Privacy*). Both cards fit at the default width, so the pair shows in full; the tight
        // case where they truncate together is `via branch truncates both equally` below. The
        // first card feeds the raw "Battersea Power" terminus, so the render also proves the
        // hardcoded display rename to "Battersea" (DepartureLabels).
        val euston = StopArrivals(
            "940GZZLUEUS",
            "Euston",
            listOf(
                dep("northern", "Northern", "southbound", "Battersea Power", 120, "Platform 1", branch = "Charing Cross"),
                dep("northern", "Northern", "southbound", "Battersea Power", 480, "Platform 1", branch = "Charing Cross"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        val kennington = StopArrivals(
            "940GZZLUKNG",
            "Kennington",
            listOf(
                dep("northern", "Northern", "southbound", "Morden", 180, "Platform 3", branch = "Bank"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        capture("main-via-branch.png") {
            MainScreen(DeparturesUiState.Loaded(listOf(euston, kennington), now.minusSeconds(60)), now, {})
        }
        // "Battersea Power" renders as the renamed "Battersea"; each row carries its branch cue.
        // Both fit at this width, so the branch shows in full ("/Charing Cross", not the board
        // short form) — the tight case where it shortens is the next test.
        composeRule.onNodeWithText("Battersea").assertExists()
        composeRule.onNodeWithText("Battersea Power").assertDoesNotExist()
        composeRule.onNodeWithText("/Charing Cross").assertExists()
        composeRule.onNodeWithText("Morden").assertExists()
        composeRule.onNodeWithText("/Bank").assertExists()
    }

    @Test
    fun `via branch shows the trunk alone and bare under a large font scale`() {
        // At a large accessibility font scale on a narrow row the whole branch fills the column,
        // leaving less than the terminus's first glyph, so the branch takes the row **bare** — the
        // board short form "Charing X" with no leading slash (no orphaned "/"), never a mid-glyph
        // clip. The full name stays the accessible label. Public line/place names only (SPEC *Privacy*).
        val kennington = StopArrivals(
            "940GZZLUKNG",
            "Kennington",
            listOf(
                dep("northern", "Northern", "northbound", "High Barnet", 120, "Platform 1", branch = "Charing Cross"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                        MainScreen(
                            DeparturesUiState.Loaded(listOf(kennington), now.minusSeconds(60)),
                            now,
                            {},
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot("main-via-branch-equal.png")
        // The branch stands alone and bare — its board form with no leading slash — and the accessible
        // label keeps BOTH the full terminus and the branch, so a screen reader can still tell the
        // Bank and Charing Cross trains apart (Codex P2).
        composeRule.onNodeWithText("Charing X").assertExists()
        composeRule.onNodeWithText("/Charing X").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("High Barnet via Charing X").assertExists()
    }

    @Test
    fun `terminus re-abbreviates when the font scale grows`() {
        // Isolated so the font scale is the ONLY variable (Codex #102): DestinationLine alone in a
        // fixed-dp-width box with no countdown, so its BoxWithConstraints.maxWidth stays constant
        // (dp→px ignores font scale) while the text width grows. Otherwise a larger font also grows
        // the pill/countdown and shrinks maxWidth, which can trip the abbreviation even from a stale
        // cached width — hiding whether the widths actually re-measure. "North Finchley" fits at 1x
        // and must shorten to "N. Finchley" once the font grows: the widths must re-measure, not
        // reuse a value cached on the label alone. Public line/place names only (SPEC *Privacy*).
        val fontScale = mutableFloatStateOf(1f)
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = fontScale.floatValue),
                ) {
                    Surface {
                        Box(Modifier.width(150.dp)) {
                            DestinationLine(label = "North Finchley", times = emptyList(), stale = false, now = now)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // At 1x the full name fits the fixed width.
        composeRule.onNodeWithText("North Finchley").assertExists()
        // Grow the font with the width held fixed: the text must re-measure and shorten.
        fontScale.floatValue = 2f
        composeRule.waitForIdle()
        composeRule.onNodeWithText("N. Finchley").assertExists()
        composeRule.onNodeWithText("North Finchley").assertDoesNotExist()
    }

    @Test
    fun `Battersea slash Charing X via-branch renders with a slash`() {
        // The literal "Battersea/Charing X" width case (maintainer, PR #92): the label joined with
        // a slash, filling the row beside a full three-arrival countdown ("0 · 8 · 12
        // min"), with the slash against the terminus. Public line/place names only (SPEC *Privacy*).
        val euston = StopArrivals(
            "940GZZLUEUS",
            "Euston",
            listOf(
                dep("northern", "Northern", "southbound", "Battersea", 30, "Platform 1", branch = "Charing X"),
                dep("northern", "Northern", "southbound", "Battersea", 480, "Platform 1", branch = "Charing X"),
                dep("northern", "Northern", "southbound", "Battersea", 720, "Platform 1", branch = "Charing X"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        capture("main-via-branch-clip.png") {
            MainScreen(DeparturesUiState.Loaded(listOf(euston), now.minusSeconds(60)), now, {})
        }
        composeRule.onNodeWithText("Battersea").assertExists()
        composeRule.onNodeWithText("/Charing X").assertExists()
    }

    @Test
    fun `two branches of one terminus keep separate lines`() {
        // Same line, same direction, same terminus, two trunks — Northern to Edgware via
        // Bank and via Charing Cross. They must NOT merge onto one line: a merged countdown
        // would show the later train under the first train's branch, defeating the
        // disambiguation (Codex P1; AGENTS.md grouping). Public line/place names only.
        val stop = StopArrivals(
            "940GZZLUKNG",
            "Kennington",
            listOf(
                dep("northern", "Northern", "northbound", "Edgware", 120, "Platform 1", branch = "Bank"),
                dep("northern", "Northern", "northbound", "Edgware", 540, "Platform 2", branch = "Charing Cross"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
                }
            }
        }
        composeRule.waitForIdle()
        // Two Edgware lines, one per branch — a single merged line would show "Edgware" once.
        composeRule.onAllNodesWithText("Edgware").assertCountEquals(2)
        // Each line carries its own branch cue (Bank is short, so it's never abbreviated).
        composeRule.onNodeWithText("/Bank").assertExists()
    }

    @Test
    fun `equivalent branches merge into one line past the junction`() {
        // The maintainer's ask, end to end through the card: at Highgate (north of Camden Town,
        // past where the two central trunks join) a High Barnet train is the same service whichever
        // trunk it came up, so the topology merges the two into one line with no branch cue (the
        // card reads it from LocalRouteTopology, the way the provider wires it in MainActivity).
        // Logic-only — the merged card is a plain single line, so no baseline to eyeball; the
        // render proves the wiring. Public names only.
        val topology = RouteTopology(
            mapOf(
                "northern" to listOf(
                    RoutePattern(
                        "Bank",
                        listOf(
                            "940GZZLUHBT", "940GZZLUHGT", "940GZZLUCTN", "940GZZLUEUS",
                            "940GZZLUBNK", "940GZZLUKNG", "940GZZLUMDN",
                        ),
                        "High Barnet",
                        "Morden",
                    ),
                    RoutePattern(
                        "Charing X",
                        listOf(
                            "940GZZLUHBT", "940GZZLUHGT", "940GZZLUCTN", "940GZZLUMTC",
                            "940GZZLUEUS", "940GZZLUCHX", "940GZZLUKNG", "940GZZLUMDN",
                        ),
                        "High Barnet",
                        "Morden",
                    ),
                ),
            ),
        )
        val stop = StopArrivals(
            "940GZZLUHGT",
            "Highgate",
            listOf(
                dep("northern", "Northern", "northbound", "High Barnet", 120, "Platform 1", branch = "Bank"),
                dep("northern", "Northern", "northbound", "High Barnet", 300, "Platform 1", branch = "Charing X"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalRouteTopology provides topology) {
                        MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // One merged High Barnet line — a split would show "High Barnet" twice — and no branch cue.
        composeRule.onAllNodesWithText("High Barnet").assertCountEquals(1)
        composeRule.onNodeWithText("/Bank").assertDoesNotExist()
        composeRule.onNodeWithText("/Charing X").assertDoesNotExist()
    }

    @Test
    fun `loaded, dark`() {
        capture("main-loaded-dark.png", dark = true) {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(120)), now.minusSeconds(120), lineStatuses = statuses()),
                now,
                {},
            )
        }
    }

    // A single Brixton stop, so the fixture produces exactly one starrable row to pin.
    private fun oneStarrableStop() = StopArrivals(
        "940GZZLUKSX",
        "King's Cross St. Pancras",
        listOf(dep("victoria", "Victoria", "southbound", "Brixton", 120, "Platform 1")),
        fetchedAt = now.minusSeconds(60),
    )

    @Test
    fun `starred route, gold leading bar, light`() {
        // A pinned route wears a gold leading-edge bar on its interior row (SPEC D8), the per-row
        // accent that replaces the old whole-card border now a card holds several routes. Light theme
        // uses the deeper gold so the bar reads on the light card surface.
        val stop = oneStarrableStop()
        val row = DepartureRows.across(listOf(stop), now).single()
        capture("main-starred.png") {
            MainScreen(
                DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                now,
                {},
                starred = setOf(StarredRow.of(row)),
            )
        }
    }

    @Test
    fun `starred route, gold leading bar, dark`() {
        // The dark theme uses the brighter gold so the bar reads on the dark card surface.
        val stop = oneStarrableStop()
        val row = DepartureRows.across(listOf(stop), now).single()
        capture("main-starred-dark.png", dark = true) {
            MainScreen(
                DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)),
                now,
                {},
                starred = setOf(StarredRow.of(row)),
            )
        }
    }

    @Test
    fun `line colors across modes`() {
        // One card per line across the colored modes, so the new fills (DLR, Elizabeth,
        // Overground, Trams) and the APCA text picks render on real pills. Victoria and
        // Bakerloo are here too because APCA flips their text to white where WCAG-2 chose
        // black. Public line/destination names only (SPEC *Privacy*).
        val stop = StopArrivals(
            "940GZZLUMOD",
            "Modes",
            listOf(
                dep("victoria", "Victoria", "southbound", "Brixton", 60, "Platform 1"),
                dep("bakerloo", "Bakerloo", "northbound", "Elephant & Castle", 120, "Platform 2"),
                dep("dlr", "DLR", "outbound", "Bank", 180, "", mode = "dlr"),
                dep("elizabeth", "Elizabeth line", "eastbound", "Abbey Wood", 240, "", mode = "elizabeth-line"),
                dep("liberty", "Liberty", "outbound", "Upminster", 300, "", mode = "overground"),
                dep("tram", "Tram", "outbound", "Wimbledon", 360, "", mode = "tram"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        capture("main-line-colors.png") {
            MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
        }
        // Pills show the short line code; the full name stays the accessible label.
        composeRule.onNodeWithText("DLR").assertExists()
        composeRule.onNodeWithText("ELI").assertExists()
        composeRule.onNodeWithText("LIB").assertExists()
        composeRule.onNodeWithText("TRA").assertExists()
        composeRule.onNodeWithContentDescription("Elizabeth line").assertExists()
        composeRule.onNodeWithContentDescription("Liberty").assertExists()
    }

    // The six named Overground lines (TfL's 2024 renaming), each a hollow pill in its own
    // line color — the card surface shows through, the accent is the border and label. Public
    // line/destination names only (SPEC *Privacy*).
    private fun overgroundStop() = StopArrivals(
        "910GOVGRND",
        "Overground",
        listOf(
            dep("lioness", "Lioness", "outbound", "Watford Junction", 60, "", mode = "overground"),
            dep("mildmay", "Mildmay", "outbound", "Stratford", 120, "", mode = "overground"),
            dep("windrush", "Windrush", "outbound", "West Croydon", 180, "", mode = "overground"),
            dep("weaver", "Weaver", "outbound", "Chingford", 240, "", mode = "overground"),
            dep("suffragette", "Suffragette", "outbound", "Barking Riverside", 300, "", mode = "overground"),
            dep("liberty", "Liberty", "outbound", "Upminster", 360, "", mode = "overground"),
        ),
        fetchedAt = now.minusSeconds(60),
    )

    @Test
    fun `overground line pills, light`() {
        capture("main-overground.png") {
            MainScreen(DeparturesUiState.Loaded(listOf(overgroundStop()), now.minusSeconds(60)), now, {})
        }
        // Each named line shows its own hollow pill by its short code.
        listOf("LIO", "MIL", "WIN", "WEA", "SUF", "LIB").forEach {
            composeRule.onNodeWithText(it).assertExists()
        }
        // The full names stay the accessible labels.
        composeRule.onNodeWithContentDescription("Mildmay").assertExists()
        composeRule.onNodeWithContentDescription("Windrush").assertExists()
    }

    @Test
    fun `overground line pills, dark`() {
        capture("main-overground-dark.png", dark = true) {
            MainScreen(DeparturesUiState.Loaded(listOf(overgroundStop()), now.minusSeconds(60)), now, {})
        }
    }

    @Test
    fun `mixed age, one stop fresh and one stale`() {
        capture("main-mixed-age.png") {
            MainScreen(
                // King's Cross refreshed 30s ago; Oxford Circus hasn't refreshed in 10 min.
                // The whole-screen stamp stays fresh (the newest stop), while Oxford Circus
                // withholds its own countdowns — one screen-wide flag no longer decides for
                // both stops (SPEC D4).
                DeparturesUiState.Loaded(
                    stops(ksxFetchedAt = now.minusSeconds(30), oxcFetchedAt = now.minusSeconds(600)),
                    now.minusSeconds(30),
                    lineStatuses = statuses(),
                ),
                now,
                {},
            )
        }
        // The fresh stop shows a live countdown; the stale stop withholds its own ("?")
        // while staying on screen, rather than vanishing or being shown as live.
        composeRule.onNodeWithText("Brixton").assertExists()
        composeRule.onAllNodesWithText("?").onFirst().assertExists()
    }

    @Test
    fun `disruptions couldn't be checked`() {
        capture("main-disruptions-unknown.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(60)), now.minusSeconds(60), disruptionUnknown = true),
                now,
                {},
            )
        }
        // Arrivals shown, but their disruption status is flagged unverified, not clean.
        composeRule.onNodeWithText("Couldn't check for disruptions").assertExists()
    }

    @Test
    fun `stale, countdowns withheld`() {
        capture("main-stale.png") {
            MainScreen(DeparturesUiState.Loaded(stops(now.minusSeconds(600)), now.minusSeconds(600)), now, {})
        }
        // Past the staleness threshold the numbers are withheld, so the stamp prompts a
        // refresh instead of showing live-looking countdowns.
        composeRule.onNodeWithText("Tap to refresh").assertExists()
    }

    @Test
    fun `partial refresh warns`() {
        capture("main-partial.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(60)), now.minusSeconds(60), partialRefresh = true),
                now,
                {},
            )
        }
        composeRule.onNodeWithText("Some stops couldn't be refreshed").assertExists()
    }

    @Test
    fun `partial snapshot that then failed to refresh shows both notices`() {
        capture("main-partial-and-failed.png") {
            MainScreen(
                DeparturesUiState.Loaded(
                    stops(now.minusSeconds(120)),
                    now.minusSeconds(120),
                    partialRefresh = true,
                    refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
                ),
                now,
                {},
            )
        }
        // Both facts stay visible — the list is incomplete AND the refresh failed.
        composeRule.onNodeWithText("Some stops couldn't be refreshed").assertExists()
        composeRule.onNodeWithText("Couldn't refresh — you're offline").assertExists()
    }

    @Test
    fun `refresh failed, showing aged data`() {
        capture("main-refresh-failed.png") {
            MainScreen(
                DeparturesUiState.Loaded(
                    stops(now.minusSeconds(120)),
                    now.minusSeconds(120),
                    refreshFailure = DeparturesUiState.Error.Kind.OFFLINE,
                ),
                now,
                {},
            )
        }
        composeRule.onNodeWithText("Couldn't refresh — you're offline").assertExists()
    }

    @Test
    fun `nothing upcoming`() {
        capture("main-empty.png") {
            MainScreen(DeparturesUiState.Loaded(emptyList(), now.minusSeconds(30)), now, {})
        }
        composeRule.onNodeWithText("No upcoming departures").assertExists()
    }

    @Test
    fun `nothing upcoming from a stale snapshot`() {
        capture("main-stale-empty.png") {
            MainScreen(DeparturesUiState.Loaded(emptyList(), now.minusSeconds(600)), now, {})
        }
        // Stale + empty must not assert "none" from untrusted data (SPEC D4) — it prompts
        // a refresh instead of the fresh "No upcoming departures".
        composeRule.onNodeWithText("Departures may be out of date").assertExists()
    }

    @Test
    fun `empty rows with a stale retained stop prompt a refresh, not none`() {
        // One stop fresh, one stale, both with only departed services → no rows. The
        // freshest-stop stamp is fresh, but the stale stop's empty rows can't be trusted as
        // "no departures" — newer services it couldn't fetch may exist (SPEC D4). A
        // logic-only assertion, so it renders without capturing a baseline.
        val fresh = StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(dep("victoria", "Victoria", "southbound", "Brixton", -60, "Platform 1")),
            fetchedAt = now.minusSeconds(30),
        )
        val stale = StopArrivals(
            "940GZZLUOXC",
            "Oxford Circus",
            listOf(dep("central", "Central", "eastbound", "Hainault", -120, "Platform 3")),
            fetchedAt = now.minusSeconds(600),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loaded(listOf(fresh, stale), now.minusSeconds(30)), now, {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Departures may be out of date").assertExists()
    }

    @Test
    fun `a long line name and full merged countdown at a large font stay legible`() {
        // The worst case for width: the longest real line name (Hammersmith & City), the
        // full three-value merged label ("0 · 3 · 6 min"), a large font, and a narrow
        // screen — where an uncapped pill would consume the card and crush the countdown.
        // The pill is capped to half the card, and the countdown is the row's reserved
        // (unweighted) element, so it is measured first and keeps real width while the
        // destination beside it is hard-clipped (SPEC D8). Logic-only — no baseline.
        val stop = StopArrivals(
            "940GZZLUHSC",
            "Hammersmith",
            listOf(
                dep("hammersmith-city", "Hammersmith & City", "eastbound", "Barking", 30, "Platform 1"),
                dep("hammersmith-city", "Hammersmith & City", "eastbound", "Barking", 200, "Platform 1"),
                dep("hammersmith-city", "Hammersmith & City", "eastbound", "Barking", 380, "Platform 1"),
            ),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    // Bound the width to the config's screen width (411dp): a logic-only
                    // layout is otherwise measured unconstrained, where nothing competes for
                    // width and even an uncapped pill leaves the countdown room. The squeeze
                    // this guards only happens at a real, narrow width.
                    Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                        MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The pill shows the short line code, so even a long line name ("Hammersmith &
        // City" → "HAM") leaves the pill narrow and the countdown its room at a large font.
        // (The width cap on the pill modifier stays as a backstop, but the code alone keeps
        // it well under half the card.)
        val pillBounds = composeRule.onNodeWithText("HAM").getUnclippedBoundsInRoot()
        val pillWidth = pillBounds.right - pillBounds.left
        assertTrue("line pill should stay narrow, was $pillWidth", pillWidth <= 180.dp)
        // The full merged countdown keeps substantial reserved width (it leads with the
        // soonest times and ellipsizes only its tail if even the whole line is too short),
        // rather than collapsing to zero behind the pill.
        val countBounds = composeRule.onNodeWithText("0 · 3 · 6 min").getUnclippedBoundsInRoot()
        val countWidth = countBounds.right - countBounds.left
        assertTrue("merged countdown should keep width, was $countWidth", countWidth >= 100.dp)
    }

    @Test
    fun `the loading placeholder carries a pending stamp`() {
        // Cold start, before the persisted snapshot is read: the frame is a placeholder, but
        // it still shows a stamp ("Loading…") so the top bar is present from the first frame
        // and fills in with the real age when the snapshot arrives (SPEC snapshot-render).
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loading, now, {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Loading…").assertExists()
    }

    @Test
    fun `offline, dark`() {
        capture("main-error-offline.png", dark = true) {
            MainScreen(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), now, {})
        }
    }

    @Test
    fun `a destination that fits keeps its full name`() {
        // Shrink step, not always-on: at a normal width the full name shows, unabbreviated.
        val stop = StopArrivals(
            "940GZZLUEFY",
            "East Finchley",
            listOf(dep("northern", "Northern", "northbound", "East Finchley", 120, "Platform 1")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
                }
            }
        }
        composeRule.waitForIdle()
        // "East Finchley" shows in full both as the group header's place name and, unabbreviated, as
        // the route row's destination — two nodes (an abbreviated "E. Finchley" would leave only one).
        composeRule.onAllNodesWithText("East Finchley").assertCountEquals(2)
    }

    @Test
    fun `a long destination abbreviates common words before truncating`() {
        // At a large font on a narrow row the full name won't fit, so common whole words are
        // shortened ("Great Portland Street" -> "Gt Portland St") before any clean cut — while
        // the full name stays the accessible label. Logic-only — no baseline.
        val stop = StopArrivals(
            "940GZZLUGPS",
            "Great Portland Street",
            listOf(dep("hammersmith-city", "Hammersmith & City", "eastbound", "Great Portland Street", 60, "Platform 1")),
            fetchedAt = now.minusSeconds(60),
        )
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = base.density, fontScale = 2f),
                ) {
                    Surface(modifier = Modifier.requiredWidth(411.dp).fillMaxHeight()) {
                        MainScreen(DeparturesUiState.Loaded(listOf(stop), now.minusSeconds(60)), now, {})
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // The abbreviated form is what's rendered...
        composeRule.onNodeWithText("Gt Portland St").assertExists()
        // ...and a screen reader still hears the full destination.
        composeRule.onNodeWithContentDescription("Great Portland Street").assertExists()
    }

    @Test
    fun `update dot shows on the overflow when an update is available`() {
        capture("main-update-dot.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(120)), now.minusSeconds(120), lineStatuses = statuses()),
                now,
                {},
                updateAvailable = true,
            )
        }
        composeRule.onNodeWithTag(UPDATE_AVAILABLE_DOT_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `no update dot when no update is available`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(stops(now.minusSeconds(120)), now.minusSeconds(120)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(UPDATE_AVAILABLE_DOT_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `the loading screen offers the update when one is available`() {
        capture("main-loading-update.png") {
            MainScreen(DeparturesUiState.Loading, now, {}, updateAvailable = true)
        }
        composeRule.onNodeWithText("Loading departures…").assertExists()
        composeRule.onNodeWithText("Update available").assertExists()
    }

    @Test
    fun `the loading screen has no update button when none is available`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(DeparturesUiState.Loading, now, {})
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun `a low-confidence location shows the banner with a try-again over the list`() {
        capture("main-location-approximate.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(now.minusSeconds(30)), now.minusSeconds(30), lineStatuses = statuses()),
                now,
                {},
                locationBanner = LocationBanner.APPROXIMATE,
            )
        }
        composeRule.onNodeWithText("Showing your last-known area").assertExists()
        composeRule.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun `no location banner when the fix is current`() {
        composeRule.setContent {
            StopCastTheme(dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        DeparturesUiState.Loaded(stops(now.minusSeconds(30)), now.minusSeconds(30)),
                        now,
                        {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Showing your last-known area").assertDoesNotExist()
    }

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            StopCastTheme(darkTheme = dark, dynamicColor = false) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.waitForIdle()
        captureSnapshot(name)
    }

    /**
     * Draws the activity window into a PNG. Measured and laid out explicitly at the
     * device size — Robolectric's window has no real surface, so an unmeasured decor
     * view captures blank. Same helper shape as the sibling Snoozemo/Simmo repos.
     */
    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 2400) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
