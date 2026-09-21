package app.stopcast.domain

/**
 * Chooses which nearby stops the "near me now" list shows (SPEC *Finding stops → Near me
 * now*). Pure math over coordinates the caller supplies — no Android, no I/O — so it stays
 * JVM-testable and off any render path.
 *
 * The policy is **distance-shaped, not a fixed count** (maintainer, on-device 2026-09-19):
 *
 * 1. **All stops within the inner radius** (~0.2 mi) — every service you could walk to right
 *    now, nothing dropped to a count. If nothing falls inside it, the ring expands to the
 *    single nearest stop, so the list is never empty when stops exist.
 * 2. **Plus the nearest stop of each mode present within the outer radius** that the inner
 *    ring doesn't already cover. This is the crowd-out fix: London bus stops are far denser
 *    than Tube/rail stations, so a pure nearest-N set is all buses and the one Tube stop a
 *    little farther off never appears (the reported "only buses everywhere" bug). By
 *    reserving one stop per mode, a denser mode can't evict a sparser one.
 *
 * The outer radius is **~1 mile (1609 m)** so a mode's nearest stop up to a mile off — a Tube
 * station 0.8 mi away, say — is still represented. TfL's `/StopPoint` geo query accepts this
 * (verified: 1609 m returns HTTP 200; the endpoint takes at least 2000 m); the single query
 * returns stop metadata only, and only the selected handful are then fetched for arrivals.
 *
 * There is deliberately **no overall count cap** — a busy interchange returns more, but a
 * count cap would silently hide options (SPEC principle 2). Collapsing the *services* a
 * line repeats across adjacent stops is a separate concern ([DepartureRows] dedupe); this
 * only picks the stops to fetch. The cost is one arrivals fetch per selected stop, so the
 * inner radius is kept tight.
 */
object NearbySelection {
    /** Inner ring: ~0.2 mi. Every stop this close is shown. */
    const val INNER_RADIUS_METERS = 322

    /**
     * Outer ring: ~1 mile (1609 m). A mode with a stop this close is guaranteed a
     * representative — a Tube station up to a mile off still shows. TfL's `/StopPoint` geo
     * query accepts this radius (verified 2026-09-19: 1609 m → HTTP 200).
     */
    const val OUTER_RADIUS_METERS = 1609

    /**
     * How many clusters of each mode the near-me list fetches and expands at once (SPEC
     * *Finding stops → Near me now*). The rest of that mode's clusters wait behind a "More"
     * tap. Two keeps a busy corner scannable and the eager fetch small — a bus cluster is one
     * arrivals request per lettered pole, so a low cap matters most for the densest mode.
     */
    const val CLUSTERS_PER_MODE = 2

    fun select(
        stops: List<StopLocation>,
        latitude: Double,
        longitude: Double,
        innerRadiusMeters: Int = INNER_RADIUS_METERS,
        outerRadiusMeters: Int = OUTER_RADIUS_METERS,
    ): List<StopLocation> {
        if (stops.isEmpty()) return emptyList()
        val byDistance = stops
            .map { it to NearestStops.distanceMeters(latitude, longitude, it.latitude, it.longitude) }
            .sortedWith(compareBy({ it.second }, { it.first.id }))

        // Inner ring, or the single nearest stop when nothing is inside it (expand-if-empty).
        val inner = byDistance.filter { it.second <= innerRadiusMeters }
            .ifEmpty { listOf(byDistance.first()) }

        val selected = inner.toMutableList()
        val selectedIds = inner.mapTo(mutableSetOf()) { it.first.id }
        val coveredModes = inner.flatMapTo(mutableSetOf()) { it.first.modes() }

        // Per-mode coverage: nearest-first, add a stop that brings a mode not yet covered,
        // until the outer radius. Bounded by the number of modes, not the stop count.
        for ((stop, distance) in byDistance) {
            if (distance > outerRadiusMeters) break
            if (stop.id in selectedIds) continue
            if (stop.modes().none { it !in coveredModes }) continue
            selected += stop to distance
            selectedIds += stop.id
            coveredModes += stop.modes()
        }

        return selected.sortedWith(compareBy({ it.second }, { it.first.id })).map { it.first }
    }

