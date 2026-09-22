package app.stopcast.domain

/**
 * Groups the flat departure list into per-**place** sections so the screen can show a small
 * name header above each group of same-place cards (SPEC D8). The flat list interleaves stops
 * soonest-first, which gives a card no boarding location once more than one place is on screen;
 * grouping restores it without putting the name back on every card.
 *
 * **One header per (place, direction)** (maintainer, 2026-09-22). A "place" is a cluster of stops
 * that share a [DepartureRow.clusterId] — the two poles of a bus junction, a station's several
 * platforms — so they read as one boarding location, the way a Tube station (a single stop id
 * aggregating its platforms) already did. Grouping by stop id instead split a junction's poles into
 * two identical headers (SPEC *Finding stops*). Within a place, a rail platform's **compass
 * direction** (parsed from `platformName` by [PlatformDirection]) splits the cards into one header
 * per direction — "King's Cross – Eastbound" — since a busy interchange under one bare name is a
 * wall of cards with direction living only in each destination. The compass, not TfL's
 * `inbound`/`outbound`, is the key: TfL's own direction is inconsistent across lines at one
 * platform (see [PlatformDirection]). A **bus** pole, which carries no compass in the arrivals feed,
 * splits on its **stop letter** ("King's Cross Station (D)"), else its **compass bearing** ("(→E)")
 * when TfL gives no letter — the bus analog of the rail compass, from the nearby lookup's
 * [DepartureRow.stopLetter]/[DepartureRow.bearing]. A bus place with neither falls back to a **shared
 * terminus** ("→ Bank") when the whole stop heads one way ([sharedBusTerminus]), else the bare
 * per-place header. The group's [StopQualifier] carries whichever cue it split on.
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
    fun groupByStop(rows: List<DepartureRow>, warningsLead: Boolean = true): List<StopGroup> {
        // Stop closures form NO group — the screen renders them as header-less cards that title
        // themselves on expand (SPEC *Disruptions*) — but a closure's stop is still a **place on
        // the screen**, so it is counted toward the header decision below (see [placeKeys]).
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
        // Group by **(place, compass direction)**: the place is the cluster (a junction's poles, a
        // station's platforms) as before, and within it a rail platform's compass (parsed from
        // `platformName` by [PlatformDirection]) splits the cards into one header per direction —
        // "King's Cross – Eastbound" (SPEC D8). A row with no parseable compass (a bus pole, whose
        // bearing lives in stop metadata not the arrivals feed, or a bare "Platform 4") carries
        // none, so it falls to the bare per-place header — the split ships rail-first without
        // regressing buses. A warned stop is not split (its warning names no direction, and its
        // timed rows ride with it, as before); its rows keep the whole-stop key from [clusterKeyOf].
        val byGroup = LinkedHashMap<String, MutableList<DepartureRow>>()
        val infoOf = HashMap<String, GroupInfo>()
        // One canonical display name per place, so a station whose members TfL spells differently
        // (the very reason for clustering by `stationNaptan`) reads the same on every direction
        // header — never "King's Cross – Eastbound" beside "King's Cross St. Pancras – Westbound"
        // (Codex P2, PR #109). The first row's name for the place wins, matching the single-group
        // behavior before the direction split.
        val placeName = HashMap<String, String>()
        for (row in listRows) {
            val place = clusterKeyOf(row, warnedStops)
            placeName.getOrPut(place) { row.stopName }
            // Resolve the compass from ALL the row's predictions, not just the soonest: a row is
            // one (line, direction), and TfL can leave the platform blank on some of its
            // predictions, so the soonest may carry no compass while a later one does. Taking the
            // first that resolves keeps the row under its direction header rather than dropping to
            // the bare header until the blank prediction departs (Codex P2, PR #109).
            //
            // A no-prediction status row (a suspended line) has an empty [upcoming], so it resolves
            // to no compass and stays directionless on its own — but the stop's LIVE timed rows still
            // parse their compass. Gating on `warnedStops` here instead would null every timed row's
            // direction at a stop where any one line is suspended, collapsing a whole interchange's
            // compass sections back into one bare group (Codex P2, PR #109). The place carve-out for
            // a warned stop stays (clusterKeyOf), so a "No departures" row is still not merged with a
            // same-named pole's live same-line departures.
            val split = splitOf(row)
            val key = "$place\u0001${split.keyPart}"
            infoOf.getOrPut(key) { GroupInfo(place, split) }
            byGroup.getOrPut(key) { mutableListOf() }.add(row)
        }
        // Distinct **places** on the whole screen (direction-independent) = the grouped departure
        // places PLUS each header-less closure alert's place. A closure-only stop (its arrivals
        // failed, so it has no departure rows) forms no group here, but it is still a place the
        // departure cards must be told apart from — so without counting it a lone departures stop
        // beside a closure-only stop would read as a single-place list and drop its name header,
        // leaving those departures with no boarding location (Codex P1, PR #91). A closure keys by
        // the same cluster identity a group's place does, so a closure at a stop that also has
        // departures counts as the one shared place, not two — and a place split into several
        // direction groups is still one place, so a single station's directions don't read as
        // several distinct places.
        val closurePlaces = closures.mapTo(HashSet()) { clusterKeyOf(it, emptySet()) }
        val placeKeys = byGroup.keys.mapTo(HashSet()) { infoOf.getValue(it).place } + closurePlaces
        val multiPlace = placeKeys.size > 1
        // How many direction groups each place split into, so a single station that splits into
        // Eastbound/Westbound/… shows a header on each even though it is one place.
        val groupsPerPlace = byGroup.keys.groupingBy { infoOf.getValue(it).place }.eachCount()
        // Order the groups so a **place's direction blocks stay adjacent** (SPEC D8 — a place's
        // cards stay together, led by its soonest): a station that split into Eastbound/Westbound/…
        // must not have another place's block wedged between its directions. So places sort first
        // (by [placeRankOf], then by first appearance — which reflects soonest or, near me, closest,
        // since the caller passes rows already so-ordered), and only within a place do its blocks
        // sort by their own first appearance. A starred place still leads (its starred row already
        // sorts ahead in the caller's list).
        //
        // **[warningsLead] decides whether a warned place is lifted.** On the watched list (the
        // default) a place carrying a "No departures" line-status row leads an ordinary one — a
        // warning the user must see isn't buried, and grouping doesn't rely on the caller pre-sorting
        // warnings first. On the near-me list the caller orders by distance and passes
        // `warningsLead = false`, so a stop is never hoisted above a closer one merely for carrying an
        // alert (maintainer, 2026-09-22); the alert just rides with its stop in the flat (distance)
        // order — no special place lift, and none within the place either. Stop closures are
        // unaffected either way — they render as standalone cards outside grouping.
        val placeRank = HashMap<String, Int>()
        for ((key, groupRows) in byGroup) {
            placeRank.merge(infoOf.getValue(key).place, groupRows.minOf(::rowPriority), ::minOf)
        }
        fun placeRankOf(key: String): Int =
            if (warningsLead) placeRank.getValue(infoOf.getValue(key).place) else 0
        val placeFirstIndex = LinkedHashMap<String, Int>()
        byGroup.keys.forEach { key ->
            placeFirstIndex.getOrPut(infoOf.getValue(key).place) { placeFirstIndex.size }
        }
        val groupIndex = byGroup.keys.withIndex().associate { (i, key) -> key to i }
        return byGroup.entries
            .sortedWith(
                compareBy<Map.Entry<String, MutableList<DepartureRow>>>(
                    { placeRankOf(it.key) },
                    { placeFirstIndex.getValue(infoOf.getValue(it.key).place) },
                    { groupIndex.getValue(it.key) },
                ),
            )
            .map { (key, groupRows) ->
                val info = infoOf.getValue(key)
                // The group's **qualifier** — the cue that tells two blocks of one place apart. A
                // rail platform keys on its compass; a **bus** pole on its letter, else its bearing
                // (the split above). A compass/letter/bearing-less bus place falls back to the shared
                // terminus ("→ Bank") when its whole stop heads one way ([sharedBusTerminus]) — else
                // it is the bare place name.
                val qualifier = when (val s = info.split) {
                    is RowSplit.Compass -> StopQualifier.Compass(s.label)
                    is RowSplit.Letter -> StopQualifier.BusLetter(s.letter)
                    is RowSplit.Bearing -> StopQualifier.BusBearing(s.bearing)
                    RowSplit.None -> sharedBusTerminus(groupRows)?.let(StopQualifier::Terminus)
                }
                // A header is drawn where it tells the reader something: more than one place
                // (counting closures), a place split into several groups, or a qualifier worth naming.
                val showHeader =
                    multiPlace || qualifier != null || (groupsPerPlace[info.place] ?: 1) > 1
                StopGroup(
                    stopName = placeName.getValue(info.place),
                    key = key,
                    showHeader = showHeader,
                    rows = groupRows,
                    qualifier = qualifier,
                    placeKey = info.place,
                )
            }
    }

    /**
     * The single terminus a **bus** place heads to, for the "→ Terminus" header qualifier, or null
     * when it has none to stand behind. Bus-only, and only for a place with no letter or bearing to
     * split on (this is called only for a [RowSplit.None] group): the letter/bearing is the primary
     * bus cue, the terminus its fallback; other compass-less modes stay bare.
     *
     * "Heads one way" is judged from **every timed prediction** in the group, not the row headline:
     * a single (line, direction) row can carry departures to more than one terminus (a short-working
     * among the through buses), and [DepartureRow.destination] names only the *soonest* — so keying
     * on it could assert "→ Bank" while a card below still shows a later Waterloo departure (Codex
     * P1, PR #116). So this checks each upcoming departure's destination: they must all name the
     * **same, non-blank** terminus. Any divergence, or a blank TfL didn't fill (which can't confirm
     * the shared terminus — SPEC principle 1), yields null so the header stays the bare name rather
     * than claim a direction the snapshot contradicts.
     *
     * A **status row** (a suspended line, [DepartureRow.upcoming] empty) names no destination to
     * confirm, and it rides in the group at the same stop id — so it too withholds the terminus,
     * rather than let its card sit under a "→ Terminus" header for a direction it may not share
     * (Codex P2, PR #116): every route in the group must name the confirmed terminus, so any route
     * that can't disqualifies it.
     */
    private fun sharedBusTerminus(rows: List<DepartureRow>): String? {
        if (rows.isEmpty() || rows.any { it.mode != "bus" || it.upcoming.isEmpty() }) return null
        val destinations = HashSet<String>()
        for (row in rows) {
            for (dep in row.upcoming) {
                val dest = dep.destination.trim()
                if (dest.isEmpty()) return null
                destinations.add(dest)
                if (destinations.size > 1) return null
            }
        }
        return destinations.singleOrNull()
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

    /** A group's place (the cluster key) and the [RowSplit] its rows share — the discriminator that
     *  keys the group within its place (a rail compass, a bus letter/bearing, or none). */
    private data class GroupInfo(val place: String, val split: RowSplit)

    /**
     * What a row splits its place on — the per-place group discriminator, resolved per row so rows
     * that share it land in one group. A **rail** platform splits on its [Compass] (parsed from
     * `platformName`); a **bus** pole on its [Letter] ("D"), else its [Bearing] ("E") when it has no
     * letter; anything else is [None] (a compass-less bus with no letter/bearing — which then falls
     * back to the shared terminus — or a bare platform). The [keyPart] tags each kind so a bus letter
     * "E" and a bearing "E" never collide in the group key.
     */
    private sealed interface RowSplit {
        val keyPart: String

        data class Compass(val label: String) : RowSplit {
            override val keyPart get() = "c\u0001$label"
        }

        data class Letter(val letter: String) : RowSplit {
            override val keyPart get() = "l\u0001$letter"
        }

        data class Bearing(val bearing: String) : RowSplit {
            override val keyPart get() = "b\u0001$bearing"
        }

        data object None : RowSplit {
            override val keyPart get() = ""
        }
    }

    /**
     * The [RowSplit] a row belongs to. Rail's **compass** (from ALL the row's predictions, not just
     * the soonest — TfL can leave the platform blank on some, so a later one may carry it, Codex P2
     * PR #109) wins where present. Else a **bus** row splits on its pole [DepartureRow.stopLetter],
     * else its [DepartureRow.bearing]. A status row (empty `upcoming`) carries no letter, so a
     * suspended bus line stays [None] on its own rather than claiming a pole.
     */
    private fun splitOf(row: DepartureRow): RowSplit {
        row.upcoming.firstNotNullOfOrNull { PlatformDirection.of(it.platform) }
            ?.let { return RowSplit.Compass(it) }
        if (row.mode == "bus") {
            row.stopLetter.trim().ifEmpty { null }?.let { return RowSplit.Letter(it) }
            row.bearing.trim().ifEmpty { null }?.let { return RowSplit.Bearing(it) }
        }
        return RowSplit.None
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
 * The cue a group header carries beside the place name — the one thing that tells two groups of one
 * place apart (SPEC D8). Exactly one kind per group; null on [StopGroup.qualifier] is the bare name.
 * The screen ([app.stopcast.ui]) owns how each renders ("– Eastbound", "(D)", "(→E)", "→ Bank").
 */
sealed interface StopQualifier {
    /** A rail platform's compass direction ("Eastbound"), parsed from `platformName`. */
    data class Compass(val label: String) : StopQualifier

    /** A bus pole's letter ("D", TfL `stopLetter`) — the letter on the physical stop. */
    data class BusLetter(val letter: String) : StopQualifier

    /** A bus pole's compass bearing ("E", TfL `CompassPoint`), the fallback when it has no letter. */
    data class BusBearing(val bearing: String) : StopQualifier

    /** The single terminus a letter/bearing-less **bus** place heads to ("Bank"), the last fallback. */
    data class Terminus(val terminus: String) : StopQualifier
}

/**
 * One group of departure [rows] the screen renders under a single header. A place is a cluster of
 * stops sharing [stopName] (a junction's poles, a station's platforms), so [rows] may span several
 * stop ids. [qualifier] is the group's discriminator within its place — the rail compass, a bus
 * pole's letter/bearing, or a bus place's shared terminus — or null when the group is the whole
 * place under its bare name (see [StopQualifier]).
 * [showHeader] is whether to draw the header at all (see [StopGrouping.groupByStop]). [key] is
 * stable and unique per group for a LazyColumn — the place key plus the split discriminator.
 */
data class StopGroup(
    val stopName: String,
    val key: String,
    val showHeader: Boolean,
    val rows: List<DepartureRow>,
    val qualifier: StopQualifier? = null,
    // The place (cluster) identity this group belongs to, shared by every group of one physical
    // place. The near-me screen resolves one **place-wide** header distance from it — the nearest of
    // all the place's members — so a station's split headers show one consistent distance rather than
    // each its own group's members' nearest (Codex P2, PR #109). Blank only for a default-constructed
    // group.
    val placeKey: String = "",
)
