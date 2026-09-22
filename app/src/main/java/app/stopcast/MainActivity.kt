package app.stopcast

import android.Manifest
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.Lifecycle
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
import app.stopcast.data.DataStoreStarredRowsStore
import app.stopcast.data.KtorTflClient
import app.stopcast.data.RouteTopologyStore
import app.stopcast.data.SharedTflRateLimiter
import app.stopcast.domain.AppSettings
import app.stopcast.domain.BugReport
import app.stopcast.domain.Coordinates
import app.stopcast.ui.BugReportConsentDialog
import app.stopcast.ui.FontSizeSetting
import app.stopcast.ui.LocalRouteTopology
import app.stopcast.ui.LicensesScreen
import app.stopcast.ui.LocationGate
import app.stopcast.ui.MainScreen
import app.stopcast.ui.MainViewModel
import app.stopcast.ui.NearbyStopsViewModel
import app.stopcast.ui.SettingsScreen
import app.stopcast.ui.StopRef
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
import kotlinx.coroutines.flow.StateFlow
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

class MainActivity : ComponentActivity() {
    // The location gate: resolves the nearby stops (an on-demand, location-sending action)
    // before the departures view, which then refreshes those stops location-free.
    private val nearbyViewModel: NearbyStopsViewModel by viewModels {
        viewModelFactory {
            initializer {
                NearbyStopsViewModel(
                    location = AndroidLocationProvider(applicationContext, warn = ::logLocationWarning),
                    finder = KtorTflClient(httpClient, rateLimiter = SharedTflRateLimiter.instance),
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
        FontSizeSetting.warm(DataStoreAppSettings.from(applicationContext, warn = ::logAppSettingsWarning))
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
                // composition rather than polling TfL behind a static screen.
                when {
                    licensesOpen -> LicensesScreen(onBack = { licensesOpen = false })
                    settingsOpen -> SettingsScreen(
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
                        onBack = { settingsOpen = false },
                    )
                    else -> when (val state = nearby) {
                        is NearbyStopsViewModel.State.Ready ->
                            DeparturesForStops(
                                ready = state,
                                relocate = { onSameSet -> nearbyViewModel.relocate(onSameSet) },
                                relocating = nearbyViewModel.relocating,
                                onOpenLicenses = openLicenses,
                                onOpenSettings = { settingsOpen = true },
                                updateAvailable = updateAvailable.value,
                                onOpenAppListing = ::openPlayListing,
                                onSendBugReport = requestBugReport,
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
                            )
                        }
                    }
                }

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
        onOpenLicenses: () -> Unit,
        onOpenSettings: () -> Unit,
        // Play reports a newer version — the overflow gets its red dot and "Update available"
        // item. Threaded from the activity's [updateAvailable] state, refreshed on each resume.
        updateAvailable: Boolean,
        onOpenAppListing: () -> Unit,
        // Overflow "Send bug report": built at the Ready branch so it captures the fix + distances.
        onSendBugReport: () -> Unit,
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
        CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
            val viewModel: MainViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MainViewModel(
                            client = KtorTflClient(httpClient, rateLimiter = SharedTflRateLimiter.instance),
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
                            warn = ::logDepartureWarning,
                            // Re-render the widget when a star changes (its pinned order — SPEC
                            // D8) or after a refresh that didn't save, so its age/staleness stays
                            // current rather than frozen at the last save (SPEC D4).
                            redrawWidget = { StopCastWidget().updateAll(appContext) },
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
            val starred by viewModel.starred.collectAsStateWithLifecycle()
            val starringAvailable by viewModel.starringAvailable.collectAsStateWithLifecycle()
            val starWriteFailed by viewModel.starWriteFailed.collectAsStateWithLifecycle()
            // The "More" buttons to offer — modes with a farther cluster still to page in.
            val revealableModes by viewModel.moreState.collectAsStateWithLifecycle()
            // These background refreshes are composed only while the departures view is shown:
            // the licenses screen is hosted above this subtree (see onCreate), so opening it
            // removes DeparturesForStops from composition and stops the polling (Codex).
            RefreshOnForeground(viewModel, relocating)
            AutoRefresh(viewModel, relocating)
            // Provide the branch topology so the card groups a branching row the way the widget
            // does — equivalent trunks merged, the label kept only where the trunk is a choice
            // ahead of the stop (see DepartureRows.destinationLines).
            CompositionLocalProvider(LocalRouteTopology provides routeTopology.value) {
                MainScreen(
                    state = state,
                    now = tickingNow(),
                    // Refresh re-locates first; the departures re-fetch is sequenced inside
                    // relocate and runs only for the confirmed same set (a moved-to set's new
                    // ViewModel fetches on its own init), so we never fetch the old set in
                    // parallel with the fix. Cancel any fetch already in flight (initial,
                    // foreground, or timed) before the fix starts, so it can't finish first and
                    // save a fresh snapshot for the old set during the fix window (Codex).
                    onRefresh = {
                        viewModel.cancelFetch()
                        relocate { fresh -> viewModel.reconcile(fresh.eager, fresh.more) }
                    },
                    refreshing = refreshing,
                    // From "near me now": collapse a line served by several adjacent nearby stops
                    // to its nearest stop (SPEC *Finding stops → Near me now*). Spans both tiers, so
                    // a revealed stop collapses like an eager one. Empty for a location-free list
                    // (a watched-stops view), which is shown as-is.
                    stopDistanceMeters = ready.distanceMeters,
                    starred = starred,
                    onToggleStar = viewModel::toggleStar,
                    starringAvailable = starringAvailable,
                    starWriteFailed = starWriteFailed,
                    onStarWriteFailureShown = viewModel::starWriteFailureShown,
                    onOpenLicenses = onOpenLicenses,
                    onOpenSettings = onOpenSettings,
                    updateAvailable = updateAvailable,
                    onOpenAppListing = onOpenAppListing,
                    revealableModes = revealableModes,
                    // Ignore a "More" tap while a relocation's fresh fix is in flight, so it can't
                    // page the pre-fix set as current (matches the cancel-on-relocate discipline).
                    onReveal = { mode -> if (!relocatingNow) viewModel.reveal(mode) },
                    onSendBugReport = onSendBugReport,
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
     * whose `init` doesn't re-run and whose [RefreshOnForeground] skips its first foreground, so
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
 * Refresh when the user returns to the foregrounded screen (SPEC D6: refresh on open),
 * so they don't come back from Recents to withheld stale countdowns and have to refresh
 * by hand. The ViewModel's `init` does the first load and survives configuration change,
 * so the **first** foreground per activity instance is skipped — only a genuine return
 * from the background triggers a re-fetch, never a duplicate of the initial request nor
 * a refetch on rotation. (Lifecycle wiring: verified by inspection, wants a device check.)
 *
 * [relocating] joins the busy gate (as it does for [AutoRefresh]): a foreground return while a
 * manual re-locate's fix is in flight must not refresh the current (soon-to-be-previous) set and
 * save it as a fresh snapshot before the fix resolves — the relocate's own resolution refreshes
 * the confirmed set, replaces a moved-to one, or shows the gate (Codex).
 */
@Composable
private fun RefreshOnForeground(viewModel: MainViewModel, relocating: StateFlow<Boolean>) {
    val lifecycleOwner = LocalLifecycleOwner.current
    // Keyed on [viewModel], not just the lifecycle owner: a moved-to relocate swaps in a fresh
    // per-set MainViewModel while this composable stays composed (DeparturesForStops recomposes
    // in place — no key() wrapper), so an effect keyed on the lifecycle alone would keep calling
    // refresh() on the cleared previous model while the new set never got a foreground refresh
    // (Codex). Re-keying restarts the effect against the replacement model — and resets its
    // first-foreground skip, which the new set's own init fetch stands in for.
    LaunchedEffect(lifecycleOwner, viewModel) {
        refreshOnForeground(lifecycleOwner.lifecycle, isBusy = { relocating.value }) { viewModel.refresh() }
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
    // Keyed on [viewModel] too (see RefreshOnForeground): a moved-to relocate replaces the per-set
    // model under this still-composed effect, and a lifecycle-only key would leave the timer
    // ticking the cleared old model while the new set never auto-refreshed and went stale (Codex).
    LaunchedEffect(lifecycleOwner, viewModel) {
        autoRefresh(
            lifecycleOwner.lifecycle,
            isRefreshing = { viewModel.refreshing.value || relocating.value },
        ) { viewModel.refresh() }
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
