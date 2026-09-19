package app.trackmo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import app.trackmo.domain.Coordinates
import app.trackmo.domain.LocationProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The device's position via the framework [LocationManager] — no Play Services dependency,
 * so nothing is added to what the app ships (SPEC *Cost and reliability*). Coarse only: the
 * nearby-stops search needs approximate position, not a precise fix, so it reads a
 * network/fused provider and never GPS, and asks only for `ACCESS_COARSE_LOCATION`.
 *
 * Returns `null` — never throws to the caller — whenever a position can't be produced
 * (permission not held, location off, no provider, no fix), so the gate renders an honest
 * "couldn't get your location" state rather than a guess (SPEC principle 2). Nothing here
 * logs a coordinate; only that a fix could not be obtained (SPEC *Privacy*).
 */
class AndroidLocationProvider(
    private val context: Context,
    private val warn: (String) -> Unit = {},
) : LocationProvider {
    override suspend fun current(): Coordinates? {
        if (!hasCoarsePermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val provider = pickProvider(manager) ?: run {
            warn("location fix failed: no coarse provider enabled")
            return null
        }
        return try {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                // getCurrentLocation delivers one fresh fix (or null) and self-cancels, so
                // there is no long-lived listener to leak — the CancellationSignal only
                // covers the caller giving up mid-request.
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    cont.resume(location?.let { Coordinates(it.latitude, it.longitude) })
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up mid-fix; a cancellation is not a location failure, and
            // swallowing it into `null` would break structured concurrency.
            throw e
        } catch (e: SecurityException) {
            // A revoke can race the permission check above; treat it as no fix, not a crash.
            warn("location fix failed: permission")
            null
        } catch (e: Exception) {
            warn("location fix failed: ${e::class.simpleName}")
            null
        }
    }

    private fun hasCoarsePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A coarse-friendly provider that is currently enabled: fused where the platform has it
     * (API 31+), else network, else passive. Never GPS — coarse permission can't drive it,
     * and a precise fix isn't needed for nearby stops.
     */
    private fun pickProvider(manager: LocationManager): String? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }
}
