package app.trackmo.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.trackmo.ui.theme.TrackmoTheme
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The About entry point wired through the departures top bar: the overflow menu opens an About
 * dialog, whose one action opens the licenses screen (its `onOpenLicenses` callback), and whose
 * Close dismisses it without navigating. The licenses list itself is covered by
 * [LicensesScreenshotTest]; this pins the menu → dialog → callback flow, which mounting
 * `LicensesContent` directly can't reach. Rendered over the `Loading` state — the top bar is
 * present in every state, so no departures fixture is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenAboutTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Test
    fun overflowMenu_opensAbout_thenLicenses() {
        var licensesOpened = false
        composeRule.setContent {
            TrackmoTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    onOpenLicenses = { licensesOpened = true },
                )
            }
        }

        // Overflow (content description "About") opens the menu; the menu item (text) opens the
        // dialog — the two match different semantics, so neither selector is ambiguous.
        composeRule.onNodeWithContentDescription("About").performClick()
        composeRule.onNodeWithText("About").performClick()

        // The dialog is up, showing its one action (the app name also appears here, but it
        // collides with the top-bar title, so the action is the unambiguous marker).
        composeRule.onNodeWithText("Open source licenses").assertIsDisplayed()

        composeRule.onNodeWithText("Open source licenses").performClick()
        composeRule.runOnIdle { assertTrue(licensesOpened) }
    }

    @Test
    fun aboutDialog_closeDismissesWithoutNavigating() {
        var licensesOpened = false
        composeRule.setContent {
            TrackmoTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    onOpenLicenses = { licensesOpened = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("About").performClick()
        composeRule.onNodeWithText("About").performClick()
        composeRule.onNodeWithText("Open source licenses").assertIsDisplayed()

        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithText("Open source licenses").assertDoesNotExist()
        composeRule.runOnIdle { assertFalse(licensesOpened) }
    }
}
