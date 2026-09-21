package app.stopcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The nearby-stop mapping's cluster-id rule (SPEC *Finding stops* / D8): a stop's cluster is TfL's
 * `stationNaptan` where it gives one, else the cleaned display name, so a station's poles group by
 * TfL's own cluster (reliable) but same-named poles with no assigned StopArea still merge by name.
 * Public infrastructure names only (SPEC *Privacy*).
 */
class TflStopPointDtoTest {

    @Test
    fun `clusterId is the stationNaptan when TfL gives one`() {
        val stop = TflStopPointDto(
            id = "490013767A",
            commonName = "Trafalgar Sq / Charing Cross Stn",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stationNaptan = "490G000804",
        ).toStopLocationOrNull()
        assertEquals("490G000804", stop?.clusterId)
    }

    @Test
    fun `clusterId falls back to the cleaned name when stationNaptan is blank`() {
        // Many bus poles carry no StopArea; same-named poles still merge by name so a junction
        // isn't split into duplicate headers.
        val stop = TflStopPointDto(
            id = "490000129D",
            commonName = "King's Cross Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stationNaptan = "",
        ).toStopLocationOrNull()
        // The fallback is the cleaned name (the same value as the display name), so two poles named
        // alike still merge; cleanStopName strips the " Station" suffix.
        assertEquals("King's Cross", stop?.clusterId)
        assertEquals(stop?.name, stop?.clusterId)
    }

    @Test
    fun `a stop with no usable identity maps to null`() {
        assertNull(TflStopPointDto(id = "", naptanId = "", commonName = "Somewhere").toStopLocationOrNull())
    }
}
