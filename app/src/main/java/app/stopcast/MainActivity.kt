package app.stopcast

import android.Manifest
import app.stopcast.data.FileNearbyStopsStore
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.stopcast.data.AndroidLocationProvider
import app.stopcast.data.DataStoreAppSettings
import app.stopcast.data.logAppSettingsWarning
import app.stopcast.data.DataStoreDismissedAlertsStore
import app.stopcast.data.DataStoreStarredRowsStore
import app.stopcast.data.KtorTflClient
import app.stopcast.data.RouteTopologyStore
import app.stopcast.domain.RouteStopsRepository
import app.stopcast.data.SharedTflRateLimiter
import app.stopcast.data.SharedTflRequestPool
import app.stopcast.data.UserApiKeySetting
import app.stopcast.domain.AppSettings
import app.stopcast.domain.BugReport
import java.io.IOException
import app.stopcast.domain.Journeys
import app.stopcast.data.DataStoreStarredJourneysStore
import app.stopcast.domain.StarredJourney
import app.stopcast.domain.CachingStopFinder
import app.stopcast.domain.NearbyStopsCache
import app.stopcast.domain.Coordinates
import app.stopcast.domain.StationMatch
import app.stopcast.domain.StopMap
import app.stopcast.ui.BugReportConsentDialog
import app.stopcast.ui.FontSizeSetting
import app.stopcast.ui.LocalRouteStops
import app.stopcast.ui.LocalRouteTopology
import app.stopcast.ui.LicensesScreen
import app.stopcast.ui.LocationBanner
import app.stopcast.ui.LocationGate
import app.stopcast.ui.MainScreen
import app.stopcast.ui.ARRIVALS_REUSE
import app.stopcast.ui.DISRUPTION_REUSE
import app.stopcast.ui.FAR_ARRIVALS_REUSE
import app.stopcast.ui.LINE_STATUS_REUSE
import app.stopcast.ui.MainViewModel
import app.stopcast.ui.NearbyStopsViewModel
import app.stopcast.ui.SettingsScreen
import app.stopcast.ui.StationPlaceholderScreen
import app.stopcast.ui.StationSearchScreen
import app.stopcast.ui.StationSearchViewModel
import app.stopcast.ui.StationStopsViewModel
import app.stopcast.ui.StopRef
import app.stopcast.ui.WriteFailures
import app.stopcast.ui.theme.StopCastTheme
import app.stopcast.widget.LiveWidgetRefreshResult
import app.stopcast.widget.StopCastWidget
import app.stopcast.widget.WidgetSnapshotStore
import app.stopcast.widget.applyLiveWidgetRefresh
import app.stopcast.widget.syncLiveWidgetRefreshSchedule
import androidx.core.content.FileProvider
import androidx.glance.appwidget.updateAll
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugReport
import com.mikelward.androidlog.android.ReportScreenshot
import com.mikelward.androidlog.android.ShareOutcome
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns a captured screenshot [file] into a shareable `content://` URI, degrading to `null` — a
 * text-only report, never a dropped share (SPEC principle 2) — rather than letting a `FileProvider`
 * failure escape the application-scoped share coroutine and crash it. The app mints the URI (the
 * provider and its authority are the app's), so this guard lives here rather than in the shared
 * capture, which took the app-local copy's place. [mint] is `FileProvider.getUriForFile` in
 * production, injected so the fallback is testable without a real provider. The orphaned PNG is
 * best-effort deleted; the shared capture's age-prune reclaims it otherwise.
 */
internal fun bugReportScreenshotUri(file: File, log: DebugLog, mint: (File) -> Uri): Uri? =
    try {
        mint(file)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warning("bug report: could not build the screenshot URI: %s", e.javaClass.simpleName)
        file.delete()
        null
    }

/**
 * Load state of the stored TfL app_key for the Settings field. Needed because the key itself is
 * nullable (null = keyless), so a plain nullable couldn't distinguish "not read yet" from "read,
 * no key" — and the field stays disabled until [Loaded] so a slow read can't be edited over a value
 * that hasn't arrived (Codex P2).
 */
private sealed interface ApiKeyLoad {
    data object Loading : ApiKeyLoad
    data class Loaded(val key: String?) : ApiKeyLoad
}

class MainActivity : ComponentActivity() {
    // The location gate: resolves the nearby stops (an on-demand, location-sending action)
    // before the departures view, which then refreshes those stops location-free.
    private val nearbyViewModel: NearbyStopsViewModel by viewModels {
        viewModelFactory {
            initializer {
                NearbyStopsViewModel(
                    location = AndroidLocationProvider(applicationContext, warn = ::logLocationWarning),
                    // Reuses a recent lookup made close by (in memory, process-wide), so reopening
                    // the app near where it was last used skips a request and a round trip.
                    finder = CachingStopFinder(
                        KtorTflClient(
                            httpClient,
                            appKey = { UserApiKeySetting.current },
                            rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                            requestPool = SharedTflRequestPool.pool,
                        ),
                        nearbyStopsCache(applicationContext),
                    ),
                    warn = ::logLocationWarning,
                )
            }
        }
    }

    // The bundled branch topology, loaded off the main thread so the ~9 KB asset parse never
    // sits on the cold-start / first-frame path (SPEC principles 3–5). The initial value is the
    // process-wide cached instance if one is already parsed — free of IO, so after a rotation the
    // retained view models' departures render merged on the very first frame rather than
    // flickering through split rows while the async load re-runs — and RouteTopology.EMPTY (the
    // safe default: branches as TfL gives them, nothing merged) only on a true cold start, where
    // the async load below fills it the instant the asset is ready, well before the network
    // snapshot arrives. The widget loads the same cached instance in its own coroutine.
    private val routeTopology = mutableStateOf(RouteTopologyStore.cached())

    // Whether Google Play reports a newer version — drives the overflow "update available" dot
    // (SPEC *Update indicator*). Rechecked on each foreground ([onResume]); a background Play
    // Task, never on a render path. False on debug (checks are disabled there).
    private val updateAvailable = mutableStateOf(false)
    private val playUpdateChecker by lazy { PlayUpdateChecker(application, warn = ::logUpdateWarning) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Warm the chosen text size into memory (off the main thread) so the first frame is sized
        // from the user's setting rather than the default, then resized a beat later (SPEC *Display
        // size*). Idempotent and shares the process-singleton DataStore instance the settings
        // collector below uses.
        val appSettings = DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
        FontSizeSetting.warm(appSettings)
        // The user's TfL app_key holder ([UserApiKeySetting]) is warmed at process start in
        // StopcastApp (every process, so a widget-only process has it too), not here. The request
        // clients read the current key per request — a paste raises the budget on the next refresh
        // without rebuilding them (SPEC D7).
        lifecycleScope.launch {
            routeTopology.value = withContext(Dispatchers.IO) { RouteTopologyStore.load(applicationContext) }
        }
        setContent {
            StopCastAppRoot {
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

                // The retained latch for a pending foreground-return re-location, composed above the
                // overlay switch by [NearbyArea] below and consumed inside the departures view. See
                // [ForegroundReturnLatch] for why it's a ViewModel rather than a `remember`.
                val returnLatch: ForegroundReturnLatch = viewModel()

                // The licenses screen is hosted here, above the location gate — not inside the
                // departures view — so the open-source attribution (and the app version) stay
                // reachable in every state, including a permission-denied gate where departures
                // never resolve (Codex). It also means departures and their background refresh
                // leave composition while licenses is open, rather than polling TfL behind a
                // static screen. Saved so it survives rotation and process death; each screen's
                // own Back closes it.
                var licensesOpen by rememberSaveable { mutableStateOf(false) }
                var settingsOpen by rememberSaveable { mutableStateOf(false) }
                val openLicenses = { licensesOpen = true }
                // "Find a station" (SPEC *Finding stops*): the search, and the station opened from it
                // (its TfL id and name). Hosted as overlays like Settings, so the near-me departures
                // stop polling while they're up; back from a station returns to the search.
                var stationSearchOpen by rememberSaveable { mutableStateOf(false) }
                var openStationId by rememberSaveable { mutableStateOf<String?>(null) }
                var openStationName by rememberSaveable { mutableStateOf("") }

                // App settings + the opt-in "live widget" refresh (SPEC D5). The setting is
                // collected here and applied to the scheduler at start — so an enabled toggle
                // resumes the ~1/min refresh chain after the process is recreated — and on every
                // change; the Settings screen itself stays UI-only (WorkManager is wired here).
                val settings = remember {
                    DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning)
                }
                // null until the first read lands (a slow or persistently-failing DataStore read
                // leaves the flow silent) — the Settings switch is disabled meanwhile so the user
                // can't act on an off value that may not reflect the stored choice (Codex P2 on #56).
                val liveWidgetRefresh: Boolean? by settings.liveWidgetRefresh()
                    .collectAsStateWithLifecycle(initialValue = null)
                val settingsScope = rememberCoroutineScope()
                // Whether the last apply of the live-widget setting failed to schedule, so the
                // Settings screen can surface it. The coordinator owns persist + schedule + the
                // error policy (see applyLiveWidgetRefresh); this is just its result.
                var liveWidgetRefreshFailed by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    liveWidgetRefreshFailed =
                        syncLiveWidgetRefreshSchedule(applicationContext, settings) ==
                            LiveWidgetRefreshResult.FAILED
                }

                // The bug-report consent gate. false-defaulted while the setting loads so a slow
                // read over-asks rather than sharing the location unprompted; "don't ask again"
                // (persisted) skips straight to the share sheet (SPEC *Privacy*).
                val skipBugReportConsent: Boolean by settings.skipBugReportConsent()
                    .collectAsStateWithLifecycle(initialValue = false)

                // The user's TfL app_key for the Settings field. Read from the store (the source of
                // truth), so an external change — a restore, or the warmed holder's own write —
                // reflects in the field. Wrapped in a load marker because the stored value is itself
                // nullable (null = keyless), so a bare `null` couldn't tell "not read yet" from
                // "loaded, no key" — and the field must stay disabled until it's really loaded, so a
                // slow read can't present an empty field the user edits over a key that then arrives
                // and resets the draft (Codex P2, mirroring the live-widget switch).
                val apiKeyLoadFlow = remember(settings) {
                    settings.userApiKey().map<String?, ApiKeyLoad> { ApiKeyLoad.Loaded(it) }
                }
                val apiKeyLoad: ApiKeyLoad by apiKeyLoadFlow
                    .collectAsStateWithLifecycle(initialValue = ApiKeyLoad.Loading)
                // Loading is a first-open concern, not a per-rotation one: the collection restarts at
                // Loading on every configuration change, and letting the field flash back through
                // "not loaded"/empty would re-seed and drop an unsaved edit the user was making
                // (Codex). Retain the last loaded key and the loaded flag across recreation, so after
                // a rotation the field keeps the real key (and stays enabled) with no transient.
                var lastLoadedKey by rememberSaveable { mutableStateOf<String?>(null) }
                var apiKeyEverLoaded by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(apiKeyLoad) {
                    (apiKeyLoad as? ApiKeyLoad.Loaded)?.let {
                        lastLoadedKey = it.key
                        apiKeyEverLoaded = true
                    }
                }
                val apiKeyLoaded = apiKeyLoad is ApiKeyLoad.Loaded || apiKeyEverLoaded
                // Loaded(null) is keyless — keep it null, don't fall back to the retained key.
                val userApiKeyValue = when (val load = apiKeyLoad) {
                    is ApiKeyLoad.Loaded -> load.key
                    ApiKeyLoad.Loading -> lastLoadedKey
                }
                // Whether the consent dialog is open is held in a retained ViewModel, not the saved
                // bundle: it survives a configuration change (rotation) so the open dialog isn't
                // discarded with the Send (Codex P2 on #86), but resets on process death — where the
                // in-memory fix is gone anyway (never persisted, SPEC *Privacy*), so a restored
                // dialog would only build a location-unavailable report (Codex P2 on #86). The report
                // inputs are rebuilt from the current [nearby] state (also ViewModel-backed) at send.
                val bugReportConsent: BugReportConsentViewModel = viewModel()
                val requestBugReport = {
                    if (skipBugReportConsent) shareBugReport(bugReportRequestFor(nearby))
                    else bugReportConsent.open = true
                }

                // Licenses and Settings are activity-level overlays (like the licenses screen's
                // existing hosting), reachable from every state and closed by their own Back, so
                // opening either takes the departures view and its background refresh out of
                // composition rather than polling TfL behind a static screen. [NearbyArea] hosts the
                // switch and composes the foreground-return observer in its aboveOverlay slot — above
                // the switch — so a return that lands while an overlay is open is still seen (#136).
                NearbyArea(
                    overlayOpen = licensesOpen || settingsOpen || stationSearchOpen || openStationId != null,
                    aboveOverlay = {
                        ForegroundReturnLatcher(
                            isReady = { nearbyViewModel.state.value is NearbyStopsViewModel.State.Ready },
                            isBusy = { nearbyViewModel.relocating.value },
                            onReturn = { returnLatch.pending = true },
                        )
                    },
                    overlayContent = {
                        // Licenses wins if both are somehow set; each closes via its own Back.
                        if (licensesOpen) {
                            LicensesScreen(onBack = { licensesOpen = false })
                        } else if (!settingsOpen) {
                            StationSearchArea(
                                stationId = openStationId,
                                stationName = openStationName,
                                onOpenStation = { match ->
                                    openStationId = match.id
                                    openStationName = match.name
                                },
                                // The name goes with the id, so nothing about a station looked at is
                                // kept in the saved state once it's closed.
                                onCloseStation = {
                                    openStationId = null
                                    openStationName = ""
                                },
                                onCloseSearch = {
                                    stationSearchOpen = false
                                    openStationId = null
                                    openStationName = ""
                                },
                            )
                        } else {
                            SettingsScreen(
                                liveWidgetRefresh = liveWidgetRefresh == true,
                                liveWidgetRefreshEnabled = liveWidgetRefresh != null,
                                liveWidgetRefreshFailed = liveWidgetRefreshFailed,
                                onLiveWidgetRefreshChange = { enabled ->
                                    settingsScope.launch {
                                        liveWidgetRefreshFailed =
                                            applyLiveWidgetRefresh(applicationContext, settings, enabled) ==
                                                LiveWidgetRefreshResult.FAILED
                                    }
                                },
                                onDismissLiveWidgetRefreshError = { liveWidgetRefreshFailed = false },
                                // The paste field. Applied to memory at once (next refresh uses it) and
                                // persisted in the background; a blank clears it back to keyless (SPEC D7).
                                // Disabled until the stored key has actually been read, so the field can't be
                                // edited over a value that hasn't loaded yet.
                                userApiKey = userApiKeyValue.orEmpty(),
                                userApiKeyLoaded = apiKeyLoaded,
                                onUserApiKeyChange = { key -> UserApiKeySetting.set(key) },
                                onBack = { settingsOpen = false },
                            )
                        }
                    },
                    body = {
                        when (val state = nearby) {
                            is NearbyStopsViewModel.State.Ready ->
                                DeparturesForStops(
                                    ready = state,
                                    relocate = { onSameSet -> nearbyViewModel.relocate(onSameSet) },
                                    relocating = nearbyViewModel.relocating,
                                    locationBanner = nearbyViewModel.locationBanner,
                                    onOpenLicenses = openLicenses,
                                    onOpenSettings = { settingsOpen = true },
                                    onFindStation = { stationSearchOpen = true },
                                    updateAvailable = updateAvailable.value,
                                    onOpenAppListing = ::openPlayListing,
                                    onSendBugReport = requestBugReport,
                                    // A foreground return that landed while an overlay was open is latched
                                    // above; consume it here so re-entering departures relocates.
                                    foregroundReturnPending = returnLatch.pending,
                                    onForegroundReturnConsumed = { returnLatch.pending = false },
                                )
                            else -> {
                                // While the gate is up (a failed/empty relocate, or a retry), drop
                                // any departures store retained from the pre-gate set, so recovering
                                // to the same stop IDs rebuilds the ViewModel and re-fetches instead
                                // of showing the pre-gate departures until the next auto-refresh
                                // (Codex). Ready never enters this branch, so a same-set relocate is
                                // untouched. Runs once on gate entry (keyed Unit).
                                val stores: NearbyDeparturesStores = viewModel()
                                DisposableEffect(Unit) {
                                    stores.clearAll()
                                    onDispose {}
                                }
                                LocationGate(
                                    state = state,
                                    permanentlyDenied = permissionPermanentlyDenied,
                                    onAllow = { permissionLauncher.launch(locationPermissions) },
                                    onRetry = {
                                        if (hasLocationPermission()) nearbyViewModel.locate()
                                        else permissionLauncher.launch(locationPermissions)
                                    },
                                    onOpenSettings = ::openAppSettings,
                                    onOpenLicenses = openLicenses,
                                    // The report is most useful in exactly these stuck states (no fix,
                                    // TfL unreachable, nothing nearby), so it is reachable here too, not
                                    // only past the gate — with no location or stops (Codex P2 on #86).
                                    onSendBugReport = requestBugReport,
                                    // The gate is in front of the departures overflow (which carries the
                                    // update item), so surface an available update on the Locating spinner.
                                    updateAvailable = updateAvailable.value,
                                    onOpenAppListing = ::openPlayListing,
                                    // The station search needs no location, so it's offered here too:
                                    // most useful to exactly the users who can't use near me.
                                    onFindStation = { stationSearchOpen = true },
                                )
                            }
                        }
                    },
                )

                // The consent gate overlays whatever is shown; requested from the Ready overflow or
                // a stuck gate. Confirm builds the report from the current state and shares it
                // (persisting the opt-out if ticked); cancel just closes. Nothing is assembled until
                // the user confirms here.
                if (bugReportConsent.open) {
                    BugReportConsentDialog(
                        onConfirm = { dontAskAgain ->
                            bugReportConsent.open = false
                            // Persist the opt-out on the application scope, not settingsScope: this
                            // Activity can be recreated the instant Continue is tapped (config
                            // change), which would cancel a settingsScope write and silently lose
                            // the "don't ask again" choice — the same reason shareBugReport uses it.
                            if (dontAskAgain) {
                                val optOutScope =
                                    (application as? StopcastApp)?.applicationScope ?: settingsScope
                                optOutScope.launch { persistBugReportOptOut(settings) }
                            }
                            shareBugReport(bugReportRequestFor(nearby))
                        },
                        onDismiss = { bugReportConsent.open = false },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check on every foreground (covers first launch, since onResume follows onCreate,
        // and a return from the Play listing or the background). Cheap and off the main thread.
        playUpdateChecker.checkForUpdate { available -> updateAvailable.value = available }
    }

    /**
     * Opens this app's Google Play listing so the user can update, tried Play-app-first then the
     * web listing. Reached only from the release build's overflow "update available" item, where
     * [packageName] is the real `app.stopcast` id (debug disables the check). If neither opens
     * (no Play app and no browser — rare, since the item only shows once Play's own check reported
     * an update), the tap would otherwise do nothing, so it shows a toast and logs rather than
     * failing silently (SPEC principle 2), mirroring the license-link flow in [LicensesScreen].
     */
    private fun openPlayListing() {
        for (uri in PLAY_LISTING_URIS) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("$uri$packageName"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return
            } catch (e: android.content.ActivityNotFoundException) {
                // No handler for this target (e.g. no Play app for market://). Log the miss —
                // sanitized, the exception class only — so a browser-only fallback is
                // diagnosable, then try the next URI (a later success returns before the
                // toast + summary below). The scheme is a fixed constant, not user data.
                logUpdateWarning("Play listing target unavailable: ${e.javaClass.simpleName}")
            }
        }
        Toast.makeText(this, R.string.update_open_failed, Toast.LENGTH_SHORT).show()
        logUpdateWarning("No app to open the Play listing")
    }

    /**
     * Shows a stop in the user's maps app, a labeled pin at TfL's published stop position (never
     * the user's fix). With no app to handle `geo:` the tap would otherwise do nothing, so it shows
     * a toast and logs rather than failing silently (SPEC principle 2); the log carries no coordinate.
     */
    private fun openStopMap(latitude: Double, longitude: Double, name: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(StopMap.geoUri(latitude, longitude, name))))
        } catch (e: android.content.ActivityNotFoundException) {
            logLocationWarning("no maps app to show a stop: ${e.javaClass.simpleName}")
            Toast.makeText(this, R.string.map_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Assembles and shares the consent-gated bug report — the diagnostic log plus the **exact
     * location** and per-stop distances the [request] captured. Reached only after
     * [BugReportConsentDialog] (or the persisted "don't ask again"): this is the sanctioned
     * exception to the on-device-only log rule, so it runs only past that gate (SPEC *Privacy*).
     *
     * The shared `mikelward/androidlog` `DebugReport` does the mechanism: [DebugReport.collect]
     * reads (and, once shared, consumes) the persisted earlier runs off the main thread, wrapping
     * this app's section; [DebugReport.deliver] copies to the clipboard, attaches the screenshot,
     * and opens the share sheet on the main thread. The shared androidlog [ReportScreenshot.capture]
     * takes the shot first — of this Activity's window, which excludes the consent dialog's separate
     * window, so it is the screen being reported, not the dialog over it; a failed capture is a
     * text-only report, never a dropped share. A `COPIED_ONLY`/`FAILED` outcome is surfaced, not swallowed
     * (SPEC principle 2).
     */
    private fun shareBugReport(request: BugReportRequest) {
        val app = application as? StopcastApp
        // Null in a test Application (or if setup failed) — the report then carries no earlier
        // runs, which is exactly what a null sink means to collect().
        val sink = app?.diagnosticSink
        // Run on the application scope with the application context, not lifecycleScope + the
        // Activity: collect reads the persisted log (up to ~10 s) and deliver opens the share
        // sheet, so a rotation mid-collect must not cancel the coroutine and drop the share with
        // no sheet or toast (SPEC principle 2; Codex P2 on #86). The chooser is launched with
        // FLAG_ACTIVITY_NEW_TASK by DebugReport, so the app context is fine. Falls back to the
        // Activity scope only in a test Application that isn't StopcastApp.
        val scope = app?.applicationScope ?: lifecycleScope
        val context = applicationContext
        // Held only until the capture returns (early in the coroutine, before the ~10 s collect);
        // capture guards a finished window and yields null rather than touching a stale one.
        val activity = this
        scope.launch {
            // The shared androidlog ReportScreenshot captures the PNG off the main thread and
            // returns the file; this app mints the FileProvider URI from it — the provider and its
            // authority are the app's (see the manifest and @xml/file_paths). A null capture is a
            // text-only report, never a dropped share.
            val screenshot = withContext(Dispatchers.IO) {
                ReportScreenshot.capture(activity, File(context.cacheDir, "bug-reports"), StopcastDebugLog)
                    ?.let { file ->
                        bugReportScreenshotUri(file, StopcastDebugLog) {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
                        }
                    }
            }
            val report = withContext(Dispatchers.IO) {
                DebugReport.collect(StopcastDebugLog, sink) {
                    BugReport.compose(
                        header = bugReportHeader(),
                        location = request.location,
                        stops = request.stops.map {
                            BugReport.StopLine(it.name, it.id, request.distanceMeters[it.id])
                        },
                        // This run's buffer, rendered in full (DEVICE fidelity) — the report is
                        // consent-gated, so it is not the redacted, location-safe export.
                        logLines = StopcastDebugLog.snapshot(),
                    )
                }
            }
            val outcome = DebugReport.deliver(
                context = context,
                log = StopcastDebugLog,
                report = report,
                subject = context.getString(R.string.bug_report_subject),
                chooserTitle = context.getString(R.string.bug_report_chooser_title),
                clipboardLabel = context.getString(R.string.bug_report_clipboard_label),
                screenshot = screenshot,
            )
            when (outcome) {
                ShareOutcome.SHARED -> {}
                ShareOutcome.COPIED_ONLY ->
                    Toast.makeText(context, R.string.bug_report_copied, Toast.LENGTH_LONG).show()
                ShareOutcome.FAILED ->
                    Toast.makeText(context, R.string.bug_report_failed, Toast.LENGTH_LONG).show()
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
     * The [WidgetSnapshotStore] here is save-only: it writes each authoritative snapshot to
     * the file the widget reads (and pokes the widget to re-render) but its `load` returns
     * null, so the in-app view does **not** restore it. That asymmetry is deliberate — the
     * watched set here is derived from location and changes as the user moves, so restoring
     * it in-app would show a previous location's departures under the newly-resolved stops,
     * and cards omit the stop name so those rows would look like the new stops' (Codex).
     * The widget wants the same last-good the app just fetched, so it gets it; the in-app
     * view still resolves fresh each open. Proper per-set persistence (and in-app offline
     * last-good) returns with Phase 2's user-chosen watched stops.
     */
    @Composable
    private fun DeparturesForStops(
        ready: NearbyStopsViewModel.State.Ready,
        // A refresh (button or pull) re-resolves the nearby set (a fresh fix) as well as
        // re-fetching departures — so walking to the next stop and refreshing updates both.
        // Called with a reconcile action, which relocate runs only when the fix confirms the
        // *same* nearby set (a moved-to set gets a fresh ViewModel that fetches on init; a
        // failed/empty relocation shows the honest gate) — so a refresh never re-fetches the
        // previous location's stops in parallel with the fix (see relocate). The fresh
        // [NearbyStopsViewModel.State.Ready] is handed back so the retained ViewModel can
        // reconcile both tiers in place and keep a revealed expansion.
        relocate: (onSameSet: (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit,
        // True while a relocate's fresh fix is in flight (the departures screen stays up). ORed
        // into the refresh indicator so pull-to-refresh doesn't retract the instant the fetch
        // is enqueued, leaving the fix to change the set under a screen that reads as settled.
        relocating: StateFlow<Boolean>,
        // Why the shown nearby set's location is low-confidence, or null — drives the top banner
        // over the list (SPEC *Finding stops*). From the gate's NearbyStopsViewModel, which owns
        // the fix and its confidence.
        locationBanner: StateFlow<LocationBanner?>,
        onOpenLicenses: () -> Unit,
        onOpenSettings: () -> Unit,
        onFindStation: () -> Unit,
        // Play reports a newer version — the overflow gets its red dot and "Update available"
        // item. Threaded from the activity's [updateAvailable] state, refreshed on each resume.
        updateAvailable: Boolean,
        onOpenAppListing: () -> Unit,
        // Overflow "Send bug report": built at the Ready branch so it captures the fix + distances.
        onSendBugReport: () -> Unit,
        // A background→foreground return, latched by the activity-level observer above the overlay
        // switch (so a return while Settings/Licenses is open isn't lost). True means "re-locate on
        // (re)entry"; [onForegroundReturnConsumed] clears it once acted on.
        foregroundReturnPending: Boolean,
        onForegroundReturnConsumed: () -> Unit,
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
        // Capture the application context once so callbacks stored on the retained ViewModel
        // (onStarsChanged below) close over it rather than over this Activity. The ViewModel
        // survives configuration changes, so a lambda that resolved `applicationContext` on the
        // Activity would keep the destroyed Activity reachable until the ViewModel is cleared.
        val appContext = applicationContext
        // Key the retained per-set ViewModel on the WHOLE nearby cluster set (order-independent),
        // not the eager stop ids: a relocation that only reorders the clusters, or shifts one across
        // the eager/more boundary while all stay in range, keeps the same key and so the same
        // ViewModel — preserving a revealed "More" expansion, which a rebuild would drop.
        val stopsKey = remember(ready) { ready.clusterSetKey }
        val stores: NearbyDeparturesStores = viewModel()
        val storeOwner = remember(stopsKey) { stores.ownerFor(stopsKey) }
        // Shared with a searched station's page, so a write failure there surfaces here too.
        val writeFailures = viewModel<WriteFailuresHolder>().failures
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val viewModel: MainViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MainViewModel(
                            client = KtorTflClient(
                                httpClient,
                                appKey = { UserApiKeySetting.current },
                                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                                requestPool = SharedTflRequestPool.pool,
                                warn = ::logDepartureWarning,
                            ),
                            seedStops = ready.eagerStops,
                            initialMore = ready.more,
                            // Save-only snapshot store: the app writes each fresh snapshot for
                            // the widget to render, but this nearby set is not restored in-app
                            // (its load() returns null) — a previous location's stops must not
                            // resurface under a newly-resolved set.
                            snapshotStore = WidgetSnapshotStore(applicationContext),
                            // Starring is persisted per row across every nearby set (it's keyed
                            // by row identity, not tied to this stop set), so the store is the
                            // shared process-wide one, not scoped to this ViewModel's key.
                            starredStore = DataStoreStarredRowsStore.from(appContext, warn = ::logStarWarning),
                            // Dismissed alerts are persisted per place across every nearby set, so
                            // the store is the shared process-wide one too.
                            dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
                            warn = ::logDepartureWarning,
                            // Re-render the widget when a star changes (its pinned order — SPEC
                            // D8) or after a refresh that didn't save, so its age/staleness stays
                            // current rather than frozen at the last save (SPEC D4).
                            redrawWidget = { StopCastWidget().updateAll(appContext) },
                            // A quick retry after a rate-limited refresh refetches only the
                            // stops still missing, and a stop's closure check is reused for a
                            // few minutes — both spare TfL's keyless rate budget.
                            arrivalsReuse = ARRIVALS_REUSE,
                            disruptionReuse = DISRUPTION_REUSE,
                            lineStatusReuse = LINE_STATUS_REUSE,
                            // Stops past the walking reach refresh every other minute on the timer.
                            stopDistanceMeters = ready.distanceMeters,
                            farArrivalsReuse = FAR_ARRIVALS_REUSE,
                            // Feeds the per-fetch debug-log line: time spent rate-limited.
                            rateWaitMillis = { SharedTflRateLimiter.waitedMillis },
                            logStats = ::logDepartureWarning,
                            writeFailures = writeFailures,
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val departuresRefreshing by viewModel.refreshing.collectAsStateWithLifecycle()
            // A relocate holds the indicator on for the whole fresh fix, not just the departures
            // fetch that follows a same-set confirmation.
            val relocatingNow by relocating.collectAsStateWithLifecycle()
            val refreshing = departuresRefreshing || relocatingNow
            val locationBannerNow by locationBanner.collectAsStateWithLifecycle()
            val starred by viewModel.starred.collectAsStateWithLifecycle()
            // Starred journeys (SPEC *Journeys*): read from the device, each turned so its origin is
            // the end nearer this fix, or flipped by a tap on its card. The shown origins are fetched
            // alongside the near-me stops.
            val journeyStore = remember { DataStoreStarredJourneysStore.from(appContext, warn = ::logStarWarning) }
            // Null until the first read arrives (and when unreadable), so journey starring stays off
            // rather than showing a saved journey as unstarred and letting a tap remove it.
            // Wrapped so "not read yet" (null) stays distinct from "read, but unreadable" (a read of
            // null): a journey view restored across a rotation waits out the first, not the second.
            val journeysRead by remember(journeyStore) { journeyStore.journeys().map { JourneysRead(it) } }
                .collectAsStateWithLifecycle(initialValue = null)
            val savedJourneys = journeysRead?.journeys
            var flippedJourneys by rememberSaveable { mutableStateOf(emptyList<String>()) }
            val shownJourneys = remember(savedJourneys, ready.location, flippedJourneys) {
                savedJourneys.orEmpty().map { journey ->
                    val oriented = Journeys.oriented(journey, ready.location.latitude, ready.location.longitude)
                    if (journey.key in flippedJourneys) oriented.reversed() else oriented
                }
            }
            val journeyScope = rememberCoroutineScope()
            var journeyWriteFailed by rememberSaveable { mutableStateOf(false) }
            // The route page's journey tip: hidden (true) until the setting is read, so it never
            // flashes up for someone who already dismissed it.
            val tipSettings = remember { DataStoreAppSettings.from(appContext, warn = ::logAppSettingsWarning) }
            val journeyTipStored by remember(tipSettings) { tipSettings.journeyTipDismissed() }
                .collectAsStateWithLifecycle(initialValue = true)
            // Closed for this session at once on "Got it", whether or not the save lands (a failed
            // one is logged, and the tip returns next launch). Held for the process, not this
            // composition, which Settings or Licenses replaces.
            val journeyTipDismissed = journeyTipStored || JourneyTipSession.closed
            val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
            val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
            val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
            val dismissWriteFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
            // The "More" buttons to offer — modes with a farther cluster still to page in.
            val revealableModes by viewModel.moreState.collectAsStateWithLifecycle()
            // These background refreshes are composed only while the departures view is shown:
            // the licenses screen is hosted above this subtree (see onCreate), so opening it
            // removes DeparturesForStops from composition and stops the polling (Codex).
            // Refresh = re-locate (a fresh fix, re-resolving the nearby set) then re-fetch the
            // confirmed same set; a moved-to set's new ViewModel fetches on its own init. Used by
            // the refresh control AND by a return to the foreground below, so walking away and back
            // moves the nearby set to where you are now (maintainer, 2026-09-23) — the auto-refresh
            // timer stays departures-only, it doesn't relocate. The cancel-then-relocate-then-
            // reconcile composition is factored into [relocateAction] so a regression back to a
            // departures-only refresh is caught by a unit test.
            val onRelocate: () -> Unit = relocateAction(
                cancelFetch = viewModel::cancelFetch,
                relocate = relocate,
                reconcile = { fresh -> viewModel.reconcile(fresh.eager, fresh.more, fresh.distanceMeters) },
            )
            // Consume a latched foreground return (set by the activity-level observer above the
            // overlay switch). Because the latch lives above this view, it survives this view being
            // out of composition (an overlay) until a re-entry consumes it here — the whole point.
            ConsumeForegroundReturn(
                pending = foregroundReturnPending,
                isBusy = { relocating.value },
                onConsumed = onForegroundReturnConsumed,
                onRelocate = onRelocate,
            )
            AutoRefresh(viewModel, relocating)
            // Provide the branch topology so the card groups a branching row the way the widget
            // does — equivalent trunks merged, the label kept only where the trunk is a choice
            // ahead of the stop (see DepartureRows.destinationLines).
            CompositionLocalProvider(
                LocalRouteTopology provides routeTopology.value,
                LocalRouteStops provides routeStops,
            ) {
                MainScreen(
                    state = state,
                    now = tickingNow(),
                    // Re-locates then re-fetches (see onRelocate above) — the same action a return
                    // to the foreground runs, so the refresh control and reopening the app both move
                    // the nearby set to the current position.
                    onRefresh = onRelocate,
                    refreshing = refreshing,
                    // From "near me now": collapse a line served by several adjacent nearby stops
                    // to its nearest stop (SPEC *Finding stops → Near me now*). Spans both tiers, so
                    // a revealed stop collapses like an eager one. Empty for a location-free list
                    // (a watched-stops view), which is shown as-is.
                    stopDistanceMeters = ready.distanceMeters,
                    journeys = shownJourneys,
                    // The journeys' origins (this way round) are fetched alongside the near-me stops.
                    onJourneyOrigins = viewModel::setJourneyStops,
                    onWidgetJourneys = viewModel::setWidgetJourneys,
                    journeysKnown = savedJourneys != null,
                    journeysLoading = journeysRead == null,
                    // Null (stations inert) while the saved journeys are a newer app version's file this
                    // build can't read: it's preserved untouched, so a toggle could only be ignored.
                    onToggleJourney = if (savedJourneys == null) {
                        null
                    } else {
                        { journey ->
                            journeyScope.launch {
                                try {
                                    journeyStore.toggle(journey)
                                } catch (e: IOException) {
                                    // Logged without the stations, and surfaced so the tap isn't
                                    // silently lost (SPEC principle 2).
                                    logStarWarning("journey star not saved: ${e::class.simpleName}")
                                    journeyWriteFailed = true
                                }
                            }
                        }
                    },
                    journeyWriteFailed = journeyWriteFailed,
                    onJourneyWriteFailureShown = { journeyWriteFailed = false },
                    onDismissJourneyTip = if (journeyTipDismissed) {
                        null
                    } else {
                        {
                            JourneyTipSession.closed = true
                            // On the application scope, like the bug-report opt-out: a rotation or
                            // Settings disposes this composition's scope, which would cancel the save.
                            ((application as? StopcastApp)?.applicationScope ?: journeyScope).launch {
                                try {
                                    tipSettings.setJourneyTipDismissed(true)
                                } catch (e: IOException) {
                                    // Closed for this session already; it shows again next launch.
                                    logAppSettingsWarning("journey tip dismissal not saved: ${e::class.simpleName}")
                                }
                            }
                        }
                    },
                    onFlipJourney = { journey ->
                        flippedJourneys = if (journey.key in flippedJourneys) flippedJourneys - journey.key else flippedJourneys + journey.key
                    },
                    // A tap on a header's distance shows that stop in the maps app. The stop comes
                    // from the same nearby set the distances span, so every distance can resolve.
                    onOpenStopMap = { stopId, name ->
                        val stop = (ready.eager + ready.more)
                            .firstNotNullOfOrNull { c -> c.stops.firstOrNull { it.id == stopId } }
                        if (stop != null) {
                            openStopMap(stop.latitude, stop.longitude, name)
                        } else {
                            logLocationWarning("map tap for a stop not in the nearby set: $stopId")
                        }
                    },
                    starred = starred,
                    onToggleStar = viewModel::toggleStar,
                    starringAvailable = starringAvailable,
                    starWriteFailed = starWriteFailed,
                    dismissed = dismissed,
                    onDismissAlert = viewModel::dismissAlert,
                    dismissWriteFailed = dismissWriteFailed,
                    onDismissWriteFailureShown = viewModel::dismissWriteFailureShown,
                    onStarWriteFailureShown = viewModel::starWriteFailureShown,
                    onOpenLicenses = onOpenLicenses,
                    onOpenSettings = onOpenSettings,
                    onFindStation = onFindStation,
                    updateAvailable = updateAvailable,
                    onOpenAppListing = onOpenAppListing,
                    revealableModes = revealableModes,
                    // Ignore a "More" tap while a relocation's fresh fix is in flight, so it can't
                    // page the pre-fix set as current (matches the cancel-on-relocate discipline).
                    onReveal = { mode -> if (!relocatingNow) viewModel.reveal(mode) },
                    onSendBugReport = onSendBugReport,
                    locationBanner = locationBannerNow,
                )
            }
        }
    }

    /**
     * "Find a station" (SPEC *Finding stops*): the name search, or — once a match is picked — that
     * station's live departures. The search's ViewModel is activity-retained, so back from a
     * station finds the query and matches as they were. A station gets its own retained store
     * (like a nearby set's), cleared when another station opens or the search closes, so its
     * fetch stops with it. Its departures are never saved for the widget (no snapshot store): the
     * widget shows the near-me set, and a station looked up once isn't one the user watches.
     */
    @Composable
    private fun StationSearchArea(
        stationId: String?,
        stationName: String,
        onOpenStation: (StationMatch) -> Unit,
        onCloseStation: () -> Unit,
        onCloseSearch: () -> Unit,
    ) {
        val search: StationSearchViewModel = viewModel(
            key = "station-search",
            factory = viewModelFactory {
                initializer {
                    StationSearchViewModel(stationFinder, createSavedStateHandle(), warn = ::logDepartureWarning)
                }
            },
        )
        val stores: NearbyDeparturesStores = viewModel(key = "station-stores")
        // The near-me list's write-failure flags: a star or dismiss that fails after this page has
        // closed (the write outlives it) is shown on the next list instead of lost with the page.
        val writeFailures = viewModel<WriteFailuresHolder>().failures
        val closeSearch = {
            stores.clearAll()
            search.clear()
            onCloseSearch()
        }
        // Leaving a station drops its retained models, so its departures don't outlive the page
        // and reopening it fetches afresh rather than showing what was loaded before.
        val closeStation = {
            stores.clearAll()
            onCloseStation()
        }
        if (stationId == null) {
            val state by search.state.collectAsStateWithLifecycle()
            StationSearchScreen(
                state = state,
                onQueryChange = search::onQueryChange,
                onOpenStation = onOpenStation,
                onRetry = search::retry,
                onBack = closeSearch,
            )
            return
        }
        val appContext = applicationContext
        val storeOwner = remember(stationId) { stores.ownerFor(stationId) }
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val stopsModel: StationStopsViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { StationStopsViewModel(stationFinder, stationId, warn = ::logDepartureWarning) }
                },
            )
            val stops by stopsModel.state.collectAsStateWithLifecycle()
            val ready = stops as? StationStopsViewModel.State.Ready
            if (ready == null) {
                StationPlaceholderScreen(
                    title = stationName,
                    state = stops,
                    onRetry = stopsModel::retry,
                    onBack = closeStation,
                )
                return@CompositionLocalProvider
            }
            val viewModel: MainViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MainViewModel(
                            client = KtorTflClient(
                                httpClient,
                                appKey = { UserApiKeySetting.current },
                                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                                requestPool = SharedTflRequestPool.pool,
                                warn = ::logDepartureWarning,
                            ),
                            seedStops = ready.stops,
                            // Stars and dismissals are per row/place across every view, so a star
                            // set here shows on the near-me list too, and the other way round.
                            starredStore = DataStoreStarredRowsStore.from(appContext, warn = ::logStarWarning),
                            dismissedStore = DataStoreDismissedAlertsStore.from(appContext, warn = ::logDepartureWarning),
                            warn = ::logDepartureWarning,
                            arrivalsReuse = ARRIVALS_REUSE,
                            disruptionReuse = DISRUPTION_REUSE,
                            lineStatusReuse = LINE_STATUS_REUSE,
                            rateWaitMillis = { SharedTflRateLimiter.waitedMillis },
                            logStats = ::logDepartureWarning,
                            // Not the widget's list: the near-me model keeps the journey pins.
                            ownsWidgetJourneys = false,
                            // A star set here reorders the widget's pinned rows too, so redraw it.
                            redrawWidget = { StopCastWidget().updateAll(appContext) },
                            writeFailures = writeFailures,
                        )
                    }
                },
            )
            val state by viewModel.state.collectAsStateWithLifecycle()
            val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
            val starred by viewModel.starred.collectAsStateWithLifecycle()
            val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
            val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
            val dismissed by viewModel.dismissed.collectAsStateWithLifecycle()
            val dismissWriteFailed by viewModel.dismissWriteFailed.collectAsStateWithLifecycle()
            // Kept live while shown, like the near-me list; there's no location to re-resolve.
            AutoRefresh(viewModel, NOT_RELOCATING)
            // And refreshed on a return to the foreground, as the near-me list is (by its relocate),
            // so coming back to the app doesn't leave aged departures up until the next tick.
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(lifecycleOwner, viewModel) {
                var returning = false
                lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    if (returning && !viewModel.refreshing.value) viewModel.refresh()
                    returning = true
                }
            }
            CompositionLocalProvider(
                LocalRouteTopology provides routeTopology.value,
                LocalRouteStops provides routeStops,
            ) {
                MainScreen(
                    state = state,
                    now = tickingNow(),
                    onRefresh = { viewModel.refresh() },
                    refreshing = refreshing,
                    starred = starred,
                    onToggleStar = viewModel::toggleStar,
                    starringAvailable = starringAvailable,
                    starWriteFailed = starWriteFailed,
                    onStarWriteFailureShown = viewModel::starWriteFailureShown,
                    dismissed = dismissed,
                    onDismissAlert = viewModel::dismissAlert,
                    dismissWriteFailed = dismissWriteFailed,
                    onDismissWriteFailureShown = viewModel::dismissWriteFailureShown,
                    stationTitle = stationName,
                    onCloseStation = closeStation,
                )
            }
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
        getSharedPreferences("stopcast.location", MODE_PRIVATE)
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

        // The Play listing, Play-app scheme first then the web fallback; the applicationId is
        // appended at open time (see [openPlayListing]).
        private val PLAY_LISTING_URIS = listOf(
            "market://details?id=",
            "https://play.google.com/store/apps/details?id=",
        )

        // Process-scoped: one OkHttp engine and connection pool shared by every ViewModel
        // the process creates, rather than a fresh client leaked per ViewModel (nothing
        // closes a Ktor client, so a per-launch one accumulates engine/pool resources). A
        // single long-lived client is OkHttp's own recommended shape; it lives for the
        // process and dies with it.
        private val httpClient by lazy { KtorTflClient.defaultHttpClient() }

        // "Find a station": the name search and a station's stop lookup, both on demand from the
        // search screen, never on the refresh path. The typed query goes to TfL only (SPEC *Privacy*).
        private val stationFinder by lazy {
            KtorTflClient(
                httpClient,
                appKey = { UserApiKeySetting.current },
                rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                requestPool = SharedTflRequestPool.pool,
            )
        }

        // The station view has no location fix to wait on, so its auto-refresh is never held off by one.
        private val NOT_RELOCATING: StateFlow<Boolean> = MutableStateFlow(false)

        // The route detail's stop lists, cached for the process so reopening a route (or the
        // activity after rotation) shows its stops without refetching. Fetched only when a route
        // page opens — never on the refresh path.
        private val routeStops by lazy {
            RouteStopsRepository(
                source = KtorTflClient(
                    httpClient,
                    appKey = { UserApiKeySetting.current },
                    rateLimiterFor = SharedTflRateLimiter::rateLimiterFor,
                    requestPool = SharedTflRequestPool.pool,
                ),
                warn = { StopcastDebugLog.warning("route stops: %s", it) },
            )
        }
    }
}

/**
 * What a bug report is filed from: the [location] fix and the watched [stops] with their
 * [distanceMeters]. Built from the current nearby state by [bugReportRequestFor] at the moment the
 * user sends (so it survives a rotation mid-consent). [location] is the fix — from [Ready], or the
 * one [Empty]/[Failed] resolved against — and null only where none was obtained (no permission, no
 * fix yet); the report then states the location as unavailable. [stops] is empty off [Ready].
 */
internal data class BugReportRequest(
    val location: Coordinates?,
    val stops: List<StopRef>,
    val distanceMeters: Map<String, Double>,
)

/**
 * The report inputs for the current nearby [state]: the retained fix, all resolved nearby stops
 * (both tiers), and their distances when [NearbyStopsViewModel.State.Ready]; the retained fix alone
 * for [Empty]/[Failed]; otherwise a null-location request. Kept out of composition so the request
 * is rebuilt fresh each time the user sends.
 */
/**
 * Holds whether the bug-report consent dialog is open, in an activity-scoped [ViewModel] so it
 * survives a configuration change (the dialog stays up through a rotation) but resets on process
 * death — where the in-memory location fix is gone anyway, so restoring the dialog would only build
 * a location-unavailable report (Codex P2 on #86). A plain flag, not the report inputs: the
 * coordinate is never persisted (SPEC *Privacy*), so the request is rebuilt from live state at send.
 */
internal class BugReportConsentViewModel : androidx.lifecycle.ViewModel() {
    var open by mutableStateOf(false)
}

/** The build/device header for a bug report — no user data (SPEC *Privacy*). Top-level so the
 *  application-scoped share coroutine doesn't capture the Activity. */
internal fun bugReportHeader(): BugReport.Header = BugReport.Header(
    versionName = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE.toLong(),
    device = "${Build.MANUFACTURER} ${Build.MODEL}",
    androidRelease = Build.VERSION.RELEASE,
    sdkInt = Build.VERSION.SDK_INT,
    capturedAt = Instant.now(),
)

internal fun bugReportRequestFor(state: NearbyStopsViewModel.State): BugReportRequest =
    when (state) {
        is NearbyStopsViewModel.State.Ready ->
            // Every resolved nearby stop (both tiers), so a report after a "More" reveal carries
            // the farther stops too — matching distanceMeters and the consent's "each nearby stop".
            BugReportRequest(state.location, state.nearbyStops, state.distanceMeters)
        // Empty and Failed obtained a fix (the lookup ran) — carry it so a report from those gates
        // says where "no stops nearby" / "can't reach TfL" happened, the context they need.
        is NearbyStopsViewModel.State.Empty ->
            BugReportRequest(state.location, stops = emptyList(), distanceMeters = emptyMap())
        is NearbyStopsViewModel.State.Failed ->
            BugReportRequest(state.location, stops = emptyList(), distanceMeters = emptyMap())
        // PermissionRequired / Locating / NoLocation have no fix to carry.
        else -> BugReportRequest(location = null, stops = emptyList(), distanceMeters = emptyMap())
    }

/**
 * Persists the bug-report "don't ask again" opt-out, guarded. A failed DataStore write is a lost
 * preference, not a crash: consent is simply asked again next time (honest, not a blank — SPEC
 * principle 2), so it is logged sanitized and swallowed rather than propagated out of the UI
 * coroutine (Codex P2 on #86 / *Error handling*). Cancellation rethrows first.
 */
/** The route page's journey tip, once closed this process (whether or not its dismissal was saved). */
internal object JourneyTipSession {
    var closed by mutableStateOf(false)
}

internal suspend fun persistBugReportOptOut(settings: AppSettings) {
    try {
        settings.setSkipBugReportConsent(true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StopcastDebugLog.warning("bug report: consent opt-out not saved: %s", e::class.simpleName)
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
/** The activity's one [WriteFailures], retained across rotation and shared by every departures model. */
internal class WriteFailuresHolder : androidx.lifecycle.ViewModel() {
    val failures = WriteFailures()
}

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

    /**
     * Drop every retained per-set store, canceling any in-flight departures fetch. Called when
     * the departures view is replaced by the location gate (a failed/empty re-locate, or a
     * retry): otherwise a recovery to the same stop IDs would reuse the retained [MainViewModel],
     * whose `init` doesn't re-run (and a foreground return only re-locates when already Ready), so
     * the pre-gate departures would return without the re-fetch the user asked for, stale until
     * the next auto-refresh (Codex).
     */
    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }

    override fun onCleared() = clearAll()
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
 * Extracted from `onCreate` so the wrapper is unit-testable: `StopCastAppRootTest` asserts the
 * content color inside it is `onSurface`, which fails if the Surface is dropped — the existing
 * `LocationGateScreenshotTest` can't catch that, since it installs its own Surface.
 */
@Composable
internal fun StopCastAppRoot(content: @Composable () -> Unit) {
    StopCastTheme {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

/**
 * The near-me area's overlay host. [aboveOverlay] composes **unconditionally** — regardless of
 * whether a Settings/Licenses overlay is open — while [overlayContent] (the open overlay) and [body]
 * (the location gate or the departures view) switch on [overlayOpen]. Production puts the
 * foreground-return observer ([ForegroundReturnLatcher]) in [aboveOverlay] so a return that lands
 * while an overlay is open is still observed and latched — the departures view that consumes the
 * latch is out of composition then (#136). Extracted as the real host so [ForegroundReturnTest] can
 * render it and pin that the above-overlay slot survives the overlay, rather than reconstructing the
 * topology in the test.
 */
@Composable
internal fun NearbyArea(
    overlayOpen: Boolean,
    aboveOverlay: @Composable () -> Unit,
    overlayContent: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    aboveOverlay()
    if (overlayOpen) overlayContent() else body()
}

/**
 * Holds the one bit "a background→foreground return is pending re-location," retained across a
 * configuration change but reset on process recreation. A rotation while a Settings/Licenses overlay
 * is still open must not drop a latched return — the recreated observer skips its first foreground,
 * so the reopened departures view would show the pre-move set. A plain `remember` drops it on
 * rotation; `rememberSaveable` would wrongly carry it through process death, where the ViewModel
 * init's own reload already covers the return. A retained ViewModel is exactly "survive a config
 * change, die with the process" (Codex).
 */
internal class ForegroundReturnLatch : androidx.lifecycle.ViewModel() {
    var pending by mutableStateOf(false)
}

/**
 * Observes the activity lifecycle for a genuine background→foreground return and, when the near-me
 * set is already [isReady], calls [onReturn] to latch a pending re-location (SPEC *Finding stops* /
 * D6). Hosted **above** the Settings/Licenses overlay switch so a return that lands while an overlay
 * is open is still seen — the departures view that consumes the latch is out of composition then, so
 * an observer hosted there would miss the return entirely. The first foreground per activity instance
 * is skipped (the ViewModel init covers it, and a rotation restarts this with its own skip), and
 * [isBusy] gates a return that lands mid-relocate. Extracted (with [ConsumeForegroundReturn]) so the
 * observer/overlay/consume wiring is exercised by a test rather than restated — a regression that
 * moved this back inside the departures view leaves the latch unset while an overlay is open.
 */
@Composable
internal fun ForegroundReturnLatcher(
    isReady: () -> Boolean,
    isBusy: () -> Boolean,
    onReturn: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Read the latest lambdas without re-keying the effect (a fresh lambda each recomposition would
    // restart it and reset the first-foreground skip).
    val currentIsReady = rememberUpdatedState(isReady)
    val currentIsBusy = rememberUpdatedState(isBusy)
    val currentOnReturn = rememberUpdatedState(onReturn)
    LaunchedEffect(lifecycleOwner) {
        refreshOnForeground(lifecycleOwner.lifecycle, isBusy = { currentIsBusy.value() }) {
            if (currentIsReady.value()) currentOnReturn.value()
        }
    }
}

/**
 * Consumes a latched foreground return: when [pending] flips true, re-locate via [onRelocate] (unless
 * [isBusy] — an in-flight relocate already covers it) and clear the latch via [onConsumed]. Composed
 * inside the departures view, so a latch set by [ForegroundReturnLatcher] while this view was out of
 * composition (an overlay) fires the moment it re-enters. [onRelocate] is read latest so a moved-to
 * set's fresh action is used.
 */
@Composable
internal fun ConsumeForegroundReturn(
    pending: Boolean,
    isBusy: () -> Boolean,
    onConsumed: () -> Unit,
    onRelocate: () -> Unit,
) {
    val currentIsBusy = rememberUpdatedState(isBusy)
    val currentOnRelocate = rememberUpdatedState(onRelocate)
    LaunchedEffect(pending) {
        if (pending) {
            if (!currentIsBusy.value()) currentOnRelocate.value()
            onConsumed()
        }
    }
}

/**
 * The re-locate action shared by the refresh control and the foreground return (SPEC *Finding
 * stops*): cancel any in-flight departures fetch first — so it can't finish and re-stamp the old
 * set as fresh during the fix window (Codex) — then force a fresh fix that re-resolves the nearby
 * set and [reconcile]s the confirmed same set in place. Extracted so the wiring that a refresh and
 * a reopen **re-locate** — rather than a departures-only [MainViewModel.refresh] — is pinned by a
 * unit test: a regression back to a location-free refresh would neither cancel first nor re-resolve.
 */
internal fun relocateAction(
    cancelFetch: () -> Unit,
    relocate: (onSameSet: (NearbyStopsViewModel.State.Ready) -> Unit) -> Unit,
    reconcile: (NearbyStopsViewModel.State.Ready) -> Unit,
): () -> Unit = {
    cancelFetch()
    relocate(reconcile)
}

/**
 * Runs [onForeground] each time [lifecycle] re-enters STARTED **except the first**: the
 * first foreground is the initial load (done in the ViewModel's `init`, and re-run on a
 * fresh activity after process death), and a configuration change restarts this with its
 * own first-skip, so neither path double-fetches while a genuine return from the
 * background does refresh (SPEC D6). Extracted so the skip-first/return-again rule is
 * unit-testable off a device.
 */
internal suspend fun refreshOnForeground(
    lifecycle: Lifecycle,
    isBusy: () -> Boolean = { false },
    onForeground: () -> Unit,
) {
    var firstForeground = true
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        if (firstForeground) firstForeground = false else if (!isBusy()) onForeground()
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
 * [app.stopcast.AutoRefreshTest].
 */
internal const val AUTO_REFRESH_MILLIS = 60_000L

/**
 * Drives [autoRefresh] from the activity's lifecycle. [relocating] joins the busy gate so the
 * one-minute tick doesn't fire `refresh()` on the current (soon-to-be-previous) set while a
 * manual re-locate's fix is still in flight — which would fetch and re-stamp the old location's
 * departures in parallel with the fix, exactly the parallel-fetch the manual path already avoids
 * (Codex). Once the relocate resolves the same-set refresh (or a new set's own fetch) takes over.
 */
@Composable
private fun AutoRefresh(viewModel: MainViewModel, relocating: StateFlow<Boolean>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed on [viewModel] too: a moved-to relocate replaces the per-set model under this
    // still-composed effect, and a lifecycle-only key would leave the timer ticking the cleared
    // old model while the new set never auto-refreshed and went stale (Codex).
    LaunchedEffect(lifecycleOwner, viewModel) {
        autoRefresh(
            lifecycleOwner.lifecycle,
            isRefreshing = { viewModel.refreshing.value || relocating.value },
        ) { viewModel.refresh(automatic = true) }
    }
}

/**
 * A clock that advances on screen so countdowns and the freshness stamp recompute
 * without a new fetch (SPEC D4). Ten seconds is enough to keep "N min"/"0 min" honest
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
 * the shipped app. Routes to [StopcastDebugLog] — the shared diagnostic log, which fans out
 * to Logcat and the on-device persisted file (`docs/PRIVACY.md`). It never leaves the device;
 * a user-shareable export with travel data redacted is a later change.
 *
 * Top-level, not an Activity method: a `MainActivity::` method reference is held by the
 * ViewModels it's passed to, and an activity-scoped ViewModel outlives the Activity across
 * configuration changes — so a bound reference would pin each destroyed Activity in the
 * ViewModel store (Codex). A top-level function captures nothing.
 */
private fun logLocationWarning(message: String) = StopcastDebugLog.warning("location: %s", message)

/**
 * The process-wide nearby-lookup cache: top-level so it outlives an Activity or ViewModel (a
 * rotation, a relocation), backed by a file in the app's cache directory (never backed up) so it
 * also survives the process. Built on first use; it reads the file lazily, on the IO lookup path.
 */
private val nearbyStopsCacheLock = Any()
private var nearbyStopsCacheInstance: NearbyStopsCache? = null

private fun nearbyStopsCache(context: Context): NearbyStopsCache = synchronized(nearbyStopsCacheLock) {
    nearbyStopsCacheInstance ?: NearbyStopsCache(
        FileNearbyStopsStore(File(context.applicationContext.cacheDir, "nearby-stops.json"), warn = ::logLocationWarning),
    ).also { nearbyStopsCacheInstance = it }
}

/**
 * The production sink for the departures/disruption seam's warnings. Without it wired,
 * `MainViewModel`'s `warn` defaulted to a no-op, so a persistent "couldn't check for
 * disruptions" left nothing in logcat to explain which line or lookup was unknown. The
 * messages are coarse — a count, a line id, an HTTP reason — with no coordinate, stop-set,
 * or key (SPEC *Privacy*: line ids are allowed). Routes to [StopcastDebugLog] like
 * [logLocationWarning], top-level for the same no-Activity-capture reason.
 */
private fun logDepartureWarning(message: String) = StopcastDebugLog.warning("departures: %s", message)

/**
 * The production sink for the starred-rows store's warnings — a discarded corrupt star file,
 * or a preserved newer-schema file. Without it wired the store defaulted to a no-op, so those
 * recovery paths left nothing in logcat. The messages are coarse facts (no stop/line id is
 * needed and none is logged); routed to [StopcastDebugLog] like [logLocationWarning], for the
 * same no-Activity-capture reason.
 */
private fun logStarWarning(message: String) = StopcastDebugLog.warning("stars: %s", message)

/**
 * The production sink for the Play update checker's warnings — a failed availability fetch.
 * Coarse and PII-free (an exception class name, no user data); routed to [StopcastDebugLog]
 * like [logLocationWarning], for the same no-Activity-capture reason.
 */
private fun logUpdateWarning(message: String) = StopcastDebugLog.warning("update: %s", message)

/** One read of the saved journeys: [journeys] is null when the store couldn't be read. */
private class JourneysRead(val journeys: List<StarredJourney>?)
