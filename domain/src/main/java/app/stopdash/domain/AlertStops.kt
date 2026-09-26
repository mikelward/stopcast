package app.stopdash.domain

/**
 * Which stations on a line's page its service alert names (SPEC *Disruptions*): a first guess at
 * the stretch a diversion or closure touches, since TfL gives it only as prose ("Diverted between
 * Moorgate and Monument") with no structured list of affected stops.
 *
 * A match is the station's name as a whole phrase in the text, compared without case but required
 * to start with a capital, since TfL writes place names as proper nouns: that keeps "bank" in "the
 * river bank" from marking Bank. A name followed by a word that makes it something other than a
 * place — "Victoria line", "Bank branch", "Hammersmith & City", "Bank Holiday" — is not a match,
 * because TfL's alerts name lines and branches far more often than those stations.
 *
 * A guess, and deliberately a generous one: a station marked that the alert only mentions in
 * passing costs a glance, where one it misses leaves the rider to read the prose anyway. So it
 * only ever adds a marker and never hides or reorders anything.
 */
object AlertStops {
    /** The ids of [stops] whose names appear in [text]; empty when there is no text. */
    fun mentioned(text: String?, stops: List<RouteStop>): Set<String> {
        if (text.isNullOrBlank() || stops.isEmpty()) return emptySet()
        // The two lines named after a station and "City" are taken out first, so neither marks its
        // station — and a station followed by another starting "City" ("Bank and City Thameslink") is
        // still a station. The placeholder is one word in capitals, so a list of lines around it keeps
        // its shape ("Victoria and Hammersmith & City lines") and an all-caps clause stays all caps.
        val haystack = normalize(text).replace(CITY_LINES, "CITYLINE")
        return stops.filterTo(LinkedHashSet()) { stop -> names(stop.name).any { named(haystack, it) } }
            .mapTo(LinkedHashSet(), RouteStop::id)
    }

    // The name as listed and as a rider would write it (no "Underground Station", no line
    // parenthetical), each folded the same way as the alert text. A bus stop is listed with its cross
    // street ("Camomile Street / Bishopsgate") where an alert names only the stop's own part, so the
    // part before the slash counts too — but not the part after, which names another road.
    private fun names(name: String): Set<String> {
        val primary = name.substringBefore(" / ").trim()
        return setOf(name, cleanStopName(name), primary, cleanStopName(primary))
            .map(::normalize).filterTo(LinkedHashSet()) { it.length >= MIN_NAME }
    }

    private fun named(haystack: String, name: String): Boolean {
        var from = 0
        while (true) {
            val at = haystack.indexOf(name, from, ignoreCase = true)
            if (at < 0) return false
            from = at + 1
            val end = at + name.length
            if (at > 0 && haystack[at - 1].isLetterOrDigit()) continue
            if (end < haystack.length && haystack[end].isLetterOrDigit()) continue
            if (haystack[at].isLowerCase()) continue
            val rest = haystack.substring(end)
            if (NOT_A_PLACE.containsMatchIn(rest)) continue
            if (!shouting(haystack, at, end) && (LIST_OF_LINES.containsMatchIn(rest) || inTowardsList(haystack, at))) continue
            return true
        }
    }

    // One spelling for the variants TfL mixes within a single alert: an apostrophe or none,
    // "&" and "and", "St." and "St", and runs of spaces.
    //
    // A line break (TfL's escaped "\n" included) ends a clause, as a full stop does: TfL often separates clauses with one and no
    // punctuation ("Buses towards London Bridge\nLondon Bridge Station is closed"). It is kept as
    // "; " rather than a space, so every check that stops at a clause end — the "towards" list
    // behind a name, the list of lines after it — stops there too.
    private fun normalize(s: String): String = s
        .replace("\\n", "\n")
        .replace(Regex("""\s*\n\s*"""), "; ")
        // No apostrophe at all, curly or straight: TfL writes "Earls Court" for the station it lists
        // as "Earl's Court".
        .replace(Regex("[’']"), "")
        .replace(Regex("""\s+&\s+"""), " and ")
        // Any case, keeping the letters' own: an all-caps alert writes "ST. PAUL'S".
        .replace(Regex("""\b(St)\.""", RegexOption.IGNORE_CASE), "$1")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // Whether the clause around [at] is written all in capitals. That loses the signal that tells a
    // place name from an ordinary word, so the checks that lean on it (a "towards" list, a list of
    // lines) are skipped there and every name in it is marked: a destination marked too costs a
    // glance, where a missed affected stop is the failure this exists to avoid. Judged per clause, so
    // lowercase prose elsewhere in the alert doesn't switch it off; neither does the name being
    // matched ([at] until [nameEnd]), which an all-caps template can still quote in its own case, nor
    // a bare "and", since [normalize] writes one for "&".
    private fun shouting(haystack: String, at: Int, nameEnd: Int): Boolean {
        val start = haystack.lastIndexOfAny(CLAUSE_ENDS, at - 1) + 1
        val end = haystack.indexOfAny(CLAUSE_ENDS, at).let { if (it < 0) haystack.length else it }
        val rest = haystack.substring(start, at) + " " + haystack.substring(nameEnd, maxOf(nameEnd, end))
        return rest.replace(AND, "").none(Char::isLowerCase)
    }

    // Whether the name at [at] is one of the destinations in a "towards Lewisham and London Bridge"
    // list: that says which way the affected buses run, not where the disruption is. The list is the
    // run after the nearest "towards" in the same sentence made only of capitalized words, commas,
    // "and" and "or" — anything else (a verb, "will miss stops") means the name is past the list.
    private fun inTowardsList(haystack: String, at: Int): Boolean {
        val sentence = haystack.lastIndexOfAny(CLAUSE_ENDS, at - 1) + 1
        val towards = haystack.lastIndexOf("towards ", at, ignoreCase = true)
        if (towards < sentence) return false
        val between = haystack.substring(towards + "towards ".length, at)
        return between.split(' ', ',').filter(String::isNotEmpty).all { it == "and" || it == "or" || it.first().isUpperCase() }
    }

    private val CLAUSE_ENDS = charArrayOf('.', '?', '!', ':', ';')
    private val AND = Regex("""\band\b""")

    // A word straight after a name that makes it a line, a branch or a holiday rather than a place.
    private val NOT_A_PLACE = Regex("""^\s+(lines?|branch(es)?|holidays?)\b""", RegexOption.IGNORE_CASE)

    // The Hammersmith & City and Waterloo & City lines, as [normalize] spells them — but not where
    // another name follows ("Waterloo and City Thameslink" is two stations), "line" aside.
    private val CITY_LINES =
        Regex("""(?i:\b(Hammersmith|Waterloo) and City\b)(?!\s+(?!(?i:lines?)\b)\p{Lu})""")

    // A name that leads a list of lines or branches — "Victoria and Piccadilly lines", "Bank,
    // Charing Cross and Kennington branches" — is a line too: more capitalized names joined by
    // commas, "and" or "or", then "lines" or "branches". Case-sensitive, so a lowercase word ends
    // the list.
    private val LIST_OF_LINES = Regex("""^(?:,?\s+(?:(?:and|or)\s+)?\p{Lu}[\p{L}'.-]*)+\s+(?:[Ll]ines|[Bb]ranch(?:es)?)\b""")

    // Shorter than this, a name is more likely a fragment of another word or phrase than a station.
    private const val MIN_NAME = 3
}
