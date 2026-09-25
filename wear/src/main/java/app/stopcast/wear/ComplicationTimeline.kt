package app.stopcast.wear

import app.stopcast.data.WatchEnvelope
import app.stopcast.data.toDomain
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.HiddenModes
import app.stopcast.domain.RouteTopology
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.lineCode
import java.time.Duration
import java.time.Instant
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** What a StopCast complication shows over one stretch of time. */
sealed interface ComplicationContent {
    /**
     * The row's next departure, at [at], to [destination], counted down by the system. [uncertain]
     * when its stop was carried forward after a failed refresh: the complication has no room for
     * the widget's separate note, so the mark sits on the time itself.
     */
    data class Departure(
        val code: String,
        val lineName: String,
        val destination: String,
        val at: Instant,
        val uncertain: Boolean,
    ) : ComplicationContent

    /**
     * The row has no departures left before its stop's staleness boundary. Fresh ([uncertain]
     * false) when a fetch established there's nothing more; else the widget's "may be out of date".
     */
    data class Empty(val code: String, val lineName: String, val uncertain: Boolean) : ComplicationContent

    /** Past its stop's staleness boundary: the line with no time, never an old countdown. */
    data class Stale(val code: String, val lineName: String) : ComplicationContent

    /** Nothing to show (never synced, no stops, no row at all): Wear's *no data* dash. */
    data object NoData : ComplicationContent
}

/** One entry of the complication's timeline, from [start] until [end] (open-ended when null). */
data class ComplicationEntry(val start: Instant, val end: Instant?, val content: ComplicationContent)

/**
 * The complication's timeline (dev-docs/wear-os.md *Staleness on the watch*): one entry per upcoming
 * departure of its row, each counted down by the system and replaced by the next when it leaves,
 * then the empty form if the row runs out before its stop's staleness boundary, then the stale form
 * from that boundary. Built from the envelope alone, so the watch face advances it with no polling
 * and no network. The row is the widget's top row, starred ones first (D8), as the tile orders them.
 */
object ComplicationTimeline {
    /**
     * The row a complication shows by default at [now]: the widget's first row as the tile orders
     * it (fresh before stale, starred first, hidden modes left out); else, when no row has any
     * departures, the first starred row the widget would still show: its stop was sent, the stop
     * still serves its line, and the line's mode isn't hidden (a star ranks, it doesn't add a
     * service). Null when there's none at all.
     */
    fun defaultRow(envelope: WatchEnvelope?, now: Instant): StarredRow? {
        envelope ?: return null
        val stops = envelope.stops.map { it.toDomain() }
        val starred = envelope.starred.map { it.toDomain() }
        val stale = stops.associate { it.stopId to isStale(it, now) }
        val ordered = DepartureRows.across(stops, now, splitPlatforms = false)
            .sortedBy { if (stale[it.stopId] == true) 1 else 0 }
        val shown = HiddenModes.rows(ordered, envelope.hiddenModes.toSet())
        DepartureRows.pinStarred(shown, starred.toSet()).firstOrNull()?.let { return StarredRow.of(it) }
        val hidden = envelope.hiddenModes.toSet()
        return starred.firstOrNull { star ->
            val stop = stops.firstOrNull { it.stopId == star.stopId } ?: return@firstOrNull false
            val mode = stop.departures.firstOrNull { it.lineId == star.lineId }?.mode
                ?: stop.lines.firstOrNull { it.id == star.lineId }?.mode
                ?: return@firstOrNull false
            !HiddenModes.isHidden(mode, hidden)
        }
    }

    /**
     * The timeline for [row] (the [defaultRow] when null, or when [row]'s stop isn't in the
     * envelope) from [now]. A single open-ended [ComplicationContent.NoData] entry when there's
     * nothing to show; otherwise always ending in the open-ended stale entry.
     */
    fun entries(
        envelope: WatchEnvelope?,
        now: Instant,
        row: StarredRow? = null,
        topology: RouteTopology = RouteTopology.EMPTY,
    ): List<ComplicationEntry> {
        val noData = listOf(ComplicationEntry(now, null, ComplicationContent.NoData))
        envelope ?: return noData
        val chosen = row?.takeIf { key -> envelope.stops.any { it.stopId == key.stopId } }
            ?: defaultRow(envelope, now)
            ?: return noData
        val stop = envelope.stops.first { it.stopId == chosen.stopId }.toDomain()
        val boundary = stop.fetchedAt.plus(Staleness.THRESHOLD.toJavaDuration())
        val current = DepartureRows.across(listOf(stop), now, splitPlatforms = false).firstOrNull { StarredRow.of(it) == chosen }
        val (lineName, mode) = current?.let { it.lineName to it.mode } ?: lineOf(stop, chosen)
        val code = lineCode(lineName, mode)
        val stale = ComplicationContent.Stale(code, lineName)
        if (now >= boundary) return listOf(ComplicationEntry(now, null, stale))

        val uncertain = !stop.arrivalsFresh
        val entries = mutableListOf<ComplicationEntry>()
        var start = now
        for (departure in upcoming(current, now, boundary)) {
            // Labeled as the tile labels a destination line (D8): the short terminus, "—" when TfL
            // gives none, and the via-branch when it's a choice ahead ("Morden/Bank"), the cue a
            // rider picks the train by.
            val label = DepartureLabels.destinationLabel(departure.destination, chosen.directionKey) ?: "—"
            val branch = topology.grouping(chosen.lineId, stop.stopId, departure.destination, departure.branch).label
            val destination = if (branch != null) "$label/$branch" else label
            val content = ComplicationContent.Departure(code, lineName, destination, departure.expectedArrival, uncertain)
            entries += ComplicationEntry(start, departure.expectedArrival, content)
            start = departure.expectedArrival
        }
        if (start < boundary) entries += ComplicationEntry(start, boundary, ComplicationContent.Empty(code, lineName, uncertain))
        entries += ComplicationEntry(boundary, null, stale)
        return entries
    }

    /**
     * [row]'s departures after [now] and before [boundary], soonest first. Uncapped: the staleness
     * window bounds them, and a cap would end a busy row early with a "None" it can't stand behind.
     */
    private fun upcoming(row: DepartureRow?, now: Instant, boundary: Instant) =
        row?.upcoming.orEmpty()
            .filter { it.expectedArrival > now && it.expectedArrival < boundary }
            .sortedBy { it.expectedArrival }
            .distinctBy { it.expectedArrival }

    /** The line's name and mode for a row with no departures now, from the stop's lines. */
    private fun lineOf(stop: StopArrivals, row: StarredRow): Pair<String, String> {
        stop.departures.firstOrNull { it.lineId == row.lineId }?.let { return it.lineName to it.mode }
        stop.lines.firstOrNull { it.id == row.lineId }?.let { return it.name to it.mode }
        return row.lineId to ""
    }

    private fun isStale(stop: StopArrivals, now: Instant): Boolean =
        Staleness.isStale(Duration.between(stop.fetchedAt, now).toKotlinDuration())
}
