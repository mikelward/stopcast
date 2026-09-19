package app.trackmo.domain

import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/**
 * Groups a stop's [Departure]s into the flat list's rows (SPEC D8): one
 * [DepartureRow] per (line, direction) at the stop, each carrying that group's
 * not-yet-departed departures soonest-first. Pure and clock-free — the caller
 * supplies [now] — so it stays JVM-testable and off any render path.
 *
 * The stop is the caller's context: the TfL client fetches per stop, so a row is
 * stamped with the [stopId]/[stopName] the caller already holds rather than the
 * domain having to carry stop identity on every [Departure].
 */
object DepartureRows {
    /**
     * Rows for one stop's [departures], soonest-first overall. Departed services are
     * dropped first (via [Countdown.upcoming]), so a (line, direction) group whose
     * every departure has gone yields no row rather than an empty one. Rows are
     * ordered by their soonest departure, ties broken by line then direction for a
     * stable order independent of input order.
     */
    fun forStop(
        stopId: String,
        stopName: String,
        departures: List<Departure>,
        now: Instant,
        lineStatuses: Map<String, LineStatus> = emptyMap(),
        // Defaults to `now` (rows "as of now") so a grouping-only caller need not supply it;
        // `across` passes the stop's own fetch age so the screen can withhold per stop.
        fetchedAt: Instant = now,
    ): List<DepartureRow> {
        // upcoming() has already dropped departed services and sorted soonest-first;
        // groupBy preserves that encounter order within each group.
        val live = Countdown.upcoming(departures, now)
        return live.groupBy { RowKey(it.lineId, directionKeyOf(it)) }
            .map { (key, group) ->
                val soonest = group.first()
                DepartureRow(
                    stopId = stopId,
                    stopName = stopName,
                    lineId = key.lineId,
                    lineName = soonest.lineName,
                    direction = soonest.direction,
                    directionKey = key.directionKey,
                    destination = soonest.destination,
                    mode = soonest.mode,
                    upcoming = group,
                    fetchedAt = fetchedAt,
                    // Marks the row only when the line is actually disrupted — a
                    // good-service (or unlooked-up) line leaves it null, so a non-null
                    // status always means "flag this" (SPEC *Disruptions* / D3).
                    status = lineStatuses[key.lineId]?.takeIf(LineStatus::disrupted),
                )
            }
            .sortedWith(rowOrder)
    }

    /**
     * The flat departures list across several stops (SPEC D8): every stop's rows in
     * one soonest-first list, so the next thing to leave — whichever stop it is at —
     * is at the top. Each [StopArrivals] is grouped by [forStop], then the rows are
     * merged and re-sorted by the same [rowOrder]. Ordering is location-free (D1): the
     * list works with location denied. Starred rows are pinned to the top by the
     * caller in Phase 2, not here.
     */
    fun across(
        stops: List<StopArrivals>,
        now: Instant,
        lineStatuses: Map<String, LineStatus> = emptyMap(),
    ): List<DepartureRow> =
        stops.flatMap { stop ->
            val timed =
                forStop(stop.stopId, stop.stopName, stop.departures, now, lineStatuses, stop.fetchedAt)
            // A synthesized line-status row asserts "No departures", which is only true when
            // this stop's arrivals were actually fetched AND are still current: fetched (not
            // a disruption-only stop stamped `now` with no arrivals, nor one carried from a
            // prior) and not stale (a delayed line's predictions may have merely expired, not
            // stopped). Otherwise it's suppressed and the stop falls to the screen's stale
            // empty-state prompt (SPEC principle 1). Timed rows (withheld once stale) and a
            // fresh stop-status closure still show.
            val status =
                if (stop.arrivalsFresh && !isStale(stop.fetchedAt, now)) {
                    statusRows(stop, timed, lineStatuses)
                } else {
                    emptyList()
                }
            stopStatusRow(stop) + timed + status
        }.sortedWith(rowOrder)

