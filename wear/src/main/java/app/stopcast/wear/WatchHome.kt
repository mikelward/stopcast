package app.stopcast.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

/** What the watch app's home shows, worked out from what was received. */
sealed interface WatchHome {
    /** Before the stored envelope is read: just the title, never a flash of a wrong state. */
    data object Loading : WatchHome

    /** Nothing has arrived since install. */
    data object NeverSynced : WatchHome

    /** The phone sent an envelope with no stops, none left out and none missing: the user
     *  watches none, or none are nearby. */
    data object NoStops : WatchHome

    /** The widget's stops, by name. [omitted] were left out for size; [partial] if any failed. */
    data class Stops(val names: List<String>, val omitted: Int, val partial: Boolean) : WatchHome
}

fun watchHome(received: WatchReceived): WatchHome = when (received) {
    WatchReceived.Loading -> WatchHome.Loading
    WatchReceived.NeverSynced -> WatchHome.NeverSynced
    is WatchReceived.Received -> {
        val envelope = received.envelope
        // No stops because every one failed to load isn't "no stops": it's an incomplete refresh.
        if (envelope.stops.isEmpty() && envelope.omittedStops == 0 && envelope.missingStopIds.isEmpty()) {
            WatchHome.NoStops
        } else {
            WatchHome.Stops(
                names = envelope.stops.map { it.stopName }.distinct(),
                omitted = envelope.omittedStops,
                partial = envelope.missingStopIds.isNotEmpty() || envelope.stops.any { !it.arrivalsFresh },
            )
        }
    }
}

/**
 * The watch app's first screen: a setup line until the phone has sent stops, then the widget's
 * stops by name. The departures themselves come with the tile and the full watch app (TODO Phase 6).
 */
@Composable
fun WatchHomeScreen(home: WatchHome) {
    MaterialTheme {
        val background = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        when (home) {
            is WatchHome.Stops -> ScalingLazyColumn(modifier = background) {
                item { Title() }
                if (home.partial) item { Note(stringResource(R.string.watch_partly_out_of_date)) }
                items(home.names) { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (home.omitted > 0) {
                    item { Note(pluralStringResource(R.plurals.watch_more_stops_on_phone, home.omitted, home.omitted)) }
                }
            }
            else -> Box(modifier = background.padding(24.dp), contentAlignment = Alignment.Center) {
                when (home) {
                    WatchHome.NeverSynced -> Message(stringResource(R.string.watch_open_phone))
                    WatchHome.NoStops -> Message(stringResource(R.string.watch_add_stops))
                    else -> Title()
                }
            }
        }
    }
}

@Composable
private fun Title() {
    Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Message(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
