package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DestinationAbbreviations
import app.stopcast.domain.PlatformDirection

/** How much of the header row (after the reserved distance) the place name keeps when a **bus
 *  terminus** qualifier is shown — a full place name, unlike the tiny compass letter, so it can't be
 *  allowed to consume the row and crowd the name to zero (Codex P2, PR #78). The compass uses no
 *  floor (its fallback letter is narrow). */
internal const val TERMINUS_NAME_FLOOR_FRACTION: Float = 0.4f

/**
 * The group header's text style: the small-caps [base] (labelMedium) with the extra letter tracking
 * and the **semi-bold weight baked in**. It is the one source of truth for both the width the header
 * *measures* (to choose the qualifier form) and the width it *renders* — measuring a lighter weight
 * than the render would under-count, letting the full form be chosen when the semi-bold text
 * overflows and clipping the name (Codex P2, PR #115).
 */
internal fun headerTextStyle(base: TextStyle): TextStyle =
    base.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)

/**
 * The trailing header qualifier — the rail **compass** ("– Eastbound") or the bus **terminus**
 * ("→ Bank") — as the strings and width rule the header renders. [fullText] is the full form (dash/
 * arrow included); [shortText] its fallback when the full form won't fit (the compass letter "– E",
 * or the word-abbreviated terminus), equal to [fullText] when there is no shorter form. [spoken] is
 * what a screen reader hears in place of the dash/arrow glyph ("Eastbound", "to Bank").
 * [nameFloorFraction] is how much of the row the place name keeps ([TERMINUS_NAME_FLOOR_FRACTION]
 * for the terminus, 0 for the compass — its letter is narrow enough not to starve the name).
 */
internal data class HeaderQualifier(
    val fullText: String,
    val shortText: String,
    val spoken: String,
    val nameFloorFraction: Float,
)

/**
 * Builds the header qualifier from the group's labels, or null when the group carries neither (a
 * bare-name header). The rail compass wins where present; a compass-less group takes the bus
 * terminus. Pure (uppercasing is locale-invariant), so the mode-aware choice is unit-testable apart
 * from the composable.
 */
internal fun headerQualifier(directionLabel: String?, terminusLabel: String?): HeaderQualifier? = when {
    directionLabel != null -> HeaderQualifier(
        fullText = " – ${directionLabel.uppercase()}",
        shortText = " – ${PlatformDirection.abbreviation(directionLabel)}",
        spoken = directionLabel,
        nameFloorFraction = 0f,
    )
    terminusLabel != null -> {
        // Apply the same display rename the destination card uses ("Battersea Power" → "Battersea",
        // DepartureLabels), so the header and the card below it read consistently and a renamed
        // terminus doesn't waste header width; grouping stays keyed on the raw value upstream (Codex
        // P2, PR #116). directionKey is unused here — the terminus is a non-blank destination.
        val display = DepartureLabels.destinationLabel(terminusLabel, "") ?: terminusLabel
        HeaderQualifier(
            fullText = " → ${display.uppercase()}",
            shortText = " → ${DestinationAbbreviations.abbreviate(display).uppercase()}",
            spoken = "to $display",
            nameFloorFraction = TERMINUS_NAME_FLOOR_FRACTION,
        )
    }
    else -> null
}

/**
 * The qualifier string a group header shows and the px width it is bounded to ([maxWidthPx]), with
 * [abbreviated] true when the fallback form stood in for the full one (so the caller keeps [spoken]
 * as the accessible label). Chosen from the measured glyph widths so the rule is unit-testable apart
 * from the measuring composable (mirrors [BranchedLabel]).
 */
internal data class HeaderQualifierFit(
    val text: String,
    val maxWidthPx: Int,
    val abbreviated: Boolean,
)

/**
 * Picks the header's qualifier form and its width budget (all widths in px).
 *
 * The **full form** ([fullText]) shows when the whole header — the place name at its natural width,
 * the full qualifier, and the distance — fits the row; otherwise the qualifier falls back to
 * [shortText] (the compass letter, or the word-abbreviated terminus), narrow enough to survive where
 * the full form won't, so the direction cue never vanishes.
 *
 * The **distance is reserved first**, then the **name keeps a floor** ([nameFloorPx], never more than
 * the name actually wants): the qualifier is bounded to what's left ([maxWidthPx]), so a long bus
 * terminus at a large font can't consume the row and crowd the name to zero (Codex P2, PR #78), and
 * a narrow pane can't hand the reserved distance's room to the qualifier (Codex P2, PR #115). The
 * compass passes a zero floor — its fallback letter is narrow, so the name may clip fully behind it.
 *
 * @param fullText the full qualifier, dash/arrow included (" – Eastbound", " → Bank")
 * @param shortText the fallback form ("– E", " → abbreviated"); equals [fullText] when none shorter
 * @param nameWidth measured width of the (uppercased) place name
 * @param fullWidth measured width of [fullText]
 * @param distanceWidth measured width of the distance suffix (" (1.2 km)"), 0 when there is none
 * @param nameFloorPx the width to keep for the name (clamped to [nameWidth] — never reserve more
 *   than the name wants), so the qualifier can't starve it; 0 for the compass
 * @param maxWidth the width available to the whole header row
 */
internal fun headerQualifierFit(
    fullText: String,
    shortText: String,
    nameWidth: Int,
    fullWidth: Int,
    distanceWidth: Int,
    nameFloorPx: Int,
    maxWidth: Int,
): HeaderQualifierFit {
    val showFull = nameWidth + fullWidth + distanceWidth <= maxWidth
    val floor = nameFloorPx.coerceAtMost(nameWidth)
    return HeaderQualifierFit(
        text = if (showFull) fullText else shortText,
        maxWidthPx = (maxWidth - distanceWidth - floor).coerceAtLeast(0),
        abbreviated = !showFull,
    )
}
