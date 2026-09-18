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
