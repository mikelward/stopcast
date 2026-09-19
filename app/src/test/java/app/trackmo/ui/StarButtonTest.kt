package app.trackmo.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.trackmo.domain.Departure
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DepartureRows
import app.trackmo.domain.StarredRow
import app.trackmo.domain.StopArrivals
import app.trackmo.ui.theme.TrackmoTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The per-card star toggle: an unstarred timed card offers "Pin to top" and a tap invokes the
 * callback for that row; a starred card offers "Unpin from top". The ordering the star drives is
 * covered by [app.trackmo.domain.DepartureRowsPinStarredTest]; this pins the control and its
 * callback wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarButtonTest {

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

    // The one row the fixture produces, so the test asserts the callback carries that identity.
    private val theRow: DepartureRow = DepartureRows.across(listOf(stop), now).single()

    private fun loaded() = DeparturesUiState.Loaded(stops = listOf(stop), fetchedAt = now)

    @Test
    fun `an unstarred card offers Pin to top and a tap invokes the callback`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            TrackmoTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                )
            }
        }
        composeRule.onNodeWithContentDescription("Pin to top").assertIsDisplayed().performClick()
        assertEquals(theRow.stopId, toggled?.stopId)
        assertEquals(theRow.lineId, toggled?.lineId)
        assertEquals(theRow.directionKey, toggled?.directionKey)
    }

    @Test
    fun `a starred card offers Unpin from top`() {
        composeRule.setContent {
            TrackmoTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = setOf(StarredRow.of(theRow)),
                    onToggleStar = {},
                )
            }
        }
        composeRule.onNodeWithContentDescription("Unpin from top").assertIsDisplayed()
    }

    @Test
    fun `no star control is shown when starring is unavailable`() {
        // A newer-schema star file this build can't read: hide the control rather than show
        // every row unfilled (a false "nothing starred" claim). assertDoesNotExist is a
        // member — no import (fleet Compose-test gotcha).
        composeRule.setContent {
            TrackmoTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = {},
                    starringAvailable = false,
                )
            }
        }
        composeRule.onNodeWithContentDescription("Pin to top").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Unpin from top").assertDoesNotExist()
    }
}
