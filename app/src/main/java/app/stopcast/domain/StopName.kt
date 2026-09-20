package app.stopcast.domain

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
private const val BRANCH_SUFFIX = " Branch"

/**
 * The "via" branch TfL names in a prediction's `towards` — `Charing X` from
 * "Battersea Power Station via Charing Cross" — or null when there is no "via" (most
 * services, and buses, whose `towards` is a plain destination or a comma list). This is
 * the branch a rider reads off the platform board to pick a train, distinct from the
 * terminus. Anything past a comma is dropped as noise, matching the destination cleaning.
 *
 * TfL's live feed spells the same trunk several ways — the Northern line's two central
 * trunks arrive as `Bank`, `Bank Branch`, and `CX` — so this folds them to one short
 * label the rider reads the same every time: a trailing " Branch" is dropped as noise,
 * and TfL's cryptic `CX` and the full `Charing Cross` both render as the board's own
 * `Charing X`. So the label is already a short form; [abbreviateBranch] only shortens a
 * still-longer branch (e.g. the Central line's Hainault-loop vias) when a row can't fit it.
 */
fun branchOf(towards: String?): String? {
    if (towards == null) return null
    val idx = towards.indexOf(VIA, ignoreCase = true)
    if (idx < 0) return null
    return normalizeBranch(towards.substring(idx + VIA.length).substringBefore(",").trim())
}

/**
 * Folds a branch label to the one short board form — see [branchOf] for the spellings TfL
 * uses and why. Applied both when parsing a prediction's `towards` and when restoring a
 * persisted snapshot, so a value an older build stored ("Charing Cross", "Bank Branch")
 * reads back as the same canonical label ("Charing X", "Bank") a fresh fetch produces — a row
 * never shows two spellings for one trunk across a process restart. `null`/blank is `null`.
 */
fun normalizeBranch(raw: String?): String? {
    if (raw == null) return null
    var branch = raw.trim()
    if (branch.endsWith(BRANCH_SUFFIX, ignoreCase = true)) {
        branch = branch.dropLast(BRANCH_SUFFIX.length).trim()
    }
    if (branch.equals("CX", ignoreCase = true) || branch.equals("Charing Cross", ignoreCase = true)) {
        return "Charing X"
    }
    return branch.ifBlank { null }
}

// The compass words, "Cross", and "Central" a departures board itself shortens ("Charing X",
// "E. Ham", "Walthamstow C."). Applied per word, so a word not here is left alone; only these
// are safe to shorten without losing which branch is meant (maintainer: Cross→X, East→E. &c.,
// and Central→C.). This is the branch/destination *word* map, not the line-pill abbreviation:
// the Central line's pill stays "CEN" (a separate mechanism), and this never touches it.
private val BRANCH_ABBREVIATIONS = mapOf(
    "Cross" to "X",
    "North" to "N.",
    "South" to "S.",
    "East" to "E.",
    "West" to "W.",
    "Central" to "C.",
)

/**
 * A shorter form of a branch for a row too narrow to fit the full one — "Charing Cross" →
 * "Charing X", "East Ham" → "E. Ham", "Walthamstow Central" → "Walthamstow C." —
 * abbreviating only the compass words, "Cross", and "Central" a board itself shortens, so
 * which branch is meant stays clear. A branch with no such word comes back unchanged (there
 * is nothing safe to drop), and the caller then ellipsizes.
 * Kept off the value stored in [branchOf] so the full name shows wherever it fits; the UI
 * measures and falls back to this only when it must.
 */
fun abbreviateBranch(branch: String): String =
    branch.split(" ").joinToString(" ") { word -> BRANCH_ABBREVIATIONS[word] ?: word }
