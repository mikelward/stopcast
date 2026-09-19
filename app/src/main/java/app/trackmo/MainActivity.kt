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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.trackmo.data.AndroidLocationProvider
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
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        permissionPermanentlyDenied = false
                        nearbyViewModel.locate()
                    } else {
                        // A denial with no rationale allowed means the system won't prompt
                        // again — route the user to Settings rather than a dead re-request.
                        permissionPermanentlyDenied =
                            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                }

                // Resolve when the coarse-location permission is held and nothing has resolved
                // yet: on open if already granted, and again on returning from Settings with a
                // fresh grant. Guarded on the still-unresolved PermissionRequired state so a
                // configuration change — which recreates the activity and re-runs this, while
                // the ViewModel and its resolved state survive — doesn't relocate over a
                // working (or in-flight) result and re-hit TfL (Codex).
                val lifecycleOwner = LocalLifecycleOwner.current
                LaunchedEffect(lifecycleOwner) {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        if (nearbyViewModel.state.value is NearbyStopsViewModel.State.PermissionRequired &&
                            hasCoarseLocation()
                        ) {
                            nearbyViewModel.locate()
                        }
                    }
                }

                when (val state = nearby) {
                    is NearbyStopsViewModel.State.Ready -> DeparturesForStops(state.stops)
                    else -> LocationGate(
                        state = state,
                        permanentlyDenied = permissionPermanentlyDenied,
                        onAllow = { permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                        onRetry = {
                            if (hasCoarseLocation()) nearbyViewModel.locate()
                            else permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
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
     * resolves; it's keyed on the stop ids so a different nearby set gets its own instance
     * rather than reusing a stale one.
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
    private fun DeparturesForStops(stops: List<StopRef>) {
        val viewModel: MainViewModel = viewModel(
            key = "departures:" + stops.joinToString(",") { it.id },
            factory = viewModelFactory {
                initializer {
                    MainViewModel(client = KtorTflClient(httpClient), seedStops = stops)
                }
            },
        )
        val state by viewModel.state.collectAsStateWithLifecycle()
        val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
        RefreshOnForeground(viewModel)
        AutoRefresh(viewModel)
        MainScreen(
            state = state,
            now = tickingNow(),
            onRefresh = viewModel::refresh,
            refreshing = refreshing,
        )
    }

    private fun hasCoarseLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Opens this app's system settings so the user can grant a permanently-denied permission. */
    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * The production sink for the location seam's warnings: the messages are already coarse
     * and carry no coordinate or key (SPEC *Privacy*), so a diagnosis of a misfiring fix
     * isn't discarded in the shipped app. Logcat only — not the persisted, shareable debug
     * log, which lands with its `docs/PRIVACY.md` disclosure in the Phase 5 logging work.
     */
    private fun logLocationWarning(message: String) = Log.w("Trackmo.Location", message)

    companion object {
        // Process-scoped: one OkHttp engine and connection pool shared by every ViewModel
        // the process creates, rather than a fresh client leaked per ViewModel (nothing
        // closes a Ktor client, so a per-launch one accumulates engine/pool resources). A
        // single long-lived client is OkHttp's own recommended shape; it lives for the
        // process and dies with it.
        private val httpClient by lazy { KtorTflClient.defaultHttpClient() }
    }
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
