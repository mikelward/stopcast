package app.stopcast.domain

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DepartureLabelsTest {
    @Test
    fun `uses the destination when TfL gives one`() {
        assertEquals("Brixton", DepartureLabels.destinationLabel("Brixton", "southbound"))
    }

    @Test
    fun `falls back to the titlecased direction key when the destination is blank`() {
        // The directionKey is the direction word when TfL gives one...
        assertEquals("Inbound", DepartureLabels.destinationLabel("", "inbound"))
        assertEquals("Outbound", DepartureLabels.destinationLabel("  ", "outbound"))
        // ...or the platform when direction is also blank (grouping keys on it, so cards
        // that TfL keeps distinct by platform stay distinguishable).
        assertEquals("Platform 3", DepartureLabels.destinationLabel("", "Platform 3"))
    }

    @Test
    fun `applies the hardcoded display rename to a long terminus`() {
        // "Battersea Power" (however it reaches here) is renamed to "Battersea".
        assertEquals("Battersea", DepartureLabels.destinationLabel("Battersea Power", "southbound"))
        // A terminus with no rename is returned unchanged.
        assertEquals("Morden", DepartureLabels.destinationLabel("Morden", "southbound"))
    }

    @Test
    fun `strips a Station suffix unconditionally at label time`() {
        // Even a destination that reached here uncleaned — e.g. restored from an older snapshot —
        // has its " … Station" type suffix dropped, so it resolves the same as a fresh fetch and a
        // long name never clips: "Battersea Power Station" → "Battersea Power" → renamed "Battersea".
        assertEquals("Battersea", DepartureLabels.destinationLabel("Battersea Power Station", "southbound"))
        assertEquals("Morden", DepartureLabels.destinationLabel("Morden Underground Station", "southbound"))
    }

    @Test
    fun `is null when the destination and the direction key are both blank`() {
        assertNull(DepartureLabels.destinationLabel("", ""))
        assertNull(DepartureLabels.destinationLabel("", "   "))
    }

    @Test
    fun `titlecases the cue invariantly, not per the device locale`() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("tr-TR"))
        try {
            // A Turkish default locale would titlecase "inbound" as "İnbound" (dotted I);
            // the cue must stay "Inbound".
            assertEquals("Inbound", DepartureLabels.destinationLabel("", "inbound"))
        } finally {
            Locale.setDefault(original)
        }
    }
}
