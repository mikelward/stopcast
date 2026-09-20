package app.stopcast.domain

import java.time.Instant

/**
 * Rebuilds the widget's persisted snapshot by re-fetching arrivals for exactly the stops it is
 * already showing (SPEC D1: the widget is location-free at refresh, so a refresh needs no
 * location — just the stop set already in the snapshot). Pure over an injected [fetchArrivals]
 * so it is JVM-testable without Android or the network.
 *
 * A stop that fetches fresh gets its new arrivals stamped [now]; a stop whose fetch fails keeps
 * its aged last-good departures and timestamp but is marked `arrivalsFresh = false` — the same
 * invariant the app's own merge holds ([Snapshot] `across`), so the widget's per-row stale
 * withhold ages it honestly and the whole-widget stamp/warning can't read it as fresh (SPEC D4 /
 * principle 2) rather than blanking it. When **no** stop fetched fresh the whole cycle is a
 * no-op — this returns null and nothing is saved, leaving the last-good in place for the next
 * cycle. Disruptions/line-status are not refreshed here; carrying them onto the widget is its
 * own follow-up (the persisted snapshot deliberately holds only last-good arrivals + age).
 */
object WidgetRefresh {
    suspend fun refreshedArrivals(
        prior: DeparturesSnapshot,
        now: Instant,
        fetchArrivals: suspend (stopId: String) -> List<Departure>?,
    ): DeparturesSnapshot? {
        if (prior.stops.isEmpty()) return null
        var anyFresh = false
        val stops = prior.stops.map { stop ->
            when (val fetched = fetchArrivals(stop.stopId)) {
                // Keep the aged last-good, but mark it not-fresh so its stale withhold fires and
                // it can't render as fresh within the freshness window (Codex P1 on #56). Its own
                // fetchedAt is preserved and still drives age-based staleness.
                null -> stop.copy(arrivalsFresh = false)
                else -> {
                    anyFresh = true
                    stop.copy(departures = fetched, fetchedAt = now, arrivalsFresh = true)
                }
            }
        }
        if (!anyFresh) return null
        // The whole-screen stamp is the freshest stop's age (matches DeparturesSnapshot).
        return DeparturesSnapshot(stops = stops, fetchedAt = stops.maxOf { it.fetchedAt })
    }
}
