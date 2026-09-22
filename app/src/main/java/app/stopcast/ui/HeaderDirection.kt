package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The group header's text style: the small-caps [base] (labelMedium) with the extra letter tracking
 * and the **semi-bold weight baked in**. It is the one source of truth for both the width the header
 * *measures* (to choose the direction form) and the width it *renders* — measuring a lighter weight
 * than the render would under-count, letting the full word be chosen when the semi-bold text
 * overflows and clipping the name (Codex P2, PR #115).
 */
internal fun headerTextStyle(base: TextStyle): TextStyle =
    base.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)

/**
 * The direction string a group header shows and the px width it is bounded to ([maxWidthPx]), with
 * [abbreviated] true when the single-letter form stood in for the full word (so the caller keeps the
 * full word as the accessible label). Chosen from the measured glyph widths so the rule is
 * unit-testable apart from the measuring composable (mirrors [BranchedLabel]).
 */
internal data class HeaderDirection(
    val text: String,
    val maxWidthPx: Int,
    val abbreviated: Boolean,
)

/**
 * Picks the header's direction form and its width budget (all widths in px).
 *
 * The **full compass word** ([fullText]) shows when the whole header — the place name at its natural
 * width, the full direction, and the distance — fits the row; otherwise the direction falls back to
 * its **single letter** ([letterText]), narrow enough to survive where the word won't, so the
 * direction cue that tells two blocks of one place apart never vanishes.
 *
 * The **distance is reserved first**: the direction is bounded to at most the row minus the distance
 * ([maxWidthPx]), so a pane too narrow for both clips the direction (or, before it, the name) but
 * never the distance — the near-me cue a rider reads. Without this bound the `Row` measures the
 * unweighted direction before the unweighted distance and would take the distance's room in a narrow
 * multi-window/foldable pane at a large font scale (Codex P2, PR #115).
 *
 * @param fullText the full direction, dash included (" – Eastbound")
 * @param letterText the single-letter fallback, dash included (" – E")
 * @param nameWidth measured width of the (uppercased) place name
 * @param fullWidth measured width of [fullText]
 * @param distanceWidth measured width of the distance suffix (" (1.2 km)"), 0 when there is none
 * @param maxWidth the width available to the whole header row
 */
internal fun headerDirection(
    fullText: String,
    letterText: String,
    nameWidth: Int,
    fullWidth: Int,
    distanceWidth: Int,
    maxWidth: Int,
): HeaderDirection {
    val showFull = nameWidth + fullWidth + distanceWidth <= maxWidth
    return HeaderDirection(
        text = if (showFull) fullText else letterText,
        maxWidthPx = (maxWidth - distanceWidth).coerceAtLeast(0),
        abbreviated = !showFull,
    )
}
