@file:OptIn(ExperimentalMaterial3Api::class)

package app.stopcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopcast.R
import app.stopcast.domain.cleanDisruptionBody
import app.stopcast.domain.Countdown
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DestinationAbbreviations
import app.stopcast.domain.DismissedAlert
import app.stopcast.domain.PlatformDirection
import app.stopcast.domain.RelativeTime
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopDistance
import app.stopcast.domain.StopGrouping
import app.stopcast.domain.abbreviateBranch
import app.stopcast.ui.theme.LocalStarredBorderColor
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

/** Test tag on the red "update available" dot overlaying the overflow menu icon. */
internal const val UPDATE_AVAILABLE_DOT_TAG = "update_available_dot"

/**
 * The departures view (SPEC D8): a flat list, one row per (service, stop, direction),
 * soonest-first across the watched stops. Pure — it renders only [state] and the
 * caller-supplied [now], with no I/O in composition (SPEC jank-free UI), so the same
 * function drives the app and the screenshot tests.
 *
 * The rows are recomputed from the snapshot's raw stops against [now], not cached from
 * fetch time, so a departed service leaves the list and the ordering advances as the
 * clock ticks (SPEC D4). Once the snapshot is [Staleness]-stale the countdowns are
 * withheld — the prediction set is likely wrong, so the honest answer is "refresh"
 * rather than live-looking numbers.
 */
