package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FartherStationsTest {
    private val here = Coordinates(51.5, -0.12)

    // About 111 m per 0.001 degrees of latitude.
    private fun station(id: String, name: String, modes: List<String>, northMeters: Double, lines: List<String> = emptyList()) =
        IndexedStation(id, name, modes, latitude = 51.5 + northMeters / 111_195.0, longitude = -0.12, tubeLines = lines)

    private val near = station("940GNEAR", "Near", listOf("tube"), 800.0, listOf("northern"))
    private val lineB = station("940GLINEB", "Line B", listOf("tube"), 2_600.0, listOf("piccadilly"))
    private val lineC = station("940GLINEC", "Line C", listOf("tube"), 3_000.0, listOf("victoria"))
    private val lineD = station("940GLINED", "Line D", listOf("tube"), 3_500.0, listOf("central"))
    private val ground = station("910GGROUND", "Ground", listOf("overground"), 3_200.0)
    private val rail = station("910GRAIL", "Rail", listOf("national-rail"), 2_000.0)
    private val farRail = station("910GFARRAIL", "Far Rail", listOf("national-rail"), 2_500.0)
    private val beyond = station("910GBEYOND", "Beyond", listOf("elizabeth-line"), 6_000.0)
    private val all = listOf(near, lineB, lineC, lineD, ground, rail, farRail, beyond)

    @Test
    fun `names the nearest station of each unreached tube line and rail mode, nearest first`() {
        val picked = FartherStations.pick(all, here, reachedLines = setOf("northern"), reachedModes = setOf("tube", "bus"))
        // Two tube lines at most (the nearest two), one per other mode, nothing past 3 mi.
        assertEquals(listOf("910GRAIL", "940GLINEB", "940GLINEC", "910GGROUND"), picked.map { it.station.id })
        assertEquals("Rail", picked.first().station.name)
        assertTrue(picked.zipWithNext().all { (a, b) -> a.meters <= b.meters })
    }

    @Test
    fun `a reached line or mode gets no button, and neither does a hidden mode`() {
        val picked = FartherStations.pick(
            all,
            here,
            reachedLines = setOf("northern", "piccadilly"),
            reachedModes = setOf("tube", "national-rail"),
            hidden = setOf("overground"),
        )
        assertEquals(listOf("940GLINEC", "940GLINED"), picked.map { it.station.id })
        assertTrue(FartherStations.pick(all, here, emptySet(), emptySet(), hidden = setOf("tube", "national-rail", "overground")).isEmpty())
    }

    @Test
    fun `a station serving two unreached lines is one button, counted once toward the cap`() {
        val both = station("940GBOTH", "Both", listOf("tube"), 1_500.0, listOf("piccadilly", "victoria"))
        val picked = FartherStations.pick(listOf(near, both, lineC, lineD), here, setOf("northern"), setOf("tube"))
        assertEquals(listOf("940GBOTH", "940GLINED"), picked.map { it.station.id })
    }

    @Test
    fun `an unplaced station or an interchange is never offered`() {
        val unplaced = IndexedStation("910GNOWHERE", "Nowhere", listOf("national-rail"))
        val hub = station("HUBEXA", "Example", listOf("national-rail"), 100.0)
        assertTrue(FartherStations.pick(listOf(unplaced, hub), here, emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `an interchange's stations are one button, opening the interchange`() {
        val tube = station("940GZZLUEXA", "Example", listOf("tube"), 2_000.0, listOf("central")).copy(hubId = "HUBEXA")
        val dlr = station("940GZZDLEXA", "Example", listOf("dlr"), 2_050.0).copy(hubId = "HUBEXA")
        val rail = station("910GEXAMPLE", "Example Rail", listOf("national-rail"), 2_100.0).copy(hubId = "HUBEXA")
        val hub = IndexedStation("HUBEXA", "Example", listOf("dlr", "national-rail", "tube"))
        val picked = FartherStations.pick(listOf(tube, dlr, rail, hub), here, emptySet(), emptySet())
        assertEquals(listOf("HUBEXA"), picked.map { it.station.id })
        assertEquals("Example", picked.single().station.name)
    }
}
