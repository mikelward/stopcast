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
import app.trackmo.ui.theme.TrackmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `LocationGate` in each state the nearby-stops search can be in before it resolves (SPEC
 * *Finding stops*): asking for permission, locating, no fix, none nearby, and a TfL
 * failure. Each renders from fixture state alone (the gate's pure `state`-in signature),
 * so the same function drives the app and these snapshots. Canned wording only — no user
 * data (SPEC *Privacy*); dynamic color off for deterministic schemes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocationGateScreenshotTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `permission required, light`() {
        capture("location-permission.png") {
            LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Departures near you").assertExists()
        composeRule.onNodeWithText("Allow location").assertExists()
    }

    @Test
    fun `permission required, dark`() {
        capture("location-permission-dark.png", dark = true) {
            LocationGate(NearbyStopsViewModel.State.PermissionRequired, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
    }

    @Test
    fun `permission permanently denied offers settings`() {
        capture("location-denied.png") {
            LocationGate(
                NearbyStopsViewModel.State.PermissionRequired,
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
                permanentlyDenied = true,
            )
        }
        composeRule.onNodeWithText("Open settings").assertExists()
    }

    @Test
    fun `finding`() {
        capture("location-finding.png") {
            LocationGate(NearbyStopsViewModel.State.Locating, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Finding stops near you…").assertExists()
    }

    @Test
    fun `no fix`() {
        capture("location-no-fix.png") {
            LocationGate(NearbyStopsViewModel.State.NoLocation, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("Couldn't get your location").assertExists()
    }

    @Test
    fun `no stops nearby`() {
        capture("location-empty.png") {
            LocationGate(NearbyStopsViewModel.State.Empty, onAllow = {}, onRetry = {}, onOpenSettings = {})
        }
        composeRule.onNodeWithText("No stops found nearby").assertExists()
    }

    @Test
    fun `lookup failed`() {
        capture("location-error.png") {
            LocationGate(
                NearbyStopsViewModel.State.Failed(DeparturesUiState.Error.Kind.OFFLINE),
                onAllow = {},
                onRetry = {},
                onOpenSettings = {},
            )
        }
        composeRule.onNodeWithText("You're offline").assertExists()
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
     * Draws the activity window into a PNG. Measured and laid out explicitly at the device
     * size — Robolectric's window has no real surface, so an unmeasured decor view captures
     * blank. Same helper shape as `MainScreenScreenshotTest` and the sibling repos.
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
