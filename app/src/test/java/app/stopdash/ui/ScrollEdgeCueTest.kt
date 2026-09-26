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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The scroll-edge cue: an edge gets its fade and chevron only while the list scrolls on past it. White
 * rows under a black edge, a blue plate and a red glyph, so each part of the cue is told apart by
 * color.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w320dp-h480dp-160dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScrollEdgeCueTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var state: LazyListState

    private fun show(rows: Int) {
        composeRule.setContent {
            state = rememberLazyListState()
            Box(Modifier.size(200.dp)) {
                LazyColumn(
                    modifier = Modifier.testTag("list").size(200.dp).scrollEdgeCue(state, ScrollCueColors(edge = Color.Black, plate = Color.Blue, glyph = Color.Red)),
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

    // At 160dpi a dp is a pixel. The chevron's plate is centered 18dp in from the edge (4dp inset plus
    // its 14dp radius); its glyph spans the middle 12dp, so 10dp left of center is plate alone.
    private fun plate(top: Boolean): Color {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        val y = if (top) 18 else pixels.height - 1 - 18
        return pixels[pixels.width / 2 - 10, y]
    }

    // The arrow's point: the down arrow's tip sits 4dp below the plate's center, the up arrow's above.
    private fun glyph(top: Boolean): Color {
        val pixels = composeRule.onNodeWithTag("list").captureToImage().toPixelMap()
        val y = if (top) 18 - 3 else pixels.height - 1 - 18 + 3
        return pixels[pixels.width / 2, y]
    }

    @Test
    fun `a list with more below cues only its bottom edge`() {
        show(rows = 10)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertNotEquals(Color.White, bottom)
        assertEquals(Color.White, plate(top = true))
        assertEquals(Color.Blue, plate(top = false))
        assertTrue(glyph(top = false).red > 0.5f && glyph(top = false).green < 0.5f)
    }

    @Test
    fun `scrolled to the end, only the top edge is cued`() {
        show(rows = 10)
        composeRule.runOnIdle { runBlocking { state.scrollToItem(9) } }
        composeRule.waitForIdle()
        val (top, bottom) = edges()
        assertNotEquals(Color.White, top)
        assertEquals(Color.White, bottom)
        assertEquals(Color.Blue, plate(top = true))
        assertEquals(Color.White, plate(top = false))
        assertTrue(glyph(top = true).red > 0.5f && glyph(top = true).green < 0.5f)
    }

    @Test
    fun `a list that fits shows no cue`() {
        show(rows = 4)
        val (top, bottom) = edges()
        assertEquals(Color.White, top)
        assertEquals(Color.White, bottom)
        assertEquals(Color.White, plate(top = true))
        assertEquals(Color.White, plate(top = false))
    }
}
