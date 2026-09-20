package app.stopcast.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Formats how long ago data was fetched, for the "updated N ago" stamp every
 * surface carries (SPEC *Freshness*). Pure and clock-free — the caller supplies
 * the age — so it is JVM-testable without Android.
 *
 * This is the freshness *stamp*, not the staleness *policy* (the single
 * threshold past which numbers are withheld); that policy lands with the
 * departures view in Phase 1.
 */
object RelativeTime {
    fun formatAge(age: Duration): String = when {
        age < 60.seconds -> "just now"
        age < 60.minutes -> "${age.inWholeMinutes} min ago"
        else -> "${age.inWholeHours} h ago"
    }
}