    /**
     * Collapse the "near me now" rows so a **(line, direction)** appears once — from the
     * **nearest** stop serving it — instead of once per adjacent stop it passes (SPEC
     * *Finding stops → Near me now*): a bus route stopping three times within the radius
     * should read as one service, not three. Both directions survive (they are distinct
     * `(lineId, directionKey)`), and nothing is dropped by a count cap — the result is
     * distance-shaped, so a mode with only one nearby stop (the lone Tube among many bus
     * stops) is never crowded out.
     *
     * Distance is the nearby flow's input, not a row's business — [across] stays
     * location-free (D1) — so it arrives as [stopDistanceMeters] (`stopId` → meters). A
     * stop missing from the map sorts last, and equal distances break by `stopId`, so the
     * kept stop never depends on input order. **Stop-level status rows pass through
     * untouched**: a closure is about one stop, not a line repeated across stops, so
     * collapsing two closed stops into one would drop a real warning. Line rows (timed and
     * line-status) are the ones deduped. Survivors are re-sorted by the same soonest-first
     * [rowOrder].
     *
     * The dedupe identity is **cross-stop**: line + TfL's `direction`, *not* the row's
     * [DepartureRow.directionKey], which for a timed row can be the stop-local platform
     * ([directionKeyOf]) — the same line and direction at adjacent stops would then get
     * different keys and both survive, defeating the collapse (Codex). A row whose
     * `direction` is blank has no cross-stop identity and stays stop-specific (see
     * [dedupeKeyOf]); a status row keeps its cross-stop-stable sentinel key. Every row of the
     * *nearest* stop for a key is kept — not just one — so a single stop with two platforms
     * of one service (distinct rows at that stop) keeps both, while a farther stop's
     * duplicate is dropped.
     */
    fun nearbyDeduped(
        rows: List<DepartureRow>,
        stopDistanceMeters: Map<String, Double>,
    ): List<DepartureRow> {
        fun distanceOf(stopId: String): Double = stopDistanceMeters[stopId] ?: Double.MAX_VALUE
        val (stopStatus, lineRows) = rows.partition { it.stopDisruption != null }
        // The nearest stop serving each cross-stop (line, direction) key.
        val nearestStopByKey = HashMap<RowKey, String>()
        for (row in lineRows) {
            val key = dedupeKeyOf(row)
            val incumbent = nearestStopByKey[key]
            if (incumbent == null || isCloserStop(row.stopId, incumbent, ::distanceOf)) {
                nearestStopByKey[key] = row.stopId
            }
        }
        // Keep every row from the nearest stop for its key, so two platforms of one service
        // at a single stop both survive; a farther stop's same-service row is dropped.
        val kept = lineRows.filter { nearestStopByKey[dedupeKeyOf(it)] == it.stopId }
        return (stopStatus + kept).sortedWith(rowOrder)
    }

    /**
     * Reorder [rows] so the user's **starred** services sit at the top — ranking only, not
     * membership (SPEC D8): a star pins its row above the unstarred ones, it does not add or
     * remove anything. Applied after [across]/[nearbyDeduped], so the rows are already in
     * soonest-first order and this only lifts the starred ones.
     *
     * **Warning rows stay on top of everything**, above even a starred service: a stop-closure
     * or a no-departure line-status row is a warning the user must see, and pinning a starred
     * service above it would push a closure down the list (SPEC principle 2 — never hide a
     * warning). So a starred *service that is itself currently a warning row* (a starred line
     * gone suspended, now a no-prediction status row) stays in the warning band too — its star
     * re-pins it the moment it has departures again. The sort is stable, so within each band the
     * soonest-first order carries through unchanged; an empty [starred] set returns [rows] as-is.
     */
    fun pinStarred(rows: List<DepartureRow>, starred: Set<StarredRow>): List<DepartureRow> {
        if (starred.isEmpty()) return rows
        return rows.sortedBy { row ->
            when {
                // Warnings (closures, no-prediction status) lead, whether starred or not.
                row.stopDisruption != null || row.upcoming.isEmpty() -> 0
                StarredRow.of(row) in starred -> 1
                else -> 2
            }
        }
    }

