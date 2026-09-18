package app.trackmo.domain

import java.time.Instant

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
        stops.flatMap { forStop(it.stopId, it.stopName, it.departures, now, lineStatuses) }
            .sortedWith(rowOrder)

    /**
     * Rows ordered by their soonest departure, ties broken by line, then direction,
     * then the resolved direction key — a total, input-order-independent order shared
     * by [forStop] and [across] so a stop's rows sort the same alone or merged.
     */
    private val rowOrder: Comparator<DepartureRow> =
        compareBy(
            { it.upcoming.first().expectedArrival },
            { it.lineName },
            { it.direction },
            { it.directionKey },
        )

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
 */
data class StopArrivals(
    val stopId: String,
    val stopName: String,
    val departures: List<Departure>,
)
