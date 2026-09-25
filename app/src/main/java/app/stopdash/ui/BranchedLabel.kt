package app.stopdash.ui

/**
 * The two strings the destination line renders for a branching row — the [terminus] and the
 * [branch] (slash included). The composable lays the branch out at its natural width and gives the
 * terminus the leftover (ellipsized), so these strings are already chosen to fit that split. The
 * full-name [contentDescription] is kept for a screen reader when the visible terminus is shortened
 * or hidden, and is null when the terminus shows in full.
 */
internal data class BranchedLabel(
    val terminus: String,
    val branch: String,
    val contentDescription: String?,
)

/**
 * Chooses the destination-line strings for a branching row from the glyph widths the caller
 * measured (all in px), so the rule is unit-testable apart from the measuring composable. The
 * branch joins with a slash, "/$branch".
 *
 * The **branch is kept whole** — it is the cue that tells a branching line's two trunks apart, so
 * truncating it would lose which train this is. The full branch is kept when the terminus **floor**
 * still fits beside it, otherwise its board short form; the terminus then takes the rest: its full
 * name when that fits, its word-abbreviated form when it doesn't, and its floor (first word +
 * initials, `DestinationAbbreviations.floor`) otherwise, which the caller ellipsizes with a single
 * "…" (never a mid-glyph cut, and the terminus yields before the branch) if even the floor overflows
 * the leftover.
 *
 * The one degenerate case is a row too narrow for even the terminus floor's first glyph beside the
 * whole short branch, at a large accessibility font scale: there the branch takes the row **alone
 * and bare** (no leading slash, so no orphaned "/"), the full name kept for a screen reader.
 *
 * @param label the full terminus name
 * @param abbreviatedLabel [label] with common whole words shortened (`DestinationAbbreviations.abbreviate`)
 * @param floorLabel [label] reduced to its first word plus later-word initials (`DestinationAbbreviations.floor`)
 * @param branch the via-branch as TfL gives it here ("Charing X", or a longer raw form)
 * @param abbreviatedBranch [branch] in its board short form (`abbreviateBranch`)
 * @param maxWidth the width available to the whole destination line
 * @param labelWidth measured width of [label]
 * @param abbrevLabelWidth measured width of [abbreviatedLabel]
 * @param floorLabelWidth measured width of [floorLabel] — the terminus's smallest recognizable form
 * @param fullBranchWidth measured width of "/$branch"
 * @param abbrevBranchWidth measured width of "/$abbreviatedBranch"
 * @param minStubWidth measured width of the terminus floor's first glyph **plus an ellipsis** — the
 *   smallest a recognizable stub can render beside the whole branch; below it the render would show
 *   only "…", so the branch takes the row alone instead
 */
internal fun branchedLabel(
    label: String,
    abbreviatedLabel: String,
    floorLabel: String,
    branch: String,
    abbreviatedBranch: String,
    maxWidth: Int,
    labelWidth: Int,
    abbrevLabelWidth: Int,
    floorLabelWidth: Int,
    fullBranchWidth: Int,
    abbrevBranchWidth: Int,
    minStubWidth: Int,
): BranchedLabel {
    // Longest terminus form that fits a budget, down to the floor; the render ellipsizes the floor
    // cleanly if even it overflows.
    fun terminusFor(budget: Int) = when {
        labelWidth <= budget -> label
        abbrevLabelWidth <= budget -> abbreviatedLabel
        else -> floorLabel
    }
    fun kept(branchStr: String, terminusStr: String) = BranchedLabel(
        terminus = terminusStr,
        branch = branchStr,
        contentDescription = if (terminusStr != label) label else null,
    )
    // Keep the full branch whole while the terminus floor still fits beside it.
    if (floorLabelWidth + fullBranchWidth <= maxWidth) {
        return kept("/$branch", terminusFor(maxWidth - fullBranchWidth))
    }
    // Otherwise keep the board short form whole — unless not even a recognizable stub (a glyph plus
    // its ellipsis) fits beside it, on a very narrow row at a large accessibility font scale: there
    // the branch takes the row alone and bare (no leading slash), the full name kept for a screen
    // reader. Reserving the ellipsis room keeps the stub from rendering as a bare "…".
    val budget = maxWidth - abbrevBranchWidth
    if (budget < minStubWidth) {
        return BranchedLabel(terminus = "", branch = abbreviatedBranch, contentDescription = label)
    }
    return kept("/$abbreviatedBranch", terminusFor(budget))
}
