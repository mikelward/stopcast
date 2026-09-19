package app.trackmo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackmo.data.AndroidLocationProvider
import app.trackmo.data.DataStoreStarredRowsStore
import app.trackmo.data.KtorTflClient
import app.trackmo.ui.LocationGate
import app.trackmo.ui.MainScreen
import app.trackmo.ui.MainViewModel
import app.trackmo.ui.NearbyStopsViewModel
import app.trackmo.ui.StopRef
import app.trackmo.ui.theme.TrackmoTheme
import java.time.Instant
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    // The location gate: resolves the nearby stops (an on-demand, location-sending action)
    // before the departures view, which then refreshes those stops location-free.
    private val nearbyViewModel: NearbyStopsViewModel by viewModels {
        viewModelFactory {
            initializer {
                NearbyStopsViewModel(
                    location = AndroidLocationProvider(applicationContext, warn = ::logLocationWarning),
                    finder = KtorTflClient(httpClient),
                    warn = ::logLocationWarning,
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrackmoAppRoot {
                val nearby by nearbyViewModel.state.collectAsStateWithLifecycle()

                // True once a request has come back denied with the rationale suppressed —
                // Android's "don't ask again" / permanently-denied signal. Then re-requesting
                // only re-denies, so the gate offers Settings instead (Codex). Survives
                // configuration change so a rotation doesn't drop back to the Allow button.
                var permissionPermanentlyDenied by rememberSaveable { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { grants ->
                    // The precise request has now been shown, whichever way it was answered —
                    // so an upgraded coarse-only user isn't prompted again on every open.
                    markPrecisePrompted()
                    // Request both so the runtime dialog offers the precise/approximate choice;
                    // either grant finds stops (precise preferred — see AndroidLocationProvider).
                    if (grants.values.any { it }) {
                        permissionPermanentlyDenied = false
                        nearbyViewModel.locate()
                    } else {
                        // A denial with no rationale allowed means the system won't prompt
                        // again — route the user to Settings rather than a dead re-request.
                        permissionPermanentlyDenied =
                            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                // Resolve when a location permission is held and nothing has resolved
                // yet: on open if already granted, and again on returning from Settings with a
                // fresh grant. Guarded on the still-unresolved PermissionRequired state so a
                // configuration change — which recreates the activity and re-runs this, while
                // the ViewModel and its resolved state survive — doesn't relocate over a
                // working (or in-flight) result and re-hit TfL (Codex).
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        if (nearbyViewModel.state.value is NearbyStopsViewModel.State.PermissionRequired) {
                            when (
                                nearbyPermissionAction(
                                    hasFine = hasFineLocation(),
                                    hasAnyLocation = hasLocationPermission(),
                                    precisePrompted = precisePrompted(),
                                )
                            ) {
                                NearbyPermissionAction.LOCATE -> nearbyViewModel.locate()
                                NearbyPermissionAction.REQUEST_PRECISE ->
                                    permissionLauncher.launch(locationPermissions)
                                NearbyPermissionAction.WAIT -> {}
                            }
                        }
                    }
                }

                when (val state = nearby) {
                    is NearbyStopsViewModel.State.Ready ->
                        DeparturesForStops(state.stops, state.distanceMeters, nearbyViewModel::locate)
                    else -> LocationGate(
                        state = state,
                        permanentlyDenied = permissionPermanentlyDenied,
                        onAllow = { permissionLauncher.launch(locationPermissions) },
                        onRetry = {
                            if (hasLocationPermission()) nearbyViewModel.locate()
                            else permissionLauncher.launch(locationPermissions)
                        },
                        onOpenSettings = ::openAppSettings,
                    )
                }
            }
        }
    }

    /**
     * The departures view for a resolved nearby set. The [MainViewModel] is created here —
     * not as an activity field — because its watched stops aren't known until location
     * resolves; each nearby set gets its own instance, scoped to a per-set store owner that
     * clears the previous one (cancelling its in-flight fetch) when the set changes, rather
     * than reusing a stale one or accumulating them.
     *
     * No persisted snapshot for this interim nearby set (`SnapshotStore.NONE`, the default):
     * the store holds one process-wide snapshot, but the watched set here is derived from
     * location and changes as the user moves, so restoring it would show a previous
     * location's departures under the newly-resolved stops — and cards omit the stop name,
     * so those rows would look like the new stops' (Codex). Proper per-set persistence (and
     * offline last-good) returns with Phase 2's user-chosen watched stops; until then the
     * view resolves fresh each open.
     */
    @Composable
    private fun DeparturesForStops(
        stops: List<StopRef>,
        stopDistanceMeters: Map<String, Double>,
        onLocateHere: () -> Unit,
    ) {
        // Each nearby set gets its own MainViewModel, and the previous one is CLEARED when
        // the set changes (the user moved and re-located) rather than left keyed in the
        // activity's store: relocating repeatedly would otherwise pile up view models and
        // leave an old location's in-flight fetch running after its screen is gone (Codex).
        //
        // The per-set ViewModelStore lives in [NearbyDeparturesStores], an activity-scoped
        // holder that survives configuration changes — so a rotation reuses the same store and
        // its MainViewModel (no reload, no duplicate fetch) — while [NearbyDeparturesStores.
        // ownerFor] clears every *other* set's store, cancelling a moved-away set's in-flight
        // fetch. A plain `remember`-created owner did neither: it was recreated on every
        // configuration change, forcing a reload and a fresh TfL fetch on each rotation (Codex).
        val stopsKey = remember(stops) { stops.joinToString(",") { it.id } }
        val stores: NearbyDeparturesStores = viewModel()
        val storeOwner = remember(stopsKey) { stores.ownerFor(stopsKey) }
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val viewModel: MainViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MainViewModel(
                            client = KtorTflClient(httpClient),
                            seedStops = stops,
                            // Starring is persisted per row across every nearby set (it's keyed
                            // by row identity, not tied to this stop set), so the store is the
                            // shared process-wide one, not scoped to this ViewModel's key.
                            starredStore = DataStoreStarredRowsStore.from(applicationContext, warn = ::logStarWarning),
                            warn = ::logDepartureWarning,
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
            val starred by viewModel.starred.collectAsStateWithLifecycle()
            val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
            val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
            RefreshOnForeground(viewModel)
            AutoRefresh(viewModel)
            MainScreen(
                state = state,
                now = tickingNow(),
                onRefresh = viewModel::refresh,
                refreshing = refreshing,
                // From "near me now": collapse a line served by several adjacent nearby stops
                // to its nearest stop (SPEC *Finding stops → Near me now*). Empty for a
                // location-free list (a watched-stops view), which is shown as-is.
                stopDistanceMeters = stopDistanceMeters,
                onLocateHere = onLocateHere,
                starred = starred,
                onToggleStar = viewModel::toggleStar,
                starringAvailable = starringAvailable,
                starWriteFailed = starWriteFailed,
                onStarWriteFailureShown = viewModel::starWriteFailureShown,
            )
        }
    }

    // Either grant is enough to find stops; precise (FINE) is preferred and requested first.
    private fun hasLocationPermission(): Boolean =
        hasFineLocation() ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Whether the precise-location request has been shown at least once. Persisted so an
    // existing coarse-only install (upgraded from before FINE was requested) is prompted for
    // precise exactly once — adding FINE to the manifest does not upgrade a live coarse grant,
    // so without this such a user would silently keep the inaccurate coarse behavior (Codex).
    // A completed approximate choice sets it too, so the user isn't nagged every open.
    private val locationPrefs by lazy {
        getSharedPreferences("trackmo.location", MODE_PRIVATE)
    }

    private fun precisePrompted(): Boolean = locationPrefs.getBoolean(KEY_PRECISE_PROMPTED, false)

    private fun markPrecisePrompted() {
        locationPrefs.edit().putBoolean(KEY_PRECISE_PROMPTED, true).apply()
    }

    /** Opens this app's system settings so the user can grant a permanently-denied permission. */
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        // FINE first so the runtime dialog leads with precise; COARSE alongside so the dialog
        // offers the approximate choice and an approximate grant still finds stops.
        private val locationPermissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

        private const val KEY_PRECISE_PROMPTED = "precise_prompted"

        // Process-scoped: one OkHttp engine and connection pool shared by every ViewModel
        // the process creates, rather than a fresh client leaked per ViewModel (nothing
        // closes a Ktor client, so a per-launch one accumulates engine/pool resources). A
        // single long-lived client is OkHttp's own recommended shape; it lives for the
        // process and dies with it.
        private val httpClient by lazy { KtorTflClient.defaultHttpClient() }
    }
}

/**
 * An activity-scoped holder of the per-nearby-set [ViewModelStore]s, so the departures
 * [MainViewModel] for the current set survives a configuration change (a rotation reuses the
 * same store rather than rebuilding it and re-fetching), while a set the user has moved away
 * from is cleared — cancelling its in-flight fetch — rather than piling up (Codex, PR #43).
 *
 * Being a [ViewModel] is what buys the config-change survival: the activity keeps the same
 * instance across recreation. [ownerFor] returns the store for [key] (creating it once) and
 * clears every other key, since only one nearby set is shown at a time; [onCleared] clears
 * them all when the activity is finished for good.
 */
internal class NearbyDeparturesStores : androidx.lifecycle.ViewModel() {
    private val stores = mutableMapOf<String, ViewModelStore>()

    /** The retained store for [key], clearing any other set's store first. */
    fun ownerFor(key: String): ViewModelStoreOwner {
        val stale = stores.keys.filter { it != key }
        for (k in stale) stores.remove(k)?.clear()
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore = store
        }
    }

    override fun onCleared() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/** What the nearby gate should do for the current location-permission state (see [nearbyPermissionAction]). */
internal enum class NearbyPermissionAction { LOCATE, REQUEST_PRECISE, WAIT }

/**
 * The nearby gate's action for a held (or absent) location permission, from the three facts the
 * runtime exposes: whether precise (FINE) is granted, whether *any* location permission is
 * granted, and whether the precise request has already been shown once (persisted).
 *
 * Extracted pure so the coarse-only-upgrade path is unit-testable off a device — the reported
 * bug was an install predating FINE keeping a live coarse grant, which the manifest change does
 * not upgrade, so it must be offered precise exactly once (AGENTS testing rule: a bug fix gets a
 * regression test). The [MainActivity] `LaunchedEffect` only supplies the three booleans and
 * carries out the returned action.
 *
 * - **precise held** → [LOCATE]: the accurate fix is available.
 * - **coarse held, precise already prompted** → [LOCATE]: the user's approximate choice stands;
 *   re-prompting every open would nag.
 * - **coarse held, precise never prompted** → [REQUEST_PRECISE]: the upgrade case — offer precise
 *   once rather than silently keeping the inaccurate coarse fix.
 * - **no location permission** → [WAIT]: the gate shows its Allow button; nothing auto-fires.
 */
internal fun nearbyPermissionAction(
    hasFine: Boolean,
    hasAnyLocation: Boolean,
    precisePrompted: Boolean,
): NearbyPermissionAction = when {
    hasFine -> NearbyPermissionAction.LOCATE
    hasAnyLocation && precisePrompted -> NearbyPermissionAction.LOCATE
    hasAnyLocation -> NearbyPermissionAction.REQUEST_PRECISE
    else -> NearbyPermissionAction.WAIT
}

/**
 * The app's composition root: the theme plus a single full-size themed [Surface]. A screen
 * without its own background — the location gate is a bare `Column`; only `MainScreen` brings
 * a `Scaffold` — then paints on `colorScheme.surface` and inherits `onSurface` as its content
 * color. Without the Surface the gate rendered over the raw window background with a black
 * default content color, unreadable in dark mode (charcoal ground, black title).
 *
 * Extracted from `onCreate` so the wrapper is unit-testable: `TrackmoAppRootTest` asserts the
 * content color inside it is `onSurface`, which fails if the Surface is dropped — the existing
 * `LocationGateScreenshotTest` can't catch that, since it installs its own Surface.
 */
@Composable
internal fun TrackmoAppRoot(content: @Composable () -> Unit) {
    TrackmoTheme {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Refresh when the user returns to the foregrounded screen (SPEC D6: refresh on open),
 * so they don't come back from Recents to withheld stale countdowns and have to refresh
 * by hand. The ViewModel's `init` does the first load and survives configuration change,
 * so the **first** foreground per activity instance is skipped — only a genuine return
 * from the background triggers a re-fetch, never a duplicate of the initial request nor
 * a refetch on rotation. (Lifecycle wiring: verified by inspection, wants a device check.)
 */
@Composable
private fun RefreshOnForeground(viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        refreshOnForeground(lifecycleOwner.lifecycle) { viewModel.refresh() }
    }
}

/**
 * Runs [onForeground] each time [lifecycle] re-enters STARTED **except the first**: the
 * first foreground is the initial load (done in the ViewModel's `init`, and re-run on a
 * fresh activity after process death), and a configuration change restarts this with its
 * own first-skip, so neither path double-fetches while a genuine return from the
 * background does refresh (SPEC D6). Extracted so the skip-first/return-again rule is
 * unit-testable off a device.
 */
internal suspend fun refreshOnForeground(lifecycle: Lifecycle, onForeground: () -> Unit) {
    var firstForeground = true
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (firstForeground) firstForeground = false else onForeground()
    }
}

/**
 * Re-fetches on a fixed cadence while the screen is on, so an always-open surface — a
 * kiosk, or a phone left on the departures screen — keeps its predictions live without a
 * manual pull (SPEC D5/D6). A failed tick keeps the last-good departures and surfaces a
 * "couldn't refresh" banner rather than blanking (handled in [MainViewModel]); once truly
 * stale the per-row countdowns withhold. Gated on the RESUMED lifecycle so a backgrounded
 * screen isn't woken for nothing (battery); the [delay] runs *before* the first tick, so a
 * return to the foreground doesn't double-fetch with [refreshOnForeground]. Extracted so
 * the cadence is unit-testable off a device.
 */
internal suspend fun autoRefresh(
    lifecycle: Lifecycle,
    intervalMillis: Long = AUTO_REFRESH_MILLIS,
    isRefreshing: () -> Boolean = { false },
    onTick: () -> Unit,
) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
        while (true) {
            delay(intervalMillis)
            // Skip a tick while a refresh is still running. refresh() cancels the in-flight
            // fetch, so ticking during a slow refresh — several TfL requests timing out past
            // one interval — would repeatedly cancel it, starving a cold load at Loading or
            // keeping an aged screen from ever reaching its "couldn't refresh" state (Codex).
            // A skipped tick simply waits for the next interval, by which point the refresh
            // has settled and its result (fresh, or the failure banner) is on screen.
            if (!isRefreshing()) onTick()
        }
    }
}