    /**
     * The cross-stop dedupe identity for [nearbyDeduped]: line + direction-of-travel.
     *
     * A timed row deduplicates across stops **only with a real cross-stop identity** — both a
     * `lineId` and TfL's own `direction`. When `direction` is blank there is no reliable
     * cross-stop discriminator: the platform is stop-local, and per `SPEC.md` a `destination`
     * cannot reconstruct a direction (opposite one-way stops can share a destination). A blank
     * `lineId` is the same problem for the line itself — TfL omits it on some predictions, and
     * `lineName` alone can name a different route — so merging on it would drop an unrelated
     * service at an adjacent stop. In either case the row stays **stop-specific** — keyed on its
     * own stop + stop-scoped [directionKey] — and is never merged across stops. That over-shows
     * a same-direction
     * service at adjacent stops in the direction-less case, which is the safe trade
     * (SPEC principle 1) against ever collapsing two opposite directions into one row and
     * mislabeling a countdown (maintainer, 2026-09-19). Earlier revisions also keyed on
     * `destination` when `direction` was blank; that produced a run of edge-case collisions
     * (blank direction + shared destination at opposite stops), so the whole `destination`
     * fallback is dropped here rather than special-cased further.
     *
     * A status row (no countdown) keeps its sentinel [DepartureRow.directionKey], which is
     * cross-stop stable and keeps it from colliding with a timed row of the same line.
     */
    private fun dedupeKeyOf(row: DepartureRow): RowKey {
        if (row.upcoming.isEmpty()) return RowKey(row.lineId, row.directionKey)
        // Dedupe across stops only with a real cross-stop identity — both a line and TfL's
        // direction. A blank `lineId` (TfL omits it on some predictions, and `lineName` alone
        // can name a different route) or a blank `direction` has no cross-stop discriminator, so
        // the row stays stop-specific — keyed on its own stop — and is never merged across stops,
        // rather than colliding with an unrelated route (blank line) or the opposite direction
        // (blank direction) at an adjacent stop and silently dropping the farther one (Codex).
        if (row.lineId.isBlank() || row.direction.isBlank()) {
            return RowKey(row.lineId, "\u0000${row.stopId}:${row.directionKey}")
        }
        return RowKey(row.lineId, row.direction)
    }

    /** True when [stopId] is nearer than [incumbent]; equal distances break by stopId. */
    private fun isCloserStop(
        stopId: String,
        incumbent: String,
        distanceOf: (String) -> Double,
    ): Boolean {
        val here = distanceOf(stopId)
        val there = distanceOf(incumbent)
        return here < there || (here == there && stopId < incumbent)
    }

    /**
     * A stop-level status row for a stop with its own disruption(s) — a closure or moved
     * stop — so it isn't shown as if its departures were catchable (SPEC *Disruptions*).
     * One row per stop, its descriptions joined; the stop's timed rows are kept (marked,
     * not suppressed), since TfL's closure data is coarse and often absent, so hiding
     * departments on it would risk dropping valid ones. Empty when the stop is clear.
     */
    private fun stopStatusRow(stop: StopArrivals): List<DepartureRow> {
        if (stop.disruptions.isEmpty()) return emptyList()
        return listOf(
            DepartureRow(
                stopId = stop.stopId,
                stopName = stop.stopName,
                lineId = "",
                lineName = "",
                direction = "",
                directionKey = STOP_STATUS_DIRECTION_KEY,
                destination = "",
                mode = "",
                upcoming = emptyList(),
                fetchedAt = stop.fetchedAt,
                status = null,
                stopDisruption = stop.disruptions.joinToString(" · ") { it.description },
            ),
        )
    }

    /**
     * Status rows for a stop's disrupted lines that have **no prediction rows** — a
     * suspended line often returns zero arrivals, so without this it would vanish from
     * the list rather than surface as suspended (SPEC *Departures*, the quietly-wrong
     * failure the model exists to avoid). Only the stop's declared [StopArrivals.lines]
     * can name such a line, since the predictions don't. A line that *does* have
     * prediction rows is already marked on them (its [DepartureRow.status]) and gets no
     * separate status row; a good-service line gets none either.
     */
    private fun statusRows(
        stop: StopArrivals,
        timed: List<DepartureRow>,
        lineStatuses: Map<String, LineStatus>,
    ): List<DepartureRow> {
        val timedLineIds = timed.mapTo(mutableSetOf()) { it.lineId }
        return stop.lines
            .filter { it.id !in timedLineIds }
            .mapNotNull { line ->
                val status = lineStatuses[line.id]?.takeIf(LineStatus::disrupted)
                    ?: return@mapNotNull null
                DepartureRow(
                    stopId = stop.stopId,
                    stopName = stop.stopName,
                    lineId = line.id,
                    lineName = line.name,
                    direction = "",
                    directionKey = STATUS_DIRECTION_KEY,
                    destination = "",
                    mode = line.mode,
                    upcoming = emptyList(),
                    fetchedAt = stop.fetchedAt,
                    status = status,
                )
            }
    }

