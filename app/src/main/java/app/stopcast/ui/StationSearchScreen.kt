package app.stopcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.stopcast.R
import app.stopcast.domain.StationMatch
import java.util.Locale

/**
 * "Find a station" (SPEC *Finding stops*): a name field in the app bar and TfL's matches below it.
 * UI-only — the query, the matches and the search itself live in [StationSearchViewModel] — so it
 * renders in a screenshot test with no network. A tap on a match opens that station's departures
 * ([onOpenStation]); Back (the arrow or the system back) closes the search.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationSearchScreen(
    state: StationSearchViewModel.State,
    onQueryChange: (String) -> Unit,
    onOpenStation: (StationMatch) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    // Off in the screenshot test, where a focused field would add a blinking cursor.
    autoFocus: Boolean = true,
) {
    BackHandler(onBack = onBack)
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = state.query,
                        onValueChange = onQueryChange,
                        placeholder = { Text(stringResource(R.string.station_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("stationSearchField"),
                    )
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Keeps the previous matches in view while the next search runs, so typing doesn't blank
            // the list on every letter; the bar says a newer answer is on its way.
            if (state.searching) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                Box(modifier = Modifier.height(4.dp))
            }
            when (val result = state.result) {
                StationSearchViewModel.Result.Idle -> Message(stringResource(R.string.station_search_prompt))
                StationSearchViewModel.Result.NoMatches -> Message(stringResource(R.string.station_search_no_matches))
                is StationSearchViewModel.Result.Failed -> Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(errorMessage(result.kind)), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                }
                is StationSearchViewModel.Result.Matches -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(result.matches, key = { it.id }) { match ->
                        MatchRow(match, onClick = { onOpenStation(match) })
                        HorizontalDivider()
                    }
                    // The bundled stations matched but TfL's search (bus stops) failed: say so under
                    // the matches rather than show them as the whole answer.
                    result.remoteFailure?.let { kind ->
                        item(key = "remote-failure") {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(R.string.station_search_bus_stops_missing), textAlign = TextAlign.Center)
                                Text(
                                    stringResource(errorMessage(kind)),
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchRow(match: StationMatch, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(match.name, style = MaterialTheme.typography.bodyLarge)
        val modes = modesLabel(match.modes)
        if (modes.isNotEmpty()) {
            Text(modes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * A match's modes as a rider reads them, in TfL's order: "Tube · Bus". TfL's mode ids are
 * lower-case and hyphenated; the known ones get their proper names, any other is spaced and
 * capitalized rather than hidden.
 */
internal fun modesLabel(modes: List<String>): String =
    modes.filter { it.isNotBlank() }.distinct().joinToString(" · ") { mode ->
        KNOWN_MODE_NAMES[mode] ?: mode.replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) }
    }

private val KNOWN_MODE_NAMES = mapOf(
    "tube" to "Tube",
    "bus" to "Bus",
    "dlr" to "DLR",
    "overground" to "Overground",
    "elizabeth-line" to "Elizabeth line",
    "national-rail" to "National Rail",
    "tram" to "Tram",
    "river-bus" to "River Bus",
    "cable-car" to "Cable car",
)

/**
 * The station page while its stops are still being looked up, or when that failed or found none:
 * the station's name in the app bar with a back arrow, so the page appears at once (AGENTS jank
 * rule) and an error says what went wrong with a way to retry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationPlaceholderScreen(
    title: String,
    state: StationStopsViewModel.State,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title, maxLines = 1) },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            when (state) {
                is StationStopsViewModel.State.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(errorMessage(state.kind)), textAlign = TextAlign.Center)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.route_stops_retry)) }
                }
                StationStopsViewModel.State.NoStops -> Message(stringResource(R.string.station_no_departures))
                else -> CircularProgressIndicator()
            }
        }
    }
}
