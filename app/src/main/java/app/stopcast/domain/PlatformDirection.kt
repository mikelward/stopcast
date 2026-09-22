package app.stopcast.domain

/**
 * Resolves the **compass direction** a rail platform names, for the per-(place, direction) group
 * headers (SPEC D8: "King's Cross – Eastbound"). TfL prints a rail platform as
 * `"<Direction> - Platform N"` — "Northbound - Platform 1", "Eastbound - Platform 2" — and that
 * leading label is what a rider reads off the platform.
 *
 * The compass is the correct grouping key because TfL's own `inbound`/`outbound` `direction` is
 * **inconsistent across lines at one platform**: at King's Cross the Circle runs "Eastbound" as
 * `inbound` while the Hammersmith & City runs the same Eastbound platform as `outbound`, and some
 * predictions carry no `direction` at all — so keying on inbound/outbound would split one
 * platform's trains into two headers (confirmed against live TfL data, 2026-09-22). The compass
 * is also correctly coarser than the platform number — Eastbound spans two platforms at King's
 * Cross — so it groups a direction as one block rather than one per platform.
 *
 * Returns the direction label (`Northbound`, `Eastbound`, `Inner Rail`, …), or **null** when the
 * platform names no direction: a bare "Platform 4", an empty platform, or a **bus** pole's
 * stop-local `platform` ("Stop A", "Stop A / 1"). Only a recognized rail-direction form is
 * accepted — a **compass** bearing (a compass point + "bound", never TfL's `Inbound`/`Outbound`
 * jargon) or a sub-surface loop label ("Inner Rail" / "Outer Rail") — so neither a bus value nor
 * the inbound/outbound words are ever mistaken for a direction (which for a bus value would
 * mislabel a header "Stop A – Stop A" and split one bus cluster's poles into separate groups —
 * Codex P2, PR #109). A null degrades the row to the bare per-place header, so this ships
 * rail-first without regressing buses; the bus bearing (`->N`, in stop metadata) is a follow-up.
 */
object PlatformDirection {
    // The compass prefixes a genuine rail bearing takes before "bound" — the four **cardinals**
    // TfL uses on rail platforms. Deliberately EXCLUDES `in`/`out` (TfL's "Inbound"/"Outbound" also
    // end in "bound" but are the jargon SPEC rejects for the header, not a compass), AND the
    // intercardinals (`northeast`…): those don't appear on TfL rail platforms and, sharing a prefix
    // with a cardinal ("North…"), would clip to an ambiguous "– NORTH…" beside "Northbound" at a
    // large font scale. The four cardinals differ in their first letter, so they stay distinct even
    // when the header clips (Codex P2, PR #109).
    private val COMPASS_PREFIXES = setOf("north", "south", "east", "west")
    // Recognized non-compass rail platform direction labels (the Circle/District sub-surface loop),
    // keyed by their lowercase form and mapped to the one canonical spelling to return.
    private val LOOP_LABELS = mapOf("inner rail" to "Inner Rail", "outer rail" to "Outer Rail")

    /** The direction label in [platform] ("Eastbound - Platform 2" → "Eastbound"), else null. */
    fun of(platform: String?): String? {
        // The label is everything before the " - Platform N" suffix (or the whole string when TfL
        // gives no platform number, e.g. a bare "Northbound").
        val label = platform?.substringBefore(" - ")?.trim().orEmpty()
        if (label.isEmpty()) return null
        // Accept only a genuine rail direction: a **compass** bearing (a compass prefix + "bound")
        // or a loop label. Anything else — a bare "Platform 4", a bus stop letter ("Stop A"), or
        // TfL's `Inbound`/`Outbound` jargon (which SPEC rejects for the header) — names no
        // direction. Return a **canonical casing** so the same compass in different casings TfL
        // might supply ("NORTHBOUND" / "Northbound") yields one grouping key and one direction
        // block, not two identical-looking sections — the UI uppercases for display, but the group
        // key uses this value (Codex P2, PR #109). `lowercase()`/`uppercaseChar()` are
        // locale-invariant.
        val normalized = label.lowercase()
        val compassPrefix = normalized.removeSuffix("bound")
        if (compassPrefix != normalized && compassPrefix in COMPASS_PREFIXES) {
            return normalized.replaceFirstChar(Char::uppercaseChar)
        }
        return LOOP_LABELS[normalized]
    }
}
