package app.stopcast.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import app.stopcast.ui.theme.StopCastTheme
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Starring a departure is a long-press on the card, not a per-row button (SPEC D8): the button
 * ate width on every row, so it was removed entirely — a starred card carries no in-row element,
 * only a gold border and its position at the top. A long-press on a timed card toggles the pin
 * and carries the "Pin to top" / "Unpin from top" accessibility label. The gold border is a
 * paint-only mark with no queryable node, so it's covered by the screenshot tests; the ordering
 * the star drives is covered by [app.stopcast.domain.DepartureRowsPinStarredTest]. This pins the
 * gesture and its wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StarLongPressTest {

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
    fun `long-pressing an unstarred card invokes the callback for that row`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertEquals(theRow.stopId, toggled?.stopId)
        assertEquals(theRow.lineId, toggled?.lineId)
        assertEquals(theRow.directionKey, toggled?.directionKey)
    }

    @Test
    fun `long-pressing a starred card invokes the callback to unpin it`() {
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = setOf(StarredRow.of(theRow)),
                    onToggleStar = { toggled = it },
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertEquals(theRow.lineId, toggled?.lineId)
    }

    @Test
    fun `long-press does not toggle when starring is unavailable`() {
        // A newer-schema star file this build can't read: the card isn't long-pressable, so a
        // long-press must not toggle (and there's no gold border to falsely imply a pin).
        var toggled: DepartureRow? = null
        composeRule.setContent {
            StopCastTheme {
                MainScreen(
                    state = loaded(),
                    now = now,
                    onRefresh = {},
                    starred = emptySet(),
                    onToggleStar = { toggled = it },
                    starringAvailable = false,
                )
            }
        }
        composeRule.onNodeWithText("Brixton").performTouchInput { longClick() }
        assertNull(toggled)
    }
}
