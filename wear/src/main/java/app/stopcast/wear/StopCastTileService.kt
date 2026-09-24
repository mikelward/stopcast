package app.stopcast.wear

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Box
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.FontStyle
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.LayoutElementBuilders.Text
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Border
import androidx.wear.protolayout.ModifiersBuilders.Corner
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Padding
import androidx.wear.protolayout.ModifiersBuilders.Semantics
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import app.stopcast.data.RouteTopologyStore
import app.stopcast.ui.PillColors
import app.stopcast.ui.pillColors
import com.google.common.util.concurrent.ListenableFuture
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The StopCast tile (dev-docs/wear-os.md *Surfaces*): the widget's rows, favorites first, as many as
 * fit, with their age, as a timeline the system steps through on its own ([TileTimeline]). It
 * renders only from the stored envelope, never the network; a new envelope asks for a re-render.
 */
class StopCastTileService : TileService() {
    /** The envelope and route-topology reads run here, never on the request's calling thread. */
    private val worker = Executors.newSingleThreadExecutor()

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            worker.execute {
                try {
                    val store = WatchEnvelopeStore.from(this)
                    store.load()
                    val envelope = (store.state.value as? WatchReceived.Received)?.envelope
                    // The screen bounds how many lines fit; a request that doesn't say gets the default.
                    val device = requestParams.deviceConfiguration
                    val screen = TileScreen(device.screenHeightDp, device.fontScale).takeIf { it.heightDp > 0 }
                    val schedule = TileTimeline.schedule(envelope, Instant.now(), RouteTopologyStore.load(this), screen)
                    val timeline = TimelineBuilders.Timeline.Builder()
                    for (entry in schedule.entries) {
                        val validity = TimelineBuilders.TimeInterval.Builder().setStartMillis(entry.start.toEpochMilli())
                        entry.end?.let { validity.setEndMillis(it.toEpochMilli()) }
                        timeline.addTimelineEntry(
                            TimelineBuilders.TimelineEntry.Builder()
                                .setValidity(validity.build())
                                .setLayout(LayoutElementBuilders.Layout.Builder().setRoot(TileLayout.root(this, entry.frame)).build())
                                .build(),
                        )
                    }
                    val tile = TileBuilders.Tile.Builder()
                        .setResourcesVersion(RESOURCES_VERSION)
                        .setTileTimeline(timeline.build())
                    // The entry cap cut the timeline short: ask to be re-rendered where it stops.
                    schedule.refreshAt?.let {
                        tile.setFreshnessIntervalMillis(Duration.between(Instant.now(), it).toMillis().coerceAtLeast(MIN_FRESHNESS_MS))
                    }
                    completer.set(tile.build())
                    lookUpOnce(store)
                } catch (e: Exception) {
                    // The failure type only; the system keeps the tile's last layout.
                    Log.w(TAG, "tile render failed: ${e::class.simpleName}")
                    completer.setException(e)
                }
            }
            "stopcast-tile"
        }

    /**
     * Looks up the item the Data Layer already holds, once per process (again on a later request if
     * it failed), and re-renders if it brought a different envelope. A tile added (or its app data
     * cleared) after the phone last published has none, and one whose listener couldn't read the
     * last publish holds an older one; either would otherwise wait for the next publish.
     */
    private fun lookUpOnce(store: WatchEnvelopeStore) {
        if (!lookedUp.compareAndSet(false, true)) return
        val before = (store.state.value as? WatchReceived.Received)?.envelope
        if (!ingestExisting(this, store)) {
            // Failed (logged): the next tile request tries again, rather than never in this process.
            lookedUp.set(false)
            return
        }
        val after = (store.state.value as? WatchReceived.Received)?.envelope
        if (after != null && after != before) requestUpdate(this)
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())
            "stopcast-tile-resources"
        }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val TAG = "StopCast.Tile"

        /** A floor on the re-render interval, so a cut a moment away can't ask for a tight loop. */
        private const val MIN_FRESHNESS_MS = 60_000L

        private val lookedUp = AtomicBoolean(false)

        /** Asks the system to re-render the tile, after a new envelope arrives. */
        fun requestUpdate(context: Context) {
            getUpdater(context).requestUpdate(StopCastTileService::class.java)
        }
    }
}

/** The tile's layout for one [TileFrame]. */
internal object TileLayout {
    private val white = Color.White.toArgb()
    private val gray = Color(0xFFB0ABA3).toArgb()
    private val warning = Color(0xFFF2C14E).toArgb()
    private val neutralFill = Color(0xFF303030).toArgb()

