package app.stopcast.domain

/**
 * Shortens common whole words in a destination name so more of the name survives before a
 * hard clip on a narrow row (SPEC destination-label). The row applies this only as a
 * **shrink step**: the full name shows when it fits, and this is tried before the clean
 * cut — so `East Finchley` stays whole where there is room and becomes `E. Finchley` only
 * when the row would otherwise truncate it.
 *
 * **Whole words only.** A word is replaced only when it stands alone (space-delimited) and
 * matches exactly (case-sensitive — destinations are Title Case), never as a substring, so
 * `High Barnet` → `H. Barnet` but `Highgate`, `Eastcote`, and `Upminster` are untouched.
 * A short form ends in `.` and is never itself a key, so [abbreviate] is idempotent.
 *
 * The map is maintainer-chosen copy (2026-09-21): single-letter forms take a trailing
 * period so they read as abbreviations, not initials; multi-letter contractions take none.
 */
object DestinationAbbreviations {
    private val forms = mapOf(
        "North" to "N.",
        "South" to "S.",
        "East" to "E.",
        "West" to "W.",
        "High" to "H.",
        "Central" to "C.",
        "Upper" to "U.",
        "Lower" to "L.",
        "Great" to "Gt",
        "Junction" to "Jct",
        "Park" to "Pk",
        "Road" to "Rd",
        "Street" to "St",
        "Point" to "Pt",
    )

    /** The name with each recognized standalone word replaced by its short form. */
    fun abbreviate(name: String): String =
        name.split(" ").joinToString(" ") { token -> forms[token] ?: token }
}
