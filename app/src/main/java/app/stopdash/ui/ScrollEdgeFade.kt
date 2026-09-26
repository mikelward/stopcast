package app.stopdash.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Copied from mikelward/simmo's ui/ScrollEdgeFade.kt (2026-09-26), the fleet's prior art for this
// cue; clothescast has a heavier overlay version. A shared mikelward/* UI library would be the home
// for one copy, but none exists yet, and this file is small.

/**
 * How tall the graduated fade is at a scrollable edge: the cue that a list keeps going past the
 * fold. StopDash's lists scroll straight to their own edge with no other "more below" cue, so a
 * list that happens to end near the bottom of the screen reads the same as one cut off mid-content.
 */
private val EDGE_FADE_HEIGHT = 24.dp

/**
 * Fades the top and/or bottom edge of a [ScrollState]-scrolled container into [edgeColor] while
 * there is more content in that direction.
 *
 * Painted as an [edgeColor]-to-transparent gradient *over* the content rather than a true alpha
 * mask of it: every screen this is used on sits directly on a flat background, so painting the same
 * color reads the same as a true fade without the offscreen compositing layer a mask would cost
 * (AGENTS *Jank-free UI*).
 *
 * **Apply this modifier *before* `.verticalScroll(scrollState)` in the chain, not after.**
 * `verticalScroll` measures everything later in the chain at the content's full height and then
 * translates it by the scroll offset, so a draw modifier placed after it would scroll with the
 * content instead of staying pinned to the viewport edge.
 */
fun Modifier.scrollEdgeFade(
    scrollState: ScrollState,
    edgeColor: Color,
    edgeHeight: Dp = EDGE_FADE_HEIGHT,
): Modifier = drawWithContent {
    drawContent()
    drawEdgeFade(scrollState.canScrollBackward, scrollState.canScrollForward, edgeColor, edgeHeight)
}

/** [scrollEdgeFade] for a [LazyListState]-scrolled `LazyColumn`; same contract. */
fun Modifier.scrollEdgeFade(
    listState: LazyListState,
    edgeColor: Color,
    edgeHeight: Dp = EDGE_FADE_HEIGHT,
): Modifier = drawWithContent {
    drawContent()
    drawEdgeFade(listState.canScrollBackward, listState.canScrollForward, edgeColor, edgeHeight)
}

private fun ContentDrawScope.drawEdgeFade(
    canScrollBackward: Boolean,
    canScrollForward: Boolean,
    edgeColor: Color,
    edgeHeight: Dp,
) {
    val heightPx = edgeHeight.toPx().coerceAtMost(size.height)
    // The same color fading its alpha, not a fade to Color.Transparent (transparent black), which
    // would pass through a darker band on a light background.
    if (canScrollBackward) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(edgeColor, edgeColor.copy(alpha = 0f)),
                startY = 0f,
                endY = heightPx,
            ),
            size = Size(size.width, heightPx),
        )
    }
    if (canScrollForward) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(edgeColor.copy(alpha = 0f), edgeColor),
                startY = size.height - heightPx,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - heightPx),
            size = Size(size.width, heightPx),
        )
    }
}
