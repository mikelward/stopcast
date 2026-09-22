package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopcast.domain.Departure
import app.stopcast.domain.StopArrivals
import app.stopcast.ui.theme.StopCastTheme
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A TAP on a departure/route card opens the detail view (SPEC D8 / `TODO.md`) — a deliberate no-op
 * before this. A long-press still pins ([StarLongPressTest]); this pins the tap wiring and that the
 * dialog closes on dismiss. The detail's own contents are covered by [RouteDetailDialogScreenshotTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteDetailTapTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private val stop = StopArrivals(
        stopId = "940GZZLUKSX",
        stopName = "King's Cross St. Pancras",
        departures = listOf(
            Departure("victoria", "Victoria", "southbound", "Brixton", null, now.plusSeconds(120), "tube"),
        ),
        fetchedAt = now,
    )

    private fun loaded() = DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now)

    @Test
    fun `tapping a card opens the route detail with its star`() {
        composeRule.setContent {
            StopCastTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {})
            }
        }

        // The list card marks a pin with a border only (no star control), so the dialog's star
        // icon — found by its "Pin to top" contentDescription — appearing means the detail opened.
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
    }

    @Test
    fun `a checked good-service line shows no disruptions even when another line is unknown`() {
        // disruptionUnknown is set by some OTHER line, but this row's line was determined (good
        // service): its detail must not claim "couldn't check" — that's the per-line fix (Codex).
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = setOf("victoria"),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        // Scope to the dialog: the screen also shows a "Couldn't check for disruptions" banner
        // whenever the aggregate flag is set, so the assertion must be about the dialog's own text.
        // The dialog naming "No disruptions reported" proves this checked-clean row isn't tainted
        // by the other line's uncertainty.
        composeRule.onNode(isDialog()).assert(hasAnyDescendant(hasText("No disruptions reported")))
    }

    @Test
    fun `a determined line whose stop disruption failed says couldnt check`() {
        // The line WAS determined (good service), but this stop's own disruption lookup (a
        // closure/move) failed — the two axes are independent. The detail must not claim the row
        // is clean when a stop-level disruption was never checked (SPEC principle 1, Codex on #100).
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = setOf("victoria"),
                        stopsDisruptionUnknown = setOf("940GZZLUKSX"),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNode(isDialog()).assert(hasAnyDescendant(hasText("Couldn't check for disruptions")))
    }

    @Test
    fun `an unchecked line's detail says couldnt check`() {
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = DeparturesUiState.Loaded(
                        stops = listOf(stop),
                        fetchedAt = now,
                        disruptionUnknown = true,
                        determinedLineIds = emptySet(),
                    ),
                    now = now,
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        // Scoped to the dialog (the screen banner carries the same text): the dialog itself names
        // the unchecked state rather than claiming the row is clean.
        composeRule.onNode(isDialog()).assert(hasAnyDescendant(hasText("Couldn't check for disruptions")))
    }

    @Test
    fun `the detail closes for good when its row leaves the list`() {
        // The open route's last departure passes (or the stop is pruned), so its row leaves the
        // list. The dialog must close AND stay closed — a later refresh reproducing the same
        // stop/line/direction identity must not silently reopen it (the saved key is cleared).
        val present = loaded()
        val gone = DeparturesUiState.Loaded(
            stops = listOf(stop.copy(departures = emptyList())),
            fetchedAt = now,
        )
        var current by mutableStateOf<DeparturesUiState>(present)
        composeRule.setContent {
            StopCastTheme {
                MainScreen(state = current, now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()

        // The row leaves the list.
        composeRule.runOnUiThread { current = gone }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()

        // The same identity returns on a later refresh — the dialog stays closed.
        composeRule.runOnUiThread { current = present }
        composeRule.waitForIdle()
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
    }

    @Test
    fun `closing the detail dismisses it`() {
        composeRule.setContent {
            StopCastTheme {
                MainScreen(state = loaded(), now = now, onRefresh = {})
            }
        }

        composeRule.onNodeWithText("Brixton").performTouchInput { click() }
        composeRule.onNodeWithText("Close").performClick()
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
    }
}
