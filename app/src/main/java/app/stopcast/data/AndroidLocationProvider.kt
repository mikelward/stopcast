package app.stopcast.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import app.stopcast.domain.Coordinates
import app.stopcast.domain.FixSelection
import app.stopcast.domain.LocationProvider
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The device's position via the framework [LocationManager] — no Play Services dependency,
 * so nothing is added to what the app ships (SPEC *Cost and reliability*). **Precise where
 * granted**: a coarse fix put the nearest stop up to ~1 km off (a stop half a mile away read
 * as nearest), so stopcast requests `ACCESS_FINE_LOCATION` and, when it is held, reads GPS as
 * well as the fused/network providers; with only `ACCESS_COARSE_LOCATION` granted it degrades
 * to the coarse-safe providers rather than failing (maintainer, 2026-09-19).
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
        if (!hasLocationPermission()) {
            warn("location fix skipped: location permission not held")
            return null
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: run {
            warn("location fix failed: no location service")
            return null
        }
        val providers = enabledProviders(manager)
        if (providers.isEmpty()) {
            warn("location fix failed: no location provider enabled")
            return null
        }
        val cached = bestLastKnown(manager, providers)
        return FixSelection.resolve(
            lastKnown = cached?.coordinates,
            lastKnownAgeMillis = cached?.ageMillis,
            // With precise granted, prefer an accurate fix: a recent *coarse* cached fix no
            // longer takes the instant fast path (it stays a fallback), and the fresh side
            // tries the accurate providers first — so precise access isn't defeated by a
            // slightly newer network fix (Codex). Off under a coarse-only grant.
            lastKnownIsAccurate = cached?.accurate ?: false,
            preferAccurate = hasFineLocationPermission(),
            warn = warn,
            // The same monotonic clock the cached age was measured against, so the fallback
            // cap is honored across the fresh-fix wait (see FixSelection.resolve).
            elapsedMillis = SystemClock::elapsedRealtime,
            // Re-checked before any cached fallback: location permission can be revoked between
            // the check above and here, and a revoked fresh fix returns null, so without this
            // a cached location would be sent to TfL after the user withdrew access.
            hasPermission = ::hasLocationPermission,
            freshFix = { requestFreshFix(manager, providers) },
        )
    }

    /**
     * A fresh fix from the first provider that yields one, tried in [providers] order — which
     * is accurate-first (fused, GPS, then the coarse network/passive), so with precise granted
     * an accurate fix is preferred over a quick coarse one. Each provider gets its own
     * [FixSelection.FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS] bound: a fused provider that accepts
     * the request but never calls back would otherwise consume the whole [FixSelection] budget
     * and GPS — which may have a fix — would never be asked (Codex). A `null` (no fix, or the
     * per-provider timeout) falls through to the next; the overall [FixSelection] timeout still
     * caps the sum, and a coarse provider only answers once the accurate ones have not.
     */
    private suspend fun requestFreshFix(manager: LocationManager, providers: List<String>): Coordinates? =
        firstFix(
            providers,
            FixSelection.FRESH_FIX_PER_PROVIDER_TIMEOUT_MILLIS,
            // Log a provider that hit its per-provider bound, so a hanging provider (fused, most
            // often) still leaves a diagnostic line even when a later provider's no-fix makes the
            // overall result null and `FixSelection` records "returned no fix" (Codex). The
            // provider name is coarse diagnostics, not user data (SPEC *Privacy*).
            onTimeout = { provider -> warn("fresh fix: $provider provider timed out") },
        ) { provider ->
            requestFreshFixFrom(manager, provider)
        }

    /**
     * One fresh fix from [provider] (or `null`). [LocationManager.getCurrentLocation] delivers a
     * single fix and self-cancels, so there is no long-lived listener to leak; the
     * [CancellationSignal] only covers the caller giving up (a [FixSelection] timeout, or the
     * screen going away).
     */
    private suspend fun requestFreshFixFrom(manager: LocationManager, provider: String): Coordinates? =
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

    /** A cached last-known fix: its coordinates, age in ms, and whether it came from an
     *  accurate (GPS/fused) provider — the last gates the precise fast path in [FixSelection]. */
    private data class CachedFix(val coordinates: Coordinates, val ageMillis: Long, val accurate: Boolean)

    /**
     * The newest last-known fix across the enabled providers — or `null` if none has one — with
     * its age and whether its provider is accurate. Age is from the monotonic elapsed-realtime
     * clock, so it is correct across a wall-clock change. A per-provider lookup that throws is
     * logged (sanitized) and skipped rather than failing the whole read. Newest-overall (not
     * newest-accurate): the accuracy flag lets [FixSelection] decline the fast path for a coarse
     * fix under precise, but a coarse cached fix is still a valid fallback if the fresh fix fails.
     */
    private fun bestLastKnown(manager: LocationManager, providers: List<String>): CachedFix? {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return providers
            .mapNotNull { provider -> lastKnownOrNull(manager, provider)?.let { provider to it } }
            .map { (provider, loc) ->
                CachedFix(
                    coordinates = loc.toCoordinates(),
                    ageMillis = (nowNanos - loc.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000,
                    accurate = isAccurateProvider(provider),
                )
            }
            .minByOrNull { it.ageMillis }
    }

    /** GPS and fused give a precise fix; network and passive are coarse. */
    private fun isAccurateProvider(provider: String): Boolean =
        provider == LocationManager.GPS_PROVIDER || provider == LocationManager.FUSED_PROVIDER

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

    /** True when either location permission is held — enough to obtain some fix. */
    private fun hasLocationPermission(): Boolean =
        hasFineLocationPermission() ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** True when precise location is granted, which is what allows GPS and a precise fix. */
    private fun hasFineLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * The candidate providers currently enabled, in the order [locationProviderCandidates]
     * ranks them — GPS included only when precise permission is held, since coarse permission
     * can't drive it.
     */
    private fun enabledProviders(manager: LocationManager): List<String> =
        locationProviderCandidates(hasFineLocationPermission(), Build.VERSION.SDK_INT)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }

    private fun Location.toCoordinates() = Coordinates(latitude, longitude)
}

