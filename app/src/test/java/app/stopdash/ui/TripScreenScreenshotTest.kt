package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.Departure
import app.stopdash.domain.LineRoute
import app.stopdash.domain.LineSequence
import app.stopdash.domain.LineStatus
import app.stopdash.domain.RouteSequenceSource
import app.stopdash.domain.RouteStopsRepository
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip with a change (SPEC *Trips with a change*): the routes, a route leg by leg, and the
 * planning and failed states. A trip between two well-known stations; the times and the Jubilee
 * delay are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TripScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")

    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private fun leg(
        mode: String,
        lineId: String,
        lineName: String,
        from: Pair<String, String>,
        to: Pair<String, String>,
        departs: Long,
        arrives: Long,
        stops: Int,
        change: Long = 0,
    ) = TripLeg(
        mode = mode,
        lineId = lineId,
        lineName = lineName,
        fromId = from.first,
        fromName = from.second,
        toId = to.first,
        toName = to.second,
        departure = at(departs),
        arrival = at(arrives),
        path = List(stops - 1) { "stop$it" } + to.first,
        changeAfter = Duration.ofMinutes(change),
    )

    private val highbury = "910GHGHI" to "Highbury & Islington"
    private val whitechapel = "910GWCHAPEL" to "Whitechapel"
    private val whitechapelXr = "910GWCHAPXR" to "Whitechapel"
    private val canaryWharfXr = "910GCANWHRF" to "Canary Wharf"
    private val canadaWater = "910GCNDAW" to "Canada Water"
    private val canadaWaterTube = "940GZZLUCWR" to "Canada Water"
    private val canaryWharf = "940GZZLUCYF" to "Canary Wharf"
    private val stratford = "910GSTFD" to "Stratford (London)"
    private val stratfordTube = "940GZZLUSTD" to "Stratford"

    private val viaWhitechapel = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, whitechapel, 3, 16, 6, change = 3),
            leg("elizabeth-line", "elizabeth", "Elizabeth line", whitechapelXr, canaryWharfXr, 19, 23, 2),
        ),
    )
    private val viaCanadaWater = TripRoute(
        listOf(
            leg("overground", "windrush", "Windrush", highbury, canadaWater, 3, 21, 9, change = 3),
            leg("tube", "jubilee", "Jubilee", canadaWaterTube, canaryWharf, 24, 26, 1),
        ),
    )
    private val viaStratford = TripRoute(
        listOf(
            leg("overground", "mildmay", "Mildmay", highbury, stratford, 5, 21, 6, change = 6),
            leg("tube", "jubilee", "Jubilee", stratfordTube, canaryWharf, 28, 37, 4),
        ),
    )

    private fun train(lineId: String, lineName: String, mode: String, destination: String, inMinutes: Long, platform: String) =
        Departure(
            lineId = lineId,
            lineName = lineName,
            direction = "outbound",
            destination = destination,
            platform = platform,
            expectedArrival = at(inMinutes),
            mode = mode,
        )

    private val live = mapOf(
        highbury.first to TripViewModel.StopLive(
            listOf(
                train("windrush", "Windrush", "overground", "Crystal Palace", 3, "Platform 2"),
                train("windrush", "Windrush", "overground", "West Croydon", 7, "Platform 2"),
                train("windrush", "Windrush", "overground", "Crystal Palace", 11, "Platform 2"),
                train("mildmay", "Mildmay", "overground", "Stratford (London)", 5, "Platform 7"),
            ),
            now,
        ),
        whitechapelXr.first to TripViewModel.StopLive(
            listOf(
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 14, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 18, "Platform A"),
                train("elizabeth", "Elizabeth line", "elizabeth-line", "Abbey Wood", 24, "Platform A"),
            ),
            now,
        ),
        canadaWaterTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stratford", 25, "Eastbound - Platform 2")),
            now,
        ),
        stratfordTube.first to TripViewModel.StopLive(
            listOf(train("jubilee", "Jubilee", "tube", "Stanmore", 29, "Westbound - Platform 13")),
            now,
        ),
    )

    private val sequences = mapOf(
        "windrush" to LineSequence(
            routes = listOf(
                LineRoute("Highbury ↔ Crystal Palace", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GCRYSTLP")),
                LineRoute("Highbury ↔ West Croydon", listOf("910GHGHI", "910GWCHAPEL", "910GCNDAW", "910GWCROYDN")),
            ),
            stopNames = mapOf(
                "910GHGHI" to "Highbury & Islington",
                "910GWCHAPEL" to "Whitechapel",
                "910GCNDAW" to "Canada Water",
                "910GCRYSTLP" to "Crystal Palace",
                "910GWCROYDN" to "West Croydon",
            ),
        ),
        "mildmay" to LineSequence(
            routes = listOf(LineRoute("Highbury ↔ Stratford", listOf("910GHGHI", "910GSTFD"))),
            stopNames = mapOf("910GHGHI" to "Highbury & Islington", "910GSTFD" to "Stratford (London)"),
        ),
        "elizabeth" to LineSequence(
            routes = listOf(LineRoute("Whitechapel ↔ Abbey Wood", listOf("910GWCHAPXR", "910GCANWHRF", "910GABWDXR"))),
            stopNames = mapOf("910GWCHAPXR" to "Whitechapel", "910GCANWHRF" to "Canary Wharf", "910GABWDXR" to "Abbey Wood"),
        ),
        "jubilee" to LineSequence(
            routes = listOf(
                LineRoute("Canada Water ↔ Stratford", listOf("940GZZLUCWR", "940GZZLUCYF", "940GZZLUSTD")),
                LineRoute("Stratford ↔ Stanmore", listOf("940GZZLUSTD", "940GZZLUCYF", "940GZZLUCWR", "940GZZLUSTM")),
            ),
            stopNames = mapOf(
                "940GZZLUCWR" to "Canada Water",
                "940GZZLUCYF" to "Canary Wharf",
                "940GZZLUSTD" to "Stratford",
                "940GZZLUSTM" to "Stanmore",
            ),
        ),
    )

    private val source = object : RouteSequenceSource {
        override suspend fun routeSequence(lineId: String, direction: String): LineSequence =
            sequences.getValue(lineId)
    }

    private val planned = TripViewModel.State(
        routes = listOf(viaStratford, viaCanadaWater, viaWhitechapel),
        plannedAt = now,
        live = live,
        statuses = mapOf(
            "jubilee" to LineStatus("jubilee", 9, "Minor Delays"),
            "windrush" to LineStatus("windrush", LineStatus.GOOD_SERVICE, "Good Service"),
        ),
    )

    private fun show(state: TripViewModel.State) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalRouteStops provides RouteStopsRepository(source)) {
                    TripScreen(
                        title = "To Canary Wharf",
                        state = state,
                        now = now,
                        access = Duration.ofMinutes(2),
                        onBack = {},
                        onRetry = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun trip_routes() {
        show(planned)
        // Via Canada Water: the 3 min Windrush (a 2 min walk to it), 18 min on, 3 to change, the
        // Jubilee at 25, 2 min on: 27 min. Via Whitechapel misses the Elizabeth line at 18 for 24.
        composeRule.onNodeWithText("27 min · ~08:29").assertIsDisplayed()
        composeRule.onNodeWithText("28 min · ~08:30").assertIsDisplayed()
        composeRule.onNodeWithText("38 min · ~08:40").assertIsDisplayed()
        // Screen readers hear each first-leg time with its destination.
        composeRule.onAllNodesWithContentDescription(" min to ", substring = true).onFirst().assertExists()
        captureSnapshot("trip-routes.png")
    }

    @Test
    fun trip_route_legs() {
        show(planned)
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("┊  3 min to change").assertIsDisplayed()
        composeRule.onNodeWithText("2 stops to Canary Wharf").assertIsDisplayed()
        captureSnapshot("trip-route-legs.png")
    }

    @Test
    fun a_failed_replan_shows_over_an_open_route() {
        show(planned.copy(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("28 min · ~08:30").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("6 stops to Whitechapel").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
    }

    @Test
    fun trip_planning() {
        show(TripViewModel.State(planning = true))
        composeRule.onNodeWithText("Planning…").assertIsDisplayed()
        captureSnapshot("trip-planning.png")
    }

    @Test
    fun trip_plan_failed() {
        show(TripViewModel.State(planError = DeparturesUiState.Error.Kind.OFFLINE))
        composeRule.onNodeWithText("Couldn't plan the trip: You're offline").assertIsDisplayed()
        captureSnapshot("trip-plan-failed.png")
    }

    private fun captureSnapshot(name: String, widthPx: Int = 1080, heightPx: Int = 1920) {
        if (!capturing()) return
        val root = composeRule.activity.window.decorView.rootView
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        bitmap.captureRoboImage(filePath = "src/test/snapshots/images/$name")
    }

    private fun capturing(): Boolean =
        System.getProperty("roborazzi.test.record") == "true" ||
            System.getProperty("roborazzi.test.verify") == "true"
}
