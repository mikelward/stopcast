@file:OptIn(ExperimentalMaterial3Api::class)

package app.stopcast.ui

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.stopcast.R
import app.stopcast.domain.Countdown
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DestinationAbbreviations
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
) {
    val snackbarHostState = remember { SnackbarHostState() }
    // Overflow-menu and About-dialog visibility. Saved so an open dialog survives rotation.
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val starWriteFailedMessage = stringResource(R.string.star_write_failed)
    LaunchedEffect(starWriteFailed) {
        if (starWriteFailed) {
            // Clear first, then show: clearing before the (suspending) showSnackbar means a
            // rotation while the snackbar is visible doesn't re-trigger it, while the flag
            // having survived until now covers a rotation that happened before this ran.
            onStarWriteFailureShown()
            snackbarHostState.showSnackbar(starWriteFailedMessage)
        }
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
                    state, now, onRefresh, refreshing, content, stopDistanceMeters,
                    starred, onToggleStar, starringAvailable,
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
    stopDistanceMeters: Map<String, Double> = emptyMap(),
    starred: Set<StarredRow> = emptySet(),
    onToggleStar: (DepartureRow) -> Unit = {},
    starringAvailable: Boolean = true,
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
    // Group against the live clock, not fetch time, so departed services leave the list
    // and the order advances between fetches (SPEC D4). Line statuses stamp each row so a
    // disrupted line is marked (SPEC D3). Cheap and pure.
    val rows = remember(state.stops, state.lineStatuses, now, stopDistanceMeters, starred) {
        val across = DepartureRows.across(state.stops, now, state.lineStatuses)
        // A "near me now" list (distances present) shows a line once, from its nearest stop,
        // instead of once per adjacent stop it passes (SPEC *Finding stops → Near me now*).
        // A location-free list has no distances and is shown as grouped.
        // A "near me now" list (distances present) shows a line once, from its nearest stop,
        // then orders the rows closest-stop-first (soonest breaks a same-stop tie). A
        // location-free list has no distances and keeps across's soonest-first order (D1).
        val ordered =
            if (stopDistanceMeters.isEmpty()) {
                across
            } else {
                val deduped = DepartureRows.nearbyDeduped(across, stopDistanceMeters)
                DepartureRows.byStopDistance(deduped, stopDistanceMeters)
            }
        // Lift the user's starred services to the top (SPEC D8), warnings still leading.
        DepartureRows.pinStarred(ordered, starred)
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
                }
            } else {
                DepartureList(rows, now, starred, onToggleStar, starringAvailable, stopDistanceMeters, Modifier.fillMaxSize())
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
    modifier: Modifier,
) {
    // Cluster the flat rows into per-place groups (stops sharing a name — a junction's poles, a
    // station's platforms) so each gets a name header — the flat list gives a card no boarding
    // location once >1 place is on screen (SPEC D8). Pure and cheap; the caller ordered the rows.
    val groups = remember(rows) { StopGrouping.groupByStop(rows) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        groups.forEachIndexed { index, group ->
            // The near-me list carries a per-stop distance; the watched list doesn't, so the
            // label is present only when this place's stops are in the map (D1). A place groups
            // several stops (a junction's poles), so the header shows the distance to the
            // *closest* of them — the one a rider walks to.
            val distanceLabel = group.rows
                .mapNotNull { stopDistanceMeters[it.stopId] }
                .minOrNull()
                ?.let(StopDistance::label)
            // Draw the header when the grouping asks for it (more than one place, or a closure)
            // OR whenever there's a distance to show. A lone near-me place suppresses the
            // watched-list header (StopGroup.showHeader is false for a single non-closed place),
            // but on the near-me path it must still show its name and distance — otherwise a
            // one-place result (nothing inside the inner radius, or dedup collapsing to one group)
            // would drop both the promised distance and the place name (Codex, PR #82).
            if (group.showHeader || distanceLabel != null) {
                item(key = "header|${group.key}") {
                    StopGroupHeader(group.stopName, distanceLabel, firstGroup = index == 0)
                }
            }
            items(group.rows, key = { "${it.stopId}|${it.lineId}|${it.directionKey}" }) { row ->
                DepartureRowCard(
                    row,
                    now,
                    isStarred = StarredRow.of(row) in starred,
                    onToggleStar = { onToggleStar(row) },
                    starAvailable = starringAvailable,
                )
            }
        }
    }
}

/**
 * The small stop-name header above a group of same-stop cards (SPEC D8): the stop name in
 * spaced small caps, text only — no border, no background, the muted `onSurfaceVariant`
 * role. The name hard-truncates (no ellipsis) at the edge. Extra top space (past the list's
 * 8dp item gap) marks the group break; the first group takes none. The mode-aware
 * direction/terminus qualifier is deferred to a follow-up (see `StopGrouping`).
 *
 * [distanceLabel], when set, follows the name in parens ("… (120 m)") — the near-me list's own
 * distance to this stop, so a rider can judge which nearby stop to walk to. It is null on the
 * location-free watched list, which carries no distance (D1). The distance is not uppercased, so
 * its unit stays lowercase, and it is a **reserved** trailing element: a long name clips rather
 * than pushing the distance off the edge (the same discipline as the departure row's countdown).
 */
@Composable
private fun StopGroupHeader(name: String, distanceLabel: String?, firstGroup: Boolean) {
    // labelMedium is the same role the freshness stamp uses; the tracking gives the small-caps
    // read. uppercase() is Kotlin's locale-invariant overload (safe from the Turkish-ı trap).
    val style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.8.sp)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    val headerModifier = Modifier
        .fillMaxWidth()
        .padding(start = 4.dp, end = 4.dp, top = if (firstGroup) 0.dp else 12.dp, bottom = 0.dp)
    if (distanceLabel == null) {
        // Watched list: no distance, so the header is the bare name (rendering unchanged).
        Text(
            text = name.uppercase(),
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
            // Truncate at the edge rather than elide — a stop name is recognized from its start.
            overflow = TextOverflow.Clip,
            modifier = headerModifier,
        )
        return
    }
    // Near-me list: the distance is an unweighted trailing element (measured first), so a long
    // name clips instead of pushing the distance off the end (Codex, PR #82). fill = false lets
    // the distance sit right after a short name rather than pinned to the far edge.
    Row(modifier = headerModifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = name.uppercase(),
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            text = " ($distanceLabel)",
            style = style,
            fontWeight = FontWeight.SemiBold,
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun DepartureRowCard(
    row: DepartureRow,
    now: Instant,
    isStarred: Boolean = false,
    onToggleStar: () -> Unit = {},
    starAvailable: Boolean = true,
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
    // Star (pin-to-top, SPEC D8) is toggled by a long-press on the card, not a per-row button:
    // the 48dp IconButton it replaced ate width on every row and crowded the one-line countdown
    // at large font scales. Only a timed row is starrable — a closure or no-departures status row
    // was never pinnable — and only when starring is available.
    //
    // The long-press is a `pointerInput` gesture plus a `semantics` long-click action, NOT
    // `combinedClickable`: `combinedClickable` merges the card's descendant semantics into one
    // node, which flattens the disrupted row's warning-first traversal order (the chip's
    // `traversalIndex = -1f` below) and also registers a real click action that would do nothing
    // (a misleading ripple, and a dead TalkBack activate) since a tap has no behavior yet (Codex).
    // This keeps the descendants separately ordered and exposes only the labeled long-click.
    val starrable = starAvailable && row.stopDisruption == null && row.upcoming.isNotEmpty()
    val starActionLabel = stringResource(if (isStarred) R.string.unstar else R.string.star)
    // Read the latest callback without re-keying the gesture: `DepartureList` rebuilds the per-row
    // `onToggleStar` on every recomposition, and the 10s `tickingNow` clock recomposes the rows —
    // so keying `pointerInput` on the callback would cancel an in-progress long-press each tick and
    // drop its down event (Codex). Key on `Unit` (stable) and invoke the current callback via
    // `rememberUpdatedState`.
    val currentToggleStar by rememberUpdatedState(onToggleStar)
    val cardModifier = Modifier.fillMaxWidth().let { base ->
        if (starrable) {
            base
                .pointerInput(Unit) {
                    detectTapGestures(onLongPress = { currentToggleStar() })
                }
                .semantics { onLongClick(label = starActionLabel) { currentToggleStar(); true } }
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
                // A stop-level status row: the whole stop is disrupted (a closure), so it
                // leads with the stop, not a line pill (SPEC D3).
                StopClosureContent(row.stopDisruption)
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
                // list (SPEC D8); long-press the card to pin/unpin. A visible, labeled star
                // returns with the tap-to-open stop detail view (deferred, TODO.md).
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
 * error-toned surface. The stop is named by the group header above (a closed stop always
 * shows one); the stop's own departures, if any, show in their own cards below — this card
 * is the closure notice, not a departure (SPEC D3).
 */
@Composable
private fun StopClosureContent(disruption: String) {
    // The stop name is not repeated here — the group header above names the stop (a closed
    // stop always shows its header, see StopGrouping.groupByStop). This card is the closure
    // notice alone.
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = disruption,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
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
private fun DestinationLine(
    label: String,
    times: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
    // The "via" branch (TfL's `towards`), shown parenthesized after the destination — the
    // cue a rider uses to pick a train ("Battersea Power (Charing X)"). Null for most
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
                    val fullWidth = remember(label) { measurer.measure(label, style, maxLines = 1).size.width }
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
                val fullBranch = "($branch)"
                val abbrevBranch = "(${abbreviateBranch(branch)})"
                val abbreviatedLabel = remember(label) { DestinationAbbreviations.abbreviate(label) }
                val labelWidth = remember(label) { measurer.measure(label, style, maxLines = 1).size.width }
                val abbrevLabelWidth = remember(abbreviatedLabel) { measurer.measure(abbreviatedLabel, style, maxLines = 1).size.width }
                val fullBranchWidth = remember(fullBranch) { measurer.measure(fullBranch, style, maxLines = 1).size.width }
                val abbrevBranchWidth = remember(abbrevBranch) { measurer.measure(abbrevBranch, style, maxLines = 1).size.width }
                val gapPx = with(LocalDensity.current) { 8.dp.roundToPx() }
                // Keep the full branch while even the abbreviated terminus fits beside it.
                val useFullBranch = abbrevLabelWidth + gapPx + fullBranchWidth <= constraints.maxWidth
                val branchText = if (useFullBranch) fullBranch else abbrevBranch
                val branchWidth = if (useFullBranch) fullBranchWidth else abbrevBranchWidth
                // The terminus takes what the branch leaves: full name if it fits, else the
                // abbreviated form (then a clean clip if even that is too wide).
                val availForLabel = constraints.maxWidth - gapPx - branchWidth
                val displayLabel = if (labelWidth <= availForLabel) label else abbreviatedLabel
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayLabel,
                        style = style,
                        maxLines = 1,
                        // Hard-clipped (a clean cut, no ellipsis) once abbreviating hasn't made
                        // it fit; fill = false so a short label doesn't gap before the branch.
                        overflow = TextOverflow.Clip,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            // Keep the full name for a screen reader when the visible text is shortened.
                            .then(
                                if (displayLabel != label) Modifier.semantics { contentDescription = label }
                                else Modifier,
                            ),
                    )
                    Text(
                        text = branchText,
                        style = style,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        // Hard-clipped too, so the whole destination line cuts cleanly; the
                        // abbreviation ladder above means the branch almost never overflows.
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.padding(start = 8.dp),
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
 * A service's merged countdown — "Due · 3 · 6 min" (SPEC D8, one line per destination).
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
        // extreme (a long pill + the full "Due · 3 · 6 min" at a large font, on a narrow
        // screen) even the whole line can be too short: ellipsize from the end rather than
        // hard-clip, so the soonest times — which lead the label — stay legible and the
        // truncation reads as one ("Due · 3 …").
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
