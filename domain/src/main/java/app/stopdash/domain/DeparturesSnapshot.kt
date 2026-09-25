package app.stopdash.domain

import java.time.Instant

/**
 * The last-good departures snapshot, persisted between sessions and read back both by the
 * app (as the stamped placeholder shown before the first refresh completes) and by the
 * lock-screen widget (which can't run the fetch itself). It is the honest last-good only:
 * the [stops], each carrying its own fetch age, and the freshest [fetchedAt] for the
 * whole-screen stamp.
 *
 * The transient refresh-cycle flags — partial refresh, refresh failure, line-status
 * disruption, "status unknown" — are **not** part of the persisted snapshot. They describe
 * the *current* refresh, not a durable fact, and a persisted line status or the aging of a
 * point-in-time closure would assert a disruption state we can no longer stand behind
 * (the same reason [Snapshot.mergeStop] never ages a disruption). On restore the stops are
 * shown at their real age — a stale stop's countdowns are withheld — and the immediate
 * refresh re-derives everything else. The restored snapshot is, in effect, the `prior` the
 * first refresh merges into, so a stop that then fails to refresh keeps its aged rows.
 */
data class DeparturesSnapshot(
    val stops: List<StopArrivals>,
    val fetchedAt: Instant,
    // The starred journeys the widget pins to its top (SPEC *Journeys*): each origin's departures
    // that call at the far end, as the app last worked them out from the route data it has.
    val journeys: List<WidgetJourney> = emptyList(),
    // Stops kept only as a journey's origin (not nearby): the widget shows just their journey
    // departures, never their other rows.
    val journeyOnlyStopIds: Set<String> = emptySet(),
    // Stops the widget should show that the last refresh asked for but couldn't get, with no
    // earlier arrivals to fall back on, so they're absent from [stops]. Without this an initial
    // refresh where one stop failed would look complete: every stop present is fresh.
    val missingStopIds: Set<String> = emptySet(),
)

/**
 * A starred journey as the widget shows it, which can't load route data itself: the departures at
 * [originId] that the app found to call at the far end, identified by their [calls]. [key] is the
 * journey's ([StarredJourney.key]), so the app can pick up what it last saved after a restart.
 */
data class WidgetJourney(
    val originId: String,
    val calls: Set<JourneyCall>,
    val key: String = "",
    // The stop the journey was shown from (its direction) when this was worked out, so a later
    // session showing it the other way round can tell the pin is for the old direction.
    val shownFrom: String = "",
)

/**
 * One journey card's latest check, as the app reports it for the widget: at [originId], the
 * departures [confirmed] to call at the far end, and — only when the check was complete — every
 * departure it [checked], so one the route data now rejects is dropped (SPEC principle 1).
 */
data class WidgetJourneyCheck(
    val key: String,
    val originId: String,
    val confirmed: Set<JourneyCall>,
    val checked: Set<JourneyCall> = emptySet(),
    val shownFrom: String = "",
)

/**
 * What the app's screen reports for the widget's journey pins: the starred journeys' [keys] (all of
 * them — a key missing is an unstar), each placed card's latest [checks], and the stop each journey
 * is shown [from] (its direction), so a flip drops the old direction's pin even before the new
 * direction's route can be checked.
 */
data class WidgetJourneysReport(
    val keys: Set<String>,
    val checks: List<WidgetJourneyCheck>,
    val from: Map<String, String> = emptyMap(),
    // For a journey whose neighboring poles are known: every boarding key it now has (its own and
    // one [WidgetJourneys.poleKey] per pole), so a pole's pin that no longer qualifies goes. A
    // journey absent here keeps its pole pins as they are (its poles are still being looked up).
    val boarding: Map<String, Set<String>> = emptyMap(),
)

object WidgetJourneys {
    /**
     * [stored] with its journey pins updated by [report] — the one place the pins change, applied
     * atomically to the stored snapshot, so every report builds on what is stored rather than on a
     * copy held elsewhere. A journey whose origin [stored] doesn't hold joins with the copy in
     * [origins] (as a journey-only stop); one with neither can't be shown, so isn't kept. A stored
     * origin takes the [origins] copy when that is newer; a journey-only stop no pin starts from
     * goes. Null when nothing is stored and there is nothing to pin.
     */
    /** The widget pin key for [journeyKey]'s departures from the neighboring pole [poleId]. */
    fun poleKey(journeyKey: String, poleId: String): String = "$journeyKey@$poleId"

