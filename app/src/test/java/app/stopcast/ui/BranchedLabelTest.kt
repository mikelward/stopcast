package app.stopcast.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [branchedLabel]'s width rule, tested apart from the measuring composable. Widths are the
 * abstract px the caller measures; only their relative sizes matter, so the numbers here are
 * chosen to land each case rather than to match any real font.
 */
class BranchedLabelTest {

    @Test
    fun `joins terminus and branch with a slash when both fit`() {
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 1000,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 200,
            abbrevBranchWidth = 140,
            firstGlyphWidth = 20,
            branchFirstGlyphWidth = 30,
        )
        assertEquals("Battersea Power", r.terminus)
        assertEquals("/Charing Cross", r.branch)
        assertEquals(200, r.branchMaxWidthPx)
        assertEquals(800, r.terminusMaxWidthPx)
        assertEquals(null, r.contentDescription)
    }

    @Test
    fun `abbreviates the terminus while the full branch still fits beside it`() {
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            branch = "Bank",
            abbreviatedBranch = "Bank",
            maxWidth = 200,
            labelWidth = 180,
            abbrevLabelWidth = 120,
            fullBranchWidth = 60,
            abbrevBranchWidth = 60,
            firstGlyphWidth = 20,
            branchFirstGlyphWidth = 30,
        )
        assertEquals("H. Barnet", r.terminus)
        assertEquals("/Bank", r.branch)
        assertEquals(60, r.branchMaxWidthPx)
        assertEquals(140, r.terminusMaxWidthPx)
        // Full name kept for a screen reader now that the visible terminus is shortened.
        assertEquals("High Barnet", r.contentDescription)
    }

    @Test
    fun `keeps the full terminus when shortening the branch alone makes it fit`() {
        // The full branch doesn't fit beside the abbreviated terminus, but the short branch does
        // beside the FULL terminus (100 + 40 = 140 <= 150), so the terminus stays whole rather than
        // being needlessly abbreviated and clipped.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 150,
            labelWidth = 100,
            abbrevLabelWidth = 80,
            fullBranchWidth = 100,
            abbrevBranchWidth = 40,
            firstGlyphWidth = 20,
            branchFirstGlyphWidth = 30,
        )
        assertEquals("High Barnet", r.terminus)
        assertEquals("/Charing X", r.branch)
        assertEquals(40, r.branchMaxWidthPx)
        assertEquals(110, r.terminusMaxWidthPx)
        assertEquals(null, r.contentDescription)
    }

    @Test
    fun `splits the width so terminus and branch clip by the same fraction under pressure`() {
        // Even the abbreviated terminus won't sit beside the full branch, so the width is shared
        // in proportion to each side's natural size — both clip, rather than the branch taking the
        // whole row. terminus 300px and branch 150px into 380px: budgets 254 and 126, each ~0.84 of
        // its natural width.
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 380,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 250,
            abbrevBranchWidth = 150,
            firstGlyphWidth = 20,
            branchFirstGlyphWidth = 40,
        )
        assertEquals("Battersea Power", r.terminus)
        assertEquals("/Charing X", r.branch)
        // Budgets sum to the row and are proportional to the natural widths (both below natural, so
        // both clip); the full name stays the accessible label.
        assertEquals(380, r.terminusMaxWidthPx + r.branchMaxWidthPx)
        assertEquals(126, r.branchMaxWidthPx)
        assertEquals(254, r.terminusMaxWidthPx)
        assertTrue("terminus budget clips its 300px natural width", r.terminusMaxWidthPx < 300)
        assertTrue("branch budget clips its 150px natural width", r.branchMaxWidthPx < 150)
        assertEquals("Battersea Power", r.contentDescription)
    }

    @Test
    fun `raises a starved branch to its first-glyph floor instead of a bare slash`() {
        // A very long terminus beside a short branch: the raw proportional share would give the
        // branch far too little to render its slash and first glyph (120 * 40 / 340 = 14px). The
        // clamp raises it to its 30px floor and the terminus takes the rest, so the branch cue
        // still shows rather than becoming a bare or partial slash.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 120,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 40,
            abbrevBranchWidth = 40,
            firstGlyphWidth = 30,
            branchFirstGlyphWidth = 30,
        )
        assertEquals("H. Barnet", r.terminus)
        assertEquals("/Charing X", r.branch)
        assertEquals(30, r.branchMaxWidthPx)
        assertEquals(90, r.terminusMaxWidthPx)
        assertEquals("High Barnet", r.contentDescription)
    }

    @Test
    fun `drops the slash and shows the branch alone when the row cannot fit both first glyphs`() {
        // At an extreme font scale on a very narrow row the terminus's and branch's first glyphs
        // can't both fit; there the row is the branch, bare (short board form, no leading slash),
        // and the full name stays the accessible label.
        val r = branchedLabel(
            label = "High Barnet",
            abbreviatedLabel = "H. Barnet",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 50,
            labelWidth = 300,
            abbrevLabelWidth = 200,
            fullBranchWidth = 260,
            abbrevBranchWidth = 200,
            firstGlyphWidth = 30,
            branchFirstGlyphWidth = 30,
        )
        assertEquals("", r.terminus)
        assertEquals("Charing X", r.branch)
        // No leading slash when the branch stands alone.
        assertEquals(false, r.branch.startsWith("/"))
        assertEquals("High Barnet", r.contentDescription)
    }
}
