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
)
