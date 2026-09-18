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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.trackmo.R
import app.trackmo.domain.Countdown
import app.trackmo.domain.Departure
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DepartureRows
import app.trackmo.domain.RelativeTime
import app.trackmo.domain.Staleness
import java.time.Duration
import java.time.Instant
import java.util.Locale
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
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    FreshnessStamp(state, now, onRefresh)
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

            is DeparturesUiState.Loaded -> LoadedContent(state, now, onRefresh, refreshing, content)

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
) {
    val stale = remember(state.fetchedAt, now) {
        Staleness.isStale(Duration.between(state.fetchedAt, now).toKotlinDuration())
    }
    // Group against the live clock, not fetch time, so departed services leave the list
    // and the order advances between fetches (SPEC D4). Line statuses stamp each row so a
    // disrupted line is marked (SPEC D3). Cheap and pure.
    val rows = remember(state.stops, state.lineStatuses, now) {
        DepartureRows.across(state.stops, now, state.lineStatuses)
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
                            if (stale) R.string.departures_stale_empty else R.string.departures_empty,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    RefreshButton(onRefresh, Modifier.padding(top = 16.dp))
                }
            } else {
                DepartureList(rows, now, stale, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FreshnessStamp(state: DeparturesUiState, now: Instant, onRefresh: () -> Unit) {
    if (state !is DeparturesUiState.Loaded) return
    val age = Duration.between(state.fetchedAt, now).toKotlinDuration()
    val text =
        if (Staleness.isStale(age)) stringResource(R.string.stale_stamp)
        else stringResource(R.string.updated_stamp, RelativeTime.formatAge(age))
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
private fun DepartureList(rows: List<DepartureRow>, now: Instant, stale: Boolean, modifier: Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(rows, key = { "${it.stopId}|${it.lineId}|${it.directionKey}" }) { row ->
            DepartureRowCard(row, now, stale)
        }
    }
}

@Composable
private fun DepartureRowCard(row: DepartureRow, now: Instant, stale: Boolean) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (row.stopDisruption != null) {
                // A stop-level status row: the whole stop is disrupted (a closure), so it
                // leads with the stop, not a line pill, and its departures — if any — are
                // still shown below in their own rows, marked not suppressed (SPEC D3).
                Text(text = row.stopName, style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(
                        text = row.stopDisruption,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
                return@Column
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinePill(lineName = row.lineName, lineId = row.lineId, mode = row.mode)
                Text(
                    text = stopLabel(row),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    // Weighted so a long stop/direction label ellipsizes into the
                    // remaining space rather than squeezing out the line name.
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            // A disrupted line is flagged here (SPEC D3) — the chip names TfL's status
            // ("Severe Delays", "Suspended"), the line itself being the pill above.
            row.status?.let { status -> DisruptionChip(status.description) }
            if (row.upcoming.isEmpty()) {
                // A status row: this line is disrupted (the chip says how) and returned no
                // predictions, so it's surfaced rather than dropped for want of a departure
                // (SPEC *Departures*). No countdown — just note there are no times.
                Text(
                    text = stringResource(R.string.status_no_departures),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            Text(
                text = row.destination.ifBlank { row.lineName },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Each upcoming departure keeps its own destination and platform, since a
            // branching direction runs several destinations in one row — so a divergent
            // train names its own destination rather than sitting under the headline's
            // (SPEC D8: a countdown is never shown under the wrong destination). Once
            // stale, the countdown is withheld ("—") rather than shown as a live number.
            row.upcoming.take(MAX_TIMES).forEach { departure ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = departureLabel(departure, row.destination),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Weighted so a long destination/platform label ellipsizes rather
                        // than consuming the row and clipping the countdown, which is the
                        // one thing on this row that must always stay visible.
                        modifier = Modifier.weight(1f).padding(end = 12.dp),
                    )
                    Text(
                        text = if (stale) WITHHELD else Countdown.label(departure, now),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color =
                            if (stale) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/**
 * Marks a row whose line is disrupted (SPEC D3). A small error-toned chip carrying TfL's
 * status wording, sat between the header and the destination so it reads before the
 * countdowns it qualifies. The pill above already names the line, so the chip is the
 * status alone ("Severe Delays"), not "Victoria line: severe delays".
 */
@Composable
private fun DisruptionChip(description: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 8.dp),
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

private fun stopLabel(row: DepartureRow): String =
    if (row.direction.isBlank()) row.stopName
    // TfL direction labels ("inbound"/"outbound") are English, so titlecase them with a
    // fixed locale — Locale.getDefault() would produce "İnbound" on a Turkish device.
    else "${row.stopName} · ${row.direction.replaceFirstChar { it.titlecase(Locale.ROOT) }}"

/**
 * The left-hand label on a departure's time row: its destination when that differs from
 * the row headline (a branching direction), then its platform. Same destination as the
 * headline → just the platform, so the common case stays uncluttered while a divergent
 * branch is always named.
 */
private fun departureLabel(departure: Departure, headlineDestination: String): String {
    val divergent = departure.destination.takeIf { it.isNotBlank() && it != headlineDestination }
    return listOfNotNull(divergent, departure.platform?.takeIf(String::isNotBlank))
        .joinToString(" · ")
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
private const val WITHHELD = "—"
