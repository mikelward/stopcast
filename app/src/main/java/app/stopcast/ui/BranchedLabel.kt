package app.stopcast.ui

/**
 * The two strings the destination line renders for a branching row — the [terminus] and the
 * [branch] — plus the full-name [contentDescription] to keep for a screen reader when the
 * visible terminus is shortened or hidden ([contentDescription] is null when the terminus
 * shows in full and needs none).
 */
internal data class BranchedLabel(
    val terminus: String,
    val branch: String,
    val contentDescription: String?,
)

/**
 * Chooses the destination-line strings for a branching row from the glyph widths the caller
 * measured (all in px), so the rule is unit-testable apart from the measuring composable.
 *
 * The branch outranks the terminus for space (SPEC destination-label): the full branch is kept
 * while even the abbreviated terminus fits beside it, and the terminus yields first — full name,
 * then its abbreviated form, then a clean clip. The branch joins in plain list style, ", $branch".
 *
 * The one degenerate case is the terminus getting **no** room — at a large accessibility font
 * scale on a narrow row the branch alone can fill the width. There the row is the branch **alone
 * and bare**: a leading comma with nothing before it (", Charing X") is a malformed label, where
 * a parenthesized branch once read fine on its own. The full name stays the accessible label.
 *
 * @param label the full terminus name
 * @param abbreviatedLabel [label] with common whole words shortened (`DestinationAbbreviations`)
 * @param branch the via-branch as TfL gives it here ("Charing X", or a longer raw form)
 * @param abbreviatedBranch [branch] in its board short form (`abbreviateBranch`)
 * @param maxWidth the width available to the whole destination line
 * @param labelWidth measured width of [label]
 * @param abbrevLabelWidth measured width of [abbreviatedLabel]
 * @param fullBranchWidth measured width of ", $branch"
 * @param abbrevBranchWidth measured width of ", $abbreviatedBranch"
 * @param firstGlyphWidth measured width of the terminus's first glyph — the floor below which
 *   the terminus can show nothing meaningful, so it is dropped rather than left at zero width
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
): BranchedLabel {
    // Keep the full branch while even the abbreviated terminus fits beside it.
    val useFullBranch = abbrevLabelWidth + fullBranchWidth <= maxWidth
    val branchWidth = if (useFullBranch) fullBranchWidth else abbrevBranchWidth
    val availForLabel = maxWidth - branchWidth
    if (availForLabel < firstGlyphWidth) {
        // No room for the terminus: branch alone, bare (no leading comma, short board form),
        // full name kept for a screen reader.
        return BranchedLabel(terminus = "", branch = abbreviatedBranch, contentDescription = label)
    }
    val displayLabel = if (labelWidth <= availForLabel) label else abbreviatedLabel
    return BranchedLabel(
        terminus = displayLabel,
        branch = if (useFullBranch) ", $branch" else ", $abbreviatedBranch",
        contentDescription = if (displayLabel != label) label else null,
    )
}