@Composable
fun MainScreen(
    state: DeparturesUiState,
    now: Instant,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    // From "near me now" (`stopId` → meters): collapse a line served by several adjacent
    // nearby stops to its nearest stop. Empty for a location-free list, shown unchanged.
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    // The rows the user has starred (SPEC D8): pinned to the top, and their star filled.
    // Empty by default so an unwired build/test renders the plain soonest-first list.
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    // False only when the stored star set is a newer-schema file this build can't read: the
    // star control is then hidden rather than shown unfilled (which would falsely read as
    // "nothing starred"). Defaults true, the normal case.
    starringAvailable: Boolean = true,
    // True while a star write has failed and not yet been surfaced (SPEC principle 2): the
    // screen shows a transient snackbar so a tap that didn't take isn't swallowed silently,
    // then calls [onStarWriteFailureShown] to clear it. Acknowledged state, not a one-shot
    // event, so a rotation between the failed tap and the snackbar doesn't drop it. False by
    // default so an unwired build/test renders no snackbar.
    starWriteFailed: Boolean = false,
    onStarWriteFailureShown: () -> Unit = {},
    // The stop-closure alerts the user has dismissed (SPEC *Disruptions*): the matching cards are
    // hidden until their notice text changes. Empty by default so an unwired build/test shows every
    // alert. [onDismissAlert] is called with the alert's stop-status row when its dismiss is tapped.
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
    // True while a dismiss write has failed and not yet been surfaced (SPEC principle 2): same
    // snackbar seam as [starWriteFailed], so a dismiss tap that didn't persist isn't swallowed
    // silently. Acknowledged state; the screen calls [onDismissWriteFailureShown] to clear it.
    dismissWriteFailed: Boolean = false,
    onDismissWriteFailureShown: () -> Unit = {},
    // Open the open-source licenses screen (from the About dialog). Default no-op so an
    // unwired build/test renders the screen without a licenses destination.
    onOpenLicenses: () -> Unit = {},
    // Open the Settings screen (from the overflow menu). Default no-op so an unwired build/test
    // renders the screen without a settings destination.
    onOpenSettings: () -> Unit = {},
    // True when Google Play reports a newer version is available: the overflow icon gets a red
    // dot and the menu gains an "Update available" item. Off by default (and in debug — see
    // PlayUpdateChecker), so the common build/test renders the plain overflow.
    updateAvailable: Boolean = false,
    // Open the Play Store listing (from the "Update available" item). Default no-op.
    onOpenAppListing: () -> Unit = {},
    // The modes (or the generic bucket, an empty string) that still have a farther "More" cluster
    // to page in (SPEC *Finding stops → Near me now*). A "More …" button per mode is shown at the
    // foot of the near-me list. Empty by default so a location-free or fully-revealed list shows
    // none. [onReveal] is called with the tapped mode.
    revealableModes: Set<String> = emptySet(),
    onReveal: (String) -> Unit = {},
    // Start the consent-gated bug report (from the overflow). Default no-op so an unwired
    // build/test renders the menu without one.
    onSendBugReport: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Overflow-menu and About-dialog visibility. Saved so an open dialog survives rotation.
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val starWriteFailedMessage = stringResource(R.string.star_write_failed)

    // The rows the screen renders, grouped against the live clock (SPEC D4) — computed once here so
    // both the list and the route-detail page below read the SAME rows. Empty for any non-Loaded
    // state. Cheap and pure; line statuses stamp each row so a disrupted line is marked (SPEC D3).
    val loaded = state as? DeparturesUiState.Loaded
    val rows = remember(loaded?.stops, loaded?.lineStatuses, now, stopDistanceMeters, starred, dismissed) {
        val ld = loaded ?: return@remember emptyList()
        val across = DepartureRows.across(ld.stops, now, ld.lineStatuses)
        // A "near me now" list (distances present) shows a line once, from its nearest stop, then
        // orders closest-stop-first (soonest breaks a same-stop tie). A location-free list keeps
        // across's soonest-first order (D1).
        val ordered =
            if (stopDistanceMeters.isEmpty()) {
                across
            } else {
                val deduped = DepartureRows.nearbyDeduped(across, stopDistanceMeters)
                DepartureRows.byStopDistance(deduped, stopDistanceMeters)
            }
        // Drop the stop-closure alerts the user has dismissed (hidden until their text changes),
        // then lift the user's starred services to the top (SPEC D8), warnings still leading.
        DepartureRows.pinStarred(DepartureRows.withoutDismissed(ordered, dismissed), starred)
    }

    // The route whose detail is open, held by its stable row identity rather than the row object: a
    // saveable String survives a configuration change (the page stays open on rotation) and resets on
    // process death, and it re-resolves against the current `rows` each recomposition so the page
    // reflects a refreshed row and closes itself if the row leaves the list — the coordinate it shows
    // is never persisted (mirrors the bug-report flow).
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    val detailRow = detailKey?.let { key -> rows.firstOrNull { it.detailKey() == key } }
    // When the open route's row leaves the list — its last departure passed on the 10s clock, or it
    // was pruned — clear the saved key so the vanished page stays closed rather than silently
    // reopening if a later refresh reproduced that same stop/line/direction identity (Codex). A live
    // refresh keeps the same identity, so this fires only on a genuine disappearance.
    LaunchedEffect(detailKey, detailRow == null) {
        if (detailKey != null && detailRow == null) detailKey = null
    }
    // Surface a failed star write as a snackbar (SPEC principle 2: a tap that didn't take isn't
    // swallowed). Gated on the list being shown — the snackbar host lives in the departures Scaffold,
    // not the full-screen route page below, so a failure while the page is open holds the flag until
    // the user returns, then shows, rather than firing at a host that isn't composed. Clear first,
    // then show, so a rotation while it's visible doesn't re-trigger it.
    LaunchedEffect(starWriteFailed, detailRow == null) {
        if (starWriteFailed && detailRow == null) {
            onStarWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
    }
    // Surface a failed dismiss write the same way as a failed star write, and gated the same way —
    // the snackbar host lives in the departures Scaffold, not the full-screen route page below, so a
    // failure while the page is open holds the flag until the user returns, then shows.
    val dismissWriteFailedMessage = stringResource(R.string.dismiss_write_failed)
    LaunchedEffect(dismissWriteFailed, detailRow == null) {
        if (dismissWriteFailed && detailRow == null) {
            // Clear-then-show, same reasoning as the star-write snackbar above.
            onDismissWriteFailureShown()
            snackbarHostState.showSnackbar(dismissWriteFailedMessage)
        }
    }
    // A full-screen page (its own app bar) that REPLACES the departures Scaffold, so it covers the
    // top bar and reads as a real destination rather than an overlay — the home for the star and the
    // line's full disruption text, and where the maps/nav hand-off will land (`TODO.md`).
    if (loaded != null && detailRow != null) {
        RouteDetailScreen(
            row = detailRow,
            isStarred = StarredRow.of(detailRow) in starred,
            // Same rule as the list card: only a timed row with starring available is pinnable.
            starrable = starringAvailable && detailRow.stopDisruption == null && detailRow.upcoming.isNotEmpty(),
            // Per this row, across BOTH uncertainty axes: its line wasn't determined (a blank id, or
            // one TfL omitted), OR its stop's own disruption lookup (a closure/move) failed. Either
            // leaves the row unchecked — clean only when TfL checked its line AND its stop's
            // disruption returned (SPEC principle 1).
            disruptionUnknown = detailRow.lineId.isBlank() ||
                detailRow.lineId !in loaded.determinedLineIds ||
                detailRow.stopId in loaded.stopsDisruptionUnknown,
            // This row's own age (the same per-row rule the card uses to withhold countdowns): a
            // stale snapshot's disruption status isn't presented as current (SPEC D4).
            stale = Staleness.isStale(Duration.between(detailRow.fetchedAt, now).toKotlinDuration()),
            onToggleStar = { onToggleStar(detailRow) },
            onBack = { detailKey = null },
        )
        return
    }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                navigationIcon = {
                    // The app's route-lines mark on a themed tile — white in light, black in
                    // dark — so it sits on the app bar without a fixed dark box. The arrow is a
                    // separate, tintable layer flipped to contrast the tile (dark on white, white
                    // on black); the colored lines stay put. The marks fill only the ~72/108
                    // adaptive-icon safe zone, so requiredSize(48dp) draws them larger than the
                    // 32dp box and the box clips back, rather than sitting tiny in the middle.
                    // Decorative — the title already names the app, so no content description.
                    // Follow the active Material theme (which may be dark from the system or an
                    // explicit override), not the raw system setting, so the tile matches the
                    // surface it sits on.
                    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (darkTheme) Color.Black else Color.White),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_appbar_route_lines),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(48.dp),
                        )
                        Image(
                            painter = painterResource(R.drawable.ic_appbar_route_arrow),
                            contentDescription = null,
                            modifier = Modifier.requiredSize(48.dp),
                            colorFilter = ColorFilter.tint(if (darkTheme) Color.White else Color.Black),
                        )
                    }
                },
                actions = {
                    FreshnessStamp(state, now, onRefresh)
                    // Refresh re-resolves the nearby set (a fresh location fix) as well as
                    // re-fetching departures, so walking to the next stop and pulling to refresh
                    // updates both — there's no separate locate control (SPEC *Finding stops*).
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
                    }
                    // Button and menu wrapped together so the dropdown anchors to the overflow
                    // button and opens from it; a bare DropdownMenu sibling anchors to the row
                    // slot instead and drops from the wrong place.
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Box {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                                if (updateAvailable) {
                                    val updateDescription = stringResource(R.string.update_available)
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            // Off-grid 2dp: an optical nudge seating the dot into
                                            // the icon's top-right corner (the standard badge spot).
                                            .offset(x = 2.dp, y = (-2).dp)
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape)
                                            .semantics { contentDescription = updateDescription }
                                            .testTag(UPDATE_AVAILABLE_DOT_TAG),
                                    )
                                }
                            }
                        }
                        // The menu opens its own window, which doesn't inherit the theme's scaled
                        // density or pinch handler — FontSizeWindow re-applies the chosen size to
                        // the items and pinchFontSizeHost lets a pinch resize while it's open, so
                        // the size setting reaches the menu too (SPEC *Display size*). The host
                        // consumes only a two-finger pinch, so item taps are unaffected.
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier.pinchFontSizeHost(),
                        ) {
                            FontSizeWindow {
                                if (updateAvailable) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.update_available)) },
                                        onClick = {
                                            menuExpanded = false
                                            onOpenAppListing()
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    onClick = {
                                        menuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_send_bug_report)) },
                                    onClick = {
                                        menuExpanded = false
                                        onSendBugReport()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_about)) },
                                    onClick = {
                                        menuExpanded = false
                                        showAbout = true
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val content = Modifier.fillMaxSize().padding(innerPadding)
        when (state) {
            DeparturesUiState.Loading -> Centered(content) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.departures_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            is DeparturesUiState.Loaded ->
                LoadedContent(
                    state, now, onRefresh, refreshing, content, rows, stopDistanceMeters,
                    starred, onToggleStar, starringAvailable, revealableModes, onReveal,
                    onOpenDetail = { detailKey = it.detailKey() },
                    dismissed = dismissed,
                    onDismissAlert = onDismissAlert,
                )

            is DeparturesUiState.Error ->
                // Under the pull box with a scrollable child so a downward swipe refreshes
                // the error screen too (SPEC D6), not only the button.
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = content) {
                    Centered(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(errorMessage(state.kind)),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                    }
                }
        }
    }
    if (showAbout) {
        AboutDialog(
            onOpenLicenses = {
                showAbout = false
                onOpenLicenses()
            },
            onDismiss = { showAbout = false },
        )
    }
}