    /**
     * A cluster of nearby stops that share a [StopLocation.clusterId] — a station's platforms or
     * a junction's poles — ranked by its nearest member's distance. The unit the near-me list
     * expands and fetches as one place (SPEC *Finding stops → Near me now*, D8).
     */
    data class NearbyCluster(
        // Grouping key: the shared clusterId, else a per-stop key so an unclustered stop stands
        // alone (mirrors StopGrouping's keyless fallback). Its identity within a [Result]'s tiers.
        val key: String,
        // The cluster's stops, nearest-first. A Tube station is one stop id (its platforms are
        // already aggregated behind it); a bus junction is one stop per lettered pole.
        val stops: List<StopLocation>,
        // The nearest member's distance from the fix — how the cluster ranks against others.
        val distanceMeters: Double,
    ) {
        /** The distinct modes served across this cluster's stops. */
        val modes: Set<String> = stops.flatMapTo(linkedSetOf()) { it.modes() }
    }

    /**
     * The two tiers of the near-me list (SPEC *Finding stops → Near me now*):
     * - [eager]: the nearest [CLUSTERS_PER_MODE] clusters of each mode, fetched and shown at
     *   once — so the nearest of a sparse mode (a lone Tube station out to the radius) is always
     *   in, without a separate reserve rule.
     * - [more]: the remaining clusters, globally distance-ordered, fetched only when the user
     *   taps "More" for a mode. The button pages this list filtered by that mode; keeping it a
     *   flat ordered list (not pre-split per mode) means a cluster serving two modes appears
     *   under each mode's "More".
     *
     * The per-mode cap replaces the old "show everything in the inner ring" set: a dense corner
     * returns many bus poles, and expanding them all is both long to scan and many arrivals
     * fetches (each bus pole is its own request — TfL doesn't aggregate a junction). The cap
     * plus the visible "More" affordance keeps the extra options reachable rather than silently
     * hidden (SPEC principle 2), and bounds the eager fetch count independent of area density.
     */
    data class Result(
        val eager: List<NearbyCluster>,
        val more: List<NearbyCluster>,
    )

    /**
     * Group the nearby [stops] into clusters and split them into the eager and "more" tiers
     * (see [Result]). Pure over the coordinates the caller supplies — no Android, no I/O — so it
     * stays JVM-testable and off any render path.
     */
    fun selectClusters(
        stops: List<StopLocation>,
        latitude: Double,
        longitude: Double,
        clustersPerMode: Int = CLUSTERS_PER_MODE,
        outerRadiusMeters: Int = OUTER_RADIUS_METERS,
    ): Result {
        val clusters = stops
            .mapNotNull { stop ->
                val meters = NearestStops.distanceMeters(latitude, longitude, stop.latitude, stop.longitude)
                if (meters <= outerRadiusMeters) stop to meters else null
            }
            // A blank clusterId groups the stop alone (a per-stop key), so two same-named stops
            // TfL never clustered don't merge — the same keyless fallback StopGrouping uses.
            .groupBy { (stop, _) -> stop.clusterId.ifBlank { "\u0000stop:${stop.id}" } }
            .map { (key, members) ->
                val sorted = members.sortedWith(compareBy({ it.second }, { it.first.id }))
                NearbyCluster(key = key, stops = sorted.map { it.first }, distanceMeters = sorted.first().second)
            }
            .sortedWith(compareBy({ it.distanceMeters }, { it.stops.first().id }))
        if (clusters.isEmpty()) return Result(emptyList(), emptyList())

        // Nearest [clustersPerMode] clusters of each mode are eager; their union is the eager
        // set. A cluster serving two modes is eager if it's in the top N of *either*, so the
        // nearest station of a sparse mode is never crowded out by a denser one.
        val eagerKeys = HashSet<String>()
        for (mode in clusters.flatMapTo(sortedSetOf()) { it.modes }) {
            clusters.asSequence()
                .filter { mode in it.modes }
                .take(clustersPerMode)
                .forEach { eagerKeys += it.key }
        }
        // Both tiers keep the global nearest-first order.
        return Result(
            eager = clusters.filter { it.key in eagerKeys },
            more = clusters.filterNot { it.key in eagerKeys },
        )
    }

    /** The distinct transport modes a stop serves, from its lines (blank modes ignored). */
    private fun StopLocation.modes(): Set<String> =
        lines.mapNotNullTo(mutableSetOf()) { it.mode.takeIf(String::isNotBlank) }
}
