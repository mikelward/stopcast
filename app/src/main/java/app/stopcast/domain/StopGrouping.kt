package app.stopcast.domain

/**
 * Groups the flat departure list into per-**place** sections so the screen can show a small
 * name header above each group of same-place cards (SPEC D8). The flat list interleaves stops
 * soonest-first, which gives a card no boarding location once more than one place is on screen;
 * grouping restores it without putting the name back on every card.
 *
 * **One header per place, bare name** (maintainer, 2026-09-21). A "place" is a cluster of stops
 * that share a [DepartureRow.clusterId] — the two poles of a bus junction, a station's several
 * platforms — so they read as one boarding location under a single header, the way a Tube station
 * (a single stop id aggregating its platforms) already did. Grouping by stop id instead split a
 * junction's northbound and southbound poles into two identical headers (SPEC *Finding stops*).
 * Direction still lives in each card's destination; a per-direction subhead (fixed compass order)
 * is a later step (TODO).
 *
 * The cluster key is TfL's `stationNaptan` where it gives one, else the cleaned display name
 * ([clusterKeyOf]; resolved in the nearby lookup). Keying on TfL's own cluster is what keeps a
 * station it spells several ways together — the display name alone is an unreliable key (King's
 * Cross St. Pancras has several forms) — while still holding two genuinely distinct same-named
 * stops apart where TfL gives them different clusters (maintainer, 2026-09-21).
 *
 * Pure and Android-free so the grouping rule is JVM-testable apart from the screen, and so the
 * widget can adopt the same headers later without the two surfaces drifting.
 */
object StopGrouping {

    /**
     * The flat [rows] clustered into one [StopGroup] per place (stops sharing a display name).
     * Order is preserved: places come out in the order they first appear in [rows]. Because
     * [rows] arrive already ordered (soonest-first, or closest-stop-first near me, with warnings
     * and starred rows lifted by the caller), the leading group is still the one with the
     * soonest/closest — or most urgent — departure, and a star lifts its whole place with it. A
     * place's poles' rows stay contiguous within its group even where the flat list interleaved
     * them with another place's.
     *
     * [StopGroup.showHeader] is false only for a lone single-place group with no closure to name
     * (the classic single-place case, where the place is implied).
     */
    fun groupByStop(rows: List<DepartureRow>): List<StopGroup> {
        // A stop carrying ANY warning row keeps its OWN group — never merged into a shared-name
        // cluster. A warning row (a row with no countdown, [DepartureRow.upcoming] empty) is a
        // stop-level closure/moved-stop notice or a line-status "No departures" row for a
        // suspended line; both name no pole or direction (the header does). Merging one beside a
        // same-named pole's catchable departures would leave a rider unable to tell which pole is
        // closed, or show "141 suspended — No departures" next to live 141 times with nothing to
        // say which pole each is (SPEC principle 2 — never quietly wrong). Clear stops (all rows
        // timed) still cluster by name so a junction's poles read as one place. Carving the warned
        // stop out — rather than adding a per-pole/direction qualifier to the warning row — takes
        // the safe half now; the qualifier is a deferred design (TODO.md). (Codex P1s, PR #83.)
        val warnedStops = HashSet<String>()
        for (row in rows) if (row.upcoming.isEmpty()) warnedStops.add(row.stopId)
        val byCluster = LinkedHashMap<String, MutableList<DepartureRow>>()
        for (row in rows) {
            byCluster.getOrPut(clusterKeyOf(row, warnedStops)) { mutableListOf() }.add(row)
        }
        val distinctClusters = byCluster.size
        val groups = byCluster.map { (key, groupRows) ->
            // A closed stop always names itself in the header (the closure card no longer
            // repeats the name); otherwise a header is drawn only where it tells the reader
            // something — more than one place to tell apart.
            val hasClosure = groupRows.any { it.stopDisruption != null }
            StopGroup(
                stopName = groupRows.first().stopName,
                key = key,
                showHeader = distinctClusters > 1 || hasClosure,
                rows = groupRows,
            )
        }
        // Preserve the caller's warnings-first ordering across the clustering: a place with a
        // closure/status warning leads an ordinary-only place, ranked by its highest-priority
        // row. Stable, so equal-priority places keep their first-appearance (soonest/closest)
        // order, and a starred place still leads (its starred row already sorts ahead of any
        // ordinary one in the caller's list). A no-op on the already-ordered rows the screen
        // passes, but makes the guarantee explicit and robust for any caller. Two *different*
        // places' warnings still interleave by block — one header per place keeps a place's own
        // rows contiguous — the accepted cost of the grouping (maintainer's call, 2026-09-21).
        return groups.sortedBy { group -> group.rows.minOf(::rowPriority) }
    }

    /**
     * The clustering identity: [DepartureRow.clusterId] — TfL's `stationNaptan` where it gives one
     * (a junction's poles and a station's platforms share it), else the cleaned display name
     * (resolved upstream in the nearby lookup). Keying on the cluster rather than the name alone is
     * what keeps a station TfL spells several ways together (King's Cross St. Pancras) while still
     * holding two genuinely distinct stops apart. A blank clusterId (a stop with no cluster known —
     * a future watched stop before its cluster is captured) groups the stop on its own.
     *
     * A stop in [warnedStops] (any stop with a warning row) is keyed by its own id instead, never
     * merged with a same-cluster clear pole — see [groupByStop]. The `\u0000` prefix keeps the
     * per-stop keys from colliding with any real clusterId.
     */
    private fun clusterKeyOf(row: DepartureRow, warnedStops: Set<String>): String =
        if (row.stopId in warnedStops) "\u0000stop:${row.stopId}"
        else row.clusterId.ifBlank { "\u0000stop:${row.stopId}" }

    // Matches DepartureRows' row ordering: a stop-closure warning outranks a no-prediction
    // status row, which outranks a timed departure.
    private fun rowPriority(row: DepartureRow): Int = when {
        row.stopDisruption != null -> 0
        row.upcoming.isEmpty() -> 1
        else -> 2
    }
}

/**
 * One place's group of departure [rows] the screen renders under a single name header. A place is
 * a cluster of stops sharing [stopName] (a junction's poles, a station's platforms), so [rows] may
 * span several stop ids. [showHeader] is whether to draw the header at all (see
 * [StopGrouping.groupByStop]). [key] is stable and unique per group for a LazyColumn — the cluster
 * key, which is the display name today.
 */
data class StopGroup(
    val stopName: String,
    val key: String,
    val showHeader: Boolean,
    val rows: List<DepartureRow>,
)
