package app.stopdash.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ranks stops by great-circle distance from a point, for the in-app "near me now"
 * list (SPEC *Finding stops*, D1). Pure math on coordinates the caller supplies —
 * no Android `Location`, no I/O — so it is JVM-testable and never sits on a render
 * path. Location is used on demand only; it never reaches a background refresh (D1).
 */
object NearestStops {
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle (haversine) distance in meters between two coordinates. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * [stops] nearest ([latitude], [longitude]) first, at most [limit]. Ties break by
     * id so the order is stable across calls rather than depending on input order.
     */
    fun nearest(
        stops: List<StopLocation>,
        latitude: Double,
        longitude: Double,
        limit: Int = stops.size,
    ): List<StopLocation> =
        stops.sortedWith(
            compareBy(
                { distanceMeters(latitude, longitude, it.latitude, it.longitude) },
                { it.id },
            ),
        ).take(limit)
}
