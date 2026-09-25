package app.stopcast.domain

import kotlin.math.roundToInt

/** The units a distance label is written in, named by its short unit; each has a long one. */
enum class DistanceSystem {
    /** Meters, then kilometers ("120 m", "0.6 km"). */
    METERS,

    /** Yards, then miles ("130 yd", "0.4 mi") — the UK's road units. */
    YARDS,

    /** Feet, then miles ("390 ft", "0.4 mi") — the US's. */
    FEET,
}

/**
 * The user's distance-units choice (Settings → *Distance units*). [AUTOMATIC], the default,
 * follows the phone's locale; the others pin a system whatever the locale says.
 */
enum class DistanceUnits {
    AUTOMATIC,
    METERS,
    YARDS,
    FEET,
    ;

    /**
     * The system to draw in. [localeSystem] is what the phone's locale measures in, worked out by
     * the caller (the platform owns that lookup), so this stays pure and JVM-testable.
     */
    fun resolve(localeSystem: DistanceSystem): DistanceSystem = when (this) {
        AUTOMATIC -> localeSystem
        METERS -> DistanceSystem.METERS
        YARDS -> DistanceSystem.YARDS
        FEET -> DistanceSystem.FEET
    }

    companion object {
        /** A stored name read back; anything unrecognized (a newer build's value) is [AUTOMATIC]. */
        fun fromStored(name: String?): DistanceUnits = entries.firstOrNull { it.name == name } ?: AUTOMATIC
    }
}

/**
 * Formats a great-circle distance (meters, the near-me ranking's own input) as the short
 * label shown after a stop's name in its group header — "120 m", "0.6 km", "130 yd", "390 ft",
 * "0.4 mi". Pure and JVM-testable, mirroring [Countdown]'s plain-string labels; the system is
 * handed in, never read from a locale here.
 *
 * Only the near-me list carries a distance; the watched list is location-free and shows no
 * label (D1). Close up the label is in the short unit, rounded to the nearest 10 (a fix on top
 * of the stop reads "10 m", not "0 m"); farther out it is the long unit to one decimal. Meters
 * and yards switch at ~500 m ("0.5 km", "0.3 mi"); feet switch at a tenth of a mile, as US maps
 * do, so a four-digit foot count never appears. Rounding keeps the label from implying
 * precision the fix doesn't have.
 */
object StopDistance {
    private const val METERS_PER_YARD = 0.9144
    private const val METERS_PER_FOOT = 0.3048
    private const val METERS_PER_MILE = 1609.344

    fun label(meters: Double, system: DistanceSystem = DistanceSystem.METERS): String {
        val m = meters.coerceAtLeast(0.0)
        val (perShort, shortUnit, longFrom) = when (system) {
            // 495 m would round to "500 m"; from there the label is "0.5 km", so meters top out at 490.
            DistanceSystem.METERS -> Triple(1.0, "m", 495.0)
            // The same ~500 m switch: yards top out at "540 yd", then "0.3 mi".
            DistanceSystem.YARDS -> Triple(METERS_PER_YARD, "yd", 495.0)
            // 160 m is 525 ft, which would round to "530 ft" — past 0.1 mi, so it reads "0.1 mi".
            DistanceSystem.FEET -> Triple(METERS_PER_FOOT, "ft", 160.0)
        }
        if (m < longFrom) {
            val rounded = (m / perShort / 10).roundToInt() * 10
            return "${rounded.coerceAtLeast(10)} $shortUnit"
        }
        val (long, longUnit) = when (system) {
            DistanceSystem.METERS -> m / 1000 to "km"
            DistanceSystem.YARDS, DistanceSystem.FEET -> m / METERS_PER_MILE to "mi"
        }
        // Floored at 0.1 so the long-unit branch can never print "0.0".
        val tenths = (long * 10).roundToInt().coerceAtLeast(1) / 10.0
        return "$tenths $longUnit"
    }
}