@Composable
private fun LoadedContent(
    state: DeparturesUiState.Loaded,
    now: Instant,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier,
    // The rows to render, computed once by the caller so the list and the route-detail page share
    // the same grouping/ordering (SPEC D4 / D8).
    rows: List<DepartureRow>,
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    starringAvailable: Boolean = true,
    revealableModes: Set<String> = emptySet(),
    onReveal: (String) -> Unit = {},
    // Open the full-screen route detail for a tapped card; the caller holds the open-route state.
    onOpenDetail: (DepartureRow) -> Unit = {},
    dismissed: Set<DismissedAlert> = emptySet(),
    onDismissAlert: (DepartureRow) -> Unit = {},
) {
    // Whether an empty list can be trusted as a real "no departures". It can only when
    // EVERY retained stop is fresh and the refresh was complete: a stale or un-refreshed
    // stop's empty rows might be expired predictions, not a true absence, and newer
    // services we couldn't fetch may exist (SPEC D4 / principle 1). So this reads every
    // stop's age and the partial/failure flags — not the freshest-stop stamp, which would
    // let one fresh stop mask a stale one's uncertainty. Per-row staleness (the withhold)
    // is decided per stop inside the card from that row's own age.
    val emptyStateUncertain = remember(state.stops, state.fetchedAt, state.partialRefresh, state.refreshFailure, now) {
        state.refreshFailure != null ||
            state.partialRefresh ||
            state.stops.any {
                Staleness.isStale(Duration.between(it.fetchedAt, now).toKotlinDuration())
            } ||
            // No retained stops to age individually — fall back to the snapshot stamp, so an
            // aged empty snapshot (e.g. one restored from storage) still prompts a refresh.
            (state.stops.isEmpty() && Staleness.isStale(Duration.between(state.fetchedAt, now).toKotlinDuration()))
    }
    // Pull-to-refresh over the whole loaded surface (SPEC D6).
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = modifier) {
        Column(Modifier.fillMaxSize()) {
            // Independent, not exclusive: a kept snapshot can be BOTH incomplete (a stop
            // was missing) and failed-to-refresh, and both facts have to stay visible —
            // collapsing them into one branch would drop the "stops missing" warning and
            // let the omitted stops go silent (SPEC principle 2).
            if (state.refreshFailure != null) {
                Banner(stringResource(refreshFailureMessage(state.refreshFailure)))
            }
            if (state.partialRefresh) {
                Banner(stringResource(R.string.partial_refresh))
            }
            // Arrivals loaded but their disruption status couldn't be checked — say so
            // rather than let the times read as verified-clean (SPEC *Disruptions*).
            if (state.disruptionUnknown) {
                Banner(stringResource(R.string.disruptions_unknown))
            }
            if (rows.isEmpty()) {
                // Scrollable even though it doesn't overflow: PullToRefreshBox reads the
                // pull from a scrollable child's nested-scroll events, so a plain Column
                // here would leave pull-to-refresh dead on the empty state (only the
                // button would work). verticalScroll forwards the gesture; the content
                // still centers.
                Centered(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // A stale snapshot with nothing left can't be read as "no departures"
                    // — the data is too old to trust that conclusion, and newer ones may
                    // exist (SPEC D4). Prompt a refresh instead of asserting an empty list.
                    Text(
                        text = stringResource(
                            if (emptyStateUncertain) R.string.departures_stale_empty else R.string.departures_empty,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                    // Keep "More" reachable even when the nearest clusters returned nothing — that's
                    // exactly when the farther ones are most useful (SPEC principle 2).
                    MoreControls(revealableModes, onReveal, Modifier.padding(top = 16.dp))
                }
            } else {
                DepartureList(
                    rows, now, starred, onToggleStar, starringAvailable, stopDistanceMeters,
                    revealableModes, onReveal,
                    onOpenDetail = onOpenDetail,
                    onDismissAlert = onDismissAlert,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun FreshnessStamp(state: DeparturesUiState, now: Instant, onRefresh: () -> Unit) {
    val text = when (state) {
        // The placeholder frame (before any snapshot is read from disk) still carries a
        // stamp — a pending "Loading…" — so the top bar is present from the first frame and
        // fills in with the real age when the snapshot arrives (SPEC snapshot-render), rather
        // than the stamp popping in late.
        DeparturesUiState.Loading -> stringResource(R.string.loading_stamp)
        is DeparturesUiState.Loaded -> {
            val age = Duration.between(state.fetchedAt, now).toKotlinDuration()
            if (Staleness.isStale(age)) stringResource(R.string.stale_stamp)
            else stringResource(R.string.updated_stamp, RelativeTime.formatAge(age))
        }
        // An error has its own full-screen message (no snapshot, so no age to stamp).
        is DeparturesUiState.Error -> return
    }
    // The stamp is tappable too, so the "Tap to refresh" it shows when stale does what
    // it says (the Refresh action beside it is the always-present control).
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable(onClick = onRefresh).padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

@Composable
private fun Banner(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DepartureList(
    rows: List<DepartureRow>,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    stopDistanceMeters: Map<String, Double>,
    revealableModes: Set<String>,
    onReveal: (String) -> Unit,
    onOpenDetail: (DepartureRow) -> Unit,
    onDismissAlert: (DepartureRow) -> Unit = {},
    modifier: Modifier,
) {
    // Stop-closure alerts render as standalone cards at the top of the list — warnings lead (the
    // caller ordered them first). Each carries its own place heading (its interchange, else its
    // stop), so it needs no group header and no distance label above it (SPEC *Disruptions*). The
    // remaining rows (timed and line-status) cluster into per-place groups so
    // each gets a name header — the flat list gives a card no boarding location once >1 place is
    // on screen (SPEC D8). Pure and cheap; the caller ordered the rows.
    val closureRows = remember(rows) { rows.filter { it.stopDisruption != null } }
    // Pass the full row set: groupByStop groups only the non-closure rows but counts each closure
    // alert's stop as a place, so a lone departures group beside a closure-only stop still shows
    // its header (SPEC *Disruptions*).
    val groups = remember(rows) { StopGrouping.groupByStop(rows) }
    // One place-wide header distance per cluster: the nearest of ALL the place's members, shared by
    // every direction group of that place — so a station split into direction headers shows one
    // consistent distance (its nearest member), not each direction's own members' nearest, which
    // could differ or read farther than the place's nearest (Codex P2, PR #109). Empty on the
    // watched list (no distances, D1).
    val placeDistanceMeters = remember(groups, stopDistanceMeters) {
        if (stopDistanceMeters.isEmpty()) {
            emptyMap()
        } else {
            groups.groupBy { it.placeKey }.mapNotNull { (place, placeGroups) ->
                placeGroups.asSequence()
                    .flatMap { it.rows.asSequence() }
                    .mapNotNull { stopDistanceMeters[it.stopId] }
                    .minOrNull()
                    ?.let { place to it }
            }.toMap()
        }
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A closure alert keys on its stop and hub so a recycled row can't carry another
        // alert's expanded state onto it.
        items(closureRows, key = { "closure|${it.stopId}|${it.hubId}" }) { row ->
            DepartureRowCard(row, now, onDismiss = { onDismissAlert(row) })
        }
        groups.forEachIndexed { index, group ->
            // The near-me list carries a per-stop distance; the watched list doesn't, so the
            // label is present only when this place's stops are in the map (D1). A place groups
            // several stops (a junction's poles, a station's platforms across directions), so the
            // header shows the distance to the *closest* of them — the one a rider walks to — and
            // the same place-wide value on every direction header of that place (placeDistanceMeters).
            val distanceLabel = placeDistanceMeters[group.placeKey]?.let(StopDistance::label)
            // Draw the header when the grouping asks for it (more than one place, or a closure)
            // OR whenever there's a distance to show. A lone near-me place suppresses the
            // watched-list header (StopGroup.showHeader is false for a single non-closed place),
            // but on the near-me path it must still show its name and distance — otherwise a
            // one-place result (nothing inside the inner radius, or dedup collapsing to one group)
            // would drop both the promised distance and the place name (Codex, PR #82).
            if (group.showHeader || distanceLabel != null) {
                item(key = "header|${group.key}") {
                    // The header names the place, plus the compass direction it split on when there
                    // is one ("King's Cross – Eastbound"); a place with no direction (a bus pole, a
                    // bare platform) shows the bare name (SPEC D8). The direction is passed
                    // separately so the header can reserve its width and clip the *name* first —
                    // the direction is the cue that tells two groups of one place apart.
                    // The first group takes no extra top break — unless a closure alert precedes
                    // it, where the break separates the alert band from the departures.
                    StopGroupHeader(
                        group.stopName,
                        group.directionLabel,
                        distanceLabel,
                        firstGroup = index == 0 && closureRows.isEmpty(),
                    )
                }
            }
            items(group.rows, key = { "${it.stopId}|${it.lineId}|${it.directionKey}" }) { row ->
                DepartureRowCard(
                    row,
                    now,
                    isStarred = StarredRow.of(row) in starred,
                    onToggleStar = { onToggleStar(row) },
                    starAvailable = starringAvailable,
                    onOpenDetail = { onOpenDetail(row) },
                )
            }
        }
        if (revealableModes.isNotEmpty()) {
            // A per-mode "More" footer pages the farther clusters on demand (SPEC principle 2).
            // The extra top padding, past the list's 8dp item gap, sets the footer apart from the
            // last group — asymmetric on purpose: it's a trailing section, not another row.
            item(key = "more-controls") {
                MoreControls(revealableModes, onReveal, Modifier.padding(top = 8.dp))
            }
        }
    }
}

/**
 * The per-mode "More" controls at the foot of the near-me list (SPEC *Finding stops → Near me
 * now*): one full-width button per mode still holding an unrevealed farther cluster, tapping which
 * pages that mode's next clusters in. Rendered nowhere when [revealableModes] is empty. Named modes
 * come first (alphabetical), the generic bucket (a modeless cluster) last, for a stable order.
 * A `TextButton` carries Material's ≥48dp interactive touch target, clearing the 44dp floor.
 */
@Composable
private fun MoreControls(revealableModes: Set<String>, onReveal: (String) -> Unit, modifier: Modifier = Modifier) {
    if (revealableModes.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (mode in revealableModes.sortedWith(compareBy({ it.isEmpty() }, { it }))) {
            TextButton(onClick = { onReveal(mode) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(moreLabelRes(mode)))
            }
        }
    }
}

/** The "More …" label for a reveal bucket — a dedicated string per mode the nearby search can
 *  return (its `StopFinder.DEFAULT_NEARBY_STOP_TYPES`), so two revealable buckets never share the
 *  generic "More stops" and become indistinguishable (Codex, PR #87). The generic label is left for
 *  the modeless bucket ([NearbySelection.GENERIC_MORE]) and any mode outside that fetched set. */
internal fun moreLabelRes(mode: String): Int = when (mode) {
    "bus" -> R.string.more_stops_bus
    "tube" -> R.string.more_stops_tube
    "dlr" -> R.string.more_stops_dlr
    "overground" -> R.string.more_stops_overground
    "elizabeth-line" -> R.string.more_stops_elizabeth
    "tram" -> R.string.more_stops_tram
    "national-rail" -> R.string.more_stops_rail
    "coach" -> R.string.more_stops_coach
    "river-bus" -> R.string.more_stops_river
    else -> R.string.more_stops
}

/**
 * The small group header above a group of same-place cards (SPEC D8): the place name — plus the
 * compass direction it split on ("King's Cross – Eastbound") where the caller passed one — in
 * spaced small caps, text only, no border/background, the muted `onSurfaceVariant` role. Buses (no
 * compass in the feed yet) pass a null [directionLabel] and show the bare name. The name
 * hard-truncates (no ellipsis) at the edge. Extra top space (past the list's 8dp item gap) marks
 * the group break; the first group takes none.
 *
 * [directionLabel] and [distanceLabel] are **reserved** trailing elements (measured first, no
 * weight): a long place name clips before either is pushed off the edge (the same discipline as the
 * departure row's countdown). The direction especially must survive the clip — it is the cue that
 * tells two direction groups of one place apart, so appending it to the name and letting it clip
 * would defeat the split (Codex P2, PR #109). [distanceLabel], when set, is the near-me list's
 * distance to this stop ("… (120 m)"), null on the location-free watched list (D1); it is not
 * uppercased, so its unit stays lowercase, and it sits after the direction.
 */
@Composable
private fun StopGroupHeader(
    name: String,
    directionLabel: String?,
    distanceLabel: String?,
    firstGroup: Boolean,
) {
    // labelMedium is the same role the freshness stamp uses; the tracking gives the small-caps read,
    // and the semi-bold weight is baked in ([headerTextStyle]) so the width the header measures below
    // matches the width it renders (Codex P2, PR #115). One style drives both the measure and every
    // Text. uppercase() is Kotlin's locale-invariant overload (safe from the Turkish-ı trap).
    val style = headerTextStyle(MaterialTheme.typography.labelMedium)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val headerModifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, end = 4.dp, top = if (firstGroup) 0.dp else 12.dp, bottom = 0.dp)
    if (directionLabel == null && distanceLabel == null) {
        // Watched list, no direction: the header is the bare name (rendering unchanged).
        Text(
            text = name.uppercase(),
            style = style,
            color = color,
            maxLines = 1,
            softWrap = false,
            // Truncate at the edge rather than elide — a stop name is recognized from its start.
            overflow = TextOverflow.Clip,
            modifier = headerModifier,
        )
        return
    }
    // The **direction** and the **distance** are reserved trailing elements (unweighted — measured
    // first, so they keep their width and the **name** clips first, recognized from its start). The
    // distance is short and bounded ("(1.2 km)"). The direction, rather than clip to an ambiguous
    // stub when the full word won't fit, falls back to its **single-letter** form ("– E") — which is
    // narrow enough to always fit, so the direction cue that tells two blocks of one place apart
    // never vanishes (maintainer, PR follow-up). Whether the full word fits, and the width the
    // direction is bounded to so the distance stays reserved even in a narrow pane, are decided by
    // the pure [headerDirection] from the measured widths below. The name still clips when even the
    // letter form leaves it no room. The direction is uppercased to match the small-caps name; the
    // distance keeps its lowercase unit.
    if (directionLabel == null) {
        // Near-me bus pole: a distance but no direction. Name (clips) + reserved distance, no measure.
        Row(modifier = headerModifier, verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name.uppercase(),
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                // fill = false so a short name packs left with the distance right after it, not
                // stretched to push the distance to the far edge; a long name still clips to the share.
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = " ($distanceLabel)",
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
        return
    }
    BoxWithConstraints(modifier = headerModifier) {
        val measurer = rememberTextMeasurer()
        // Key each measurement on the font scale as well as the text: a display-size / accessibility
        // resize grows the glyphs while the row's px width is unchanged, so a width cached on the
        // string alone would stay stale and the fallback never fire (mirrors DestinationLine).
        val fontScale = LocalDensity.current.fontScale
        val nameText = name.uppercase()
        val fullDirectionText = " – ${directionLabel.uppercase()}"
        val letterDirectionText = " – ${PlatformDirection.abbreviation(directionLabel)}"
        val distanceText = distanceLabel?.let { " ($it)" }.orEmpty()
        fun widthOf(text: String) =
            if (text.isEmpty()) 0 else measurer.measure(text, style, maxLines = 1).size.width
        val nameWidth = remember(nameText, style, fontScale) { widthOf(nameText) }
        val fullDirectionWidth = remember(fullDirectionText, style, fontScale) { widthOf(fullDirectionText) }
        val distanceWidth = remember(distanceText, style, fontScale) { widthOf(distanceText) }
        // Choose the direction form (full word when it fits at the name's natural width, else the
        // letter) and its width budget, reserving the distance first — pure and unit-tested.
        val direction = headerDirection(
            fullText = fullDirectionText,
            letterText = letterDirectionText,
            nameWidth = nameWidth,
            fullWidth = fullDirectionWidth,
            distanceWidth = distanceWidth,
            maxWidth = constraints.maxWidth,
        )
        val directionMaxWidth = with(LocalDensity.current) { direction.maxWidthPx.toDp() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = nameText,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                // fill = false so a short name sits directly before the direction (adjacent, packed
                // left), not stretched to push it right; the reserved direction/distance keep their
                // width and the name clips to its share only when the row is too tight.
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = direction.text,
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                // Bounded so the reserved distance keeps its width in a pane too narrow for both;
                // announce the full direction when the letter is shown, so a screen reader hears
                // "Eastbound", not "E".
                modifier = Modifier
                    .widthIn(max = directionMaxWidth)
                    .then(
                        if (direction.abbreviated) {
                            Modifier.semantics { contentDescription = directionLabel }
                        } else {
                            Modifier
                        },
                    ),
            )
            if (distanceLabel != null) {
                Text(
                    text = distanceText,
                    style = style,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun DepartureRowCard(
    row: DepartureRow,
    now: Instant,
    isStarred: Boolean = false,
    onToggleStar: () -> Unit = {},
    starAvailable: Boolean = true,
    // Open the tap-to-open route detail (star + full disruption text, SPEC D8 / *Disruptions*).
    // Default no-op so an unwired build/test renders the list without it; a stop-closure row
    // ignores it (that card expands in place instead — see below).
    onOpenDetail: () -> Unit = {},
    // Dismiss this alert — only a stop-closure row shows the control (see its `StopClosureContent`).
    // Default no-op so an unwired build/test renders the card without a dismiss button.
    onDismiss: () -> Unit = {},
) {
    // Staleness is per row, from this row's own stop age: a stop that failed to refresh
    // withholds its countdowns ("—") while a fresh stop beside it stays live (SPEC D4).
    val stale = remember(row.fetchedAt, now) {
        Staleness.isStale(Duration.between(row.fetchedAt, now).toKotlinDuration())
    }
    // Cap the line pill at half the card's inner width, so a long name at a large font
    // scale ellipsizes rather than consuming the card and starving the countdown — which
    // must stay one line (SPEC D8). Inner width ≈ screen minus the list's 16dp side padding
    // and the card's 16dp padding. No real line name reaches the cap at the default font.
    val cardInnerWidth = LocalConfiguration.current.screenWidthDp.dp - 64.dp
    val pillModifier = Modifier.widthIn(max = cardInnerWidth * 0.5f)
    // Two gestures on a route card: a TAP opens the detail view (star + full disruption text),
    // and a LONG-PRESS is the pin-to-top shortcut (SPEC D8) — the star is a per-row button no
    // longer, since a 48dp IconButton ate width on every row and crowded the one-line countdown.
    // A stop-closure row is neither tappable-to-detail nor starrable: its own `StopClosureContent`
    // Surface handles the tap (expand in place), and a whole-stop closure was never pinnable.
    // Only a timed row is starrable — a no-departures status row has nothing to rank — and only
    // when starring is available; but a status row is still tappable, so its full disruption text
    // is reachable.
    //
    // Both are a `pointerInput` gesture plus `semantics` actions, NOT `combinedClickable`:
    // `combinedClickable` merges the card's descendant semantics into one node, which flattens the
    // disrupted row's warning-first traversal order (the chip's `traversalIndex = -1f` below). The
    // gesture and the non-merging `semantics` block keep the descendants separately ordered while
    // exposing the labeled tap (now a real action — the detail view) and long-press.
    val tappable = row.stopDisruption == null
    val starrable = starAvailable && row.stopDisruption == null && row.upcoming.isNotEmpty()
    val starActionLabel = stringResource(if (isStarred) R.string.unstar else R.string.star)
    val detailActionLabel = stringResource(R.string.departure_details)
    // Read the latest callbacks without re-keying the gesture: `DepartureList` rebuilds the per-row
    // callbacks on every recomposition, and the 10s `tickingNow` clock recomposes the rows — so
    // keying `pointerInput` on a callback would cancel an in-progress long-press each tick and drop
    // its down event (Codex). Key on `Unit` (stable) and invoke the current callbacks via
    // `rememberUpdatedState`.
    val currentToggleStar by rememberUpdatedState(onToggleStar)
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    // Long-press pins, but only where the row is starrable; typed so the nullable handler is
    // unambiguous (a status row keeps its tap-to-detail with no long-press).
    val onLongPress: ((Offset) -> Unit)? = if (starrable) {
        { currentToggleStar() }
    } else {
        null
    }
    val cardModifier = Modifier.fillMaxWidth().let { base ->
        if (tappable) {
            base
                // Keyed on `starrable`, not `Unit`: at a cold start the persisted list can render
                // before `starringAvailable` flips false→true, capturing onLongPress = null; keying
                // on `starrable` restarts the gesture on that transition so long-press starts
                // working (Codex). `starrable` is stable across the 10s tick — it flips only on that
                // rare availability change, not per recomposition — so this doesn't re-key each tick
                // and drop an in-progress long-press (the callbacks are still read via
                // rememberUpdatedState to keep the per-tick callback churn from re-keying).
                .pointerInput(starrable) {
                    detectTapGestures(
                        onTap = { currentOpenDetail() },
                        onLongPress = onLongPress,
                    )
                }
                .semantics {
                    onClick(label = detailActionLabel) { currentOpenDetail(); true }
                    if (starrable) onLongClick(label = starActionLabel) { currentToggleStar(); true }
                }
        } else {
            base
        }
    }
    // A starred card is marked by a gold border rather than any in-row element, so the pinned
    // state costs no width (the border draws inside the card's bounds). The gold is the theme's
    // resolved starred tone; an unstarred card keeps the default outline (SPEC D8).
    val cardBorder =
        if (isStarred) BorderStroke(2.dp, LocalStarredBorderColor.current)
        else CardDefaults.outlinedCardBorder()
    OutlinedCard(modifier = cardModifier, border = cardBorder) {
        // A traversal group so a disrupted timed row can announce its status chip *before*
        // the destination and countdown it qualifies (the chip is placed below but carries
        // a lower traversalIndex) — a screen reader shouldn't voice a departure as
        // actionable before its warning (SPEC principle 2).
        Column(modifier = Modifier.padding(16.dp).semantics { isTraversalGroup = true }) {
            if (row.stopDisruption != null) {
                // A stop-level status row: the whole stop is disrupted (a closure). It titles
                // itself by the interchange, else the stop when there's no hub name — always shown,
                // so a bus "Bus Stop Closed" notice that never names its own stop still says which
                // stop (SPEC *Disruptions*). The body collapses to its first line, expands on tap.
                // The place name is stripped from the body here, at display — not upstream — so the
                // near-me fold's identity stays member-independent (see cleanDisruptionBody).
                StopClosureContent(
                    cleanDisruptionBody(
                        row.stopDisruption,
                        stopName = row.stopName,
                        hubName = row.hubName,
                        aliases = row.placeAliases,
                    ),
                    title = row.hubName.ifBlank { row.stopName },
                    onDismiss = onDismiss,
                )
                return@Column
            }

            if (row.upcoming.isEmpty()) {
                // A status row: the line is disrupted (the chip says how) and returned no
                // predictions (SPEC *Departures*). Pill + chip on the left, "No departures"
                // where a countdown would sit on the right — the pill already names the
                // line, so no destination text is repeated (it would read "Circle Circle").
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                    // The chip lives in the weighted slack so it absorbs the shrink (and
                    // ellipsizes) when space is tight; "No departures" is unweighted, so the
                    // Row measures it first and always reserves its width — the status can't
                    // be squeezed to zero on a narrow screen or at a large font scale.
                    Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                        row.status?.let { status -> DisruptionChip(status.description) }
                    }
                    val noDepartures = stringResource(R.string.status_no_departures_description)
                    Text(
                        text = stringResource(R.string.status_no_departures),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // The visible glyph is a compact dash; a screen reader hears the
                        // explicit "No departures" so a bare dash isn't heard as missing data.
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .semantics { contentDescription = noDepartures },
                    )
                }
                return@Column
            }

            // The next few times, grouped by destination *and branch* and ordered
            // soonest-first: the soonest group leads, and a branching direction (same line,
            // same direction) keeps each destination — and each via-branch of one terminus —
            // on its own line with its own merged countdown, so a countdown is never read
            // under the wrong destination or the wrong branch (SPEC D8). The grouping is the
            // shared `destinationLines` (the widget uses the same one, so the two surfaces
            // can't drift); each line renders identically — the leading one is not styled as a
            // bigger "headline" — so a multi-line card reads as a parallel set.
            val destinationLines = DepartureRows.destinationLines(row, MAX_TIMES, LocalRouteTopology.current)

            // The pill sits to the left of the destination line(s). A single-destination
            // card centers the pill against its one line so pill and destination sit level
            // (the common case); a branching card top-aligns it so the pill hugs the first
            // destination rather than floating against the pair. The stop name is
            // intentionally not shown on the card for now — the stop returns with multi-stop
            // watching (Phase 2), see TODO.
            val pillAlignment =
                if (destinationLines.size > 1) Alignment.Top else Alignment.CenterVertically
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = pillAlignment) {
                LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    destinationLines.forEachIndexed { index, group ->
                        DestinationLine(
                            // The destination, or the direction key (direction word, else
                            // platform) as a cue when TfL gives no destination, so cards TfL
                            // keeps distinct stay distinguishable (SPEC principle 1).
                            label = DepartureLabels.destinationLabel(group.destination, row.directionKey)
                                ?: stringResource(R.string.destination_unknown),
                            times = group.times,
                            stale = stale,
                            now = now,
                            // The branch is part of the group key, so every time in this group
                            // shares it — the line's branch names this group, not just its
                            // first departure.
                            branch = group.branch,
                            // Space the lines of a branching card apart; the first hugs the
                            // pill's top.
                            modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // No trailing star element at all — the whole point is to reclaim the width the
                // per-row button took on every card. A starred (pinned) service is marked by the
                // card's gold border (see cardBorder above) and its position at the top of the
                // list (SPEC D8); long-press the card to pin/unpin, or tap it for the detail view,
                // where a visible, labeled star is the discoverable path ([RouteDetailScreen]).
            }
            // A disrupted line is flagged below the departures, left-aligned with the pill,
            // but announced first (traversalIndex) so the warning precedes the countdowns it
            // qualifies. The chip names TfL's status ("Severe Delays"), the line being the
            // pill above (SPEC D3).
            row.status?.let { status ->
                DisruptionChip(
                    status.description,
                    Modifier.padding(top = 8.dp).semantics { traversalIndex = -1f },
                )
            }
        }
    }
}

/**
 * A stop closure (a stop-level disruption) as the card's content: the disruption in an
 * error-toned surface under the place's own [title] (the interchange name, else the stop). The
 * stop's own departures, if any, show in their own cards below.
 *
 * **The title heads the card always; the body collapses to one line and expands on tap.** Not
 * every notice names its own stop — a bus "Bus Stop Closed" never does — so the heading is what
 * says *which* stop even when collapsed (SPEC *Disruptions*); a tube notice's own leading repeat of
 * the name is stripped upstream ([cleanDisruptionBody]) so the heading isn't said twice. TfL's
 * notices are prose (a paragraph on a lift outage), and a glance surface shouldn't be dominated by
 * one, so collapsed the [disruption] body is its first line alone and tapping expands it to the full
 * text (SPEC *Concise copy* / jank-free UI). The body `Text` exposes its full string to the
 * accessibility tree regardless of the visual clip, so a screen reader reads the whole notice
 * whether or not it is expanded; the tap only changes what is drawn. Expanded state is
 * `rememberSaveable`, keyed on the notice text, so it survives a configuration change and never
 * bleeds onto a different notice when a `LazyColumn` row is recycled.
 */
@Composable
private fun StopClosureContent(disruption: String, title: String, onDismiss: () -> Unit = {}) {
    CollapsibleStatus(text = disruption, title = title, onDismiss = onDismiss)
}

/**
 * The shared "first line, tap to expand" disruption surface (SPEC *Disruptions* / *Concise
 * copy*): an error-toned rounded box that collapsed shows [text]'s first line alone and
 * expanded shows it in full, with a chevron marking it expandable. When a [title] is given it
 * heads the surface, collapsed and expanded. Used for the stop-closure card
 * ([StopClosureContent], where [title] is the interchange/stop name) and for the route detail's
 * line disruption ([RouteDetailScreen], where [title] is null — the app bar already carries the
 * line and destination).
 *
 * The `text` `Text` exposes its full string to the accessibility tree regardless of the visual
 * clip, so a screen reader reads the whole notice whether or not it is expanded; the tap only
 * changes what is drawn. Expanded state is `rememberSaveable`, keyed on [text], so it survives a
 * configuration change and never bleeds onto a different notice when a `LazyColumn` row is
 * recycled.
 *
 * When [onDismiss] is non-null a leading dismiss (×) button hides the alert (the stop-closure card
 * — the user's read-and-clear control, SPEC *Disruptions*); the route detail passes null and shows
 * no dismiss (a line disruption there isn't dismissible). The button has its own click target, so a
 * dismiss tap doesn't also toggle the expand/collapse.
 */
@Composable
private fun CollapsibleStatus(
    text: String,
    title: String?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    contentColor: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val clickLabel = stringResource(if (expanded) R.string.alert_collapse else R.string.alert_expand)
    Surface(
        color = container,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = clickLabel) { expanded = !expanded },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                // The title (the interchange/stop name) heads the surface whenever one is given —
                // collapsed and expanded — so a notice that doesn't name its own stop still says
                // which stop. The route detail passes null (its dialog header already carries the
                // line and place), so nothing is shown there.
                if (title != null) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (title != null) Modifier.padding(top = 4.dp) else Modifier,
                )
            }
            // A quiet chevron marks the row as expandable; the click label carries the action
            // for a screen reader, so the icon itself needs no separate description.
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
            // The dismiss (×) sits after the chevron with its own click target, so tapping it hides
            // the alert without also toggling expand/collapse (SPEC *Disruptions*). The IconButton
            // keeps its default 48dp interactive target — a 20dp glyph in a full-size touch area — so
            // a near miss doesn't fall through to the card's expand/collapse. (An explicit small
            // `size` would clamp that target below 48dp.)
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** The stable identity of the route a [DepartureRow] represents — its stop, line, and direction —
 *  used as the saveable key for the open route-detail page so it re-resolves against live rows. */
private fun DepartureRow.detailKey(): String = "$stopId|$lineId|$directionKey"

/**
 * The tap-to-open route detail (SPEC D8 / *Disruptions*, `TODO.md`): a full-screen page — reached by
 * a tap on a timed or line-status card (a stop-closure card expands in place, so it never reaches
 * here) — that replaces the departures screen with its own app bar. The bar names the route (line
 * pill + destination) and carries the star (the list card only long-presses to pin); the body names
 * the boarding stop and the line's full disruption text the compact chip stands in for.
 *
 * A full screen rather than a dialog (maintainer, 2026-09-22): it will grow the maps/nav hand-off
 * and other per-route actions (`TODO.md`), which a dialog would cap. Mirrors the app's other full
 * screens ([LicensesScreen], [SettingsScreen]) — a composable with an [onBack], switched in by the
 * host's screen state; [BackHandler] routes the system back to [onBack] too, and it inherits the
 * theme's scaled density from the host, so no per-window font host is needed here.
 *
 * Pure: renders only [row] and reports the two actions. The star shows only when [starrable] (a
 * timed row with starring available) — a no-departures status row has nothing to rank, matching the
 * list's own rule — while the disruption text shows whenever the line carries prose, so a status
 * row still opens to its full alert.
 */
@Composable
internal fun RouteDetailScreen(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    // True when THIS row's disruption state is unchecked (its line was blank-id or omitted by TfL,
    // the line-status lookup failed, or its stop's own disruption lookup failed) rather than
    // checked-clean: the detail then says "couldn't check" rather than a verified-clean "no
    // disruptions" (SPEC principle 1). The caller decides this per row, so one unknown line or stop
    // doesn't taint a checked-clean row.
    disruptionUnknown: Boolean,
    // True when this row's snapshot has crossed the staleness threshold: the status is from an old
    // fetch, so the page — which carries no freshness stamp of its own, unlike the list — caveats it
    // and never claims "no disruptions" from stale data (SPEC D4).
    stale: Boolean,
    onToggleStar: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val place = row.hubName.ifBlank { row.stopName }
    // The terminus(es) this service runs to, from its own departures — empty for a status row
    // (no predictions), which then shows only the line and its disruption.
    val destinations = if (row.upcoming.isEmpty()) {
        emptyList()
    } else {
        DepartureRows.destinationLines(row, MAX_TIMES, LocalRouteTopology.current)
            .mapNotNull { DepartureLabels.destinationLabel(it.destination, row.directionKey) }
            .distinct()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                // Name the route being viewed — the line pill, then where it's going — so the bar
                // reflects the page's subject (maintainer, 2026-09-22). A status row with no
                // predictions shows just the pill.
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode)
                        if (destinations.isNotEmpty()) {
                            Text(
                                text = destinations.joinToString(", "),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                },
                actions = {
                    // The star (pin-to-top) as the bar's action — the discoverable control, shown
                    // only where the row is pinnable (same rule the list card uses). Filled and gold
                    // when starred, matching the list card's gold pin border; the vendored outline
                    // star when not (material-icons-core ships the filled Star but not its outline).
                    // No visible label — the contentDescription flips with state so a screen reader
                    // still hears what the tap does (SPEC principle 2).
                    if (starrable) {
                        IconButton(onClick = onToggleStar) {
                            Icon(
                                imageVector = if (isStarred) Icons.Filled.Star else StarBorderIcon,
                                contentDescription = stringResource(
                                    if (isStarred) R.string.unstar else R.string.star,
                                ),
                                tint = if (isStarred) {
                                    LocalStarredBorderColor.current
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        // Scrollable so a long expanded alert or a large text size isn't clipped (SPEC *Display
        // size*); the 16dp gutter matches the app's other reading surfaces.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (place.isNotBlank()) {
                Text(
                    // "From Victoria" — where the service departs; the app bar already shows where
                    // it's going, so the body names the boarding stop, not the line.
                    text = stringResource(R.string.route_detail_from, place),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val status = row.status
            if (status != null) {
                // The short chip label always; the full prose below it, collapsed to its first line
                // with tap-to-expand, when TfL gave a reason (SPEC *Disruptions*).
                DisruptionChip(status.description, Modifier.padding(top = 12.dp))
                status.fullText?.let { fullText ->
                    CollapsibleStatus(
                        text = fullText,
                        title = null,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            // The disruption-check state, independent of staleness (the two caveats are separate
            // facts, both shown when both apply — Codex): "couldn't check" whenever this row's line
            // was undetermined OR its stop's disruption lookup failed — a known line alert doesn't
            // mean the stop-level closure/move check ran, so the note sits alongside the alert too,
            // and the stale caveat below never stands in for it. "No disruptions reported" only when
            // determined-clean AND fresh — never claimed from stale or unchecked data (SPEC principle
            // 1 / D4). A stale but determined-clean row shows neither here; the stale caveat covers it.
            if (disruptionUnknown) {
                Text(
                    text = stringResource(R.string.disruptions_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            } else if (status == null && !stale) {
                Text(
                    text = stringResource(R.string.route_detail_no_disruption),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            // The page has no freshness stamp of its own, so a stale snapshot is caveated here — an
            // independent fact from the disruption-check state above: it sits under a shown
            // disruption (which may be resolved or superseded), replaces a "no disruptions" claim
            // (a new one may exist), and sits alongside a "couldn't check" note (age and a failed
            // check are different gaps) alike (SPEC D4).
            if (stale) {
                Text(
                    text = stringResource(R.string.route_detail_status_stale),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * One destination line within a card — `destination · · · countdown` — used for the
 * soonest destination and for each divergent one of a branching direction alike, so they
 * render at the **same weight and the same indentation** (all sit in the card's one
 * destination column, beside the pill). [times] empty is a status row: the line is named
 * with no countdown. A long destination first shortens common whole words
 * ([DestinationAbbreviations]) and then, if it still doesn't fit, is hard-truncated with a
 * clean cut — no ellipsis (maintainer preference) — rather than crowding out the countdown.
 * The whole destination line — terminus and its branch/via — clips; only the countdown
 * keeps its ellipsis (below).
 */
@Composable
internal fun DestinationLine(
    label: String,
    times: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
    // The "via" branch (TfL's `towards`), joined to the destination with a slash —
    // "Battersea/Charing X" — the cue a rider uses to pick a train. Null for most
    // services; a branch-free row whose name has no abbreviatable word takes the cheap
    // no-measuring path below.
    branch: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (branch == null) {
            val style = MaterialTheme.typography.titleMedium
            val abbreviated = remember(label) { DestinationAbbreviations.abbreviate(label) }
            if (abbreviated == label) {
                // Nothing to abbreviate — keep the cheap no-measuring path: the destination
                // takes the space the countdown leaves and is hard-clipped (a clean cut, no
                // ellipsis) if it must.
                Text(
                    text = label,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
            } else {
                // The name has a word we can shorten, so measure: show it in full when it
                // fits, shrink to the abbreviated form when it wouldn't, and clip only if even
                // that is too wide (SPEC destination-label — abbreviate before truncating).
                BoxWithConstraints(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    val measurer = rememberTextMeasurer()
                    // Key the measurement on the font scale, not the label alone: a display-size /
                    // accessibility resize grows the text while the row's px width is unchanged, so
                    // a width cached on the label would stay stale and the abbreviation never fire.
                    val fontScale = LocalDensity.current.fontScale
                    val fullWidth = remember(label, style, fontScale) {
                        measurer.measure(label, style, maxLines = 1).size.width
                    }
                    val display = if (fullWidth <= constraints.maxWidth) label else abbreviated
                    Text(
                        text = display,
                        style = style,
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        // Keep the full name for a screen reader when the visible text is shortened.
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (display != label) Modifier.semantics { contentDescription = label }
                                else Modifier,
                            ),
                    )
                }
            }
        } else {
            // The branch is the cue that tells a branching line's two trunks apart, so it
            // outranks the terminus for space: the full branch is kept while an abbreviated
            // terminus can sit beside it, and the terminus yields first — full name, then its
            // abbreviated form, then a clean clip (SPEC destination-label). The branch's own
            // label is already the board short form ("Charing X"), so [abbreviateBranch] is a
            // no-op here; a fuller rider-readable form under width pressure is a follow-up (TODO).
            BoxWithConstraints(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                val style = MaterialTheme.typography.titleMedium
                val measurer = rememberTextMeasurer()
                val abbreviatedLabel = remember(label) { DestinationAbbreviations.abbreviate(label) }
                val shortBranch = remember(branch) { abbreviateBranch(branch) }
                // Measure each candidate string, keyed on the font scale as well as the text, so a
                // display-size / accessibility resize re-measures rather than reusing a width cached
                // on the string alone (which would leave the abbreviation stuck). branchedLabel turns
                // the widths into the terminus/branch strings so the rule (branch outranks terminus;
                // branch-alone and bare when the terminus has no room at all) is unit-tested apart
                // from the render.
                val fontScale = LocalDensity.current.fontScale
                fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
                val resolved = branchedLabel(
                    label = label,
                    abbreviatedLabel = abbreviatedLabel,
                    branch = branch,
                    abbreviatedBranch = shortBranch,
                    maxWidth = constraints.maxWidth,
                    labelWidth = remember(label, style, fontScale) { widthOf(label) },
                    abbrevLabelWidth = remember(abbreviatedLabel, style, fontScale) { widthOf(abbreviatedLabel) },
                    fullBranchWidth = remember(branch, style, fontScale) { widthOf("/$branch") },
                    abbrevBranchWidth = remember(shortBranch, style, fontScale) { widthOf("/$shortBranch") },
                    firstGlyphWidth = remember(abbreviatedLabel, style, fontScale) { widthOf(abbreviatedLabel.take(1)) },
                    branchFirstGlyphWidth = remember(shortBranch, style, fontScale) { widthOf("/${shortBranch.take(1)}") },
                )
                val density = LocalDensity.current
                val terminusMaxWidth = with(density) { resolved.terminusMaxWidthPx.toDp() }
                val branchMaxWidth = with(density) { resolved.branchMaxWidthPx.toDp() }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = resolved.terminus,
                        style = style,
                        maxLines = 1,
                        // One line, no wrap: without this a two-word terminus ("Battersea Power")
                        // wraps its second word onto a dropped line while the Text still fills its
                        // slot, floating the branch off to the far edge — the slash ends up detached,
                        // as in "Battersea        /Charing X". softWrap = false clips on one line so
                        // the slash stays against the last visible glyph. Hard clip (a clean cut, no
                        // ellipsis). The width budget from branchedLabel caps each side; under
                        // pressure the two share the row in proportion so both clip by the same
                        // fraction (equal truncation), and a budget wider than the natural text just
                        // lets it sit at its own width with no gap before the branch.
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .widthIn(max = terminusMaxWidth)
                            // Keep the full name for a screen reader when the visible text is shortened or hidden.
                            .then(
                                resolved.contentDescription?.let { full ->
                                    Modifier.semantics { contentDescription = full }
                                } ?: Modifier,
                            ),
                    )
                    Text(
                        text = resolved.branch,
                        style = style,
                        maxLines = 1,
                        // Hard-clipped to its own budget too, so under pressure the branch cuts
                        // cleanly at the same fraction as the terminus instead of pushing it off.
                        softWrap = false,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.widthIn(max = branchMaxWidth),
                    )
                }
            }
        }
        if (times.isNotEmpty()) {
            CountdownLabel(times, stale, now)
        }
    }
}

/**
 * A service's merged countdown — "0 · 3 · 6 min" (SPEC D8, one line per destination).
 * Withheld as "—" once the stop is stale, since the underlying predictions are likely
 * wrong and a live-looking number would misrepresent them (SPEC D4).
 */
@Composable
private fun CountdownLabel(
    departures: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Text(
        text = if (stale) WITHHELD else Countdown.mergedLabel(departures, now),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        // One line, never wrapped — the countdown is the one thing that must stay legible
        // (SPEC D8). It's the unweighted element of its row, so it's measured first and its
        // width reserved; the destination beside it ellipsizes when space is tight. In the
        // extreme (a long pill + the full "0 · 3 · 6 min" at a large font, on a narrow
        // screen) even the whole line can be too short: ellipsize from the end rather than
        // hard-clip, so the soonest times — which lead the label — stay legible and the
        // truncation reads as one ("0 · 3 …").
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        color =
            if (stale) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

/**
 * Marks a row whose line is disrupted (SPEC D3). A small error-toned chip carrying TfL's
 * status wording, sat between the header and the destination so it reads before the
 * countdowns it qualifies. The pill above already names the line, so the chip is the
 * status alone ("Severe Delays"), not "Victoria line: severe delays".
 */
@Composable
private fun DisruptionChip(description: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            text = description,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Centered(modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun RefreshButton(onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onRefresh, modifier = modifier) {
        Text(stringResource(R.string.refresh))
    }
}

private fun errorMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.error_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.error_rate_limited
    DeparturesUiState.Error.Kind.UNREACHABLE -> R.string.error_unreachable
}

private fun refreshFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.refresh_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.refresh_failed_rate_limited
    DeparturesUiState.Error.Kind.UNREACHABLE -> R.string.refresh_failed_unreachable
}

private const val MAX_TIMES = 3
// A stale stop's countdown is unknown, not zero, so it withholds the number as "?" — "—"
// read as "none," which is a different thing (that's the no-departures status). The stamp
// up top ("Tap to refresh") says why.
private const val WITHHELD = "?"
