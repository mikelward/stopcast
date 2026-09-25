package app.stopcast.domain

/**
 * The last precise (GPS/fused) fix, remembered **in memory only** for [TTL_MILLIS], so a coarse
 * network fix that comes in while the rider hasn't moved doesn't replace it (SPEC *Finding stops*;
 * maintainer, 2026-09-25). A rider standing still indoors often gets a coarse fix ±400 m wide while
 * GPS is slow to answer; the precise fix from a few minutes ago is the better answer as long as it
 * sits inside the coarse fix's own accuracy circle — the coarse fix then agrees the rider could
 * still be there. Outside the circle, or past the TTL, the coarse fix stands.
 *
 * Never persisted and never logged as a coordinate (SPEC *Privacy*): it lives only as long as the
 * process, like the fix a shown list was resolved from. Times are from a monotonic clock
 * (elapsed realtime), so a wall-clock change can't stretch the TTL.
 */
class PreciseFixMemory {
    private data class Remembered(
        val coordinates: Coordinates,
        val atElapsedMillis: Long,
        val provider: String,
        val accuracyMeters: Float?,
    )

    // Guarded by `this`: a locate and a precise follow-up can remember at once, and newest-wins is a
    // read-then-write that must not interleave.
    private var last: Remembered? = null

    /**
     * A precise fix was just obtained from [provider], taken at [atElapsedMillis]. The newest one
     * taken is kept, with its provider and accuracy so a recall can be logged as the fix it is.
     */
    @Synchronized
    fun remember(
        coordinates: Coordinates,
        atElapsedMillis: Long,
        provider: String = "unknown",
        accuracyMeters: Float? = null,
    ) {
        val current = last
        if (current == null || atElapsedMillis >= current.atElapsedMillis) {
            last = Remembered(coordinates, atElapsedMillis, provider, accuracyMeters)
        }
    }

    /**
     * The remembered precise fix to use in place of [coarse], or `null` to use [coarse] itself: null
     * when nothing is remembered, when it is older than [TTL_MILLIS] at [nowElapsedMillis], when the
     * coarse fix reports no accuracy (so there is no circle to be inside), or when it lies outside
     * the coarse fix's [coarseAccuracyMeters] circle.
     */
    fun instead(coarse: Coordinates, coarseAccuracyMeters: Float?, nowElapsedMillis: Long): Recalled? {
        val remembered = synchronized(this) {
            expire(nowElapsedMillis)
            last
        } ?: return null
        val age = nowElapsedMillis - remembered.atElapsedMillis
        if (age < 0) return null
        val radius = coarseAccuracyMeters?.takeIf { it > 0f } ?: return null
        val apart = NearestStops.distanceMeters(
            coarse.latitude,
            coarse.longitude,
            remembered.coordinates.latitude,
            remembered.coordinates.longitude,
        )
        return if (apart <= radius) {
            Recalled(remembered.coordinates, age, remembered.provider, remembered.accuracyMeters)
        } else {
            null
        }
    }

    /**
     * Deletes the remembered fix once it is older than [TTL_MILLIS] at [nowElapsedMillis] — deleted,
     * not just ignored (SPEC *Privacy*). Called on every location the app takes, whether or not
     * that location could use it, so an expired position never outlives the next one.
     */
    @Synchronized
    fun expire(nowElapsedMillis: Long) {
        val current = last ?: return
        if (nowElapsedMillis - current.atElapsedMillis > TTL_MILLIS) last = null
    }

    /** A remembered precise fix used instead of a coarse one: how old it is, and where it came from. */
    data class Recalled(
        val coordinates: Coordinates,
        val ageMillis: Long,
        val provider: String,
        val accuracyMeters: Float?,
    )

    companion object {
        /** How long a precise fix is remembered. Reversible — one constant. */
        const val TTL_MILLIS = 10 * 60 * 1000L
    }
}
