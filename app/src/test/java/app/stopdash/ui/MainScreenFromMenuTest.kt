package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.ui.theme.StopDashTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The overflow's trip entries (SPEC *Finding stops → From… To…*): "From…" opens the station search
 * through `onFindStation`, and "To…" starts a trip from the stops near the rider through `onPlanTo`. The location
 * screen's own button keeps "Find a station" ([LocationGateScreenshotTest]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenFromMenuTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    @Test
    fun overflowMenu_fromOpensStationSearch() {
        var searchOpened = false
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    onFindStation = { searchOpened = true },
                )
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("Find a station").assertDoesNotExist()
        composeRule.onNodeWithText("From…").performClick()
        composeRule.runOnIdle { assertTrue(searchOpened) }
    }

    @Test
    fun overflowMenu_hidesFromWithoutASearch() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = DeparturesUiState.Loading, now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("From…").assertDoesNotExist()
    }

    @Test
    fun overflowMenu_toPlansATripFromHere() {
        var planned = false
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    onPlanTo = { planned = true },
                )
            }
        }

        // On the near-me list To… is an overflow item, not an app-bar action.
        composeRule.onNodeWithText("To…").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("To…").performClick()
        composeRule.runOnIdle { assertTrue(planned) }
    }

    @Test
    fun appBar_toButtonPlansATripInOneTap() {
        var planned = false
        composeRule.setContent {
            StopDashTheme {
                MainScreen(
                    state = DeparturesUiState.Loading,
                    now = now,
                    onRefresh = {},
                    onPlanTo = { planned = true },
                )
            }
        }

        // The Directions button beside the crosshairs: To… without opening the overflow.
        composeRule.onNodeWithContentDescription("To…").performClick()
        composeRule.runOnIdle { assertTrue(planned) }
    }

    @Test
    fun appBar_noToButtonWithoutATrip() {
        composeRule.setContent {
            StopDashTheme {
                MainScreen(state = DeparturesUiState.Loading, now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithContentDescription("To…").assertDoesNotExist()
    }
}
