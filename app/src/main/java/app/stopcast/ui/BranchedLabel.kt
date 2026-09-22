package app.stopcast.ui

/**
 * The two strings the destination line renders for a branching row — the [terminus] and the
 * [branch] — each with the px width budget the caller clips it to ([terminusMaxWidthPx],
 * [branchMaxWidthPx]), plus the full-name [contentDescription] to keep for a screen reader
 * when the visible terminus is shortened, clipped, or hidden ([contentDescription] is null
 * when the terminus shows in full and needs none).
 */
internal data class BranchedLabel(
    val terminus: String,
    val terminusMaxWidthPx: Int,
    val branch: String,
    val branchMaxWidthPx: Int,
    val contentDescription: String?,
)

/**
 * Chooses the destination-line strings and their width budgets for a branching row from the
 * glyph widths the caller measured (all in px), so the rule is unit-testable apart from the
 * measuring composable. The branch joins with a slash, "/$branch".
 *
 * While the full branch fits beside at least the abbreviated terminus, the branch is kept whole
 * (it is the cue that tells a branching line's two trunks apart) and the terminus takes the rest
 * — its full name when that fits, its abbreviated form when it doesn't.
 *
 * Under tighter pressure — when even the abbreviated terminus won't sit beside the full branch —
 * the width is **split between the two in proportion to their natural sizes**, so both clip by the
 * same fraction rather than the branch taking the whole row and the terminus vanishing (SPEC
 * destination-label — equal truncation). The one remaining degenerate case is the terminus's fair
 * share falling below its first glyph, on a very narrow row at a large accessibility font scale:
 * there the row is the branch **alone and bare** (short board form, no leading slash), the full
 * name kept for a screen reader.
 *
 * @param label the full terminus name
 * @param abbreviatedLabel [label] with common whole words shortened (`DestinationAbbreviations`)
 * @param branch the via-branch as TfL gives it here ("Charing X", or a longer raw form)
 * @param abbreviatedBranch [branch] in its board short form (`abbreviateBranch`)
 * @param maxWidth the width available to the whole destination line
 * @param labelWidth measured width of [label]
 * @param abbrevLabelWidth measured width of [abbreviatedLabel]
 * @param fullBranchWidth measured width of "/$branch"
 * @param abbrevBranchWidth measured width of "/$abbreviatedBranch"
 * @param firstGlyphWidth measured width of the terminus's first glyph — the floor below which
 *   the terminus can show nothing meaningful, so it is dropped rather than left at zero width
 * @param branchFirstGlyphWidth measured width of the branch's slash plus its first glyph ("/C")
 *   — the floor below which the branch would render as a bare or partial slash, so the split is
 *   clamped to keep it at least this wide (or the branch takes the row alone when even that and
 *   the terminus's first glyph can't both fit)
 */
internal fun branchedLabel(
    label: String,
    abbreviatedLabel: String,
    branch: String,
    abbreviatedBranch: String,
    maxWidth: Int,
    labelWidth: Int,
    abbrevLabelWidth: Int,
    fullBranchWidth: Int,
    abbrevBranchWidth: Int,
    firstGlyphWidth: Int,
    branchFirstGlyphWidth: Int,
): BranchedLabel {
    // Full branch fits beside at least the abbreviated terminus: keep it whole, give the terminus
    // the rest — full name when it fits there, abbreviated when it doesn't.
    if (abbrevLabelWidth + fullBranchWidth <= maxWidth) {
        return branchKeptWhole(label, abbreviatedLabel, "/$branch", fullBranchWidth, maxWidth, labelWidth)
    }
    // The full branch won't fit, but its board short form does beside the abbreviated terminus:
    // keep the short branch whole and, again, give the terminus the rest. Without this tier a full
    // terminus that would fit next to the short branch is needlessly abbreviated and clipped.
    if (abbrevLabelWidth + abbrevBranchWidth <= maxWidth) {
        return branchKeptWhole(label, abbreviatedLabel, "/$abbreviatedBranch", abbrevBranchWidth, maxWidth, labelWidth)
    }
    // Too tight for even the abbreviated terminus beside the short branch. If the row can't show a
    // meaningful first glyph of both halves, the branch is the cue that tells the trains apart, so
    // it takes the row alone and bare (short board form, no leading slash).
    if (maxWidth < firstGlyphWidth + branchFirstGlyphWidth) {
        return BranchedLabel(
            terminus = "",
            terminusMaxWidthPx = 0,
            branch = abbreviatedBranch,
            branchMaxWidthPx = maxWidth,
            contentDescription = label,
        )
    }
    // Split the width in proportion to their natural sizes, so both clip by the same fraction
    // (equal truncation), then clamp so neither half falls below its first glyph — the row affords
    // both (checked above), so a lopsided natural ratio never starves one side into a bare or
    // partial slash.
    val total = abbrevLabelWidth + abbrevBranchWidth
    val proportionalBranch = if (total == 0) 0 else maxWidth * abbrevBranchWidth / total
    val branchBudget = proportionalBranch.coerceIn(branchFirstGlyphWidth, maxWidth - firstGlyphWidth)
    val terminusBudget = maxWidth - branchBudget
    return BranchedLabel(
        terminus = abbreviatedLabel,
        terminusMaxWidthPx = terminusBudget,
        branch = "/$abbreviatedBranch",
        branchMaxWidthPx = branchBudget,
        // Keep the full name for a screen reader when the shown terminus is abbreviated or its
        // budget clips it; null when the abbreviated form is the full name and fits its share.
        contentDescription = if (abbreviatedLabel != label || abbrevLabelWidth > terminusBudget) label else null,
    )
}

/**
 * The result when [branch] (already a full or short form, slash included) is kept whole and the
 * terminus takes the leftover width: the full name when it fits that budget, else the abbreviated
 * form, with the full name kept as the accessible label when the visible one is shortened.
 */
private fun branchKeptWhole(
    label: String,
    abbreviatedLabel: String,
    branch: String,
    branchWidth: Int,
    maxWidth: Int,
    labelWidth: Int,
): BranchedLabel {
    val terminusBudget = maxWidth - branchWidth
    val displayLabel = if (labelWidth <= terminusBudget) label else abbreviatedLabel
    return BranchedLabel(
        terminus = displayLabel,
        terminusMaxWidthPx = terminusBudget,
        branch = branch,
        branchMaxWidthPx = branchWidth,
        contentDescription = if (displayLabel != label) label else null,
    )
}
