package app.stopcast.ui

import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.StopQualifier

/**
 * A group header's **qualifier segment** — the title-case cue that follows the place name on the one
 * line header ("Platform 1", "Stop G", "Eastbound", "→ Bank"). It joins the place name with " – "
 * ([app.stopcast.ui] owns that join and the styling); null when the group carries no qualifier, so
 * the header is the bare place name. Title case with no small-caps treatment, and it **drops the
 * direction/towards parenthetical** the old two-level sub-header showed — the compass/towards moves
 * into the [groupHeaderSpoken] label a screen reader hears instead (SPEC D8). Pure, so the mapping is
 * unit-testable apart from the composable.
 */
internal fun groupHeaderLabel(qualifier: StopQualifier?): String? = when (qualifier) {
    null -> null
    is StopQualifier.Platform -> "Platform ${qualifier.number}"
    is StopQualifier.Compass -> qualifier.label
    // uppercase() is locale-invariant (Turkish-ı safe); the letter reads the same case however TfL
    // supplied it.
    is StopQualifier.BusStop -> "Stop ${qualifier.letter.uppercase()}"
    is StopQualifier.BusBearing -> "→${qualifier.bearing.uppercase()}"
    is StopQualifier.Terminus ->
        // The same display rename the destination line uses ("Battersea Power" → "Battersea",
        // DepartureLabels), so the header and the card read consistently.
        "→ ${DepartureLabels.destinationLabel(qualifier.terminus, "") ?: qualifier.terminus}"
}

/**
 * The **spoken** form of a group's qualifier — what a screen reader hears in place of the visible
 * segment's glyphs, keeping the direction/towards the visible label drops ("Platform 1, Northbound",
 * "Stop G, towards Farringdon", "Southwest-bound"). Null when the group carries no qualifier. The
 * composable prepends the place name and appends the distance for the header's full announced label.
 */
internal fun groupHeaderSpoken(qualifier: StopQualifier?): String? = when (qualifier) {
    null -> null
    is StopQualifier.Platform ->
        qualifier.direction?.let { "Platform ${qualifier.number}, $it" } ?: "Platform ${qualifier.number}"
    is StopQualifier.Compass -> qualifier.label
    is StopQualifier.BusStop -> {
        // Trim TfL's " Or " so "Farringdon Or Holborn Circus" reads "Farringdon" — a short cue, not a
        // paragraph. uppercase() the letter so the spoken "Stop D" matches the display case.
        val towards = qualifier.towards?.substringBefore(" Or ")?.trim()?.ifEmpty { null }
        val letter = qualifier.letter.uppercase()
        towards?.let { "Stop $letter, towards $it" } ?: "Stop $letter"
    }
    is StopQualifier.BusBearing -> bearingSpoken(qualifier.bearing.uppercase())
    is StopQualifier.Terminus -> {
        val display = DepartureLabels.destinationLabel(qualifier.terminus, "") ?: qualifier.terminus
        "to $display"
    }
}

/** The spoken form of a compass bearing ("E" → "Eastbound"), so a screen reader hears the direction
 *  rather than the letter behind the "→E" glyph. Intercardinals get the hyphenated "-bound" form. */
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
