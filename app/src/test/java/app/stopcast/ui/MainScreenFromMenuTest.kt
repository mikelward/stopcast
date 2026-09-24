package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopcast.ui.theme.StopCastTheme
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The overflow's station-search entry (SPEC *Finding stops*): labeled "From…" — the start of a
 * trip, with a "To…" to follow — and opening the search through `onFindStation`. The location
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
            StopCastTheme {
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
            StopCastTheme {
                MainScreen(state = DeparturesUiState.Loading, now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithContentDescription("More options").performClick()
        composeRule.onNodeWithText("From…").assertDoesNotExist()
    }
}
