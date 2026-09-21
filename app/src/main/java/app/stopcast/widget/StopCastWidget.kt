package app.stopcast.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.stopcast.MainActivity
import app.stopcast.StopcastDebugLog
import app.stopcast.data.DataStoreSnapshotStore
import app.stopcast.data.DataStoreStarredRowsStore
import app.stopcast.data.RouteTopologyStore
import app.stopcast.domain.Countdown
import app.stopcast.domain.DepartureLabels
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.DestinationGroup
import app.stopcast.domain.RelativeTime
import app.stopcast.domain.RouteTopology
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StarredRowSet
import app.stopcast.domain.abbreviateBranch
import app.stopcast.domain.lineCode
import app.stopcast.ui.lineFillColor
import app.stopcast.ui.textColorOn
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The home-screen (and, where Android 16 QPR allows, lock-screen) widget. It renders the
 * **persisted** departures snapshot ([DataStoreSnapshotStore]) — the same last-good the app
 * writes and restores (SPEC *One widget, many surfaces*) — never the network: `provideGlance`
 * reads the snapshot once off the render path and renders from it, so the widget can't stall
 * on a fetch. The stamp keeps it honest when the data is old, and tapping opens the app.
 *
 * **Interim data source**: until Phase 2's user-chosen watched stops, the app feeds this the
 * last *nearby* set it fetched (see the save-only [WidgetSnapshotStore] wiring in
 * `MainActivity`), so the widget shows "the stops near where you last opened the app". Phase 2
 * replaces that with the watched stops; a live-refresh cadence for the widget is D5.
 */
class StopCastWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Off the render path: read the persisted snapshot before composing. A read failure
        // degrades to the empty state (open-the-app prompt) rather than crashing the host —
        // but cancellation is rethrown (never swallowed, which would break structured
        // concurrency) and a real read failure is logged, sanitized, so it's not silent.
        val snapshot = try {
            // Pass the sanitized warn sink so a *corrupt* file is logged, not silently
            // dropped: the corruption handler consumes the CorruptionException and calls this
            // callback (the catch below only sees a read that throws all the way out, which a
            // handled corruption doesn't). from() uses the first caller's warn, so both this
            // and WidgetSnapshotStore wire it — whichever initializes the singleton first.
            DataStoreSnapshotStore.from(context, warn = ::logWidgetSnapshotWarning).load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget snapshot read failed: ${e::class.simpleName}")
            null
        }
        // The starred set (SPEC D8), read once off the render path like the snapshot, so the
        // widget pins a starred service to the top exactly as the in-app list does. A set this
        // build can't read (Unavailable) or a read failure degrades to "nothing pinned" — the
        // rows still render soonest-first — rather than crashing the host.
        val starred = try {
            when (val set = DataStoreStarredRowsStore.from(context, warn = ::logWidgetSnapshotWarning).starred().first()) {
                is StarredRowSet.Loaded -> set.starred
                StarredRowSet.Unavailable -> emptySet()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget starred read failed: ${e::class.simpleName}")
            emptySet()
        }
        val now = Instant.now()
        // Arm the one-shot staleness-boundary redraw from the snapshot we're about to render, on
        // the render path itself: first add, host rebind, and the app's updateAll after a fetch
        // all go through here, so each arms the flip from the snapshot it just drew — and a host
        // with no widget never runs this, so a widgetless user is never scheduled for (SPEC D4).
        scheduleStalenessRedrawFor(context, snapshot?.fetchedAt, now)
        // A widget render means a widget exists, so resume the opt-in live-refresh chain if the
        // setting is on and it isn't already running — the worker retires the chain when the last
        // widget is removed, and this restarts it after one is re-added (SPEC D5, Codex P1 on #56).
        // Wrapped: this is an optional scheduler-recovery step, so a settings/WorkManager I/O error
        // here must not fail the render — the snapshot is already loaded and must still paint
        // (SPEC jank-free UI, Codex P2 on #56).
        try {
            resumeWidgetRefreshIfEnabled(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget refresh resume failed: ${e::class.simpleName}")
        }
        // The bundled branch topology (shared, cached instance), so the widget merges/labels a
        // branching row exactly as the in-app card does.
        val topology = RouteTopologyStore.load(context)
        provideContent { WidgetContent(widgetModel(snapshot, now, starred, topology = topology), now) }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        super.onDelete(context, glanceId)
        // When the last instance is removed, cancel the pending staleness redraw so a
        // widgetless user isn't left with a scheduled wake. Best-effort — the schedule path's
        // own installed-widget guard is the reliable backstop on the next save; here we catch
        // the removed-while-app-closed case. Cancellation rethrown; other failures logged.
        try {
            if (GlanceAppWidgetManager(context).getGlanceIds(StopCastWidget::class.java).isEmpty()) {
                cancelWidgetStalenessRedraw(context)
                // Also retire the opt-in live-refresh chain — a widgetless user isn't left with a
                // ~1/min fetch loop (Codex P1 on #56). The worker's own installed-widget guard is
                // the backstop; this catches the removed-while-app-closed case promptly.
                cancelWidgetRefresh(context)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget staleness redraw cancel on delete failed: ${e::class.simpleName}")
        }
    }
}

/**
 * The widget's render inputs, derived purely from the snapshot and the clock so the decision
 * of what to show is unit-testable without a Glance host. [rows] is line-budgeted so a long or
 * branching list can't push the widget past its cell (see [WidgetRowModel]); [stale] drives the
 * "tap to refresh" note (SPEC D4 — old data is marked, not passed off as live).
 */
internal data class WidgetModel(
    val hasData: Boolean,
    val stale: Boolean,
    val uncertain: Boolean,
    val stamp: String?,
    val rows: List<WidgetRowModel>,
)

/**
 * One rendered widget row: a [DepartureRow] and the exact per-(destination, branch) [groups] to
 * draw for it. The groups are chosen in [widgetModel], not by the renderer, so the fixed-height
 * widget's line budget is decided in one pure, tested place — a branching row that would overflow
 * shows only the groups that fit (line-exact), rather than expanding to an unbounded height (D8
 * parity within the budget the cell allows).
 */
internal data class WidgetRowModel(val row: DepartureRow, val groups: List<DestinationGroup>)

internal fun widgetModel(
    snapshot: DeparturesSnapshot?,
    now: Instant,
    starred: Set<StarredRow> = emptySet(),
    maxLines: Int = 6,
    topology: RouteTopology = RouteTopology.EMPTY,
): WidgetModel {
    if (snapshot == null || snapshot.stops.isEmpty()) {
        return WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList())
    }
    val age = Duration.between(snapshot.fetchedAt, now)
    val stale = Staleness.isStale(age.toKotlinDuration())
    // Whether an empty list can be trusted as a real "no departures". Only when every stop is
    // fresh: a stop that wasn't refreshed (arrivalsFresh = false, carried from a partial
    // refresh) or is stale by its own age might have expired predictions, not a true absence —
    // so an overall-fresh snapshot can still be uncertain if one stop lagged (SPEC D4 /
    // principle 1). Mirrors MainScreen's emptyStateUncertain; drives the empty-state message,
    // not the stamp.
    val uncertain = stale || snapshot.stops.any {
        !it.arrivalsFresh || Staleness.isStale(Duration.between(it.fetchedAt, now).toKotlinDuration())
    }
    // Order for the cap, matching the in-app list: rank fresh rows ahead of stale ones (so a
    // partial refresh doesn't spend every slot on `?`-withheld stale rows and drop a trustworthy
    // later one — stable, preserving `across`'s soonest-first order within each group), then pin
    // the user's starred services to the top (SPEC D8), so a starred service past the cap isn't
    // dropped. Warnings still lead (pinStarred keeps them above even a starred row). Distance
    // ordering (nearbyDeduped / byStopDistance) stays a deferred follow-up — moot once Phase 2's
    // watched stops replace the interim nearby source (TODO).
    val ordered = DepartureRows.across(snapshot.stops, now)
        .sortedBy { if (Staleness.isStale(Duration.between(it.fetchedAt, now).toKotlinDuration())) 1 else 0 }
    // Bound the widget by total RENDERED lines, not outer rows: a branching (line, direction)
    // row expands to one line per destination/branch group, and the widget has a fixed height,
    // so a single multi-destination service must not push later services off the bottom. Fill
    // the line budget group-by-group across the ordered rows: each row contributes as many of
    // its groups as still fit, and once the budget is spent no more rows are added. This bounds
    // an oversized *first* row too (it shows at most `maxLines` groups) rather than exempting
    // it. Groups within a row keep destinationLines' soonest-first order and per-line time cap.
    val pinned = DepartureRows.pinStarred(ordered, starred)
    val rows = buildList {
        var used = 0
        for (row in pinned) {
            if (used >= maxLines) break
            val groups = DepartureRows.destinationLines(row, WIDGET_MAX_TIMES, topology)
            val shown = if (groups.size <= maxLines - used) groups else groups.take(maxLines - used)
            add(WidgetRowModel(row, shown))
            used += shown.size
        }
    }
    return WidgetModel(
        hasData = true,
        stale = stale,
        uncertain = uncertain,
        stamp = "Updated ${RelativeTime.formatAge(age.toKotlinDuration())}",
        rows = rows,
    )
}

