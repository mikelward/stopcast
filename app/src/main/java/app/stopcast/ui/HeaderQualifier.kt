package app.stopcast.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.StopQualifier

/**
 * A header's text style: the small-caps [base] with the extra letter tracking and the **semi-bold
 * weight baked in**. One source of truth for the place name and every sub-header, so the two levels
 * share one look and differ only by the [base] size the caller passes.
 */
internal fun headerTextStyle(base: TextStyle): TextStyle =
    base.copy(letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold)

/**
 * A sub-header's rendered parts: the [primary] pole/platform label ("PLATFORM 2", "STOP D"), the
 * optional [paren] direction that qualifies it ("Eastbound", "towards Farringdon"), and the [spoken]
 * form a screen reader hears in place of the raw glyphs ("Platform 2, Eastbound"). The primary is
 * uppercased for the small-caps read; the paren keeps its case (a place name, a compass word).
 */
internal data class SubHeaderText(
    val primary: String,
    val paren: String?,
    val spoken: String,
)

/**
 * Renders a group's [StopQualifier] into its sub-header parts. A **rail platform** leads with
 * "Platform N" and the compass in parens; a **bus pole** with "Stop X" and its "towards" in parens
 * (trimmed at TfL's " Or ", so "Farringdon Or Holborn Circus" reads "Farringdon"). The fallbacks — a
 * bare rail compass, a bus bearing, a bus terminus — are single labels with no parenthetical. Pure
 * (uppercasing is locale-invariant), so the mapping is unit-testable apart from the composable.
 */
internal fun subHeaderText(qualifier: StopQualifier): SubHeaderText = when (qualifier) {
    is StopQualifier.Platform -> SubHeaderText(
        primary = "Platform ${qualifier.number}".uppercase(),
        paren = qualifier.direction,
        spoken = qualifier.direction?.let { "Platform ${qualifier.number}, $it" }
            ?: "Platform ${qualifier.number}",
    )
    is StopQualifier.Compass -> SubHeaderText(
        primary = qualifier.label.uppercase(),
        paren = null,
        spoken = qualifier.label,
    )
    is StopQualifier.BusStop -> {
        val towards = qualifier.towards?.substringBefore(" Or ")?.trim()?.ifEmpty { null }
        // Uppercase the letter so the spoken "Stop D" matches the display whatever case TfL supplied
        // (the display uppercases everything for small caps; the spoken keeps title case).
        // uppercase() is locale-invariant.
        val letter = qualifier.letter.uppercase()
        SubHeaderText(
            primary = "Stop $letter".uppercase(),
            paren = towards?.let { "towards $it" },
            spoken = towards?.let { "Stop $letter, towards $it" } ?: "Stop $letter",
        )
    }
    is StopQualifier.BusBearing -> {
        val bearing = qualifier.bearing.uppercase()
        SubHeaderText(
            primary = "(→$bearing)",
            paren = null,
            spoken = bearingSpoken(bearing),
        )
    }
    is StopQualifier.Terminus -> {
        // The same display rename the destination card uses ("Battersea Power" → "Battersea",
        // DepartureLabels), so header and card read consistently (Codex P2, PR #116).
        val display = DepartureLabels.destinationLabel(qualifier.terminus, "") ?: qualifier.terminus
        SubHeaderText(
            primary = "→ ${display.uppercase()}",
            paren = null,
            spoken = "to $display",
        )
    }
}

/** The spoken form of a compass bearing ("E" → "Eastbound"), so a screen reader hears the direction
 *  rather than the letter behind the "(→E)" glyph. Intercardinals get the hyphenated "-bound" form. */
private fun bearingSpoken(bearing: String): String = when (bearing.uppercase()) {
    "N" -> "Northbound"
    "E" -> "Eastbound"
    "S" -> "Southbound"
    "W" -> "Westbound"
    "NE" -> "Northeast-bound"
    "NW" -> "Northwest-bound"
    "SE" -> "Southeast-bound"
    "SW" -> "Southwest-bound"
    else -> bearing
}
