package app.trackmo.data

import app.trackmo.domain.StarredRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PersistedStarredRowsTest {
    private val sample = setOf(
        StarredRow("940GZZLUOXC", "victoria", "southbound"),
        StarredRow("490000123A", "24", "Pimlico"),
    )

    @Test
    fun `round trips through the persisted form unchanged`() {
        assertEquals(sample, sample.toPersisted().toDomain())
    }

    @Test
    fun `an empty set round trips`() {
        assertEquals(emptySet<StarredRow>(), emptySet<StarredRow>().toPersisted().toDomain())
    }

    @Test
    fun `an unknown format version is discarded rather than mis-read`() {
        val fromFuture = sample.toPersisted().copy(version = PersistedStarredRows.CURRENT_VERSION + 1)
        assertNull(fromFuture.toDomain())
    }
}
