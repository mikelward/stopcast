package app.stopcast.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [branchedLabel]'s width rule, tested apart from the measuring composable. Widths are the
 * abstract px the caller measures; only their relative sizes matter, so the numbers here are
 * chosen to land each case rather than to match any real font.
 */
class BranchedLabelTest {

    @Test
    fun `joins terminus and branch in list style when both fit`() {
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
        )
        assertEquals("Battersea Power", r.terminus)
        assertEquals(", Charing Cross", r.branch)
        assertEquals(null, r.contentDescription)
    }

    @Test
    fun `abbreviates the terminus under pressure and keeps the comma and full name`() {
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
        )
        assertEquals("H. Barnet", r.terminus)
        assertEquals(", Bank", r.branch)
        // Full name kept for a screen reader now that the visible terminus is shortened.
        assertEquals("High Barnet", r.contentDescription)
    }

    @Test
    fun `shortens the branch to its board form before clipping the terminus`() {
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 520,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 250,
            abbrevBranchWidth = 150,
            firstGlyphWidth = 20,
        )
        assertEquals("Battersea Power", r.terminus)
        assertEquals(", Charing X", r.branch)
        assertEquals(null, r.contentDescription)
    }

    @Test
    fun `drops the comma and shows the branch alone when the terminus has no room`() {
        // The Codex P2 on #92: at an extreme font scale on a narrow row the branch alone can
        // fill the width, so a leading comma would render with nothing before it. The row is
        // then the branch, bare, and the full name stays the accessible label.
        val r = branchedLabel(
            label = "Battersea Power",
            abbreviatedLabel = "Battersea Power",
            branch = "Charing Cross",
            abbreviatedBranch = "Charing X",
            maxWidth = 140,
            labelWidth = 300,
            abbrevLabelWidth = 300,
            fullBranchWidth = 250,
            abbrevBranchWidth = 150,
            firstGlyphWidth = 25,
        )
        assertEquals("", r.terminus)
        assertEquals("Charing X", r.branch)
        // No leading comma when the branch stands alone.
        assertEquals(false, r.branch.startsWith(","))
        assertEquals("Battersea Power", r.contentDescription)
    }
}
