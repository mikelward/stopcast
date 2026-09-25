package app.stopcast.domain

import kotlin.math.roundToLong

/**
 * The one-line diagnostic for a location fix the app used: where it came from, how accurate the
 * provider says it is, and how old it is — never the coordinate (SPEC *Privacy*, `docs/PRIVACY.md`).
 *
 * It exists to explain a wrong nearby set (maintainer, 2026-09-23): underground the network provider
 * places the user by the station's Wi-Fi, often at a different station, and whether the lever is
 * accuracy (a confident but wrong fix), staleness (a last-known fix near the fallback limit), or the
 * provider can only be told apart from what the fix actually reported. A missing accuracy estimate is
 * printed as "unknown", never 0 m, which would read as a perfect fix.
 */
object FixDiagnostics {
    /** How the fix was chosen: a fresh request, the instant recent-cache path, or the fallback. */
    enum class Source(val label: String) {
        FRESH("fresh"),
        RECENT_CACHED("recent cached"),
        FALLBACK("last-known fallback"),
        /** The follow-up request to GPS/fused after a coarse fix was shown ([LocationProvider.precise]). */
        PRECISE("precise follow-up"),
        /** A remembered precise fix used in place of a coarse one that agreed with it ([PreciseFixMemory]). */
        REMEMBERED("remembered precise"),
    }

    fun describe(source: Source, provider: String, accuracyMeters: Float?, ageMillis: Long): String {
        val accuracy = accuracyMeters?.let { "accuracy ${it.roundToLong()} m" } ?: "accuracy unknown"
        val age = "${(ageMillis.coerceAtLeast(0) + 500) / 1000} s old"
        return "location fix: ${source.label} from $provider, $accuracy, $age"
    }
}
