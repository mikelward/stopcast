package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StopMapTest {
    @Test
    fun `a stop opens as a labeled pin at its own coordinate`() {
        assertEquals(
            "geo:0,0?q=51.5,-0.12(Manor%20House)",
            StopMap.geoUri(51.5, -0.12, "Manor House"),
        )
    }

    @Test
    fun `punctuation in the name is encoded so it cannot end the query`() {
        assertEquals(
            "geo:0,0?q=51.5,-0.12(King%27s%20Cross%20%28D%29%20%26%20more)",
            StopMap.geoUri(51.5, -0.12, "King's Cross (D) & more"),
        )
    }

    @Test
    fun `a blank name drops the label rather than an empty one`() {
        assertEquals("geo:0,0?q=51.5,-0.12", StopMap.geoUri(51.5, -0.12, "  "))
    }
}
