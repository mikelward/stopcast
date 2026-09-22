package app.stopcast.ui

import android.graphics.Bitmap
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
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DismissedAlert
import app.stopcast.domain.LineRef
import app.stopcast.domain.LineStatus
import app.stopcast.domain.RoutePattern
import app.stopcast.domain.RouteTopology
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.StopDisruption
import app.stopcast.ui.theme.StopCastTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        // Circle is suspended with no arrivals, so it surfaces as a status row — a dash
        // ("none") where a countdown would sit (the "Suspended" chip carries the reason).
        composeRule.onNodeWithText("Suspended").assertExists()
        composeRule.onNodeWithText("–").assertExists()
        // The dash announces "No departures" to a screen reader rather than a bare glyph.
        composeRule.onNodeWithContentDescription("No departures").assertExists()
        // Oxford Circus has a stop-level disruption, shown as a stop-status row.
        composeRule.onNodeWithText("Station closed until further notice").assertExists()
        // Each group gets one combined title-case header, the place name repeated per platform (SPEC
        // D8). King's Cross splits into a Platform 1 group and a bare bus/status group; Oxford Circus
        // into its two platforms.
        composeRule.onAllNodesWithText("King's Cross St. Pancras").onFirst().assertExists()
        composeRule.onAllNodesWithText("Oxford Circus").onFirst().assertExists()
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
        // way is qualified "-> BANK", so a rider reads the stop's direction off the header. Logic-only —
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
        composeRule.onNodeWithText("-> Bank", substring = true).assertExists()
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
        // On the one-line header the place name and the "-> Terminus" qualifier SHARE the row (each
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
        val terminus = composeRule.onNodeWithText("-> Finsbury", substring = true).getUnclippedBoundsInRoot()
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
    fun `via branch truncates both equally under a large font scale`() {
        // At a large accessibility font scale on a narrow row, "High Barnet/Charing X" no longer
        // fits, so the terminus and branch share the width in proportion and both clip (SPEC
        // destination-label — equal truncation), rather than the branch taking the whole row.
        // The semantic strings stay whole (the clip is visual only), so the full name and the
        // trunk cue both remain the accessible label. Public line/place names only (SPEC *Privacy*).
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
        // Under pressure the terminus abbreviates ("High"→"H.") and the branch shortens to its
        // board form; the full name stays the accessible label. Both survive — the branch does
        // not take the whole row.
        composeRule.onNodeWithText("H. Barnet").assertExists()
        composeRule.onNodeWithContentDescription("High Barnet").assertExists()
        composeRule.onNodeWithText("/Charing X").assertExists()
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
