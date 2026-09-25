package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CollapsedPlacesTest {
    @Test
    fun `a farther station becomes a collapsed place with its lines named as TfL spells them`() {
        val farther = FartherStations.Farther(
            StationMatch("940GZZLUEXA", "Example", listOf("tube")),
            1_600.0,
            listOf(FartherStations.Line("tube", "hammersmith-city"), FartherStations.Line("national-rail", "c2c")),
        )
        val place = CollapsedPlaces.of(farther, mapOf("hammersmith-city" to "Hammersmith & City"))
        assertEquals("station:940GZZLUEXA", place.key)
        assertEquals("940GZZLUEXA", place.stationId)
        assertEquals("Example", place.name)
        assertEquals(1_600.0, place.meters, 0.0)
        assertEquals(
            // A line the list has no name for keeps its id.
            listOf(LineRef("hammersmith-city", "Hammersmith & City", "tube"), LineRef("c2c", "c2c", "national-rail")),
            place.lines,
        )
    }
}
