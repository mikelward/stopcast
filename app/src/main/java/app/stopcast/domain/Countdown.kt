package app.stopcast.domain

import java.time.Duration
import java.time.Instant

/**
 * Turns [Departure]s into what a surface shows, applying SPEC D4's client-side
 * rules against a caller-supplied [now] (never a wall clock read here, so this
 * stays pure and JVM-testable):
 *
 * - **Recompute from now**, not the fetch-time countdown, so the number stays
 *   honest as the clock advances between fetches.
 * - **Drop a departed service** rather than holding it at "Due" or showing
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
     * The minutes label: "Due" inside the last minute, "N min" otherwise. Assumes
     * [departure] has not yet gone — call [upcoming] first, which drops departed
     * services — but is defensive: a non-positive remaining still reads "Due"
     * rather than a negative number.
     */
    fun label(departure: Departure, now: Instant): String {
        val minutes = remaining(departure, now).toMinutes()
        return if (minutes < 1) "Due" else "$minutes min"
    }

    /**
     * Several [departures]' countdowns as one line — "Due · 3 · 6 min" — for a card that
     * merges a service's next few times instead of one row each. Each departure renders as
     * [label] would ("Due" or the bare minute count), joined by " · ", with the "min" unit
     * written once at the end so it reads as a list of minutes rather than repeating it.
     *
     * The caller passes an already-[upcoming] list (soonest-first, departed ones dropped),
     * **all to the same destination** — the screen groups by destination first so a
     * branching direction never merges a divergent train's time under the wrong headline
     * (SPEC D8). Empty in, empty out. The unit is omitted when every entry is "Due" (all
     * imminent), since there is no number for it to qualify.
     */
    fun mergedLabel(departures: List<Departure>, now: Instant): String {
        if (departures.isEmpty()) return ""
        val minutes = departures.map { remaining(it, now).toMinutes() }
        val parts = minutes.map { if (it < 1) "Due" else it.toString() }
        // upcoming() sorts soonest-first, so any "Due" precedes the numbers — the unit
        // belongs at the end, present whenever at least one entry is a minute count.
        val unit = if (minutes.any { it >= 1 }) " min" else ""
        return parts.joinToString(" · ") + unit
    }

    /** [departures] that have not yet gone, soonest-first (ties broken by line for stability). */
    fun upcoming(departures: List<Departure>, now: Instant): List<Departure> =
        departures.filterNot { hasDeparted(it, now) }
            .sortedWith(compareBy({ it.expectedArrival }, { it.lineName }))
}
