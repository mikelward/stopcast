package app.stopcast.domain

import java.util.Locale

/**
 * The user-facing label for a service's destination line (SPEC D8). Pure and
 * Android-free so the rule is JVM-testable apart from the screen.
 */
object DepartureLabels {
    /**
     * Hardcoded display renames for a terminus whose TfL name is longer than a rider needs.
     * Applied at label time only — grouping (SPEC D8) and topology resolution still key on the
     * raw terminus — so a rename never changes which trains share a row. Keyed on the terminus
     * as it reaches here, after [cleanStopName] has already dropped a " Station" suffix (so
     * "Battersea Power Station" arrives as "Battersea Power"). Maintainer-chosen (2026-09-22).
     */
    private val displayRenames = mapOf(
        "Battersea Power" to "Battersea",
    )

    /**
     * The destination line's label: TfL's resolved [destination] (`destinationName`, else
     * `towards`) when present; otherwise the [directionKey] as a **direction cue**. The
     * card no longer shows the direction in words, so without this a line with no
     * destination would repeat its own name and leave cards that TfL keeps distinct
     * indistinguishable (SPEC principle 1). The [directionKey] is exactly what the grouping
     * uses to keep those cards apart — the TfL `direction` word ("inbound"/"outbound"),
     * else the `platformName`, else the (blank) destination — so keying the cue off it
     * covers every case a card can be split on, not just the direction one. Null when even
     * the key is blank (nothing distinguishes the card), so the caller shows an explicit
     * "unknown destination" label. The line name is never returned here — the pill carries
     * it.
     */
    fun destinationLabel(destination: String, directionKey: String): String? {
        if (destination.isNotBlank()) return displayRenames[destination] ?: destination
        val cue = directionKey.trim()
        // Locale.ROOT: the direction word is English TfL data, and the default locale would
        // mangle the first letter on a Turkish device ("İnbound"). A platform cue
        // ("Platform 3") is unaffected by titlecasing its first letter.
        if (cue.isNotEmpty()) return cue.replaceFirstChar { it.titlecase(Locale.ROOT) }
        return null
    }
}
