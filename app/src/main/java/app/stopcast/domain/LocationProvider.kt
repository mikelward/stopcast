package app.stopcast.domain

/**
 * A point on Earth — the device's own position, used **only** for the on-demand
 * nearby-stops search and never on a background refresh (SPEC D1 / *Privacy*). A plain
 * data class of two doubles so the ranking math ([NearestStops]) and the resolver stay
 * pure and JVM-testable, with no Android `Location` in the domain.
 */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * Supplies the device's current position for the nearby-stops search. A domain seam so
 * the resolver is testable with a fake and never touches Android's `LocationManager`
 * directly (the Android implementation lives in `data`).
 *
 * [current] returns `null` when there is no position to give — location is switched off,
 * the permission isn't held, or no fix is available yet — which the caller renders as an
 * honest "couldn't get your location" state rather than guessing one (SPEC principle 2).
 * It suspends: a fix can take a moment, and it must never block a thread or the first
 * frame (SPEC jank-free UI). The permission itself is the caller's to request; a provider
 * asked without it simply returns `null`.
 */
interface LocationProvider {
    /**
     * The device's current position, or `null` when none can be given.
     *
     * [forceFresh] governs the recent-cache fast path: by default a very recent cached fix is
     * returned at once (fast, and fine for a first open where the user just arrived). A
     * **re-locate on refresh** passes `true` — the user may have walked since the last fix, so
     * a cached one (even a recent one) would re-query TfL for the *previous* position and show
     * the old area's stops; forcing fresh requests a new fix and falls back to a cached one only
     * within a bounded age if the fresh request fails.
     */
    suspend fun current(forceFresh: Boolean = false): Coordinates?
}
