package app.stopdash.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrowTextTest {
    @Test
    fun `the arrow and its spaces become one inline icon, text unchanged`() {
        val text = withArrowIcons("Victoria ➔ Warren Street")

        // The semantics text keeps the arrow, so the label still reads (and matches) as before.
        assertEquals("Victoria ➔ Warren Street", text.text)
        val icon = text.getStringAnnotations(0, text.length).single()
        assertEquals(" ➔ ", text.text.substring(icon.start, icon.end))
    }

    @Test
    fun `a label without an arrow has no icon`() {
        val text = withArrowIcons(" – Platform 2")

        assertEquals(" – Platform 2", text.text)
        assertEquals(0, text.getStringAnnotations(0, text.length).size)
    }
}
