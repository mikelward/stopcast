package app.stopdash.domain

import app.stopdash.domain.FixDiagnostics.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The location-fix diagnostic line: provider, accuracy (or unknown), age, source — no coordinate. */
class FixDiagnosticsTest {
    @Test
    fun `a fresh network fix names its provider, accuracy and age`() {
        assertEquals(
            "location fix: fresh from network, accuracy 1200 m, 2 s old",
            FixDiagnostics.describe(Source.FRESH, "network", 1200.4f, 1_600),
        )
    }

    @Test
    fun `a missing accuracy estimate reads as unknown, never as zero`() {
        val line = FixDiagnostics.describe(Source.FALLBACK, "fused", null, 480_000)
        assertEquals("location fix: last-known fallback from fused, accuracy unknown, 480 s old", line)
        assertFalse(line.contains("0 m"))
    }

    @Test
    fun `a position is logged to five places, whatever the locale`() {
        val saved = java.util.Locale.getDefault()
        try {
            // A comma-decimal locale must not turn "lat,lon" ambiguous.
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("51.50000,-0.12000", FixDiagnostics.position(Coordinates(51.5, -0.12)))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }

    @Test
    fun `a recent cached fix is labeled as such`() {
        assertEquals(
            "location fix: recent cached from gps, accuracy 8 m, 40 s old",
            FixDiagnostics.describe(Source.RECENT_CACHED, "gps", 7.6f, 40_000),
        )
    }
}
