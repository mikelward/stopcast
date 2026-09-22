package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.LineStatus
import app.stopcast.domain.StopArrivals
import app.stopcast.ui.theme.StopCastTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tap-to-open route detail (SPEC D8 / *Disruptions*): the discoverable home for the star (the
 * list card only long-presses to pin) and for the line's full disruption text, which the compact
 * chip stands in for. Collapsed the alert is the reason's first line; tapping expands it — the same
 * widget the stop-closure card uses. The composable is pure, so it renders under Robolectric with
 * nothing wired. Pins the chip + collapsed-alert layout as a golden and the star / expand behavior.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RouteDetailDialogScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    // TfL's prose behind the "Severe Delays" chip — deliberately long, so the collapsed one-line
    // clip and the tap-to-expand are exercised. Synthetic wording, a well-known line as an example.
    private val reason =
        "Victoria line: Severe delays while we fix a signal failure at Victoria. " +
            "Tickets will be accepted on local buses and London Overground."

    private fun disruptedRow(): DepartureRow {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube"),
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(360), "tube"),
            ),
            fetchedAt = now,
        )
        val statuses = mapOf("victoria" to LineStatus("victoria", 6, "Severe Delays", reason))
        return DepartureRows.across(listOf(stop), now, statuses).first { it.upcoming.isNotEmpty() }
    }

    private fun healthyRow(): DepartureRow {
        val stop = StopArrivals(
            stopId = "940GZZLUVIC",
            stopName = "Victoria",
            departures = listOf(
                Departure("victoria", "Victoria", "northbound", "Walthamstow Central", null, now.plusSeconds(120), "tube"),
            ),
            fetchedAt = now,
        )
        return DepartureRows.across(listOf(stop), now).first { it.upcoming.isNotEmpty() }
    }

    @Test
    fun disruptedRoute_showsChipStarAndCollapsedAlert() {
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        // The discoverable star — a top-right icon button whose contentDescription labels the
        // action, unlike the list card's border-only mark.
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
        composeRule.onNodeWithText("Towards Walthamstow Central", substring = true).assertIsDisplayed()
        // Collapsed: the alert shows its first line (clipped), the chevron marks it expandable.
        composeRule.onNodeWithText(reason, substring = true).assertIsDisplayed()

        if (capturing()) {
            composeRule.onNode(isDialog())
                .captureRoboImage(filePath = "src/test/snapshots/images/route-detail-disrupted.png")
        }
    }

    @Test
    fun tappingTheAlert_expandsToTheFullText() {
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Tapping the alert (its first-line text, inside the clickable surface) toggles expand;
        // the golden captures the full text unclipped. "Show full alert"/"Show less" are the
        // surface's click-action LABEL, not displayed text, so the golden is the assertion here.
        composeRule.onNodeWithText(reason, substring = true).performTouchInput { click() }
        composeRule.waitForIdle()

        if (capturing()) {
            composeRule.onNode(isDialog())
                .captureRoboImage(filePath = "src/test/snapshots/images/route-detail-expanded.png")
        }
    }

    @Test
    fun tappingTheStar_togglesTheRow() {
        var toggled = false
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    onToggleStar = { toggled = true },
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Pin to top").performClick()
        composeRule.runOnIdle { assertEquals(true, toggled) }
    }

    @Test
    fun aStarredRoute_offersToUnpin() {
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = true,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("Unpin from top").assertIsDisplayed()
    }

    @Test
    fun aHealthyRoute_saysNoDisruptions_andStillStars() {
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No disruptions reported").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed()
    }

    @Test
    fun whenDisruptionUnknown_saysCouldntCheck_notNoDisruptions() {
        // The lookup failed, so a null status is unchecked, not clean: the detail must not claim
        // "No disruptions reported" (SPEC principle 1).
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = true,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    @Test
    fun aKnownLineAlert_stillFlagsUncheckedStopDisruption() {
        // The line status is known (a disruption is shown), but this stop's own disruption lookup
        // (a closure/move) failed — disruptionUnknown is set. The alert isn't the whole story, so
        // the detail must still say the stop-level disruption wasn't checked (SPEC principle 1).
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = false,
                    disruptionUnknown = true,
                    stale = false,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        // Both the known alert AND the unchecked-disruption note appear.
        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
    }

    @Test
    fun whenStaleAndUnchecked_showsBothCaveats_notJustAge() {
        // A stale row whose stop-disruption lookup also failed: "may be out of date" (the age) and
        // "couldn't check for disruptions" (the failed stop-level check) are independent facts, so
        // both must show — the age warning must not stand in for the failed check (SPEC principle 1).
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = disruptedRow(),
                    isStarred = false,
                    starrable = false,
                    disruptionUnknown = true,
                    stale = true,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Severe Delays").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't check for disruptions").assertIsDisplayed()
        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
    }

    @Test
    fun whenStale_saysStatusMayBeOutOfDate_notNoDisruptions() {
        // A stale snapshot: the disruption status is from an old fetch, so the detail must not
        // claim "No disruptions reported" (SPEC D4) — it caveats instead.
        composeRule.setContent {
            StopCastTheme {
                RouteDetailDialog(
                    row = healthyRow(),
                    isStarred = false,
                    starrable = true,
                    disruptionUnknown = false,
                    stale = true,
                    onToggleStar = {},
                    onDismiss = {},
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Status may be out of date").assertIsDisplayed()
        composeRule.onNodeWithText("No disruptions reported").assertDoesNotExist()
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
