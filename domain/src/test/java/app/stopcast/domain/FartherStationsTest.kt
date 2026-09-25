package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FartherStationsTest {
    private val here = Coordinates(51.5, -0.12)

    // About 111 m per 0.001 degrees of latitude.
    private fun station(id: String, name: String, northMeters: Double, lines: Map<String, List<String>>) =
        IndexedStation(id, name, lines.keys.toList(), latitude = 51.5 + northMeters / 111_195.0, longitude = -0.12, lines = lines)

    private fun tube(vararg ids: String) = mapOf("tube" to ids.toList())
    private fun reached(vararg lines: Pair<String, String>) = lines.mapTo(HashSet()) { FartherStations.Line(it.first, it.second) }

    private val near = station("940GNEAR", "Near", 800.0, tube("northern"))
    private val lineB = station("940GLINEB", "Line B", 2_600.0, tube("piccadilly"))
    private val lineC = station("940GLINEC", "Line C", 3_000.0, tube("victoria"))
    private val lineD = station("940GLINED", "Line D", 3_500.0, tube("central"))
    private val ground = station("910GGROUND", "Ground", 3_200.0, mapOf("overground" to listOf("suffragette")))
    private val rail = station("910GRAIL", "Rail", 2_000.0, mapOf("national-rail" to listOf("great-northern")))
    private val sameService = station("910GSAME", "Same Service", 2_200.0, mapOf("national-rail" to listOf("great-northern")))
    private val beyond = station("910GBEYOND", "Beyond", 6_000.0, mapOf("elizabeth-line" to listOf("elizabeth")))
    private val all = listOf(near, lineB, lineC, lineD, ground, rail, sameService, beyond)

    @Test
    fun `names the nearest station of each unreached line, nearest first`() {
        val picked = FartherStations.pick(all, here, reached = reached("tube" to "northern"))
        // Two tube lines at most (the nearest two), one button per service, nothing past 3 mi.
        assertEquals(listOf("910GRAIL", "940GLINEB", "940GLINEC", "910GGROUND"), picked.map { it.station.id })
        assertTrue(picked.zipWithNext().all { (a, b) -> a.meters <= b.meters })
    }

    @Test
    fun `each National Rail service counts as its own line`() {
        val thameslink = station("910GTHAMES", "Thameslink Stop", 2_800.0, mapOf("national-rail" to listOf("thameslink")))
        val picked = FartherStations.pick(listOf(rail, sameService, thameslink), here, reached = emptySet())
        assertEquals(listOf("910GRAIL", "910GTHAMES"), picked.map { it.station.id })
    }

    @Test
    fun `at most five buttons in all`() {
        val many = (1..8).map { station("910GR$it", "Rail $it", 1_000.0 + it * 100, mapOf("national-rail" to listOf("service-$it"))) }
        assertEquals(FartherStations.MAX_BUTTONS, FartherStations.pick(many, here, emptySet()).size)
    }

    @Test
    fun `a reached line gets no button, and neither does a hidden mode`() {
        val picked = FartherStations.pick(
            all,
            here,
            reached = reached("tube" to "northern", "tube" to "piccadilly", "national-rail" to "great-northern"),
            hidden = setOf("overground"),
        )
        assertEquals(listOf("940GLINEC", "940GLINED"), picked.map { it.station.id })
        assertTrue(FartherStations.pick(all, here, emptySet(), hidden = setOf("tube", "national-rail", "overground")).isEmpty())
    }

    @Test
    fun `a station serving two unreached lines is one button, counted once toward the tube cap`() {
        val both = station("940GBOTH", "Both", 1_500.0, tube("piccadilly", "victoria"))
        val picked = FartherStations.pick(listOf(near, both, lineC, lineD), here, reached("tube" to "northern"))
        assertEquals(listOf("940GBOTH", "940GLINED"), picked.map { it.station.id })
    }

    @Test
    fun `an interchange's stations are one button, opening the interchange`() {
        val tubeStation = station("940GZZLUEXA", "Example", 2_000.0, tube("central")).copy(hubId = "HUBEXA")
        val dlr = station("940GZZDLEXA", "Example", 2_050.0, mapOf("dlr" to listOf("dlr"))).copy(hubId = "HUBEXA")
        val railStation = station("910GEXAMPLE", "Example Rail", 2_100.0, mapOf("national-rail" to listOf("c2c"))).copy(hubId = "HUBEXA")
        val hub = IndexedStation("HUBEXA", "Example", listOf("dlr", "national-rail", "tube"))
        val picked = FartherStations.pick(listOf(tubeStation, dlr, railStation, hub), here, emptySet())
        assertEquals(listOf("HUBEXA"), picked.map { it.station.id })
    }

    @Test
    fun `an unplaced station is never offered, nor a bus line`() {
        val unplaced = IndexedStation("910GNOWHERE", "Nowhere", listOf("national-rail"), lines = mapOf("national-rail" to listOf("x")))
        val bus = station("490GBUS", "Bus", 100.0, mapOf("bus" to listOf("1")))
        assertTrue(FartherStations.pick(listOf(unplaced, bus), here, emptySet()).isEmpty())
    }
}