@androidx.compose.runtime.Composable
internal fun WidgetContent(model: WidgetModel, now: Instant) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = "StopCast",
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            model.stamp?.let { stamp ->
                // The stamp reflects the *freshest* stop, so on a partial refresh (one stop
                // fresh, another carried `arrivalsFresh = false` but not yet age-stale) a clean
                // "Updated just now" would present the whole list as freshly refreshed while a
                // carried row still shows a live countdown. Surface `uncertain` here — the same
                // flag the empty state uses — so one fresh stop can't mask the others (SPEC D4).
                // A whole-snapshot-stale stamp keeps its stronger "tap to refresh".
                val text = when {
                    model.stale -> "$stamp · tap to refresh"
                    model.uncertain -> "$stamp · some stops out of date"
                    else -> stamp
                }
                Text(
                    text = text,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            when {
                !model.hasData ->
                    WidgetMessage("Open StopCast to load departures")
                // Has a snapshot but no rows to show — every service has departed or the
                // stops returned none. Distinguish a trustworthy "none" from data too old to
                // assert that (SPEC D4), rather than leaving the widget blank below the header.
                model.rows.isEmpty() ->
                    WidgetMessage(
                        if (model.uncertain) "Departures may be out of date" else "No upcoming departures",
                    )
                else ->
                    model.rows.forEach { rowModel ->
                        WidgetRow(rowModel, now)
                        Spacer(GlanceModifier.height(8.dp))
                    }
            }
        }
    }
}

