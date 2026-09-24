package app.stopcast.ui

import app.stopcast.domain.LineRef
import org.junit.Assert.assertEquals
import org.junit.Test

class JourneyOriginsTest {
    @Test
    fun `an origin shared by an older and a newer journey keeps the known interchange`() {
        val legacy = StopRef("910GEXAMPLE", "Example", lines = listOf(LineRef("thameslink", "Thameslink", "national-rail")))
        val current = legacy.copy(lines = listOf(LineRef("great-northern", "Great Northern", "national-rail")), hubId = "HUBEXA")
        val merged = mergeJourneyOrigins(listOf(legacy, current)).single()
        assertEquals("HUBEXA", merged.hubId)
        assertEquals(listOf("thameslink", "great-northern"), merged.lines.map { it.id })
    }
}