/**
 * How often the on-screen view re-fetches (SPEC D5). One minute keeps TfL predictions
 * (which update roughly every ~30 s) fresh enough for a glance surface while staying a tiny
 * fraction of the keyless per-IP rate budget — the intended targets are home users and a
 * kiosk display, where the request volume is low. Reversible: one constant, pinned by
 * [app.trackmo.AutoRefreshTest].
 */
internal const val AUTO_REFRESH_MILLIS = 60_000L

/** Drives [autoRefresh] from the activity's lifecycle. */
@Composable
private fun AutoRefresh(viewModel: MainViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        autoRefresh(
            lifecycleOwner.lifecycle,
            isRefreshing = { viewModel.refreshing.value },
        ) { viewModel.refresh() }
    }
}

/**
 * A clock that advances on screen so countdowns and the freshness stamp recompute
 * without a new fetch (SPEC D4). Ten seconds is enough to keep "N min"/"Due" honest
 * while staying off a per-frame recomposition. The tick is gated on the RESUMED
 * lifecycle so a backgrounded screen isn't woken every 10 s for nothing (battery).
 */
@Composable
private fun tickingNow(): Instant {
    val lifecycleOwner = LocalLifecycleOwner.current
    val now by androidx.compose.runtime.produceState(initialValue = Instant.now(), lifecycleOwner) {
        val scope = this
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                scope.value = Instant.now()
                delay(10_000)
            }
        }
    }
    return now
}

