package app.stopdash.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scroll-edge fade: an edge is painted toward the fade color only while the list scrolls on past
 * it. White rows under a black fade color, so a faded pixel is plainly not white.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-160dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollEdgeFadeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: LazyListState

    private fun show(rows: Int) {
        composeRule.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(200.dp)) {
                LazyColumn(
                    modifier = Modifier.testTag("list").size(200.dp).scrollEdgeFade(state, Color.Black),
                    state = state,
                ) {
                    items(rows) { Box(Modifier.fillMaxWidth().height(50.dp).background(Color.White)) }
                }
            }
        }
        composeRule.waitForIdle()
    }

    // The color at the top and bottom rows of the list, in the middle of its width.
    private fun edges(): Pair<Color, Color> {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        return pixels[pixels.width / 2, 0] to pixels[pixels.width / 2, pixels.height - 1]
    }

    @Test
    fun `a list with more below fades only its bottom edge`() {
        show(rows = 10)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertNotEquals(Color.White, bottom)
    }

    @Test
    fun `scrolled to the end, only the top edge fades`() {
        show(rows = 10)
        composeRule.runOnIdle { runBlocking { state.scrollToItem(9) } }
        composeRule.waitForIdle()
        val (top, bottom) = edges()
        assertNotEquals(Color.White, top)
        assertEquals(Color.White, bottom)
    }

    @Test
    fun `a list that fits shows no fade`() {
        show(rows = 4)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertEquals(Color.White, bottom)
    }
}
