package app.stopcast.domain

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

object WidgetJourneys {
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
