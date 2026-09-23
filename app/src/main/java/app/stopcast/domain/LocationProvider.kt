package app.stopcast.domain

/**
 * A point on Earth — the device's own position, used **only** for the on-demand
 * nearby-stops search and never on a background refresh (SPEC D1 / *Privacy*). A plain
 * data class of two doubles so the ranking math ([NearestStops]) and the resolver stay
 * pure and JVM-testable, with no Android `Location` in the domain.
 */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * A raw device fix plus the confidence signals a bug report and the debug log read — the coordinate
 * with its [accuracyMeters] and [provider], the point being that a *fresh* fix can still be
 * confidently wrong (a station's Wi-Fi mislocates the network provider to a different station), so
 * the honest diagnostic carries how the fix was obtained, not just where. [accuracyMeters] is `null`
 * when the platform reported no estimate (`Location.hasAccuracy()` false) — **unknown**, never `0f`,
 * which would read as perfectly precise. [provider] is the framework provider name
 * ("fused"/"gps"/"network"/"passive") or `null`. Coarse diagnostics only; the coordinate leaves the
 * device solely inside a consent-gated bug report (SPEC *Privacy*).
 */
data class FixSample(
    val coordinates: Coordinates,
    val accuracyMeters: Float?,
    val provider: String?,
)

/**
 * A resolved position plus how much to trust it. [isFallback] is true when the fresh-fix
 * attempt failed and a bounded last-known fix was used instead (the classic no-signal case, e.g.
 * the Underground): the coordinate is real but is the user's *previous* position, so a surface
 * that re-resolves the nearby set from it may show the wrong stops. A fresh fix — or the recent
 * cached one the fast path returns — has [isFallback] false. The caller decides what a fallback
 * means (SPEC *Finding stops*): don't jump the set to it on a re-locate, and label a set shown
 * from one as "your last-known area."
 *
 * [accuracyMeters] (null = unknown, see [FixSample]), [provider], and [ageMillis] (of the fix that
 * was actually selected — ~0 for a fresh fix, the cache age for the fast path or a fallback) are the
 * confidence signals threaded through for the consent-gated bug report and the coarse debug log, so
 * a "confidently wrong" fix is diagnosable and a future accuracy/age gate has the inputs it needs
 * (`TODO.md`). They default absent for callers and tests that don't supply them.
 */
data class LocationFix(
    val coordinates: Coordinates,
    val isFallback: Boolean,
    val accuracyMeters: Float? = null,
    val provider: String? = null,
    val ageMillis: Long? = null,
)

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
     * The device's current position (with its [LocationFix.isFallback] trust flag), or `null`
     * when none can be given.
     *
     * [forceFresh] governs the recent-cache fast path: by default a very recent cached fix is
     * returned at once (fast, and fine for a first open where the user just arrived). A
     * **re-locate on refresh** passes `true` — the user may have walked since the last fix, so
     * a cached one (even a recent one) would re-query TfL for the *previous* position and show
     * the old area's stops; forcing fresh requests a new fix and falls back to a cached one only
     * within a bounded age if the fresh request fails (that fallback is flagged [LocationFix.isFallback]).
     */
    suspend fun current(forceFresh: Boolean = false): LocationFix?
}
