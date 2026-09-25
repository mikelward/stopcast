package app.stopdash.ui

import app.stopdash.domain.DistanceSystem
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The locale → system lookup behind "Auto" distance units (SPEC *Finding stops*). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DistanceUnitsTest {
    @Test
    fun `the UK reads yards and the US feet`() {
        assertEquals(DistanceSystem.YARDS, localeDistanceSystem(Locale.UK))
        assertEquals(DistanceSystem.FEET, localeDistanceSystem(Locale.US))
    }

    @Test
    fun `a metric locale reads meters`() {
        assertEquals(DistanceSystem.METERS, localeDistanceSystem(Locale.FRANCE))
        assertEquals(DistanceSystem.METERS, localeDistanceSystem(Locale.forLanguageTag("en-AU")))
    }
}
