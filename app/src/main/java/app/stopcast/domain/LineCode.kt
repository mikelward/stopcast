package app.stopcast.domain

/**
 * The short label shown for a service — a line's **first three letters**, uppercased
 * (Victoria → VIC, Bakerloo → BAK, Elizabeth line → ELI); the maintainer's "first three
 * letters" call, not a cleverer abbreviation, so the pill stays narrow and the row keeps
 * its width for the countdown.
 *
 * A route identified by a number or code keeps it verbatim: a bus route (`24`, `N73`) or a
 * river-bus route (`RB1`, `RB6`) carries a digit, so it is returned as-is rather than
 * collapsed to its letters — otherwise every `RBn` would read "RB" and be indistinguishable
 * at a shared pier. A name with no letters falls back to itself. `uppercase()` is the
 * no-arg, locale-invariant overload, so it's safe from the Turkish-ı trap.
 *
 * Pure domain logic (no Android, no Compose) so every surface — the in-app pill now, the
 * Glance widget later — derives a service's displayed identity one way.
 */
fun lineCode(lineName: String, mode: String): String {
    if (mode.equals("bus", ignoreCase = true) || lineName.any { it.isDigit() }) {
        return lineName.trim()
    }
    val letters = lineName.filter { it.isLetter() }
    return if (letters.isEmpty()) lineName.trim() else letters.take(3).uppercase()
}
