package app.stopcast.domain

import java.time.Duration
import java.time.Instant

/**
 * Remembers the last few nearby-stop lookups, so re-opening the app (or refreshing) close to where
 * a lookup was made reuses its stop list rather than asking TfL again — a whole request, and the
 * round trip every near-me load otherwise waits on before any departure is requested (SPEC
 * *Finding stops*). Stops barely move, so a list is reused for up to [maxAge] when the new fix is
 * within [reuseWithinMeters] of the lookup's position; the caller still ranks and selects from the
 * new fix, so distances and order are current. Only non-empty results are kept, for the last
 * [maxAreas] places (most recent first).
 *
 * Kept on the device through [store] so a reopen after the process was killed still benefits
 * (maintainer, 2026-09-23): the lookup positions and their stop lists live in app-private storage
 * the OS never backs up, never in a log or anything that leaves the device (SPEC *Privacy*,
 * `docs/PRIVACY.md`). [store] is read once, on first use, and written after each new lookup —
 * both blocking, so call [lookup] and [put] off the main thread (the nearby lookup runs on IO).
 */
class NearbyStopsCache(
    private val store: NearbyStopsStore = NearbyStopsStore.NONE,
    private val clock: () -> Instant = Instant::now,
    private val reuseWithinMeters: Double = REUSE_WITHIN_METERS,
    private val maxAge: Duration = MAX_AGE,
    private val maxAreas: Int = MAX_AREAS,
) {
    data class Entry(
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Int,
        val stopTypes: List<String>,
        val at: Instant,
        val stops: List<StopLocation>,
    )

    private val entries = ArrayDeque<Entry>()
    private var loaded = false

    private fun loadOnce() {
        if (loaded) return
        loaded = true
        entries.addAll(store.load().take(maxAreas))
    }

    /** A recent lookup's stops for a query at ([latitude], [longitude]), or null to ask TfL. */
    @Synchronized
    fun lookup(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>): List<StopLocation>? {
        loadOnce()
        val now = clock()
        // An expired entry leaves the stored file too, not just memory, so a position older than a
        // day isn't kept on the device (docs/PRIVACY.md).
        if (entries.removeAll { !fresh(it, now) }) store.save(entries.toList())
        return entries.firstOrNull { entry ->
            entry.radiusMeters == radiusMeters &&
                entry.stopTypes == stopTypes &&
                NearestStops.distanceMeters(latitude, longitude, entry.latitude, entry.longitude) <= reuseWithinMeters
        }?.stops
    }

    @Synchronized
    fun put(latitude: Double, longitude: Double, radiusMeters: Int, stopTypes: List<String>, stops: List<StopLocation>) {
        if (stops.isEmpty()) return
        loadOnce()
        // A new lookup supersedes any older one it would answer for.
        entries.removeAll { NearestStops.distanceMeters(latitude, longitude, it.latitude, it.longitude) <= reuseWithinMeters }
        entries.addFirst(Entry(latitude, longitude, radiusMeters, stopTypes, clock(), stops))
        while (entries.size > maxAreas) entries.removeLast()
        // Expired entries go with the write, so the stored file never holds more than it needs.
        val now = clock()
        entries.removeAll { !fresh(it, now) }
        store.save(entries.toList())
    }

    // A negative age (the clock moved back) is never fresh — ask again rather than trust it.
    private fun fresh(entry: Entry, now: Instant): Boolean {
        val age = Duration.between(entry.at, now)
        return !age.isNegative && age < maxAge
    }

    companion object {
        /**
         * How close a new fix must be to reuse a lookup: a short walk, well inside the 500 m eager
         * reach, so the eager stops are the same ones TfL would return. Only the outer ring's far edge
         * (~1.5 km out) can differ, and the next lookup past this distance refreshes it.
         */
        const val REUSE_WITHIN_METERS = 150.0

        /** Stops rarely move or change lines; a day keeps the list current enough. */
        val MAX_AGE: Duration = Duration.ofHours(24)

        /** Home, work, and a couple of others. */
        const val MAX_AREAS = 4
    }
}

/**
 * Where [NearbyStopsCache] keeps its entries between processes. Blocking; a store that fails to read
 * returns empty, and one that fails to write logs and carries on — the cache is an optimization.
 */
interface NearbyStopsStore {
    fun load(): List<NearbyStopsCache.Entry>
    fun save(entries: List<NearbyStopsCache.Entry>)

    companion object {
        /** Keeps nothing: the cache lives only in memory (tests, and an unwired build). */
        val NONE = object : NearbyStopsStore {
            override fun load() = emptyList<NearbyStopsCache.Entry>()
            override fun save(entries: List<NearbyStopsCache.Entry>) = Unit
        }
    }
}

/** A [StopFinder] that answers from [cache] when it can, and fills it from [delegate] when not. */
class CachingStopFinder(
    private val delegate: StopFinder,
    private val cache: NearbyStopsCache,
) : StopFinder {
    override suspend fun nearbyStops(
        latitude: Double,
        longitude: Double,
        radiusMeters: Int,
        stopTypes: List<String>,
    ): List<StopLocation> =
        cache.lookup(latitude, longitude, radiusMeters, stopTypes)
            ?: delegate.nearbyStops(latitude, longitude, radiusMeters, stopTypes)
                .also { cache.put(latitude, longitude, radiusMeters, stopTypes, it) }
}
