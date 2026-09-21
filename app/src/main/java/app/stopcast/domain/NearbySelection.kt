package app.stopcast.domain

/**
 * Chooses which nearby stops the "near me now" list shows (SPEC *Finding stops → Near me
 * now*). Pure math over coordinates the caller supplies — no Android, no I/O — so it stays
 * JVM-testable and off any render path.
 *
 * Stops group into **clusters** (a station's platforms, a bus junction's poles — keyed on
 * TfL's `stationNaptan`, SPEC D8). The nearest [CLUSTERS_PER_MODE] clusters of each mode are
 * **eager** (fetched and shown at once); the rest wait behind a per-mode "More" tap that
 * fetches them on demand ([selectClusters] returns the two tiers as [Result]). Per-mode
 * selection guarantees the nearest station of a sparse mode — a Tube up to the ~1 mile outer
 * radius — is always eager, without a separate reserve rule.
 *
 * The per-mode cap replaces an "all of the inner ring" set: a dense corner returns many bus
 * poles, and each pole is its own arrivals request (TfL doesn't aggregate a junction), so
 * expanding them all is both long to scan and many fetches. The cap plus the (deferred) "More"
 * affordance keeps the extra options reachable rather than silently hidden (SPEC principle 2)
 * and caps how many *clusters* are fetched — sharply fewer at a dense corner. It bounds the
 * cluster count, not the request count: one large junction cluster is still one arrivals
 * request per pole, so it isn't a hard fetch bound independent of density (a per-cluster fetch
 * budget is a `TODO.md` follow-up). TfL's `/StopPoint` geo query takes the 1609 m radius
 * (verified 2026-09-19: HTTP 200); it returns stop metadata only, and only the selected
 * clusters' stops are then fetched for arrivals.
 */
object NearbySelection {
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
     * arrivals request per lettered pole, so a low cap matters most for the densest mode. (The
     * cap is on cluster count, not request count — a large junction cluster is still many poles;
     * a hard per-cluster fetch budget is a `TODO.md` follow-up.)
     */
    const val CLUSTERS_PER_MODE = 2

    // Selection bucket for a cluster with no declared mode (a stop TfL listed no lines for), so
    // it isn't dropped from every mode's top-N and made to vanish. Internal to selection — never
    // a real mode, so it never surfaces as a "More" button.
    private const val NO_MODE = "\u0000none"

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
     * plus the (deferred) "More" affordance keeps the extra options reachable rather than
     * silently hidden (SPEC principle 2), and caps how many clusters are fetched (the count, not
     * the request total — a large junction cluster is still many poles).
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
        // nearest station of a sparse mode is never crowded out by a denser one. A cluster with
        // no mode buckets under [NO_MODE] so it's still selected rather than vanishing.
        fun modesOf(cluster: NearbyCluster): Set<String> = cluster.modes.ifEmpty { setOf(NO_MODE) }
        val eagerKeys = HashSet<String>()
        for (mode in clusters.flatMapTo(sortedSetOf()) { modesOf(it) }) {
            clusters.asSequence()
                .filter { mode in modesOf(it) }
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
