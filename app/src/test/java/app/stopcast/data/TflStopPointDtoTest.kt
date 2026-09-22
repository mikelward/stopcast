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
    fun `the stop letter, compass bearing, and towards are captured for the per-pole bus header`() {
        // TfL prints a bus pole's letter (`stopLetter` "D", "Stop D"), its bearing (a CompassPoint in
        // `additionalProperties`), and where it heads (a `Towards`); all feed the per-pole bus
        // sub-header ("Stop D (towards Farringdon)", SPEC D8).
        val stop = TflStopPointDto(
            id = "490000129D",
            commonName = "King's Cross Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "D",
            additionalProperties = listOf(
                TflAdditionalPropertyDto(key = "Towards", value = "Farringdon Or Holborn Circus"),
                TflAdditionalPropertyDto(key = "CompassPoint", value = "E"),
            ),
        ).toStopLocationOrNull()
        assertEquals("D", stop?.stopLetter)
        assertEquals("E", stop?.bearing)
        assertEquals("Farringdon Or Holborn Circus", stop?.towards)
    }

    @Test
    fun `an arrow in stopLetter is treated as the compass, not a pole letter`() {
        // TfL is inconsistent: some poles put the compass in CompassPoint, others jam it into
        // stopLetter as an arrow ("->N") in place of a real letter. An arrow-in-stopLetter is not a
        // pole letter — it drops to the bearing, so the pole renders the one ASCII way ("Stop ->N",
        // the bearing path) rather than a raw letter beside a CompassPoint pole (SPEC D8).
        val stop = TflStopPointDto(
            id = "490000000N",
            commonName = "Example Road",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "->N",
            additionalProperties = listOf(TflAdditionalPropertyDto(key = "CompassPoint", value = "N")),
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("N", stop?.bearing)
    }

    @Test
    fun `an arrow-only stopLetter with no CompassPoint still yields the bearing`() {
        // The same arrow form, but TfL omitted CompassPoint — the compass is recovered from the
        // arrow's letters so the pole still renders its direction, not a bare name.
        val stop = TflStopPointDto(
            id = "490000000S",
            commonName = "Example Road",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("bus"),
            stopLetter = "->S",
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("S", stop?.bearing)
    }

    @Test
    fun `a stop with no letter or compass point carries neither`() {
        val stop = TflStopPointDto(
            id = "940GZZLUKSX",
            commonName = "King's Cross St. Pancras Underground Station",
            lat = 51.5,
            lon = -0.12,
            modes = listOf("tube"),
        ).toStopLocationOrNull()
        assertEquals("", stop?.stopLetter)
        assertEquals("", stop?.bearing)
        assertEquals("", stop?.towards)
    }

    @Test
    fun `a stop with no usable identity maps to null`() {
        assertNull(TflStopPointDto(id = "", naptanId = "", commonName = "Somewhere").toStopLocationOrNull())
    }
}