/**
 * The location providers to try, most-accurate first, for a request with [fineGranted] precise
 * permission on API [sdkInt]. Pure (no `LocationManager`) so the ranking is unit-testable off a
 * device; the caller filters this to the ones actually enabled.
 *
 * - **Fused** leads where the platform exposes it (API 31+): it already returns the most
 *   accurate location the caller is permitted.
 * - **GPS** is included **only when precise permission is held** — coarse permission can't drive
 *   it (a request would throw `SecurityException`), and it's the fallback that gives a precise
 *   fix on a device whose fused provider is weak.
 * - **Network** then **passive** are the coarse-safe providers, always tried.
 */
/**
 * The first fix any of [providers] yields, tried in order, each attempt bounded by
 * [perProviderTimeoutMillis] via [fetch]. A provider that returns `null` (no fix) or exceeds its
 * bound falls through to the next — so a fused provider that accepts the request but never calls
 * back can't starve GPS behind it (Codex). Pure over [fetch] so the accurate-first waterfall (a
 * fused provider that never answers, then GPS) is unit-testable off a device with virtual time;
 * `AndroidLocationProvider` supplies the real `LocationManager`-backed fetch.
 */
internal suspend fun firstFix(
    providers: List<String>,
    perProviderTimeoutMillis: Long,
    onTimeout: (String) -> Unit = {},
    fetch: suspend (String) -> Coordinates?,
): Coordinates? {
    for (provider in providers) {
        var completed = false
        val fix = withTimeoutOrNull(perProviderTimeoutMillis) {
            fetch(provider).also { completed = true }
        }
        if (fix != null) return fix
        // `withTimeoutOrNull` returns null both when the provider promptly answered "no fix"
        // and when it exceeded its bound; `completed` is set only on the former, so an unset
        // flag means this provider timed out — report it (a hanging provider is the diagnostic
        // worth keeping) before moving to the next (Codex).
        if (!completed) onTimeout(provider)
    }
    return null
}

internal fun locationProviderCandidates(fineGranted: Boolean, sdkInt: Int): List<String> =
    buildList {
        if (sdkInt >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        if (fineGranted) add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        add(LocationManager.PASSIVE_PROVIDER)
    }
