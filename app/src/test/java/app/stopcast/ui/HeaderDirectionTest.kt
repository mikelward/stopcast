package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [headerDirection]'s form-and-budget rule, tested apart from the measuring composable. Widths are
 * the abstract px the caller measures; only their relative sizes matter, so the numbers here are
 * chosen to land each case rather than to match any real font.
 */
class HeaderDirectionTest {

    @Test
    fun `shows the full word when the whole header fits it`() {
        val r = headerDirection(
            fullText = " – EASTBOUND",
            letterText = " – E",
            nameWidth = 200,
            fullWidth = 250,
            distanceWidth = 120,
            maxWidth = 1000,
        )
        assertEquals(" – EASTBOUND", r.text)
        assertFalse(r.abbreviated)
        // The direction is bounded to the row minus the distance, so the distance stays reserved.
        assertEquals(880, r.maxWidthPx)
    }

    @Test
    fun `falls back to the letter when the full word does not fit`() {
        // The name alone plus the full direction and distance overflow — collapse to the letter,
        // which buys the name its room back before the name itself has to clip.
        val r = headerDirection(
            fullText = " – EASTBOUND",
            letterText = " – E",
            nameWidth = 700,
            fullWidth = 250,
            distanceWidth = 120,
            maxWidth = 1000,
        )
        assertEquals(" – E", r.text)
        assertTrue(r.abbreviated)
    }

    @Test
    fun `reserves the distance first in a pane too narrow for both`() {
        // A narrow multi-window/foldable pane at a large font scale: even the letter form plus the
        // distance exceed the row. The direction is bounded to the row minus the distance (here 0),
        // so the Row can't hand the direction the distance's width — the distance stays reserved and
        // the direction (or, before it, the name) yields (Codex P2, PR #115).
        val r = headerDirection(
            fullText = " – EASTBOUND",
            letterText = " – E",
            nameWidth = 60,
            fullWidth = 250,
            distanceWidth = 160,
            maxWidth = 150,
        )
        assertEquals(" – E", r.text)
        assertTrue(r.abbreviated)
        // maxWidth - distanceWidth is negative, clamped to 0: the direction gives way, not the distance.
        assertEquals(0, r.maxWidthPx)
    }

    @Test
    fun `the header style bakes in the semi-bold weight it renders`() {
        // The header must measure the same weight it renders, or the width used to choose the
        // direction form under-counts and the name clips instead of the direction abbreviating
        // (Codex P2, PR #115). This is the one place both the measure and every header Text read.
        val resolved = headerTextStyle(TextStyle(fontWeight = FontWeight.Normal))
        assertEquals(FontWeight.SemiBold, resolved.fontWeight)
    }

    @Test
    fun `a header with no distance bounds the direction to the whole row`() {
        val r = headerDirection(
            fullText = " – WESTBOUND",
            letterText = " – W",
            nameWidth = 200,
            fullWidth = 250,
            distanceWidth = 0,
            maxWidth = 1000,
        )
        assertEquals(" – WESTBOUND", r.text)
        assertEquals(1000, r.maxWidthPx)
    }
}
