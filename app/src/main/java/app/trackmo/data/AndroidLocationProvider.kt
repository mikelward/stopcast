package app.trackmo.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import app.trackmo.domain.Coordinates
import app.trackmo.domain.FixSelection
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
 * This class is only the framework glue: it reads the last-known fix and adapts
 * `getCurrentLocation` into a suspending call. *Which* fix to trust — the recent-cached fast
 * path, the fresh-fix bound, the cached fallback and its age cap, the honest `null` — is the
 * pure policy in [FixSelection], tested off a device. Every failure path leaves a sanitized
 * line in the log (SPEC principle 2 / *Privacy* — a fix could not be obtained, never a
 * coordinate); the previous version logged nothing when `getCurrentLocation` simply returned
 * no fix, which is why that failure was invisible in a bug report.
 */
class AndroidLocationProvider(
    private val context: Context,
    private val warn: (String) -> Unit = {},
) : LocationProvider {
    override suspend fun current(): Coordinates? {
        if (!hasCoarsePermission()) {
            warn("location fix skipped: coarse permission not held")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            warn("location fix failed: no location service")
            return null
        }
        val providers = enabledProviders(manager)
        if (providers.isEmpty()) {
            warn("location fix failed: no coarse provider enabled")
            return null
        }
        val cached = bestLastKnown(manager, providers)
        return FixSelection.resolve(
            lastKnown = cached?.first,
            lastKnownAgeMillis = cached?.second,
            warn = warn,
            // The same monotonic clock the cached age was measured against, so the fallback
            // cap is honored across the fresh-fix wait (see FixSelection.resolve).
            elapsedMillis = SystemClock::elapsedRealtime,
            // Re-checked before any cached fallback: coarse permission can be revoked between
            // the check above and here, and a revoked fresh fix returns null, so without this
            // a cached location would be sent to TfL after the user withdrew access.
            hasPermission = ::hasCoarsePermission,
            freshFix = { requestFreshFix(manager, providers.first()) },
        )
    }

    /**
     * One fresh coarse fix (or `null`). [LocationManager.getCurrentLocation] delivers a single
     * fix and self-cancels, so there is no long-lived listener to leak; the [CancellationSignal]
     * only covers the caller giving up (a [FixSelection] timeout, or the screen going away).
     */
    private suspend fun requestFreshFix(manager: LocationManager, provider: String): Coordinates? =
        try {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    cont.resume(location?.toCoordinates())
                }
            }
        } catch (e: CancellationException) {
            // The caller gave up (timeout / screen gone); not a location failure, and
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

    /**
     * The newest last-known fix across the enabled providers, paired with its age in
     * milliseconds — or `null` if none has one. Age is from the monotonic elapsed-realtime
     * clock, so it is correct across a wall-clock change. A per-provider lookup that throws is
     * logged (sanitized) and skipped rather than failing the whole read.
     */
    private fun bestLastKnown(manager: LocationManager, providers: List<String>): Pair<Coordinates, Long>? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return providers
            .mapNotNull { provider -> lastKnownOrNull(manager, provider) }
            .map { it to (nowNanos - it.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000 }
            .minByOrNull { it.second }
            ?.let { (loc, ageMillis) -> loc.toCoordinates() to ageMillis }
    }

    private fun lastKnownOrNull(manager: LocationManager, provider: String): Location? =
        try {
            manager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            warn("last-known lookup failed: permission")
            null
        } catch (e: Exception) {
            warn("last-known lookup failed: ${e::class.simpleName}")
            null
        }

    private fun hasCoarsePermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The coarse-friendly providers currently enabled, most-accurate first: fused where the
     * platform has it (API 31+), then network, then passive. Never GPS — coarse permission
     * can't drive it, and a precise fix isn't needed for nearby stops.
     */
    private fun enabledProviders(manager: LocationManager): List<String> {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    private fun Location.toCoordinates() = Coordinates(latitude, longitude)
}
