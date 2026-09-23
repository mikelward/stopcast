@file:OptIn(androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi::class)

package app.stopcast.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.core.app.ApplicationProvider
import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRow
import app.stopcast.domain.DepartureRows
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel coverage for the Glance widget (SPEC *Testing* — Glance layouts get Roborazzi
 * screenshots). Glance emits RemoteViews rather than a Compose tree, so we render
 * [WidgetContent] to real RemoteViews with [GlanceRemoteViews.compose], inflate them to a
 * `View`, and capture that — which catches clipping, sizing, color, and alignment
 * regressions the node-based [WidgetContentTest] can't see. The render decision is covered
 * separately by [WidgetModelTest]; this exercises the drawn result of each state.
 *
 * Public infrastructure/line names only in the fixtures — no user route data (SPEC *Privacy*).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetScreenshotTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun row(
        lineId: String,
        lineName: String,
        destination: String,
        offsetSeconds: Long,
        fetchedAt: Instant = now.minusSeconds(30),
        branch: String? = null,
    ) = DepartureRow(
        stopId = "490000001A",
        stopName = "Example Stop",
        lineId = lineId,
        lineName = lineName,
        direction = "inbound",
        directionKey = "inbound",
        destination = destination,
        mode = "tube",
        upcoming = listOf(
            Departure(lineId, lineName, "inbound", destination, null, now.plusSeconds(offsetSeconds), "tube", branch = branch),
        ),
        fetchedAt = fetchedAt,
    )

    private fun branchingRow() = DepartureRow(
        stopId = "490000002B",
        stopName = "Example Stop",
        lineId = "northern",
        lineName = "Northern",
        direction = "southbound",
        directionKey = "southbound",
        destination = "Battersea Power",
        mode = "tube",
        upcoming = listOf(
            Departure("northern", "Northern", "southbound", "Battersea Power", null, now.plusSeconds(180), "tube", branch = "Charing Cross"),
            Departure("northern", "Northern", "southbound", "Edgware", null, now.plusSeconds(420), "tube", branch = "Bank"),
        ),
        fetchedAt = now.minusSeconds(30),
    )

    // The groups are chosen in widgetModel; here a row's own grouping stands in for the model.
    private fun rowModel(r: DepartureRow) = WidgetRowModel(r, DepartureRows.destinationLines(r, 3))

    @Test
    fun `fresh, light`() {
        capture(
            "widget-fresh.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120)),
                    rowModel(branchingRow()),
                ),
            ),
        )
    }

    @Test
    fun `fresh, dark`() {
        capture(
            "widget-fresh-dark.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated just now",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120)),
                    rowModel(branchingRow()),
                ),
            ),
            dark = true,
        )
    }

    @Test
    fun `stale, countdowns withheld`() {
        capture(
            "widget-stale.png",
            WidgetModel(
                hasData = true,
                stale = true,
                uncertain = true,
                stamp = "Updated 12 min ago",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120, fetchedAt = now.minusSeconds(900)))),
            ),
        )
    }

    @Test
    fun `no data prompts opening the app`() {
        capture(
            "widget-empty.png",
            WidgetModel(hasData = false, stale = false, uncertain = false, stamp = null, rows = emptyList()),
        )
    }

    @Test
    fun `partial refresh flags some stops out of date`() {
        capture(
            "widget-partial.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = true,
                stamp = "Updated just now",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120))),
            ),
        )
    }

    @Test
    fun `fresh snapshot with nothing due says so`() {
        capture(
            "widget-no-departures.png",
            WidgetModel(hasData = true, stale = false, uncertain = false, stamp = "Updated just now", rows = emptyList()),
        )
    }

    // The provider's minWidth x minHeight (180x110dp): the title and the longest ordinary stamp
    // must share the header row without clipping, and the rows below get what's left.
    @Test
    fun `minimum size keeps the stamp beside the title`() {
        capture(
            "widget-min-size.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated 14 min ago",
                rows = listOf(
                    rowModel(row("victoria", "Victoria", "Brixton", 120)),
                    rowModel(branchingRow()),
                ),
            ),
            size = DpSize(180.dp, 110.dp),
        )
    }

    // Minimum size at a large system font: the title gives way, the freshness stamp stays whole.
    @Test
    fun `minimum size at a large font keeps the stamp whole`() {
        capture(
            "widget-min-size-large-font.png",
            WidgetModel(
                hasData = true,
                stale = false,
                uncertain = false,
                stamp = "Updated 14 min ago",
                rows = listOf(rowModel(row("victoria", "Victoria", "Brixton", 120))),
            ),
            size = DpSize(180.dp, 110.dp),
            fontScale = 1.3f,
        )
    }

    private fun capture(
        name: String,
        model: WidgetModel,
        dark: Boolean = false,
        size: DpSize = DpSize(240.dp, 180.dp),
        fontScale: Float = 1f,
    ) {
        if (dark) RuntimeEnvironment.setQualifiers("+night") else RuntimeEnvironment.setQualifiers("+notnight")
        // Set after the qualifiers so they can't override it; the inflated widget reads its sp sizes
        // from this context's configuration, as a real host does.
        RuntimeEnvironment.setFontScale(fontScale)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = runBlocking {
            GlanceRemoteViews().compose(context, size = size) {
                WidgetContent(model, now)
            }
        }
        val view = result.remoteViews.apply(context, FrameLayout(context))
        // Capture at the widget's own size in px (420dpi), so a small size shows its real clipping.
        val density = context.resources.displayMetrics.density
        captureSnapshot(view, name, (size.width.value * density).toInt(), (size.height.value * density).toInt())
    }

    /**
     * Draws the inflated widget view into a PNG. Measured and laid out explicitly — Robolectric's
     * views have no real surface, so an unmeasured view captures blank. Same shape as the sibling
     * screen screenshot tests; only recording/verifying actually writes a file.
     */
    private fun captureSnapshot(view: View, name: String, widthPx: Int, heightPx: Int) {
        val recording = System.getProperty("roborazzi.test.record") == "true"
        val verifying = System.getProperty("roborazzi.test.verify") == "true"
        if (!recording && !verifying) return

        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }
}
