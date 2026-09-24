package app.stopcast.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import app.stopcast.R
import app.stopcast.domain.DirectTrips
import app.stopcast.domain.LineSequence
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.TflException
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * A searched station's page narrowed by "To…" (SPEC *Finding stops → From… To…*): the [state] to
 * render, a [notice] over the list while some departures are still being checked or couldn't be,
 * and the [emptyMessage] for an empty list.
 */
internal data class TripView(val state: DeparturesUiState, val notice: String?, val emptyMessage: String)

/**
 * [state] (a station's departures) kept to the ones that call at [destination] — [destinationName]'s
 * stops — by each line's route ([DirectTrips.filter]). [hubs] gives each origin stop's interchange.
 * The routes come from [LocalRouteStops]: the process cache at once, else fetched off the render path
 * (the same lookup a route page makes, a request or two per line a day), retried hourly while shown.
 */
@Composable
internal fun rememberTripView(
    state: DeparturesUiState,
    destination: List<DirectTrips.End>,
    destinationName: String,
    hubs: Map<String, String>,
    now: Instant,
): TripView {
    val loaded = state as? DeparturesUiState.Loaded
    val lineIds = remember(loaded?.stops) { DirectTrips.lineIds(loaded?.stops.orEmpty()) }
    val sequences = rememberLineSequences(lineIds, now)
    val checking = stringResource(R.string.trip_checking)
    val incomplete = stringResource(R.string.journey_incomplete)
    val none = stringResource(R.string.trip_none, destinationName)
    val result = remember(loaded?.stops, destination, sequences, hubs) {
        DirectTrips.filter(loaded?.stops.orEmpty(), destination, sequences, hubs)
    }
    if (loaded == null) return TripView(state, null, none)
    fun text(message: TripMessage) = when (message) {
        TripMessage.CHECKING -> checking
        TripMessage.INCOMPLETE -> incomplete
        TripMessage.NONE -> none
    }
    val messages = tripMessages(result)
    return TripView(tripLoaded(loaded, result.stops), messages.notice?.let(::text), text(messages.empty))
}

/** What a To… page says about how complete its list is (see [tripMessages]). */
internal enum class TripMessage { CHECKING, INCOMPLETE, NONE }

/** The line over a To… list ([notice], null for none) and the text of an empty one ([empty]). */
internal data class TripMessages(val notice: TripMessage?, val empty: TripMessage)

/**
 * What a filtered trip may claim (SPEC principle 2). "No direct trips" only when every departure
 * and line was checked: while a route loads the page says it's checking, and where one failed or a
 * path couldn't be followed it says some couldn't be checked, empty or not. An empty list carries
 * that itself, so no banner repeats it.
 */
internal fun tripMessages(result: DirectTrips.Result): TripMessages {
    val caveat = when {
        result.pending -> TripMessage.CHECKING
        result.unresolved -> TripMessage.INCOMPLETE
        else -> null
    }
    return if (result.stops.isEmpty()) TripMessages(null, caveat ?: TripMessage.NONE) else TripMessages(caveat, TripMessage.NONE)
}

/**
 * [loaded] narrowed to the trip's [stops], its stamp worked out again from what the page shows
 * (SPEC D4): the freshest kept stop's age, as the full page's is of all its stops, so a fresh stop
 * the filter dropped can't pass older kept departures off as just updated. An empty trip takes the
 * oldest source stop's age: with no stop left to age, "No direct trips" over aged data would be the
 * quietly-wrong case; this way the page prompts a refresh instead.
 */
internal fun tripLoaded(loaded: DeparturesUiState.Loaded, stops: List<StopArrivals>): DeparturesUiState.Loaded =
    loaded.copy(
        stops = stops,
        fetchedAt = stops.maxOfOrNull { it.fetchedAt }
            ?: loaded.stops.minOfOrNull { it.fetchedAt }
            ?: loaded.fetchedAt,
    )

/**
 * The routes (both directions) of [lineIds]: absent while loading, null when the load failed (so a
 * departure on it is flagged, not dropped as a "no"). Loaded again each hour, so a route the
 * repository let expire is refetched while the page stays up; the old copy shows meanwhile.
 */
@Composable
private fun rememberLineSequences(lineIds: List<String>, now: Instant): Map<String, LineSequence?> {
    val repository = LocalRouteStops.current
    val loaded = remember { mutableStateMapOf<String, LineSequence?>() }
    val recheck = now.epochSecond / 3600
    LaunchedEffect(repository, lineIds, recheck) {
        val routes = repository ?: return@LaunchedEffect
        for (lineId in lineIds) {
            val held = loaded[lineId]
            if (held != null && routes.cached(lineId, "") != null) continue
            loaded[lineId] = routes.cached(lineId, "") ?: try {
                routes.load(lineId, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: TflException) {
                // Logged (sanitized) by the repository. A day-old copy beats none; with none, null
                // marks the failure so the page says some routes couldn't be checked.
                held
            }
        }
    }
    return lineIds.filter { it in loaded }.associateWith { loaded[it] }
}
