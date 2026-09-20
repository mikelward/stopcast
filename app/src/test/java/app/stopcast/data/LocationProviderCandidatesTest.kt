package app.stopcast.data

import android.location.LocationManager
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure provider-ranking used by `AndroidLocationProvider` (the `LocationManager` glue
 * itself needs a device). Pins that GPS is offered only with precise permission — coarse
 * permission can't drive it — and that fused leads where the platform has it.
 */
class LocationProviderCandidatesTest {
    @Test
    fun `precise permission includes GPS, most-accurate first`() {
        assertEquals(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ),
            locationProviderCandidates(fineGranted = true, sdkInt = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `coarse-only permission omits GPS`() {
        assertEquals(
            listOf(
                LocationManager.FUSED_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ),
            locationProviderCandidates(fineGranted = false, sdkInt = Build.VERSION_CODES.S),
        )
    }

    @Test
    fun `below API 31 has no fused provider`() {
        // FUSED_PROVIDER is API 31+; before that the list starts at GPS (precise) / network.
        assertEquals(
            listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
            ),
            locationProviderCandidates(fineGranted = true, sdkInt = Build.VERSION_CODES.R),
        )
    }
}
