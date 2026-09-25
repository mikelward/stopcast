package app.stopdash.domain

/**
 * The transport modes the user has hidden from the near-me list ("bus", "national-rail"…), and
 * what hiding one means (SPEC *Finding stops → Hiding a mode*): a stop that serves only hidden
 * modes isn't picked or fetched at all, and a hidden mode's rows are left out of a stop that also
 * serves others. A closure notice still shows at a stop that keeps an unhidden mode, so hiding one
 * mode never hides a closed stop the user still rides from; a stop serving only hidden modes isn't
 * checked, since its closure matters only to a rider of that mode. Modes compare case-insensitively,
 * as TfL's mode ids are lowercase but not guaranteed so.
 */
object HiddenModes {
    /** Whether [mode] is one of [hidden]. */
    fun isHidden(mode: String, hidden: Set<String>): Boolean = hidden.any { it.equals(mode, ignoreCase = true) }

    /**
     * [stops] without their hidden-mode lines, and without a stop left serving nothing: it isn't
     * picked for the near-me set, so it costs no request. A stop TfL listed no lines for is kept as
     * it is — it has no mode to hide, and the near-me selection already treats it as route-less.
     */
    fun stops(stops: List<StopLocation>, hidden: Set<String>): List<StopLocation> {
        if (hidden.isEmpty()) return stops
        return stops.mapNotNull { stop ->
            if (stop.lines.isEmpty()) return@mapNotNull stop
            val kept = stop.lines.filterNot { isHidden(it.mode, hidden) }
            when {
                kept.isEmpty() -> null
                kept.size == stop.lines.size -> stop
                else -> stop.copy(lines = kept)
            }
        }
    }

    /** [rows] without a hidden mode's departures and status rows; stop-closure rows always stay. */
    fun rows(rows: List<DepartureRow>, hidden: Set<String>): List<DepartureRow> {
        if (hidden.isEmpty()) return rows
        return rows.filter { it.stopDisruption != null || !isHidden(it.mode, hidden) }
    }
}