/**
 * The production sink for the location seam's warnings: coarse messages carrying no
 * coordinate or key (SPEC *Privacy*), so a diagnosis of a misfiring fix isn't discarded in
 * the shipped app. Logcat only — not the persisted, shareable debug log, which lands with
 * its `docs/PRIVACY.md` disclosure in the Phase 5 logging work.
 *
 * Top-level, not an Activity method: a `MainActivity::` method reference is held by the
 * ViewModels it's passed to, and an activity-scoped ViewModel outlives the Activity across
 * configuration changes — so a bound reference would pin each destroyed Activity in the
 * ViewModel store (Codex). A top-level function captures nothing.
 */
private fun logLocationWarning(message: String) = Log.w("Trackmo.Location", message)

/**
 * The production sink for the departures/disruption seam's warnings. Without it wired,
 * `MainViewModel`'s `warn` defaulted to a no-op, so a persistent "couldn't check for
 * disruptions" left nothing in logcat to explain which line or lookup was unknown. The
 * messages are coarse — a count, a line id, an HTTP reason — with no coordinate, stop-set,
 * or key (SPEC *Privacy*: line ids are allowed). Logcat only, like [logLocationWarning];
 * the persisted, shareable debug log lands in the Phase 5 work. Top-level for the same
 * no-Activity-capture reason as [logLocationWarning].
 */
private fun logDepartureWarning(message: String) = Log.w("Trackmo.Departures", message)

/**
 * The production sink for the starred-rows store's warnings — a discarded corrupt star file,
 * or a preserved newer-schema file. Without it wired the store defaulted to a no-op, so those
 * recovery paths left nothing in logcat. The messages are coarse facts (no stop/line id is
 * needed and none is logged); Logcat only, like [logLocationWarning], for the same
 * no-Activity-capture reason.
 */
private fun logStarWarning(message: String) = Log.w("Trackmo.Stars", message)
