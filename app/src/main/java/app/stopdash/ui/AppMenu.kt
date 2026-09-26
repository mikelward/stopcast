package app.stopdash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopdash.R

/**
 * The app-wide overflow actions a screen's own menu offers besides its own: whether Play has a
 * newer version (the red dot and "Update available"), the bug report, and the licenses About opens.
 * The activity provides them ([LocalAppMenu]) once, so a screen several layers down — a trip — gets
 * the same menu as the list without each layer passing them on.
 */
data class AppMenuActions(
    val updateAvailable: Boolean,
    val onOpenAppListing: () -> Unit,
    val onSendBugReport: () -> Unit,
    val onOpenLicenses: () -> Unit,
)

/** The activity's [AppMenuActions]; null (a test, a preview) offers none. */
val LocalAppMenu = compositionLocalOf<AppMenuActions?> { null }

/**
 * A top bar's overflow button and its menu, as every screen shows them: the button with a red dot
 * while an update is available ([updateAvailable]), and the menu opening from it — "Update
 * available" first when there is one, then the screen's [items], each given a `close` to call as
 * it acts. The menu opens its own window, which doesn't inherit the theme's scaled density or pinch
 * handler: [FontSizeWindow] re-applies the chosen size to the items, and [pinchFontSizeHost] lets a
 * pinch resize while it's open (SPEC *Display size*); the host consumes only a two-finger pinch.
 */
@Composable
internal fun AppOverflowMenu(
    updateAvailable: Boolean,
    onOpenAppListing: () -> Unit,
    items: @Composable (close: () -> Unit) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    // Button and menu wrapped together so the dropdown anchors to the overflow button and opens from
    // it; a bare DropdownMenu sibling anchors to the row slot instead and drops from the wrong place.
    Box {
        IconButton(onClick = { expanded = true }) {
            Box {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                if (updateAvailable) {
                    val updateDescription = stringResource(R.string.update_available)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            // Off-grid 2dp: an optical nudge seating the dot into the icon's
                            // top-right corner (the standard badge spot).
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(8.dp)
                            .background(MaterialTheme.colorScheme.error, CircleShape)
                            .semantics { contentDescription = updateDescription }
                            .testTag(UPDATE_AVAILABLE_DOT_TAG),
                    )
                }
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.pinchFontSizeHost(),
        ) {
            FontSizeWindow {
                if (updateAvailable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.update_available)) },
                        onClick = {
                            expanded = false
                            onOpenAppListing()
                        },
                    )
                }
                items { expanded = false }
            }
        }
    }
}