    fun root(context: Context, frame: TileFrame): LayoutElement {
        val column = Column.Builder().setWidth(expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
        when (frame) {
            TileFrame.NeverSynced -> column.addContent(message(context.getString(R.string.watch_open_phone)))
            TileFrame.NoStops -> column.addContent(message(context.getString(R.string.watch_add_stops)))
            TileFrame.NoneLoaded ->
                column.addContent(text(context.getString(R.string.watch_partly_out_of_date), 14f, warning, maxLines = 3))
            is TileFrame.Rows -> {
                val stamp = when {
                    frame.ageMinutes < 1 -> context.getString(R.string.tile_updated_now)
                    else -> context.getString(R.string.tile_updated_ago, frame.ageMinutes)
                }
                column.addContent(text(stamp, 12f, if (frame.stale) warning else gray))
                if (frame.stale || frame.partial) {
                    val note = if (frame.stale) R.string.tile_out_of_date else R.string.watch_partly_out_of_date
                    column.addContent(text(context.getString(note), 12f, warning))
                }
                if (frame.omitted > 0) {
                    val more = context.resources.getQuantityString(R.plurals.watch_more_stops_on_phone, frame.omitted, frame.omitted)
                    column.addContent(text(more, 12f, gray))
                }
                for (line in frame.lines) {
                    column.addContent(Spacer.Builder().setHeight(dp(4f)).build())
                    column.addContent(
                        when (line) {
                            is TileLine.Header -> text(line.text, 12f, gray, bold = true, spoken = line.spoken)
                            is TileLine.Departure -> row(line.row)
                            TileLine.OnlyHidden -> text(context.getString(R.string.tile_only_hidden), 14f, white, maxLines = 2)
                            is TileLine.EmptyStop -> {
                                val empty = if (line.uncertain) R.string.tile_may_be_out_of_date else R.string.tile_no_departures
                                text(context.getString(R.string.tile_stop_empty, line.stopName, context.getString(empty)), 14f, white)
                            }
                        },
                    )
                }
            }
        }
        return Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(Modifiers.Builder().setPadding(Padding.Builder().setAll(dp(24f)).build()).build())
            .addContent(column.build())
            .build()
    }

    private fun row(row: TileRow): LayoutElement =
        Row.Builder()
            .setWidth(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .addContent(pill(row))
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(
                Box.Builder().setWidth(expand())
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
                    .addContent(text(row.label, 14f, white))
                    .build(),
            )
            .addContent(Spacer.Builder().setWidth(dp(4f)).build())
            .addContent(text(row.countdown, 14f, if (row.stale) warning else white, bold = true))
            .build()

    private fun pill(row: TileRow): LayoutElement {
        val colors = pillColors(row.lineName, row.lineId, row.mode, Color.Black)
        val (fill, label, border) = when (colors) {
            is PillColors.Solid -> Triple(colors.fill.toArgb(), colors.label.toArgb(), colors.border.toArgb())
            is PillColors.Hollow -> Triple(Color.Black.toArgb(), colors.label.toArgb(), colors.border.toArgb())
            PillColors.Neutral -> Triple(neutralFill, white, neutralFill)
        }
        return Box.Builder()
            .setWidth(dp(36f))
            .setModifiers(
                Modifiers.Builder()
                    .setBackground(Background.Builder().setColor(argb(fill)).setCorner(Corner.Builder().setRadius(dp(6f)).build()).build())
                    .setBorder(Border.Builder().setWidth(dp(1f)).setColor(argb(border)).build())
                    .setPadding(Padding.Builder().setTop(dp(4f)).setBottom(dp(4f)).build())
                    // A screen reader says the line's name, not its short code (SPEC *Line pill colors*).
                    .setSemantics(semantics(row.lineName))
                    .build(),
            )
            .addContent(text(row.code, 11f, label, bold = true))
            .build()
    }

    private fun semantics(spoken: String): Semantics = Semantics.Builder().setContentDescription(spoken).build()

    private fun message(value: String): LayoutElement = text(value, 14f, white, maxLines = 3)

    /** A line of text; [spoken], when set, is what a screen reader says in its place. */
    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false,
        maxLines: Int = 1,
        spoken: String? = null,
    ): LayoutElement =
        Text.Builder()
            .setText(value)
            .apply { spoken?.let { setModifiers(Modifiers.Builder().setSemantics(semantics(it)).build()) } }
            .setMaxLines(maxLines)
            .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE)
            .setFontStyle(
                FontStyle.Builder()
                    .setSize(sp(size))
                    .setColor(argb(color))
                    .setWeight(if (bold) LayoutElementBuilders.FONT_WEIGHT_BOLD else LayoutElementBuilders.FONT_WEIGHT_NORMAL)
                    .build(),
            )
            .build()
}
