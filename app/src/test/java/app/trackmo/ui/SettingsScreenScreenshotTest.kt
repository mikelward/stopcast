package app.trackmo.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.trackmo.ui.theme.TrackmoTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Settings screen in both toggle states (SPEC D5): the "refresh widget every minute" row off
 * (the default) and on. The composable is UI-only — persistence and the WorkManager scheduler are
 * the caller's job — so it renders under Robolectric with no Android services and no user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settings_off() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh widget every minute").assertIsDisplayed()
        captureSnapshot("settings-off.png")
    }

    @Test
    fun settings_on() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(liveWidgetRefresh = true, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        captureSnapshot("settings-on.png")
    }

    @Test
    fun settings_scheduleError() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = true,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't update live refresh").assertIsDisplayed()
        captureSnapshot("settings-error.png")
    }

    /** The error notice is shown only when scheduling failed, and Dismiss reports the dismissal. */
    @Test
    fun dismissingTheError_reportsIt() {
        var dismissed = false
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = true,
                    onDismissLiveWidgetRefreshError = { dismissed = true },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Dismiss").performClick()
        composeRule.runOnIdle { assert(dismissed) }
    }

    @Test
    fun noErrorRow_whenSchedulingSucceeded() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(
                    liveWidgetRefresh = true,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                    liveWidgetRefreshFailed = false,
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't update live refresh").assertDoesNotExist()
    }

    /**
     * Until the persisted setting has been read (a slow or persistently-failing DataStore read
     * leaves the flow silent), the switch is disabled so the user can't act on an off value that
     * may not reflect the stored choice (Codex P2 on #56).
     */
    @Test
    fun theSwitchIsDisabled_whileTheSettingHasNotLoaded() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    liveWidgetRefreshEnabled = false,
                    onLiveWidgetRefreshChange = {},
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsNotEnabled()
    }

    /**
     * The row reflects its state and reports a flip: tapping the row (not just the switch) toggles
     * it, since the whole row is the tap target.
     */
    @Test
    fun tappingTheRow_reportsTheToggle() {
        var latest: Boolean? = null
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(
                    liveWidgetRefresh = false,
                    onLiveWidgetRefreshChange = { latest = it },
                    onBack = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Refresh widget every minute").performClick()
        composeRule.runOnIdle { assert(latest == true) }
    }

    /** The switch reflects the state passed in — off by default, on when enabled. */
    @Test
    fun theSwitchReflectsTheState() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(liveWidgetRefresh = true, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOn()
    }

    @Test
    fun theSwitchIsOffByDefault() {
        composeRule.setContent {
            TrackmoTheme {
                SettingsScreen(liveWidgetRefresh = false, onLiveWidgetRefreshChange = {}, onBack = {})
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(androidx.compose.ui.test.isToggleable()).assertIsOff()
    }

    /**
     * Whether this run is one that touches PNGs at all — matches [LicensesScreenshotTest]. Without
     * a flag the screenshot tests still render and assert, they just don't record, so
     * `./gradlew test` doesn't rewrite snapshots on every machine.
     */
    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
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
