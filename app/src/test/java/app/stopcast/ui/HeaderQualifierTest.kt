package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [headerQualifier]'s mode-aware build and [headerQualifierFit]'s form-and-budget rule, tested apart
 * from the measuring composable. Widths are the abstract px the caller measures; only their relative
 * sizes matter, so the numbers here are chosen to land each case rather than to match any real font.
 */
class HeaderQualifierTest {

    @Test
    fun `builds a compass qualifier with a single-letter fallback and no name floor`() {
        val q = headerQualifier(directionLabel = "Eastbound", terminusLabel = null)!!
        assertEquals(" – EASTBOUND", q.fullText)
        assertEquals(" – E", q.shortText)
        assertEquals("Eastbound", q.spoken)
        assertEquals(0f, q.nameFloorFraction, 0f)
    }

    @Test
    fun `builds a bus terminus qualifier with an arrow, word-abbreviated fallback, and a name floor`() {
        val q = headerQualifier(directionLabel = null, terminusLabel = "Bank")!!
        assertEquals(" → BANK", q.fullText)
        assertEquals("to Bank", q.spoken)
        // The terminus keeps a name floor so a long place name can't be crowded to zero.
        assertEquals(TERMINUS_NAME_FLOOR_FRACTION, q.nameFloorFraction, 0f)
    }

    @Test
    fun `a bus terminus with a display rename shows the renamed form, matching the card`() {
        // The destination card renames "Battersea Power" → "Battersea" (DepartureLabels); the header
        // must use the same so it doesn't read "→ BATTERSEA POWER" above a "Battersea" card (Codex
        // P2, PR #116).
        val q = headerQualifier(directionLabel = null, terminusLabel = "Battersea Power")!!
        assertEquals(" → BATTERSEA", q.fullText)
        assertEquals("to Battersea", q.spoken)
    }

    @Test
    fun `the compass wins when a group somehow carries both, and neither means no qualifier`() {
        // Grouping never sets both, but the builder is explicit about precedence and the null case.
        assertEquals(" – NORTHBOUND", headerQualifier("Northbound", "Bank")!!.fullText)
        assertNull(headerQualifier(null, null))
    }

    @Test
    fun `shows the full form when the whole header fits it`() {
        val r = headerQualifierFit(
            fullText = " – EASTBOUND",
            shortText = " – E",
            nameWidth = 200,
            fullWidth = 250,
            distanceWidth = 120,
            nameFloorPx = 0,
            maxWidth = 1000,
        )
        assertEquals(" – EASTBOUND", r.text)
        assertFalse(r.abbreviated)
        // The qualifier is bounded to the row minus the distance, so the distance stays reserved.
        assertEquals(880, r.maxWidthPx)
    }

    @Test
    fun `falls back to the short form when the full form does not fit`() {
        // Name plus full qualifier and distance overflow — collapse to the short form, which buys the
        // name its room back before the name itself has to clip.
        val r = headerQualifierFit(
            fullText = " – EASTBOUND",
            shortText = " – E",
            nameWidth = 700,
            fullWidth = 250,
            distanceWidth = 120,
            nameFloorPx = 0,
            maxWidth = 1000,
        )
        assertEquals(" – E", r.text)
        assertTrue(r.abbreviated)
    }

    @Test
    fun `reserves the distance first in a pane too narrow for both`() {
        // A narrow multi-window/foldable pane at a large font scale: even the short form plus the
        // distance exceed the row. The qualifier is bounded to the row minus the distance (here 0),
        // so the Row can't hand it the distance's width — the distance stays reserved (Codex P2, #115).
        val r = headerQualifierFit(
            fullText = " – EASTBOUND",
            shortText = " – E",
            nameWidth = 60,
            fullWidth = 250,
            distanceWidth = 160,
            nameFloorPx = 0,
            maxWidth = 150,
        )
        assertEquals(" – E", r.text)
        assertEquals(0, r.maxWidthPx)
    }

    @Test
    fun `a name floor keeps a long bus terminus from crowding the name to zero`() {
        // A long terminus at a large font: the qualifier is bounded to the row minus the distance AND
        // the name's floor, so the name always keeps its floor and the terminus clips instead of
        // consuming the whole row (Codex P2, PR #78).
        val r = headerQualifierFit(
            fullText = " → CROXLEY GREEN INTERCHANGE",
            shortText = " → CROXLEY GRN INTERCHANGE",
            nameWidth = 400,
            fullWidth = 900,
            distanceWidth = 100,
            nameFloorPx = 360, // 0.4 * (1000 - 100), clamped below to nameWidth
            maxWidth = 1000,
        )
        assertTrue(r.abbreviated)
        // maxWidth - distance - floor = 1000 - 100 - 360 = 540 left for the qualifier; the name keeps 360.
        assertEquals(540, r.maxWidthPx)
    }

    @Test
    fun `the name floor never reserves more than the name actually wants`() {
        // A short name asks for less than the floor: the qualifier gets the rest, so a short stop name
        // beside a terminus isn't padded out with empty space it doesn't need.
        val r = headerQualifierFit(
            fullText = " → BANK",
            shortText = " → BANK",
            nameWidth = 120,
            fullWidth = 200,
            distanceWidth = 100,
            nameFloorPx = 360, // would reserve 360, but the name only wants 120
            maxWidth = 1000,
        )
        assertFalse(r.abbreviated)
        // Floor clamped to nameWidth (120): 1000 - 100 - 120 = 780 for the qualifier.
        assertEquals(780, r.maxWidthPx)
    }

    @Test
    fun `the header style bakes in the semi-bold weight it renders`() {
        // The header must measure the same weight it renders, or the width used to choose the
        // qualifier form under-counts and the name clips instead of the qualifier abbreviating
        // (Codex P2, PR #115). This is the one place both the measure and every header Text read.
        val resolved = headerTextStyle(TextStyle(fontWeight = FontWeight.Normal))
        assertEquals(FontWeight.SemiBold, resolved.fontWeight)
    }
}
