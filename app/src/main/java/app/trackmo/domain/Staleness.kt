package app.trackmo.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * The single, shared "too old to trust" policy (SPEC D4). One threshold, applied
 * identically by every surface, past which countdowns are withheld and the surface
 * shows "tap to refresh" instead of numbers that are probably wrong. Exactly one
 * constant, pinned by test, so no two surfaces can disagree about when data has
 * gone stale.
 *
 * Clock-free like [RelativeTime]: the caller passes the age of the fetch (now minus
 * the snapshot's fetch time), so this is JVM-testable without Android.
 */
object Staleness {
    /**
     * Live predictions drift within about a minute, but withholding that aggressively
     * would leave every surface showing "tap to refresh" between routine refreshes,
     * with the client-side recompute (SPEC D4) already keeping each countdown honest in
     * between. This is the longer bound past which the underlying prediction *set* is
     * itself likely wrong — services since added or dropped, not merely each number a
     * little off — so the safe answer becomes "refresh" rather than a stale list.
     *
     * A tuned constant, not a spec value (SPEC *Freshness*); tunable on device, tracked
     * under TODO "Decisions needing review".
     */
    val THRESHOLD: Duration = 5.minutes

    /** True once a fetch this old should no longer have its countdowns shown. */
    fun isStale(age: Duration): Boolean = age >= THRESHOLD

    /**
     * Time left before a fetch this old crosses the staleness boundary — for scheduling a
     * one-shot render-only redraw that flips a *static* surface (the widget, whose host never
     * re-renders it on its own) to the stale treatment at the boundary. [Duration.ZERO] once
     * already at or past it: there is nothing left to flip, so the caller schedules nothing.
     * Clock-free like [isStale]; the caller passes the age.
     */
    fun remainingUntilStale(age: Duration): Duration = (THRESHOLD - age).coerceAtLeast(Duration.ZERO)
}
