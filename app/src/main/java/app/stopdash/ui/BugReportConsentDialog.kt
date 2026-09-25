package app.stopdash.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.stopdash.R

/** Test tag for the "don't ask again" toggle, so a UI test can flip it without matching copy. */
const val BUG_REPORT_DONT_ASK_TAG = "bugReportDontAsk"

/**
 * The consent screen shown before a bug report is shared (SPEC *Privacy*, `TODO.md`). It spells
 * out **exactly** what leaves the device — a diagnostic log, the **exact location**, and each
 * nearby stop's distance — because this report deliberately carries the location the rest of the
 * app keeps off the device, so the user must see that before anything is shared. Nothing is
 * assembled or sent until [onConfirm]; the share sheet is the final, separate choice of where.
 *
 * Pure: it renders strings and reports the two outcomes. [onConfirm] carries whether the user
 * ticked "don't ask again" so the caller can persist the opt-out (the report then skips this
 * screen next time). No I/O, no user data — it takes none.
 */
@Composable
fun BugReportConsentDialog(
    onConfirm: (dontAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var dontAskAgain by rememberSaveable { mutableStateOf(false) }
    // A dialog opens its own window, which doesn't inherit the theme's scaled density or its pinch
    // handler (SPEC *Display size*): [FontSizeWindow] re-applies the chosen text size to each slot
    // and [pinchFontSizeHost] keeps "pinch anywhere" working while it's up, matching AboutDialog.
    AlertDialog(
        modifier = Modifier.pinchFontSizeHost(),
        onDismissRequest = onDismiss,
        title = { FontSizeWindow { Text(stringResource(R.string.bug_report_consent_title)) } },
        text = {
            FontSizeWindow {
                // Scrollable: at a large text size, or on a short screen, the body + toggle can
                // exceed the dialog's height, which would otherwise clip the "don't ask again" row
                // and the copy naming what leaves (SPEC *Display size*; a11y). The dialog keeps its
                // own buttons pinned; only this content scrolls.
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.bug_report_consent_body))
                    Row(
                        modifier = Modifier
                            .testTag(BUG_REPORT_DONT_ASK_TAG)
                            .toggleable(
                                value = dontAskAgain,
                                role = androidx.compose.ui.semantics.Role.Checkbox,
                                onValueChange = { dontAskAgain = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(
                            checked = dontAskAgain,
                            // Null: the whole row is the toggle target (above), so the box itself is
                            // decorative and must not add a second, separate a11y toggle.
                            onCheckedChange = null,
                        )
                        Text(stringResource(R.string.bug_report_consent_dont_ask))
                    }
                }
            }
        },
        confirmButton = {
            FontSizeWindow {
                TextButton(onClick = { onConfirm(dontAskAgain) }) {
                    Text(stringResource(R.string.bug_report_consent_continue))
                }
            }
        },
        dismissButton = {
            FontSizeWindow {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        },
    )
}
