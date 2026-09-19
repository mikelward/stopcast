package app.trackmo.ui

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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.trackmo.domain.Departure
import app.trackmo.domain.LineRef
import app.trackmo.domain.LineStatus
import app.trackmo.domain.StopArrivals
import app.trackmo.domain.StopDisruption
import app.trackmo.ui.theme.TrackmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
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
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = mode,
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
            TrackmoTheme(dynamicColor = false) {
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
        // destination beside it ellipsizes (SPEC D8). Logic-only — no baseline.
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
            TrackmoTheme(dynamicColor = false) {
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
            TrackmoTheme(dynamicColor = false) {
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
    fun `the locate action shows left of refresh and invokes the callback`() {
        var located = false
        composeRule.setContent {
            TrackmoTheme(dynamicColor = false) {
                Surface {
                    MainScreen(
                        DeparturesUiState.Loaded(stops(now.minusSeconds(60)), now.minusSeconds(60)),
                        now,
                        onRefresh = {},
                        onLocateHere = { located = true },
                    )
                }
            }
        }
        composeRule.onNodeWithContentDescription("Stops near me").assertExists().performClick()
        assertTrue(located)
    }

    private fun capture(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        composeRule.setContent {
            TrackmoTheme(darkTheme = dark, dynamicColor = false) {
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
