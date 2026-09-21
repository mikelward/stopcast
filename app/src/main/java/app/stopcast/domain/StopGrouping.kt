package app.stopcast.domain

/**
 * Groups the flat departure list into per-stop sections so the screen can show a small
 * stop-name header above each group of same-stop cards (SPEC D8 / TODO "restore the stop
 * name"). The flat list interleaves stops soonest-first, which gives a card no boarding
 * location once more than one stop is on screen; grouping restores it without putting the
 * stop back on every card.
 *
 * **One header per stop, bare name** (maintainer, 2026-09-21). This step ships the grouping
 * and the stop name alone. The mode-aware direction/terminus qualifier that would
 * disambiguate two same-named stops — a bus-stop letter or bearing, a rail platform's
 * direction, a shared terminus — is deliberately left out so it can be designed on its own;
 * it is the next step's work (TODO).
 *
 * Pure and Android-free so the grouping rule is JVM-testable apart from the screen, and so
 * the widget can adopt the same headers later without the two surfaces drifting.
 */
object StopGrouping {

    /**
     * The flat [rows] clustered into one [StopGroup] per stop. Order is preserved: stops come
     * out in the order they first appear in [rows]. Because [rows] arrive already ordered
     * (soonest-first, or closest-stop-first near me, with warnings and starred rows lifted by
     * the caller), the leading group is still the one with the soonest/closest — or most
     * urgent — departure, and a star lifts its whole stop with it.
     *
     * [StopGroup.showHeader] is false only for a lone single-stop group with no closure to
     * name (the classic single-stop case, where the stop is implied).
     */
    fun groupByStop(rows: List<DepartureRow>): List<StopGroup> {
        val distinctStops = rows.mapTo(HashSet()) { it.stopId }.size
        val byStop = LinkedHashMap<String, MutableList<DepartureRow>>()
        for (row in rows) {
            byStop.getOrPut(row.stopId) { mutableListOf() }.add(row)
        }
        val groups = byStop.map { (stopId, groupRows) ->
            // A closed stop always names itself in the header (the closure card no longer
            // repeats the name); otherwise a header is drawn only where it tells the reader
            // something — more than one stop to tell apart.
            val hasClosure = groupRows.any { it.stopDisruption != null }
            StopGroup(
                stopId = stopId,
                stopName = groupRows.first().stopName,
                key = stopId,
                showHeader = distinctStops > 1 || hasClosure,
                rows = groupRows,
            )
        }
        // Preserve the caller's warnings-first ordering across the clustering: a stop with a
        // closure/status warning leads an ordinary-only stop, ranked by its highest-priority
        // row. Stable, so equal-priority stops keep their first-appearance (soonest/closest)
        // order, and a starred stop still leads (its starred row already sorts ahead of any
        // ordinary one in the caller's list). A no-op on the already-ordered rows the screen
        // passes, but makes the guarantee explicit and robust for any caller. Two *different*
        // stops' warnings still interleave by block — one header per stop keeps a stop's own
        // rows contiguous — the accepted cost of the grouping (maintainer's call, 2026-09-21).
        return groups.sortedBy { group -> group.rows.minOf(::rowPriority) }
    }

    // Matches DepartureRows' row ordering: a stop-closure warning outranks a no-prediction
    // status row, which outranks a timed departure.
    private fun rowPriority(row: DepartureRow): Int = when {
        row.stopDisruption != null -> 0
        row.upcoming.isEmpty() -> 1
        else -> 2
    }
}

/**
 * One stop's group of departure [rows] the screen renders under a single stop-name header.
 * [showHeader] is whether to draw the header at all (see [StopGrouping.groupByStop]). [key]
 * is stable and unique per group for a LazyColumn.
 */
data class StopGroup(
    val stopId: String,
    val stopName: String,
    val key: String,
    val showHeader: Boolean,
    val rows: List<DepartureRow>,
)