/** A single-line message row (empty / prompt states), styled like the stamp. */
@androidx.compose.runtime.Composable
private fun WidgetMessage(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

/**
 * The label the widget shows for a destination [group] of [row]: the resolved destination (or
 * the direction-key cue when TfL gives no destination), with the via-branch appended in the
 * board's short form ("Edgware (Charing X)") when the row branches. The branch is
 * [abbreviateBranch]'d unconditionally here — the compact widget can't measure available width
 * the way the in-app card does, so it shows the short form rather than risk clipping the full
 * one; a branch with no shortenable word comes through unchanged. Combined into one string so
 * the countdown beside it stays column-aligned.
 */
internal fun widgetLineLabel(row: DepartureRow, group: DestinationGroup): String {
    val base = DepartureLabels.destinationLabel(group.destination, row.directionKey) ?: "—"
    return if (group.branch != null) "$base (${abbreviateBranch(group.branch)})" else base
}

@androidx.compose.runtime.Composable
private fun WidgetRow(rowModel: WidgetRowModel, now: Instant) {
    val row = rowModel.row
    // Withhold this row's countdown once ITS stop is stale (per-row, from the row's own fetch
    // age — a fresh stop beside a stale one stays live), so old predictions aren't shown as
    // live-looking numbers (SPEC D4). "?" means "unknown", matching the in-app card.
    val stale = Staleness.isStale(Duration.between(row.fetchedAt, now).toKotlinDuration())
    // A branching (service, direction) row keeps each destination — and each via-branch of one
    // terminus — on its own line with its own countdown, so a divergent train's time never sits
    // under the wrong destination or branch (SPEC D8). The groups were chosen (and line-budgeted)
    // in widgetModel from the shared destinationLines, so the card and widget can't drift.
    val lines = rowModel.groups
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        // Single line → pill level with it; a branching row top-aligns the pill so it hugs the
        // first line rather than floating against the set (matching the in-app card).
        verticalAlignment = if (lines.size > 1) Alignment.Top else Alignment.CenterVertically,
    ) {
        WidgetPill(row)
        Spacer(GlanceModifier.width(8.dp))
        Column(modifier = GlanceModifier.defaultWeight()) {
            lines.forEachIndexed { index, group ->
                if (index > 0) Spacer(GlanceModifier.height(4.dp))
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = widgetLineLabel(row, group),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = if (stale) "?" else Countdown.mergedLabel(group.times, now),
                        maxLines = 1,
                        style = TextStyle(
                            color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }
        }
    }
}

/** The line pill — short code visible, full line name to TalkBack, fixed-width so a column of
 *  pills and the labels beside them line up (SPEC fixed-width pill invariant). */
@androidx.compose.runtime.Composable
private fun WidgetPill(row: DepartureRow) {
    val fill = lineFillColor(row.lineId, row.mode)
    Box(
        modifier = GlanceModifier
            .background(if (fill != null) ColorProvider(fill) else GlanceTheme.colors.surfaceVariant)
            .cornerRadius(6.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            // The visible label is the short code; the accessible label is the full line
            // name, so TalkBack announces "Victoria", not "VIC" (SPEC parity with the app).
            .semantics { contentDescription = row.lineName },
    ) {
        Text(
            text = lineCode(row.lineName, row.mode),
            modifier = GlanceModifier.width(WIDGET_PILL_LABEL_WIDTH),
            style = TextStyle(
                color = if (fill != null) ColorProvider(textColorOn(fill)) else GlanceTheme.colors.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/**
 * Sanitized log sink for the widget's snapshot read — a discarded corrupt file, or a read
 * that threw. Class name / a fixed reason only, never a stop id or coordinate (SPEC Privacy).
 * A top-level function so both [StopCastWidget.provideGlance] and [WidgetSnapshotStore] can
 * wire it into `DataStoreSnapshotStore.from`, which keeps the first caller's sink.
 */
internal fun logWidgetSnapshotWarning(message: String) = StopcastDebugLog.warning("widget: %s", message)

/**
 * The widget line pill's fixed label width — every pill is the same size down the column so
 * destinations and countdowns line up (the SPEC fixed-width pill invariant, matching the in-app
 * [app.stopcast.ui.LinePill]). Sized to the widest code shown — a four-character bus route
 * (`N550`, `SL10`) — so nothing truncates; a shorter code centers with more room. A fixed dp
 * (Glance has no font-scale hook like the in-app pill's), which suits the widget's fixed 12sp
 * label.
 */
private val WIDGET_PILL_LABEL_WIDTH = 48.dp

/**
 * How many of a row's next departures the widget shows across its destination lines, matching
 * the in-app card's `MAX_TIMES` — passed to [DepartureRows.destinationLines], which caps the
 * row before grouping so a frequent service can't render an arbitrarily long merged countdown
 * that crowds the destination out of the fixed-width row.
 */
private const val WIDGET_MAX_TIMES = 3
