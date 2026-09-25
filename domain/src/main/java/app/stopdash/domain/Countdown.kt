package app.stopdash.domain

import java.time.Duration
import java.time.Instant

/**
 * Turns [Departure]s into what a surface shows, applying SPEC D4's client-side
 * rules against a caller-supplied [now] (never a wall clock read here, so this
 * stays pure and JVM-testable):
 *
 * - **Recompute from now**, not the fetch-time countdown, so the number stays
 *   honest as the clock advances between fetches.
 * - **Drop a departed service** rather than holding it at "0 min" or showing
 *   negative time — a countdown that reaches zero leaves the list.
 * - **Soonest-first**, so the next departure is at the top.
 */
object Countdown {
    /** Time until [departure], negative once it has gone. */
    fun remaining(departure: Departure, now: Instant): Duration =
        Duration.between(now, departure.expectedArrival)

    /** True once [departure]'s time has reached or passed [now]; such a service is dropped. */
    fun hasDeparted(departure: Departure, now: Instant): Boolean {
        val left = remaining(departure, now)
        return left.isZero || left.isNegative
    }

    /**
     * The minutes label: "0 min" inside the last minute, "N min" otherwise. Assumes
     * [departure] has not yet gone — call [upcoming] first, which drops departed
     * services — but is defensive: a non-positive remaining still reads "0 min"
     * rather than a negative number.
     */
    fun label(departure: Departure, now: Instant): String {
        val minutes = remaining(departure, now).toMinutes()
        return if (minutes < 1) "0 min" else "$minutes min"
    }

    /**
     * Several [departures]' countdowns as one line — "0 · 3 · 6 min" — for a card that
     * merges a service's next few times instead of one row each. Each departure renders as
     * [label] would ("0" or the bare minute count), joined by " · ", with the "min" unit
     * written once at the end so it reads as a list of minutes rather than repeating it.
     *
     * The caller passes an already-[upcoming] list (soonest-first, departed ones dropped),
     * **all to the same destination** — the screen groups by destination first so a
     * branching direction never merges a divergent train's time under the wrong headline
     * (SPEC D8). Empty in, empty out. Every entry is a number ("0" for an imminent train),
     * so the "min" unit is always written once at the end.
     */
    fun mergedLabel(departures: List<Departure>, now: Instant): String {
        if (departures.isEmpty()) return ""
        val minutes = departures.map { remaining(it, now).toMinutes() }
        val parts = minutes.map { if (it < 1) "0" else it.toString() }
        // Every entry is a number now, so the "min" unit always belongs at the end.
        return parts.joinToString(" · ") + " min"
    }

    /** [departures] that have not yet gone, soonest-first (ties broken by line for stability). */
    fun upcoming(departures: List<Departure>, now: Instant): List<Departure> =
        departures.filterNot { hasDeparted(it, now) }
            .sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))
}
