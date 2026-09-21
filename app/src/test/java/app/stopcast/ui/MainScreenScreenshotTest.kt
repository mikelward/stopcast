package app.stopcast.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRows
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
        composeRule.onNodeWithText("Due · 4 min").assertExists()
        // A branching direction (Central eastbound) keeps its headline destination and its
        // divergent one apart, so neither countdown sits under the wrong destination.
        composeRule.onNodeWithText("Hainault").assertExists()
        composeRule.onNodeWithText("Woodford").assertExists()
        // The disrupted Victoria line is flagged (SPEC D3).
        composeRule.onNodeWithText("Severe Delays").assertExists()
        // Circle is suspended with no arrivals, so it surfaces as a status row — a dash
        // ("none") where a countdown would sit (the "Suspended" chip carries the reason).
        composeRule.onNodeWithText("Suspended").assertExists()
        composeRule.onNodeWithText("–").assertExists()
        // The dash announces "No departures" to a screen reader rather than a bare glyph.
        composeRule.onNodeWithContentDescription("No departures").assertExists()
        // Oxford Circus has a stop-level disruption, shown as a stop-status row.
        composeRule.onNodeWithText("Station closed until further notice").assertExists()
        // Cards are grouped under one bare stop-name header per stop (SPEC D8). The
        // direction/terminus qualifier is a follow-up, so the header is the name alone.
        composeRule.onNodeWithText("KING'S CROSS ST. PANCRAS").assertExists()
        composeRule.onNodeWithText("OXFORD CIRCUS").assertExists()
    }

    // The busiest interchange on the network: King's Cross St. Pancras, six Underground lines
    // both ways. Real line ids and termini so the pills and destinations render as they would
    // on the day. Public infrastructure / line names only (SPEC *Privacy*).
    private fun kingsCrossStPancras() = StopArrivals(
        "940GZZLUKSX",
        "King's Cross St. Pancras",
        listOf(
            dep("victoria", "Victoria", "northbound", "Walthamstow Central", 60, "Northbound - Platform 1"),
            dep("victoria", "Victoria", "southbound", "Brixton", 210, "Southbound - Platform 2"),
            dep("piccadilly", "Piccadilly", "eastbound", "Cockfosters", 120, "Eastbound - Platform 3"),
            dep("piccadilly", "Piccadilly", "westbound", "Heathrow Terminal 5", 330, "Westbound - Platform 4"),
            dep("northern", "Northern", "northbound", "High Barnet", 90, "Northbound - Platform 5", branch = "Bank"),
            dep("northern", "Northern", "southbound", "Morden", 240, "Southbound - Platform 6", branch = "Bank"),
            // Circle, Hammersmith & City and Metropolitan share the sub-surface platforms here,
            // so all three run eastbound (Platform 2) / westbound (Platform 1) at King's Cross —
            // not the Metropolitan's whole-network northbound/southbound convention.
            dep("circle", "Circle", "eastbound", "Edgware Road", 300, "Eastbound - Platform 2"),
            dep("circle", "Circle", "westbound", "Hammersmith", 390, "Westbound - Platform 1"),
            dep("metropolitan", "Metropolitan", "eastbound", "Aldgate", 180, "Eastbound - Platform 2"),
            dep("metropolitan", "Metropolitan", "westbound", "Uxbridge", 270, "Westbound - Platform 1"),
            dep("hammersmith-city", "Hammersmith & City", "eastbound", "Barking", 150, "Eastbound - Platform 2"),
            dep("hammersmith-city", "Hammersmith & City", "westbound", "Hammersmith", 420, "Westbound - Platform 1"),
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
    fun `the most connected station renders under one bare-name header`() {
        // The wall the large-station grain decision turns on (TODO): King's Cross' six lines both
        // ways sit under a single bare "KING'S CROSS ST. PANCRAS" header today, with direction
        // living only in each card's destination. A nearby bus stop makes the multi-stop header
        // render (a single stop would imply itself) and sits below the wall — a busy interchange
        // overflows one screen. This captures the shipped one-per-stop behavior: the "before"
        // for any grain change.
        // Wire the bundled branch topology the way MainActivity does, so the capture is the real
        // production "before" and not the unwired RouteTopology.EMPTY fallback. King's Cross is on
        // the Northern line's Bank (City) branch alone — the Charing Cross branch runs Camden Town →
        // Mornington Crescent → Warren Street → Charing Cross, not via King's Cross — so only one
        // trunk serves this stop and the topology drops the redundant ", Bank" cue here (High Barnet,
        // Morden render bare). EMPTY would instead keep ", Bank", which production never shows.
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
        // The header and the two soonest cards are within the composed window; the bus stop's own
        // block is below the wall (off-screen, so not in the semantics tree here). Real lines
        // render their own pills, with direction only on the cards — the grain question.
        composeRule.onNodeWithText("KING'S CROSS ST. PANCRAS").assertExists()
        composeRule.onNodeWithText("Walthamstow Central").assertExists()
        composeRule.onNodeWithText("Cockfosters").assertExists()
        // The Northern rows here are Bank-branch-only at King's Cross, so the topology drops the
        // ", Bank" cue — the production render, not the RouteTopology.EMPTY fallback.
        composeRule.onNodeWithText("High Barnet").assertExists()
        composeRule.onNodeWithText(", Bank").assertDoesNotExist()
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
        // One "TURNPIKE LANE" header, not two — the two poles merged into one place.
        composeRule.onAllNodesWithText("TURNPIKE LANE").assertCountEquals(1)
        composeRule.onNodeWithText("MANOR HOUSE").assertExists()
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
        // Meters below a kilometer, km above — each stop's own distance, not one shared value.
        // The distance is a reserved node beside the name, so name and distance read separately.
        composeRule.onNodeWithText("KING'S CROSS ST. PANCRAS").assertExists()
        composeRule.onNodeWithText("(120 m)", substring = true).assertExists()
        composeRule.onNodeWithText("OXFORD CIRCUS").assertExists()
        composeRule.onNodeWithText("(1.2 km)", substring = true).assertExists()
    }

    @Test
    fun `a hub-wide alert repeated across an interchange shows once, titled on expand`() {
        // TfL reports a hub-wide notice (a lift outage) against every stop point in an
        // interchange, so the near-me set carries the identical text once per member. Those
        // members share one hubNaptanCode, so the notice folds by hub identity to a single
        // header-less card — collapsed it is the notice's first line, and tapping expands it to
        // the interchange name over the full text. Synthetic accessibility copy + public station
        // ids/names only (SPEC *Privacy*).
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
        // Deduped to one card, header-less — no per-member name header, collapsed or otherwise.
        composeRule.onAllNodesWithText(notice).assertCountEquals(1)
        composeRule.onNodeWithText("LONDON ST PANCRAS INTERNATIONAL", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("KING'S CROSS ST. PANCRAS").assertDoesNotExist()
        // The interchange title appears only on expand, not on the collapsed card.
        composeRule.onNodeWithText(hubName).assertDoesNotExist()
        // Tapping the collapsed alert expands it in place: the interchange name titles it, over
        // the full notice text.
        composeRule.onNodeWithText(notice).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(hubName).assertExists()
        captureSnapshot("main-near-me-hub-alert-expanded.png")
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
        composeRule.onNodeWithText("KING'S CROSS ST. PANCRAS").assertExists()
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
    fun `via branch joined to the terminus in list style`() {
        // The Northern line's branch (TfL's `towards` "via Charing Cross") joins the terminus
        // in list style ("Morden, Bank"), so a rider can pick the train by its central trunk
        // (SPEC destination-label). Canned public line/place names only (SPEC *Privacy*).
        // Two cards, so the layout shows both behaviors: a short destination lets the branch
        // sit fully beside it (Morden, Bank), while a long one keeps the branch (the trunk
        // cue) by shortening it to the board's own form and hard-clipping the destination to
        // make room (Batter, Charing X) — the branch outranks the terminus (SPEC destination-label).
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
        // The destination's semantic text stays the full "Battersea Power" even where it's
        // visually truncated; the branch shortens to the board's form where the row is tight,
        // and stays full where it fits.
        composeRule.onNodeWithText("Battersea Power").assertExists()
        composeRule.onNodeWithText(", Charing X").assertExists()
        composeRule.onNodeWithText("Morden").assertExists()
        composeRule.onNodeWithText(", Bank").assertExists()
    }

    @Test
    fun `Battersea, Charing X via-branch renders as a comma list`() {
        // The literal "Battersea, Charing X" width case (maintainer, PR #92): the label joined in
        // plain list style, filling the row beside a full three-arrival countdown ("Due · 8 · 12
        // min"), with the comma against the terminus. Public line/place names only (SPEC *Privacy*).
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
        composeRule.onNodeWithText(", Charing X").assertExists()
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
        composeRule.onNodeWithText(", Bank").assertExists()
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
        composeRule.onNodeWithText(", Bank").assertDoesNotExist()
        composeRule.onNodeWithText(", Charing X").assertDoesNotExist()
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
    fun `starred card, gold border, light`() {
        // A pinned card carries a gold border and no in-row star element (SPEC D8). Light theme
        // uses the deeper gold so the border reads on the light card surface.
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
    fun `starred card, gold border, dark`() {
        // The dark theme uses the brighter gold so the border reads on the dark card surface.
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
        // full three-value merged label ("Due · 3 · 6 min"), a large font, and a narrow
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
        val countBounds = composeRule.onNodeWithText("Due · 3 · 6 min").getUnclippedBoundsInRoot()
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
        composeRule.onNodeWithText("East Finchley").assertExists()
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
