package app.trackmo.domain

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

    /** The distinct transport modes a stop serves, from its lines (blank modes ignored). */
    private fun StopLocation.modes(): Set<String> =
        lines.mapNotNullTo(mutableSetOf()) { it.mode.takeIf(String::isNotBlank) }
}
