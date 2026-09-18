package app.trackmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.trackmo.domain.Departure
import app.trackmo.domain.StopArrivals
import app.trackmo.ui.theme.TrackmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
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
    ) = Departure(
        lineId = lineId,
        lineName = lineName,
        direction = direction,
        destination = destination,
        platform = platform,
        expectedArrival = now.plusSeconds(offsetSeconds),
    )

    private fun stops(): List<StopArrivals> = listOf(
        StopArrivals(
            "940GZZLUKSX",
            "King's Cross St. Pancras",
            listOf(
                dep("victoria", "Victoria", "southbound", "Brixton", 40, "Platform 1"),
                dep("victoria", "Victoria", "southbound", "Brixton", 240, "Platform 1"),
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
        ),
    )

    @Test
    fun `loaded, light`() {
        capture("main-loaded.png") {
            MainScreen(DeparturesUiState.Loaded(stops(), now.minusSeconds(120)), now, {})
        }
        composeRule.onNodeWithText("Brixton").assertExists()
    }

    @Test
    fun `loaded, dark`() {
        capture("main-loaded-dark.png", dark = true) {
            MainScreen(DeparturesUiState.Loaded(stops(), now.minusSeconds(120)), now, {})
        }
    }

    @Test
    fun `stale, countdowns withheld`() {
        capture("main-stale.png") {
            MainScreen(DeparturesUiState.Loaded(stops(), now.minusSeconds(600)), now, {})
        }
        // Past the staleness threshold the numbers are withheld, so the stamp prompts a
        // refresh instead of showing live-looking countdowns.
        composeRule.onNodeWithText("Tap to refresh").assertExists()
    }

    @Test
    fun `partial refresh warns`() {
        capture("main-partial.png") {
            MainScreen(
                DeparturesUiState.Loaded(stops(), now.minusSeconds(60), partialRefresh = true),
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
                    stops(),
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
                    stops(),
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
    fun `offline, dark`() {
        capture("main-error-offline.png", dark = true) {
            MainScreen(DeparturesUiState.Error(DeparturesUiState.Error.Kind.OFFLINE), now, {})
        }
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
