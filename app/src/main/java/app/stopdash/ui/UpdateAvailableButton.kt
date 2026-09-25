package app.stopdash.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * The "Update available" call to action shown on the loading screens — the departures spinner
 * ([DeparturesUiState.Loading]) and the location gate's Locating spinner — when Google Play reports
 * a newer version (SPEC *Update indicator*). The location gate has no overflow menu, so this is its
 * only update affordance; on the departures spinner the overflow (with its dot) is already present,
 * so this is a more direct prompt than a dot the user may not notice while waiting. Opens the Play
 * listing via [onClick]. Outlined, not filled, so it stays a secondary offer beside the spinner —
 * the "action" of a loading screen is still to wait for the content.
 */
@Composable
fun UpdateAvailableButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.padding(top = 24.dp)) {
        Text(stringResource(R.string.update_available))
    }
}
