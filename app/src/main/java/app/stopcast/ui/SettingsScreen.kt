package app.stopcast.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.stopcast.R
import app.stopcast.domain.DEFAULT_FONT_SCALE
import app.stopcast.domain.MAX_FONT_SCALE
import app.stopcast.domain.MIN_FONT_SCALE
import app.stopcast.domain.fontScalePercent

/**
 * The Settings screen, hosted at the activity top level (like [LicensesScreen]) so it is
 * reachable from every state via the overflow menu, and rendered as an overlay whose own Back
 * closes it. Its first setting is the opt-in "refresh widget every minute" toggle (SPEC D5).
 *
 * Kept UI-only: it reflects [liveWidgetRefresh] and reports a change through
 * [onLiveWidgetRefreshChange]; persistence (the settings store) and the refresh scheduler
 * (WorkManager) are wired by the caller, so this composable stays JVM/Robolectric-renderable
 * for the screenshot test without touching Android services.
 */
@Composable
fun SettingsScreen(
    liveWidgetRefresh: Boolean,
    onLiveWidgetRefreshChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    liveWidgetRefreshFailed: Boolean = false,
    onDismissLiveWidgetRefreshError: () -> Unit = {},
    // False while the persisted setting hasn't been read yet (a slow or persistently-failing
    // DataStore read leaves the flow silent): the control is disabled so the user can't act on a
    // value that may not reflect the stored choice — showing it off-and-tappable would let a
    // previously-enabled install read as off (Codex P2 on #56).
    liveWidgetRefreshEnabled: Boolean = true,
) {
    BackHandler(onBack = onBack)
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // Title with a Back button at the end, matching the licenses screen.
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
            }
            // The rows scroll under the fixed header, so a large text size (up to 160%) stacked on
            // a large Android font scale can't push the lower controls off a short screen where
            // they'd be unreachable (Codex).
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Text size (display scaling, SPEC *Display size*): a slider that mirrors — and
                // moves — the same size a two-finger pinch changes, plus the switch that gates the
                // pinch. Read from the theme's shared handle so the slider, the pinch, and the app
                // all move one value; absent only outside the theme (a bare test render), where the
                // section hides.
                val fontSize = LocalFontSizeState.current
                if (fontSize != null) {
                    TextSizeRow(
                        scale = fontSize.scale,
                        onPreview = fontSize::preview,
                        onSettled = { fontSize.commit(fontSize.scale) },
                        onReset = { fontSize.chooseScale(DEFAULT_FONT_SCALE) },
                    )
                    SettingSwitchRow(
                        title = stringResource(R.string.settings_pinch_title),
                        summary = stringResource(R.string.settings_pinch_summary),
                        checked = fontSize.pinchEnabled,
                        onCheckedChange = fontSize::choosePinch,
                        switchTestTag = "pinchSwitch",
                    )
                }
                SettingSwitchRow(
                    title = stringResource(R.string.settings_live_widget_refresh_title),
                    summary = stringResource(R.string.settings_live_widget_refresh_summary),
                    checked = liveWidgetRefresh,
                    onCheckedChange = onLiveWidgetRefreshChange,
                    enabled = liveWidgetRefreshEnabled,
                    switchTestTag = "liveWidgetSwitch",
                )
                // Surfaced when applying the setting failed to schedule the refresh (a rare
                // DataStore or WorkManager error) — the choice is kept and self-heals, but the user
                // is told rather than left guessing (SPEC principle 2: fail visibly, not silently).
                if (liveWidgetRefreshFailed) {
                    LiveWidgetRefreshErrorRow(onDismiss = onDismissLiveWidgetRefreshError)
                }
            }
        }
    }
}

/** The "couldn't update live refresh" notice with a Dismiss action, shown under the toggle. */
@Composable
private fun LiveWidgetRefreshErrorRow(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.settings_live_widget_refresh_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(16.dp))
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
    }
}

/**
 * The text-size control: the title with the current percentage and a Reset, over a slider across
 * the offered range (SPEC *Display size*). It moves the same size a two-finger pinch does — both
 * write one value — so a user who can't pinch (or would rather not) has an equivalent control.
 *
 * The slider is hosted at the **unscaled** density ([StableInputDensity]): the size it drags
 * changes the density every frame, and Compose restarts a pointer handler when the density under
 * it changes, so a slider under the scaled density would die on its first resizing movement.
 */
@Composable
private fun TextSizeRow(
    scale: Float,
    onPreview: (Float) -> Unit,
    onSettled: () -> Unit,
    onReset: () -> Unit,
) {
    val percent = fontScalePercent(scale)
    val sliderDescription = stringResource(R.string.settings_text_size_content_description, percent)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Weighted so at a large text size (up to 160% over a large Android font scale) the
            // title wraps and yields width; the percentage and Reset are then measured at their
            // full size and stay reachable rather than being clipped off the row's end (Codex).
            Text(
                text = stringResource(R.string.settings_text_size_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.settings_text_size_value, percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onReset) { Text(stringResource(R.string.settings_text_size_reset)) }
            }
        }
        StableInputDensity {
            Slider(
                value = scale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
                onValueChange = onPreview,
                onValueChangeFinished = onSettled,
                valueRange = MIN_FONT_SCALE..MAX_FONT_SCALE,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("textSizeSlider")
                    .semantics { contentDescription = sliderDescription },
            )
        }
    }
}

/**
 * A labelled setting row: a title + explanatory summary on the left, a [Switch] on the right.
 * The whole row is clickable so the tap target is the full width, not just the switch (the
 * fewer-larger-targets discipline the sibling apps follow). [switchTestTag] disambiguates the
 * switches when a screen has more than one.
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    switchTestTag: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (switchTestTag != null) Modifier.testTag(switchTestTag) else Modifier,
        )
    }
}
