package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class NearestStopsTest {
    // Synthetic coordinates only (SPEC *Privacy*): never a real position in a fixture.
    private fun stop(id: String, lat: Double, lon: Double) = StopLocation(id, "Stop $id", lat, lon)

    @Test
    fun `distance is the great-circle distance in meters`() {
        // One degree of longitude at the equator is ~111.2 km.
        val meters = NearestStops.distanceMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_195.0, meters, 100.0)
    }

    @Test
    fun `nearest orders by distance from the point`() {
        val near = stop("near", 0.0, 0.01)
        val mid = stop("mid", 0.0, 0.05)
        val far = stop("far", 0.0, 0.20)

        assertEquals(
            listOf(near, mid, far),
            NearestStops.nearest(listOf(far, near, mid), latitude = 0.0, longitude = 0.0),
        )
    }

    @Test
    fun `limit caps the result to the closest`() {
        val near = stop("near", 0.0, 0.01)
        val mid = stop("mid", 0.0, 0.05)
        val far = stop("far", 0.0, 0.20)

        assertEquals(
            listOf(near, mid),
            NearestStops.nearest(listOf(far, near, mid), latitude = 0.0, longitude = 0.0, limit = 2),
        )
    }

    @Test
    fun `equal distances break by id for a stable order`() {
        val b = stop("b", 0.0, 0.01)
        val a = stop("a", 0.0, 0.01)

        assertEquals(listOf(a, b), NearestStops.nearest(listOf(b, a), latitude = 0.0, longitude = 0.0))
    }
}
