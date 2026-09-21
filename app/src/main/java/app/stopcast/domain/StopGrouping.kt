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
     * [StopGroup.showHeader] is false only when the whole screen is a single place (the place is
     * then implied). Stop-closure alerts are not grouped — the screen renders them as header-less
     * cards ahead of these groups (SPEC *Disruptions*) — but each closure's stop still counts as a
     * place, so a lone departures group beside a closure-only stop still shows its name header.
     */
    fun groupByStop(rows: List<DepartureRow>): List<StopGroup> {
        // Stop closures form NO group — the screen renders them as header-less cards that title
        // themselves on expand (SPEC *Disruptions*) — but a closure's stop is still a **place on
        // the screen**, so it is counted toward the header decision below (see [distinctPlaces]).
        // Only the non-closure rows are grouped and returned.
        val (closures, listRows) = rows.partition { it.stopDisruption != null }
        // A stop carrying a line-status "No departures" row (a suspended line with no predictions —
        // a row with no countdown, [DepartureRow.upcoming] empty) keeps its OWN group, never merged
        // into a shared-name cluster: the row names no pole or direction (the header does), so
        // merging it beside a same-named pole's catchable departures would show "141 suspended — No
        // departures" next to live 141 times with nothing to say which pole each is (SPEC principle
        // 2 — never quietly wrong). Clear stops (all rows timed) still cluster by name so a
        // junction's poles read as one place. Carving the warned stop out — rather than adding a
        // per-pole/direction qualifier to the warning row — takes the safe half now; the qualifier
        // is a deferred design (TODO.md). (Codex P1s, PR #83.)
        val warnedStops = HashSet<String>()
        for (row in listRows) if (row.upcoming.isEmpty()) warnedStops.add(row.stopId)
        val byCluster = LinkedHashMap<String, MutableList<DepartureRow>>()
        for (row in listRows) {
            byCluster.getOrPut(clusterKeyOf(row, warnedStops)) { mutableListOf() }.add(row)
        }
        // Distinct places on the whole screen = the grouped departure places PLUS each header-less
        // closure alert's place. A closure-only stop (its arrivals failed, so it has no departure
        // rows) forms no group here, but it is still a place the departure cards must be told apart
        // from — so without counting it a lone departures stop beside a closure-only stop would
        // read as a single-place list and drop its name header, leaving those departures with no
        // boarding location on the header-less watched list (Codex P1, PR #91). A closure keys by
        // the same cluster identity a group does, so a closure at a stop that also has departures
        // counts as the one shared place, not two.
        val closurePlaces = closures.mapTo(HashSet()) { clusterKeyOf(it, emptySet()) }
        val distinctPlaces = (byCluster.keys + closurePlaces).size
        val groups = byCluster.map { (key, groupRows) ->
            // A header is drawn only where it tells the reader something — more than one place to
            // tell apart, counting closure alerts.
            StopGroup(
                stopName = groupRows.first().stopName,
                key = key,
                showHeader = distinctPlaces > 1,
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
