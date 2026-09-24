package app.stopcast.domain

import kotlin.math.roundToInt

/**
 * Formats a great-circle distance (meters, the near-me ranking's own input) as the short
 * label shown after a stop's name in its group header — "120 m", "1.2 km". Pure and
 * JVM-testable, mirroring [Countdown]'s plain-string labels.
 *
 * Only the near-me list carries a distance; the watched list is location-free and shows no
 * label (D1). The value is rounded so the label never implies precision the fix doesn't
 * have — to the nearest 10 m below a kilometer, to the nearest 0.1 km above — and a fix
 * on top of the stop reads "10 m" rather than "0 m".
 */
object StopDistance {
    fun label(meters: Double): String {
        val m = meters.coerceAtLeast(0.0)
        // < 995 m rounds to at most "990 m"; 995 m and up round to "1.0 km" and beyond, so
        // the meters branch never produces the awkward "1000 m".
        if (m < 995) {
            val rounded = (m / 10).roundToInt() * 10
            return "${rounded.coerceAtLeast(10)} m"
        }
        val km = (m / 100).roundToInt() / 10.0
        return "$km km"
    }
}
