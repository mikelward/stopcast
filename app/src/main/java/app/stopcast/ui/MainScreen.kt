@file:OptIn(ExperimentalMaterial3Api::class)

package app.stopcast.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import app.stopcast.domain.RelativeTime
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopDistance
import app.stopcast.domain.StopGroup
import app.stopcast.domain.StopGrouping
import app.stopcast.domain.StopQualifier
import app.stopcast.domain.abbreviateBranch
import app.stopcast.domain.RouteFocus
import app.stopcast.domain.followedDeparture
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
        // then lift the user's starred services to the top (SPEC D8). Warnings still lead on the
        // location-free watched list; on the near-me list (distances present) an alert is not
        // hoisted, so a nearer stop is never pushed below a farther one for carrying one.
        DepartureRows.pinStarred(
            DepartureRows.withoutDismissed(ordered, dismissed),
            starred,
            warningsLead = stopDistanceMeters.isEmpty(),
        )
    }

    // The route whose detail is open, held by its stable row identity rather than the row object: a
    // saveable String survives a configuration change (the page stays open on rotation) and resets on
    // process death, and it re-resolves against the current `rows` each recomposition so the page
    // reflects a refreshed row and closes itself if the row leaves the list — the coordinate it shows
    // is never persisted (mirrors the bug-report flow).
    var detailKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Which of the row's routes was tapped (a card shows one route row per destination), so the page
    // follows that route rather than whichever train is soonest. Two saveable strings, not a
    // RouteFocus, so it survives rotation with no custom Saver; null destination = no focus.
    var detailDestination by rememberSaveable { mutableStateOf<String?>(null) }
    var detailBranch by rememberSaveable { mutableStateOf<String?>(null) }
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
            focus = detailDestination?.let { RouteFocus(it, detailBranch) },
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
                    onOpenDetail = { row, focus ->
                        detailKey = row.detailKey()
                        detailDestination = focus?.destination
                        detailBranch = focus?.branch
                    },
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
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit = { _, _ -> },
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
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
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
    // On the near-me list (distances present) a place is ordered by distance, not lifted for
    // carrying a line-status alert; the watched list keeps warnings leading (D1, SPEC *Disruptions*).
    val groups = remember(rows, stopDistanceMeters) {
        StopGrouping.groupByStop(rows, warningsLead = stopDistanceMeters.isEmpty())
    }
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
            StopClosureCard(row, onDismiss = { onDismissAlert(row) })
        }
        // One combined one-line header per group, then the group's card (SPEC D8): the place name and
        // its platform/pole qualifier read on one title-case line ("King's Cross St. Pancras – Platform
        // 1 (120 m)"), and every route of the group's stop(s) sits as an interior row of the one card
        // below. The place name repeats on each platform header of a station (as does the distance) —
        // the near-me rider judges each platform on its own line.
        groups.forEachIndexed { index, group ->
            // The near-me list carries a per-stop distance; the watched list doesn't, so the label is
            // present only when this place's stops are in the map (D1). A place groups several stops (a
            // junction's poles, a station's platforms), so it shows the distance to the *closest* of
            // them — the one a rider walks to (placeDistanceMeters).
            val distanceLabel = placeDistanceMeters[group.placeKey]?.let(StopDistance::label)
            // Draw the header when the grouping distinguishes this place (>1 place, a split into
            // platforms/poles, a closure) OR there's a distance to promise. A lone bare near-me place
            // still shows its name and distance, else a one-place result would drop both (Codex, PR
            // #82). The first group on screen takes no extra top break — unless a closure alert precedes
            // it.
            if (group.showHeader || distanceLabel != null) {
                item(key = "header|${group.key}") {
                    StopGroupHeader(
                        group.stopName,
                        group.qualifier,
                        distanceLabel,
                        firstOnScreen = index == 0 && closureRows.isEmpty(),
                    )
                }
            }
            item(key = "card|${group.key}") {
                StopGroupCard(
                    group,
                    now,
                    starred = starred,
                    onToggleStar = onToggleStar,
                    starringAvailable = starringAvailable,
                    onOpenDetail = onOpenDetail,
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
 * The one combined header above a group's card (SPEC D8): the place name and its platform/pole
 * qualifier on a single title-case line — "King's Cross St. Pancras – Platform 1", "Cranley Gardens –
 * Stop G", "Highgate – Eastbound" — with [distanceLabel] ("(120 m)", null on the location-free watched
 * list, D1) appended dimmed. No small caps, no tracking: the place name and the qualifier share one
 * [MaterialTheme.typography.labelLarge] / [FontWeight.SemiBold] / `onSurface` style, and only the
 * distance is muted to `onSurfaceVariant`.
 *
 * The place name is the one element that clips: it takes the row's slack and ellipsizes when long,
 * while the " – <qualifier>" and " (<distance>)" are reserved (measured first) so they stay fully
 * visible — a long "King's Cross St. Pancras – Platform 7 (120 m)" keeps "– Platform 7 (120 m)" and
 * clips the name. The whole row announces one screen-reader label — the place name, the spoken
 * qualifier (which keeps the direction/towards the visible segment drops), then the distance. Extra
 * top space marks the break between groups; the first on screen takes none.
 */
@Composable
private fun StopGroupHeader(
    name: String,
    qualifier: StopQualifier?,
    distanceLabel: String?,
    firstOnScreen: Boolean,
) {
    val style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    val label = remember(qualifier) { groupHeaderLabel(qualifier) }
    // The full spoken label: the place name, the spoken qualifier (direction/towards kept), then the
    // distance — read as one, so a screen reader hears the whole header rather than three fragments.
    val spoken = remember(name, qualifier, distanceLabel) {
        buildString {
            append(name)
            groupHeaderSpoken(qualifier)?.let { append(", ").append(it) }
            distanceLabel?.let { append(", ").append(it) }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = if (firstOnScreen) 0.dp else 16.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The distance ("(120 m)") is short and reserved (unweighted, measured first). The name and
        // the qualifier then SHARE the rest, each weighted, so neither can consume the whole row and
        // crowd the other to zero: a long place name and a long qualifier each keep at least their
        // half and clip within it, so the rider's boarding place always stays visible (Codex P1, PR
        // #122). `fill = false` lets a short name/qualifier sit at its natural width and pack left
        // rather than pad out its half. The name ellipsizes; the qualifier hard-clips its glyphs.
        Text(
            text = name,
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (label != null) {
            Text(
                text = " – $label",
                style = style,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                // Elide with a single "…" (never a mid-glyph cut) when the name and qualifier can't
                // both fit their shared half of the row.
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (distanceLabel != null) {
            Text(
                text = " ($distanceLabel)",
                style = style,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * A stop-closure alert as its own header-less card at the top of the list (SPEC *Disruptions*): the
 * disruption in an error-toned surface titled by the interchange (else the stop), the body collapsed
 * to its first line and expanded on tap. Kept as a standalone card — a whole-stop closure has no
 * platform/pole qualifier to head, and its departures (if any) show in the group cards below.
 */
@Composable
private fun StopClosureCard(row: DepartureRow, onDismiss: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // A traversal group, matching the departure cards. The place name is stripped from the body
        // at display (not upstream) so the near-me fold's identity stays member-independent
        // (cleanDisruptionBody); the title supplies the stop name a bus "Bus Stop Closed" never does.
        Column(modifier = Modifier.padding(16.dp).semantics { isTraversalGroup = true }) {
            StopClosureContent(
                cleanDisruptionBody(
                    row.stopDisruption.orEmpty(),
                    stopName = row.stopName,
                    hubName = row.hubName,
                    aliases = row.placeAliases,
                ),
                title = row.hubName.ifBlank { row.stopName },
                onDismiss = onDismiss,
            )
        }
    }
}

/**
 * One card per group (SPEC D8): an [OutlinedCard] whose body is a [Column] of interior **route
 * rows** — one per destination line of each of the group's rows — separated by thin dividers. Each
 * route row carries its line pill, its destination, an inline ⚠ when the line is disrupted, and the
 * merged countdown; a starred route wears a gold leading-edge bar (the per-row accent that replaces
 * the old whole-card gold border, which no longer maps now a card holds several routes).
 *
 * Interactions live on each route row, not the card: a TAP opens the detail view and a LONG-PRESS
 * pins (where starrable). Staleness is per row, from the row's own stop age, so a stale stop
 * withholds its countdowns ("?") while a fresh stop's card beside it stays live (SPEC D4).
 */
@Composable
private fun StopGroupCard(
    group: StopGroup,
    now: Instant,
    starred: Set<StarredRow>,
    onToggleStar: (DepartureRow) -> Unit,
    starringAvailable: Boolean,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
) {
    // Cap the line pill at half the card's inner width, so a long name at a large font scale
    // ellipsizes rather than consuming the card and starving the countdown, which must stay one line
    // (SPEC D8). Inner width ≈ screen minus the list's 16dp side padding and the row's 16dp padding.
    val cardInnerWidth = LocalConfiguration.current.screenWidthDp.dp - 64.dp
    val pillModifier = Modifier.widthIn(max = cardInnerWidth * 0.5f)
    val topology = LocalRouteTopology.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // The card is a traversal group; each interior route row is its own sub-group (below) so its
        // ⚠ precedes its own countdown, not the next row's (SPEC principle 2).
        Column(modifier = Modifier.semantics { isTraversalGroup = true }) {
            var firstRow = true
            group.rows.forEach { row ->
                val stale = Staleness.isStale(Duration.between(row.fetchedAt, now).toKotlinDuration())
                val isStarred = StarredRow.of(row) in starred
                if (row.upcoming.isEmpty()) {
                    // A status row: the line is suspended (its reason shown) and returned no
                    // predictions (SPEC *Departures*). Tappable to the detail view, but not starrable
                    // — a no-departures row has nothing to rank.
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    RouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = false,
                        onToggleStar = onToggleStar,
                        onOpenDetail = onOpenDetail,
                    ) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                        // The reason chip lives in the weighted slack so it absorbs the shrink (and
                        // ellipsizes) when space is tight; "No departures" is unweighted, so the Row
                        // reserves its width — the status can't be squeezed to zero.
                        Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                            row.status?.let { status -> DisruptionChip(status.description) }
                        }
                        val noDepartures = stringResource(R.string.status_no_departures_description)
                        Text(
                            text = stringResource(R.string.status_no_departures),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            // The visible glyph is a compact dash; a screen reader hears the explicit
                            // "No departures" so a bare dash isn't heard as missing data.
                            modifier = Modifier
                                .padding(start = 12.dp)
                                .semantics { contentDescription = noDepartures },
                        )
                    }
                    return@forEach
                }
                // Timed rows: grouped by destination *and branch* (the shared `destinationLines`, so
                // the widget can't drift), each destination its own route row with its own merged
                // countdown — a countdown is never read under the wrong destination or branch (SPEC
                // D8). A branching row (Northern to Morden and to Battersea) shows its pill on each.
                val starrable = starringAvailable && row.stopDisruption == null && row.upcoming.isNotEmpty()
                val destinationLines = DepartureRows.destinationLines(row, MAX_TIMES, topology)
                destinationLines.forEach { group2 ->
                    if (!firstRow) RouteDivider()
                    firstRow = false
                    // The destination, or the direction key as a cue when TfL gives no destination, so
                    // cards TfL keeps distinct stay distinguishable (SPEC principle 1).
                    val label = DepartureLabels.destinationLabel(group2.destination, row.directionKey)
                        ?: stringResource(R.string.destination_unknown)
                    RouteRow(
                        row = row,
                        isStarred = isStarred,
                        starrable = starrable,
                        onToggleStar = onToggleStar,
                        onOpenDetail = onOpenDetail,
                        // The route row's own soonest train names the route the detail follows.
                        focus = RouteFocus.of(group2),
                    ) {
                        LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode, modifier = pillModifier)
                        DestinationLabelContent(
                            label = label,
                            branch = group2.branch,
                            modifier = Modifier.weight(1f).padding(start = 8.dp, end = 12.dp),
                        )
                        // A disrupted line shows an inline ⚠ just left of the countdown, announced
                        // first (traversalIndex, in DisruptionWarningGlyph) so the warning precedes the
                        // countdown it qualifies (SPEC D3 / principle 2). It replaces the old full-width
                        // chip; the full status text stays reachable in the detail view.
                        row.status?.let { status ->
                            DisruptionWarningGlyph(status.description, Modifier.padding(end = 8.dp))
                        }
                        CountdownLabel(group2.times, stale, now)
                    }
                }
            }
        }
    }
}

/** A thin divider between the interior route rows of a group card, in the outline color. */
@Composable
private fun RouteDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * One interior route row of a group card: a [Row] whose [content] is the pill, destination, optional
 * ⚠, and countdown. A TAP opens the detail view; a LONG-PRESS pins the route when [starrable]
 * (starring available, not a stop closure, and it has upcoming departures). A [isStarred] route wears
 * a 3dp gold leading-edge bar — all destination rows of one starred [DepartureRow] show it, since
 * starring is keyed per line+direction ([StarredRow.of]).
 *
 * A `pointerInput` gesture plus a **non-merging** `semantics` block (NOT `combinedClickable`, which
 * flattens the descendant traversal so the ⚠ could no longer precede its countdown): the block adds
 * the labeled tap/long-press and marks the row its own traversal sub-group. Callbacks are read via
 * `rememberUpdatedState` and the gesture is keyed on the stable [starrable] so the 10s clock's
 * recomposition never drops an in-progress long-press (Codex).
 */
@Composable
private fun RouteRow(
    row: DepartureRow,
    isStarred: Boolean,
    starrable: Boolean,
    onToggleStar: (DepartureRow) -> Unit,
    onOpenDetail: (DepartureRow, RouteFocus?) -> Unit,
    // The route this row shows (null for a status row), handed to the detail so it opens on it.
    focus: RouteFocus? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val starActionLabel = stringResource(if (isStarred) R.string.unstar else R.string.star)
    val detailActionLabel = stringResource(R.string.departure_details)
    val currentRow by rememberUpdatedState(row)
    val currentToggleStar by rememberUpdatedState(onToggleStar)
    val currentOpenDetail by rememberUpdatedState(onOpenDetail)
    val currentFocus by rememberUpdatedState(focus)
    val onLongPress: ((Offset) -> Unit)? = if (starrable) {
        { currentToggleStar(currentRow) }
    } else {
        null
    }
    val starColor = LocalStarredBorderColor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(starrable) {
                detectTapGestures(
                    onTap = { currentOpenDetail(currentRow, currentFocus) },
                    onLongPress = onLongPress,
                )
            }
            .semantics {
                isTraversalGroup = true
                onClick(label = detailActionLabel) { currentOpenDetail(currentRow, currentFocus); true }
                if (starrable) onLongClick(label = starActionLabel) { currentToggleStar(currentRow); true }
            }
            .then(
                if (isStarred) {
                    Modifier.drawBehind {
                        drawRect(color = starColor, size = size.copy(width = 3.dp.toPx()))
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The inline disruption warning ⚠ on a route row (SPEC D3): a small glyph in the error color, sat
 * just left of the countdown but announced first ([traversalIndex] = -1f) so a screen reader voices
 * the warning — TfL's full status wording, its [description] — before the countdown it qualifies
 * (SPEC principle 2). A glyph `Text`, not `Icons.Filled.Warning`, since that isn't in
 * material-icons-core (like the outline star the app already vendors).
 */
@Composable
private fun DisruptionWarningGlyph(description: String, modifier: Modifier = Modifier) {
    Text(
        text = "⚠",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.semantics {
            contentDescription = description
            traversalIndex = -1f
        },
    )
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
            val chevron = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown
            if (onDismiss != null) {
                // On a dismissible closure card the chevron and the × are BOTH centered in a 48dp
                // band, so their glyphs line up on one axis, with a clear gap between them — they read
                // as two separate controls instead of a cramped, misaligned cluster.
                Box(
                    modifier = Modifier.padding(start = 8.dp).height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(chevron, contentDescription = null, modifier = Modifier.size(20.dp))
                }
                // The dismiss (×) keeps its default 48dp interactive target, flush against the chevron
                // band so there is no dead gap between them: the visual separation from the chevron is
                // the button's own internal padding, which is part of the dismiss hit target — a near
                // miss just left of the glyph still dismisses rather than toggling expand/collapse
                // (SPEC *Disruptions*). No outer padding, which would sit outside the clickable and
                // fall through to the card.
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.alert_dismiss),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                // A non-dismissible status card (line status, route detail) keeps the top-aligned
                // chevron beside its text — no × to align with.
                Icon(chevron, contentDescription = null, modifier = Modifier.padding(start = 8.dp).size(20.dp))
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
    // The stop list for the soonest train. Null resolves it from [LocalRouteStops]; a screenshot
    // test passes a fixed state.
    routeStops: RouteStopsUi? = null,
    // The route tapped on the card; null (a status row, or a caller with no route) follows the
    // row's soonest train.
    focus: RouteFocus? = null,
) {
    BackHandler(onBack = onBack)
    val followed = followedDeparture(row, focus, LocalRouteTopology.current)
    var routeStopsRetry by rememberSaveable { mutableIntStateOf(0) }
    // A stale row's soonest prediction may not be the next train any more, so its stop list is
    // withheld rather than shown as current (SPEC D4); it returns as soon as a refresh lands.
    val stops = when {
        // Only a row with a train to follow: a status row has no list to withhold.
        stale && row.upcoming.isNotEmpty() && row.lineId.isNotBlank() -> RouteStopsUi.Stale
        routeStops != null -> routeStops
        else -> rememberRouteStops(row, followed, routeStopsRetry)
    }
    val place = row.hubName.ifBlank { row.stopName }
    // The terminus(es) this service runs to, from its own departures — empty for a status row
    // (no predictions), which then shows only the line and its disruption.
    val destinations = if (row.upcoming.isEmpty()) {
        emptyList()
    } else if (focus != null && followed != null) {
        // A tapped route names just that route, matching the stop list below it.
        listOfNotNull(DepartureLabels.destinationLabel(followed.destination, row.directionKey))
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
            // Every station from here to where the soonest train terminates (SPEC *Route detail*).
            RouteStopsSection(
                state = stops,
                railColor = railColorFor(row),
                onRetry = { routeStopsRetry++ },
                modifier = Modifier.padding(top = 16.dp),
            )
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
        DestinationLabelContent(label, branch, Modifier.weight(1f).padding(end = 12.dp))
        if (times.isNotEmpty()) {
            CountdownLabel(times, stale, now)
        }
    }
}

/**
 * The destination label of a route — the terminus, its abbreviation/branch rendering — laid into the
 * [modifier] the caller provides (a weighted slot beside the countdown, or beside the pill on a group
 * card). Extracted from [DestinationLine] so both the standalone destination line and the group
 * card's route rows render the label identically. A branch-free name shortens common whole words
 * ([DestinationAbbreviations]) then hard-clips (no ellipsis); a branching name shares the width with
 * its "/branch" cue ([branchedLabel]), the branch outranking the terminus. The full name stays the
 * screen-reader label when the visible text is shortened.
 */
@Composable
private fun RowScope.DestinationLabelContent(label: String, branch: String?, modifier: Modifier) {
    if (branch == null) {
        val style = MaterialTheme.typography.titleMedium
        val abbreviated = remember(label) { DestinationAbbreviations.abbreviate(label) }
        val floor = remember(label) { DestinationAbbreviations.floor(label) }
        if (abbreviated == label && floor == label) {
            // Nothing to shorten (a one-word name, no mapped words): show it, and elide with a single
            // "…" (never a mid-glyph cut) only if the countdown leaves too little room.
            Text(
                text = label,
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = modifier,
            )
        } else {
            // Measure the ladder and show the longest form that fits — full, then word-abbreviated
            // ("East Finchley" → "E. Finchley"), then the floor ("Battersea Power" → "Battersea P.") —
            // eliding with a single "…" only below the floor (SPEC destination-label — shorten before
            // eliding, and never cut mid-glyph).
            BoxWithConstraints(modifier = modifier) {
                val measurer = rememberTextMeasurer()
                // Key each measurement on the font scale, not the text alone: a display-size /
                // accessibility resize grows the text while the row's px width is unchanged, so a
                // width cached on the string would stay stale and the shrink never fire.
                val fontScale = LocalDensity.current.fontScale
                fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
                val fullWidth = remember(label, style, fontScale) { widthOf(label) }
                val abbrevWidth = remember(abbreviated, style, fontScale) { widthOf(abbreviated) }
                val max = constraints.maxWidth
                val display = when {
                    fullWidth <= max -> label
                    abbrevWidth <= max -> abbreviated
                    else -> floor
                }
                Text(
                    text = display,
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
        // The branch is the cue that tells a branching line's two trunks apart, so it is kept whole:
        // the branch lays out at its natural width and the terminus takes the leftover, shrinking
        // (word-abbreviated, then floored) and eliding with a single "…" before it would cut — never
        // mid-glyph (SPEC destination-label). The branch's own label is already the board short form
        // ("Charing X"), so [abbreviateBranch] is a no-op here; a fuller rider-readable form is a
        // follow-up (TODO). branchedLabel turns the measured widths into the strings, so the rule is
        // unit-tested apart from the render.
        BoxWithConstraints(modifier = modifier) {
            val style = MaterialTheme.typography.titleMedium
            val measurer = rememberTextMeasurer()
            val abbreviatedLabel = remember(label) { DestinationAbbreviations.abbreviate(label) }
            val floorLabel = remember(label) { DestinationAbbreviations.floor(label) }
            val shortBranch = remember(branch) { abbreviateBranch(branch) }
            // Key each measurement on the font scale as well as the text, so a display-size /
            // accessibility resize re-measures rather than reusing a width cached on the string alone.
            val fontScale = LocalDensity.current.fontScale
            fun widthOf(text: String) = measurer.measure(text, style, maxLines = 1).size.width
            val resolved = branchedLabel(
                label = label,
                abbreviatedLabel = abbreviatedLabel,
                floorLabel = floorLabel,
                branch = branch,
                abbreviatedBranch = shortBranch,
                maxWidth = constraints.maxWidth,
                labelWidth = remember(label, style, fontScale) { widthOf(label) },
                abbrevLabelWidth = remember(abbreviatedLabel, style, fontScale) { widthOf(abbreviatedLabel) },
                floorLabelWidth = remember(floorLabel, style, fontScale) { widthOf(floorLabel) },
                fullBranchWidth = remember(branch, style, fontScale) { widthOf("/$branch") },
                abbrevBranchWidth = remember(shortBranch, style, fontScale) { widthOf("/$shortBranch") },
                minStubWidth = remember(floorLabel, style, fontScale) { widthOf("${floorLabel.take(1)}…") },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (resolved.terminus.isNotEmpty()) {
                    Text(
                        text = resolved.terminus,
                        style = style,
                        maxLines = 1,
                        softWrap = false,
                        // Ellipsize cleanly (single "…", never mid-glyph) in the room the whole branch
                        // leaves; branchedLabel already shrank the terminus to a form that fits.
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // Keep the full name for a screen reader when the visible text is shortened.
                            .then(
                                resolved.contentDescription?.let { full ->
                                    Modifier.semantics { contentDescription = full }
                                } ?: Modifier,
                            ),
                    )
                }
                Text(
                    text = resolved.branch,
                    style = style,
                    maxLines = 1,
                    // Kept whole at its natural width; ellipsis is a last resort only for a long
                    // branch standing alone in the narrowest row (nothing left to yield).
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    // When the branch stands alone (no terminus shown), its contentDescription carries
                    // BOTH the full terminus and the branch, so a screen reader can still tell two
                    // otherwise-identical Bank and Charing Cross rows apart (Codex P2).
                    modifier = if (resolved.terminus.isEmpty()) {
                        resolved.contentDescription?.let { full ->
                            Modifier.semantics { contentDescription = "$full via ${resolved.branch}" }
                        } ?: Modifier
                    } else {
                        Modifier
                    },
                )
            }
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