    /**
     * Rows ordered by **rank** first — stop-status rows (a whole stop disrupted), then
     * line-status rows (a line disrupted with no countdown), then timed rows — since a
     * disruption is the most important thing to see and has no departure time to sort by.
     * Within timed rows: soonest departure, ties broken by line, direction, then the
     * resolved direction key. A total, input-order-independent order shared by [forStop]
     * and [across] so a stop's rows sort the same alone or merged; stop is the final
     * tie-break so status rows for the same line/stop-status across stops stay stable.
     */
    private val rowOrder: Comparator<DepartureRow> =
        compareBy<DepartureRow> { rank(it) }
            .thenBy { it.upcoming.firstOrNull()?.expectedArrival ?: Instant.MIN }
            .thenBy { it.lineName }
            .thenBy { it.direction }
            .thenBy { it.directionKey }
            .thenBy { it.stopName }

    /** 0 = stop-status row, 1 = line-status row (no countdown), 2 = timed row. */
    private fun rank(row: DepartureRow): Int = when {
        row.stopDisruption != null -> 0
        row.upcoming.isEmpty() -> 1
        else -> 2
    }

    /** Whether a stop fetched at [fetchedAt] is past the shared staleness bound at [now]. */
    private fun isStale(fetchedAt: Instant, now: Instant): Boolean =
        Staleness.isStale(Duration.between(fetchedAt, now).toKotlinDuration())

    /**
     * The discriminator that keeps directions apart within a line at a stop. TfL's
     * `direction` is the intended key, but it omits it on some services; when it is
     * blank, falling back to `direction` alone would merge opposite directions into
     * one row that then mislabels a countdown (SPEC principle 1 — never present a
     * departure trackmo can't stand behind). So a blank direction falls back to the
     * platform (which usually names the direction, e.g. "Northbound - Platform 1")
     * and then the destination, both unambiguous enough to keep the groups apart.
     * A present `direction` is used as-is, so two platforms of the same direction
     * still share one row.
     */
    private fun directionKeyOf(d: Departure): String =
        d.direction.ifBlank { d.platform?.takeIf(String::isNotBlank) ?: d.destination }

    private data class RowKey(val lineId: String, val directionKey: String)
}

/**
 * One watched stop's arrivals, as [DepartureRows.across] takes them: the stop's
 * identity ([stopId]/[stopName], the caller's context since the client fetches per
 * stop) paired with the raw [departures] TfL returned for it.
 *
 * [lines] is the stop's served lines, known independently of the predictions, so a
 * disrupted line with zero arrivals still surfaces as a status row (SPEC *Departures*).
 * Empty when the caller has no such mapping — then only prediction-derived rows are shown.
 * [disruptions] are the stop's own disruptions (a closure, a moved stop), surfaced as a
 * stop-level status row so a closed stop isn't shown as if its departures were catchable
 * (SPEC *Disruptions*). Empty when the stop is clear or wasn't checked.
 * [fetchedAt] is when *this stop's* [departures] were fetched — each stop carries its own
 * age, so a partial refresh keeps a failed stop's aged rows (at their older age) beside a
 * fresh stop's, and staleness is decided per stop rather than one screen-wide flag
 * (SPEC D4). [Snapshot.mergeStop] sets it when merging a refresh into the prior snapshot.
 * [arrivalsFresh] is whether these [departures] came from a **successful arrivals fetch in
 * this snapshot** (vs carried from a prior, or absent because the fetch failed). It gates
 * the "No departures" claim a synthesized status row makes: a disruption-only stop is
 * stamped [fetchedAt] = now yet has no fetched arrivals, so freshness alone can't stand in
 * for "we know there are no departures" — only a fetch can (SPEC principle 1).
 */
data class StopArrivals(
    val stopId: String,
    val stopName: String,
    val departures: List<Departure>,
    val fetchedAt: Instant,
    val lines: List<LineRef> = emptyList(),
    val disruptions: List<StopDisruption> = emptyList(),
    val arrivalsFresh: Boolean = true,
)
