package app.stopcast.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.stopcast.R
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.LineSequence
import app.stopcast.domain.RouteStop
import app.stopcast.domain.RouteStops
import app.stopcast.domain.RouteStopsRepository
import app.stopcast.domain.TflException
import kotlinx.coroutines.CancellationException

/**
 * The route stop lists, provided once at the composition root ([app.stopcast.MainActivity]).
 * Null (the default) hides the stop list — a screenshot test or preview makes no network call.
 */
val LocalRouteStops = staticCompositionLocalOf<RouteStopsRepository?> { null }

/** What the route detail's stop list shows. */
sealed interface RouteStopsUi {
    /** No list: a status row (no train to follow), or no source wired. */
    data object Hidden : RouteStopsUi
    data object Loading : RouteStopsUi

    /**
     * The row's snapshot is stale: its soonest prediction may no longer be the next train, so its
     * train-specific stop list is withheld until fresh departures arrive (SPEC D4).
     */
    data object Stale : RouteStopsUi

    /** TfL answered, but no single path from here to this train's destination matched. */
    data object Unavailable : RouteStopsUi
    data class Failed(val kind: DeparturesUiState.Error.Kind) : RouteStopsUi
    data class Loaded(val destination: String, val stops: List<RouteStop>) : RouteStopsUi
}

/**
 * The stop list for [row]'s soonest departure: from the boarding stop through that train's
 * destination. Rendered at once from the in-memory cache when this line was already fetched this
 * process, else [RouteStopsUi.Loading] while it fetches off the render path (SPEC D8, route detail).
 * [retry] bumps to refetch after a failure.
 */
@Composable
internal fun rememberRouteStops(row: DepartureRow, retry: Int): RouteStopsUi {
    val repository = LocalRouteStops.current
    val next = row.upcoming.firstOrNull()
    if (repository == null || next == null || row.lineId.isBlank()) return RouteStopsUi.Hidden
    val destination = next.destination
    fun resolve(sequence: LineSequence): RouteStopsUi =
        RouteStops.ahead(sequence, row.stopId, destination, next.branch)
            ?.let { RouteStopsUi.Loaded(destination, it) }
            ?: RouteStopsUi.Unavailable
    // Keyed by the followed train, so a change of soonest train (a refresh, or one departing)
    // discards the old state outright: the first frame for the new train is its cached list or
    // Loading, never the previous train's stops.
    return key(repository, row.lineId, row.direction, row.stopId, destination, next.branch) {
        val initial = remember { repository.cached(row.lineId, row.direction)?.let(::resolve) ?: RouteStopsUi.Loading }
        val state by produceState(initial, retry) {
            if (value !is RouteStopsUi.Loading && value !is RouteStopsUi.Failed) return@produceState
            value = RouteStopsUi.Loading
            value = try {
                resolve(repository.load(row.lineId, row.direction))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Already logged (sanitized) by the repository; surfaced here with its reason.
                RouteStopsUi.Failed(
                    when (e) {
                        is TflException.Offline -> DeparturesUiState.Error.Kind.OFFLINE
                        is TflException.RateLimited -> DeparturesUiState.Error.Kind.RATE_LIMITED
                        is TflException.Unreachable -> DeparturesUiState.Error.Kind.UNREACHABLE
                    },
                )
            }
        }
        state
    }
}

/**
 * The route detail's "Stops to X" section: every station from the boarding stop to the train's
 * destination on a rail in the line's [railColor], the boarding stop and terminus solid and bold.
 */
@Composable
internal fun RouteStopsSection(
    state: RouteStopsUi,
    railColor: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val note = when (state) {
        RouteStopsUi.Hidden -> return
        RouteStopsUi.Loading -> stringResource(R.string.route_stops_loading)
        RouteStopsUi.Unavailable -> stringResource(R.string.route_stops_unavailable)
        RouteStopsUi.Stale -> stringResource(R.string.route_stops_stale)
        is RouteStopsUi.Failed -> stringResource(routeStopsFailureMessage(state.kind))
        is RouteStopsUi.Loaded -> null
    }
    Column(modifier = modifier.fillMaxWidth()) {
        if (state is RouteStopsUi.Loaded) {
            Text(
                text = stringResource(R.string.route_stops_title, state.destination),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            state.stops.forEachIndexed { index, stop ->
                StopOnRail(
                    name = stop.name.ifBlank { stop.id },
                    railColor = railColor,
                    first = index == 0,
                    last = index == state.stops.lastIndex,
                )
            }
        } else if (note != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (state is RouteStopsUi.Failed) {
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                }
            }
        }
    }
}

/** One station: a dot on the line-colored rail, then its name. The ends of the rail stop at the dot. */
@Composable
private fun StopOnRail(name: String, railColor: Color, first: Boolean, last: Boolean) {
    val surface = MaterialTheme.colorScheme.surface
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Intrinsic height so the rail spans a wrapped two-line name without a gap.
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 32.dp),
    ) {
        Box(
            modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2
                    val mid = size.height / 2
                    val rail = 4.dp.toPx()
                    drawLine(
                        color = railColor,
                        start = Offset(x, if (first) mid else 0f),
                        end = Offset(x, if (last) mid else size.height),
                        strokeWidth = rail,
                    )
                    val radius = 6.dp.toPx()
                    // The boarding stop and terminus are solid; a calling point is hollow, like a
                    // TfL line diagram's tick.
                    if (first || last) {
                        drawCircle(railColor, radius, Offset(x, mid))
                    } else {
                        drawCircle(surface, radius, Offset(x, mid))
                        drawCircle(railColor, radius - 1.dp.toPx(), Offset(x, mid), style = Stroke(2.dp.toPx()))
                    }
                },
        )
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (first || last) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
        )
    }
}

private fun routeStopsFailureMessage(kind: DeparturesUiState.Error.Kind): Int = when (kind) {
    DeparturesUiState.Error.Kind.OFFLINE -> R.string.route_stops_failed_offline
    DeparturesUiState.Error.Kind.RATE_LIMITED -> R.string.route_stops_failed_rate_limited
    DeparturesUiState.Error.Kind.UNREACHABLE -> R.string.route_stops_failed_unreachable
}

/** The rail color for a row's line: the color its pill takes (TfL line, rail operator, Overground accent), kept visible on the surface, else a neutral tone. */
@Composable
internal fun railColorFor(row: DepartureRow): Color {
    val surface = MaterialTheme.colorScheme.surface
    // Nudged for contrast on the page surface, as the hollow pill's border is: a thin rail in a
    // dark line color (the Northern line's black, a dark Overground accent) would otherwise
    // vanish in dark theme.
    return lineAccentColor(row.lineId, row.mode, row.lineName)?.let { accentEdgeOn(it, surface) }
        ?: MaterialTheme.colorScheme.outline
}

/** The color a line is drawn in, in the pill's own lookup order, or null when it has none. */
internal fun lineAccentColor(lineId: String, mode: String, lineName: String): Color? =
    lineFillColor(lineId, mode)
        ?: railOperatorColor(mode, lineName)
        ?: overgroundAccentColor(lineId)
