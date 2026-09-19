package app.trackmo.domain

/**
 * Trims TfL's `commonName` down to what a rider reads on a sign. TfL suffixes a stop's
 * type onto the name — "Charing Cross Underground Station", "London Bridge Rail Station",
 * "Canary Wharf DLR Station" — which is noise once the app is already a departures board;
 * dropping it keeps the list glanceable (SPEC *Concise copy*). Suffix-only: a name with
 * no type suffix (most bus stops) is returned unchanged, and a stop literally called
 * "Station" is never emptied.
 *
 * Order matters — the specific multi-word suffixes are tried before the bare " Station"
 * catch-all, so "X Underground Station" loses the whole phrase, not just "Station".
 */
fun cleanStopName(raw: String): String {
    val trimmed = raw.trim()
    val suffixes = listOf(
        " Underground Station",
        " DLR Station",
        " Rail Station",
        " Overground Station",
        " Station",
    )
    for (suffix in suffixes) {
        if (trimmed.length > suffix.length && trimmed.endsWith(suffix, ignoreCase = true)) {
            return trimmed.substring(0, trimmed.length - suffix.length).trim()
        }
    }
    return trimmed
}

private const val VIA = " via "

/**
 * The "via" branch TfL names in a prediction's `towards` — "Charing Cross" from
 * "Battersea Power Station via Charing Cross" — or null when there is no "via" (most
 * services, and buses, whose `towards` is a plain destination or a comma list). This is
 * the branch a rider reads off the platform board to pick a train, distinct from the
 * terminus. Anything past a comma is dropped as noise, matching the destination cleaning.
 * Returned in full; [abbreviateBranch] shortens it only when a row can't fit the full form.
 */
fun branchOf(towards: String?): String? {
    if (towards == null) return null
    val idx = towards.indexOf(VIA, ignoreCase = true)
    if (idx < 0) return null
    return towards.substring(idx + VIA.length).substringBefore(",").trim().ifBlank { null }
}

// The compass words and "Cross" a departures board itself shortens ("Charing X",
// "E. Ham"). Applied per word, so a word not here is left alone; only these are safe to
// shorten without losing which branch is meant (maintainer: Cross→X, and East→E. &c.).
private val BRANCH_ABBREVIATIONS = mapOf(
    "Cross" to "X",
    "North" to "N.",
    "South" to "S.",
    "East" to "E.",
    "West" to "W.",
)

/**
 * A shorter form of a branch for a row too narrow to fit the full one — "Charing Cross" →
 * "Charing X", "East Ham" → "E. Ham" — abbreviating only the compass words and "Cross" a
 * board itself shortens, so which branch is meant stays clear. A branch with no such word
 * comes back unchanged (there is nothing safe to drop), and the caller then ellipsizes.
 * Kept off the value stored in [branchOf] so the full name shows wherever it fits; the UI
 * measures and falls back to this only when it must.
 */
fun abbreviateBranch(branch: String): String =
    branch.split(" ").joinToString(" ") { word -> BRANCH_ABBREVIATIONS[word] ?: word }