    /** The journey a pin key belongs to: itself, or the journey of a [poleKey]. */
    fun baseKey(key: String): String = key.substringBefore('@')

    fun apply(
        stored: DeparturesSnapshot?,
        report: WidgetJourneysReport,
        origins: List<StopArrivals>,
    ): DeparturesSnapshot? {
        val prior = stored?.journeys.orEmpty()
            .filter { it.key.isNotEmpty() }
            // Pinned for the other direction than the one now shown: not this journey's pin now.
            .filterNot { j -> report.from[baseKey(j.key)]?.let { j.shownFrom.isNotEmpty() && it != j.shownFrom } == true }
            // A neighboring pole's pin its journey no longer boards from.
            .filterNot { j -> report.boarding[baseKey(j.key)]?.let { j.key !in it } == true }
            .associateBy { it.key }
        // A journey's pole pins are starred while the journey is.
        val keys = (prior.keys + report.checks.map { it.key }).filterTo(HashSet()) { baseKey(it) in report.keys }
        val stops = stored?.stops.orEmpty()
        val storedIds = stops.mapTo(HashSet()) { it.stopId }
        val supplied = origins.associateBy { it.stopId }
        val journeys = merge(prior, keys, report.checks).values
            .filter { it.calls.isNotEmpty() && (it.originId in storedIds || it.originId in supplied) }
            .sortedBy { it.key }
        val pinnedOrigins = journeys.mapTo(HashSet()) { it.originId }
        val journeyOnly = stored?.journeyOnlyStopIds.orEmpty()
        val added = pinnedOrigins.filter { it !in storedIds }.sorted()
        val next = stops
            .filter { it.stopId !in journeyOnly || it.stopId in pinnedOrigins }
            .map { stop ->
                supplied[stop.stopId]?.takeIf { stop.stopId in pinnedOrigins && it.fetchedAt > stop.fetchedAt } ?: stop
            } + added.map { supplied.getValue(it) }
        if (stored == null && next.isEmpty()) return null
        val nextIds = next.mapTo(HashSet()) { it.stopId }
        return DeparturesSnapshot(
            stops = next,
            fetchedAt = next.maxOfOrNull { it.fetchedAt } ?: stored!!.fetchedAt,
            journeys = journeys,
            // An origin that was a missing nearby stop is nearby, recovered: not journey-only.
            journeyOnlyStopIds = journeyOnly.filterTo(HashSet()) { it in nextIds } +
                added.filterNot { it in stored?.missingStopIds.orEmpty() },
            missingStopIds = stored?.missingStopIds.orEmpty() - nextIds,
        )
    }

    /**
     * [memory] after the latest [checks], keeping only the starred [keys]. A journey keeps what it
     * last knew through a check that couldn't finish (a route loading or failed), so a reload
     * doesn't unpin it; a complete check replaces what it judged; a new origin (the journey
     * flipped) starts afresh.
     */
    fun merge(
        memory: Map<String, WidgetJourney>,
        keys: Set<String>,
        checks: List<WidgetJourneyCheck>,
    ): Map<String, WidgetJourney> {
        val next = memory.filterKeys { it in keys }.toMutableMap()
        for (check in checks) {
            if (check.key !in keys) continue
            val prior = next[check.key]?.takeIf { it.originId == check.originId }?.calls.orEmpty()
            next[check.key] = WidgetJourney(
                check.originId, (prior - check.checked) + check.confirmed, check.key, check.shownFrom,
            )
        }
        return next
    }
}

/** A departure's route identity at a journey origin: its line, destination and branch. */
data class JourneyCall(val lineId: String, val destination: String, val branch: String?) {
    companion object {
        fun of(departure: Departure) = JourneyCall(departure.lineId, departure.destination, departure.branch)
    }
}
