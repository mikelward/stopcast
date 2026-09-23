package app.stopcast.domain

import java.time.Instant
import java.util.Locale

/**
 * Builds the **text** of a user-shareable bug report — the app's own section, which the shared
 * `mikelward/androidlog` `DebugReport` then wraps (it appends any persisted earlier runs and
 * handles the clipboard + share sheet). Pure and JVM-testable: it takes plain values and returns
 * a string, so its exact wording is pinned by a unit test rather than an emulator.
 *
 * **This report deliberately crosses the on-device-log privacy line — under consent.** Unlike
 * everything the app logs (coarse stop/line/status only, never a coordinate — `docs/PRIVACY.md`),
 * a report composed here carries the user's **exact location** and **how far they are from each
 * nearby stop**, because that is exactly the context a routing/location bug is diagnosed from. It
 * is never assembled or shared except behind the explicit consent screen that names the location
 * (SPEC *Privacy*, `TODO.md`), so it is honest rather than location-safe: it says plainly what it
 * shares instead of stripping the context that makes it useful.
 */
object BugReport {
    /** The build/device facts that head the report — none of it user data. */
    data class Header(
        val versionName: String,
        val versionCode: Long,
        val device: String,
        val androidRelease: String,
        val sdkInt: Int,
        val capturedAt: Instant,
    )

    /** One nearby stop and, when known, the distance from the fix to it. */
    data class StopLine(val name: String, val id: String, val meters: Double?)

    /**
     * Compose the report's app section from the current context: the build [header], the exact
     * [fix] the report is being filed from — its coordinate plus how it was obtained (provider,
     * accuracy, age, whether it was a last-known fallback), null when no fix is available — the
     * [stops] the user is watching with their distances, and this run's diagnostic [logLines] (the
     * shared `DebugLog` snapshot). The fix's confidence signals are what a "confidently wrong
     * location" report is diagnosed from (`TODO.md`). Deterministic and side-effect-free.
     */
    fun compose(
        header: Header,
        fix: LocationFix?,
        stops: List<StopLine>,
        logLines: List<String>,
    ): String = buildString {
        appendLine("StopDash bug report")
        appendLine("version: ${header.versionName} (${header.versionCode})")
        appendLine("device: ${header.device}, Android ${header.androidRelease} (API ${header.sdkInt})")
        appendLine("captured: ${header.capturedAt}")
        appendLine()

        // The exact coordinate — the point of this report, and why it is consent-gated. Fixed to
        // six decimals (about 0.1 m) in US format so a decimal-comma locale can't mangle it.
        // Labeled as the *last nearby lookup* fix, not the send-time position: the departures
        // screen can stay open while the user moves (auto-refresh doesn't re-locate), so the fix
        // may predate `captured` — the report says so rather than misattributing it to now.
        if (fix == null) {
            appendLine("location: unavailable")
        } else {
            appendLine("location (last nearby lookup): ${format(fix.coordinates.latitude)}, ${format(fix.coordinates.longitude)}")
            // How the fix was obtained — the "confidently wrong" diagnostic: a coarse network fix
            // underground, an unknown accuracy, a stale fallback. Accuracy is null when the platform
            // gave no estimate (shown "unknown", never a spurious 0), age is of the fix that was
            // used (~0 for a fresh one), and the fallback flag marks a last-known fix stood in.
            appendLine("  fix: ${fixConfidence(fix)}")
        }

        appendLine("nearby stops (${stops.size}):")
        if (stops.isEmpty()) {
            appendLine("  none")
        } else {
            for (stop in stops) {
                appendLine("  • ${stop.name} (${stop.id}) — ${distance(stop.meters)}")
            }
        }
        appendLine()

        appendLine("--- log (this run) ---")
        if (logLines.isEmpty()) {
            append("(no diagnostics recorded this run)")
        } else {
            append(logLines.joinToString("\n"))
        }
    }

    // The human label ("120 m") plus the raw metres to one decimal, so the report keeps the
    // diagnostic precision the UI rounds away. "distance unknown" rather than a fabricated 0.
    private fun distance(meters: Double?): String =
        if (meters == null) "distance unknown" else "${StopDistance.label(meters)} (${format1(meters)} m)"

    // "provider network, accuracy ±42 m, age 12 s, last-known fallback" — coarse diagnostics that
    // say how the fix was obtained. Accuracy/provider read "unknown" rather than a fabricated value
    // when absent (SPEC principle 1), the fallback clause appears only for a fallback fix.
    private fun fixConfidence(fix: LocationFix): String = buildString {
        append("provider ${fix.provider ?: "unknown"}")
        append(", accuracy ${fix.accuracyMeters?.let { "±${format1(it.toDouble())} m" } ?: "unknown"}")
        append(", age ${fix.ageMillis?.let { "${it / 1000} s" } ?: "unknown"}")
        if (fix.isFallback) append(", last-known fallback")
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun format1(value: Double): String = String.format(Locale.US, "%.1f", value)
}
