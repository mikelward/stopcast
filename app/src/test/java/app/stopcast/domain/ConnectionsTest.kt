package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionsTest {
    @Test
    fun `the ridden line and buses are left out`() {
        val lines = listOf(
            LineRef("northern", "Northern", "tube"),
            LineRef("victoria", "Victoria", "tube"),
            LineRef("24", "24", "bus"),
        )
        assertEquals(listOf("victoria"), Connections.of(lines, "northern").map { it.id })
    }

    @Test
    fun `a mixed-mode interchange keeps only lines known by id`() {
        // Blank modes: an interchange lists buses, national rail and rail-type lines together.
        val lines = listOf(
            LineRef("lioness", "Lioness", ""),
            LineRef("elizabeth", "Elizabeth line", ""),
            LineRef("avanti-west-coast", "Avanti West Coast", ""),
            LineRef("n5", "N5", ""),
        )
        val connections = Connections.of(lines, "northern")
        assertEquals(listOf("lioness", "elizabeth"), connections.map { it.id })
        assertEquals(listOf("overground", "elizabeth-line"), connections.map { it.mode })
    }

    @Test
    fun `a single-mode rail station keeps its operators, once each`() {
        val lines = listOf(
            LineRef("southern", "Southern", "national-rail"),
            LineRef("southern", "Southern", ""),
        )
        assertEquals(listOf(LineRef("southern", "Southern", "national-rail")), Connections.of(lines, "thameslink"))
    }

    @Test
    fun `only rail-type modes count, so a pier's river buses and a coach stop's coaches don't`() {
        val lines = listOf(
            LineRef("rb1", "RB1", "river-bus"),
            LineRef("rb6", "RB6", "river-bus"),
            LineRef("a1", "A1", "coach"),
        )
        assertEquals(emptyList<LineRef>(), Connections.of(lines, "rb2"))
    }

    @Test
    fun `rail is the same allowlist, known by line id when TfL gave no mode`() {
        assertEquals(true, Connections.isRail("tube", "northern"))
        assertEquals(true, Connections.isRail("", "victoria"))
        assertEquals(false, Connections.isRail("bus", "24"))
        assertEquals(false, Connections.isRail("coach", "a1"))
        assertEquals(false, Connections.isRail("river-bus", "rb1"))
        assertEquals(false, Connections.isRail("", "unknown-line"))
    }

    @Test
    fun `a rail line's mode is known by id when TfL gives none`() {
        assertEquals("tube", Connections.knownMode("northern"))
        assertEquals(null, Connections.knownMode("24"))
    }
}
