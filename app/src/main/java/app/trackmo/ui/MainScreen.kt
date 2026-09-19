@file:OptIn(ExperimentalMaterial3Api::class)

package app.trackmo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trackmo.R
import app.trackmo.domain.Countdown
import app.trackmo.domain.Departure
import app.trackmo.domain.DepartureLabels
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DepartureRows
import app.trackmo.domain.RelativeTime
import app.trackmo.domain.Staleness
import app.trackmo.domain.StarredRow
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration

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
    // Re-resolve the nearby set at the device's current position. Non-null only in the
    // "near me now" context; a watched-stops view (Phase 2) omits it. Temporary — a manual
    // re-locate for on-device testing of the nearby radius (TODO: auto-locate-on-open UX).
    onLocateHere: (() -> Unit)? = null,
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
) {
    val snackbarHostState = remember { SnackbarHostState() }
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
                actions = {
                    FreshnessStamp(state, now, onRefresh)
                    // Left of refresh: re-find stops at the current location.
                    if (onLocateHere != null) {
                        IconButton(onClick = onLocateHere) {
                            Icon(CrosshairIcon, contentDescription = stringResource(R.string.locate_here))
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.refresh))
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
        val deduped =
            if (stopDistanceMeters.isEmpty()) across
            else DepartureRows.nearbyDeduped(across, stopDistanceMeters)
        // Lift the user's starred services to the top (SPEC D8), warnings still leading.
        DepartureRows.pinStarred(deduped, starred)
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
                DepartureList(rows, now, starred, onToggleStar, starringAvailable, Modifier.fillMaxSize())
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
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { "${it.stopId}|${it.lineId}|${it.directionKey}" }) { row ->
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        // A traversal group so a disrupted timed row can announce its status chip *before*
        // the destination and countdown it qualifies (the chip is placed below but carries
        // a lower traversalIndex) — a screen reader shouldn't voice a departure as
        // actionable before its warning (SPEC principle 2).
        Column(modifier = Modifier.padding(16.dp).semantics { isTraversalGroup = true }) {
            if (row.stopDisruption != null) {
                // A stop-level status row: the whole stop is disrupted (a closure), so it
                // leads with the stop, not a line pill (SPEC D3).
                StopClosureContent(row.stopName, row.stopDisruption)
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

            // The next few times, grouped by destination and ordered soonest-first: the
            // soonest destination leads, and a branching direction (same line, same
            // direction, different destinations) keeps each destination on its own line
            // with its own merged countdown, so a countdown is never read under the wrong
            // destination (SPEC D8). Take before grouping so the card shows a bounded few
            // times total. Each destination line renders identically — the leading one is
            // not styled as a bigger "headline" — so a two-destination card reads as a
            // parallel pair, not a headline plus an afterthought.
            val byDestination = row.upcoming.take(MAX_TIMES).groupBy { it.destination }
            // The soonest group first (it holds `row.destination`), then the rest in their
            // soonest-first encounter order.
            val destinationLines = buildList {
                byDestination[row.destination]?.let { add(row.destination to it) }
                byDestination.forEach { (destination, times) ->
                    if (destination != row.destination) add(destination to times)
                }
            }

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
                    destinationLines.forEachIndexed { index, (destination, times) ->
                        DestinationLine(
                            // The destination, or the direction key (direction word, else
                            // platform) as a cue when TfL gives no destination, so cards TfL
                            // keeps distinct stay distinguishable (SPEC principle 1).
                            label = DepartureLabels.destinationLabel(destination, row.directionKey)
                                ?: stringResource(R.string.destination_unknown),
                            times = times,
                            stale = stale,
                            now = now,
                            // Space the lines of a branching card apart; the first hugs the
                            // pill's top.
                            modifier = if (index == 0) Modifier else Modifier.padding(top = 8.dp),
                        )
                    }
                }
                // Trailing star pins this service to the top (SPEC D8). Filled + primary when
                // starred, the vendored outline in a low-emphasis tone when not, so the two
                // states read at a glance. The countdown inside the column is unweighted and
                // measured first, so it keeps its width; the destination ellipsizes instead.
                // Hidden entirely when starring is unavailable (a newer-schema star file this
                // build can't read), so no pill falsely reads as "not starred".
                if (starAvailable) {
                    StarButton(isStarred = isStarred, onToggleStar = onToggleStar)
                }
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
 * A stop closure (a stop-level disruption) as the card's content: the stop name, then the
 * disruption in an error-toned surface. The stop's own departures, if any, show in their
 * own cards elsewhere — this card is the closure notice, not a departure (SPEC D3).
 */
@Composable
private fun StopClosureContent(stopName: String, disruption: String) {
    Text(
        text = stopName,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
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
 * with no countdown. The destination elides so a long name truncates rather than crowding
 * out the countdown.
 */
@Composable
private fun DestinationLine(
    label: String,
    times: List<Departure>,
    stale: Boolean,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
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

/**
 * The per-card star toggle (SPEC D8): pins the service to the top of the list when on. Filled
 * `Star` in the theme's primary tint for the starred state, the vendored [StarBorderIcon]
 * outline in a low-emphasis tone for the unstarred one, so the two states read at a glance. The
 * `IconButton` is a 48dp target (above the 44dp touch floor); its content description flips so a
 * screen reader announces the action, not just "star".
 */
@Composable
private fun StarButton(isStarred: Boolean, onToggleStar: () -> Unit) {
    IconButton(onClick = onToggleStar) {
        Icon(
            imageVector = if (isStarred) Icons.Filled.Star else StarBorderIcon,
            contentDescription = stringResource(if (isStarred) R.string.unstar else R.string.star),
            tint =
                if (isStarred) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
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
