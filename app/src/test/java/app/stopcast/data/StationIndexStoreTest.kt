package app.stopcast.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIndexStoreTest {
    @Test
    fun `parses stations, cleans their names and keeps the hub`() {
        val index = StationIndexStore.parse(
            """
            {"version":1,"stations":[
              {"id":"940GZZLUEXA","name":"Example Underground Station","modes":["tube"],"hub":"HUBEXA"},
              {"id":"HUBEXA","name":"Example","modes":["tube"]},
              {"id":"","name":"No Id"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("940GZZLUEXA", "HUBEXA"), index.stations.map { it.id })
        assertEquals("Example", index.stations.first().name)
        assertEquals("HUBEXA", index.stations.first().hubId)
    }

    @Test
    fun `a newer format is ignored whole`() {
        var warned = ""
        val index = StationIndexStore.parse("""{"version":2,"stations":[{"id":"X","name":"Y"}]}""") { warned = it }
        assertTrue(index.stations.isEmpty())
        assertTrue(warned.contains("version"))
    }
}
