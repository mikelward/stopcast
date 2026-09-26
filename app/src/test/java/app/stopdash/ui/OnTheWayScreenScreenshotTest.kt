package app.stopdash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.stopdash.domain.ActiveTrip
import app.stopdash.domain.TripLeg
import app.stopdash.domain.TripProgress
import app.stopdash.domain.TripRoute
import app.stopdash.ui.theme.StopDashTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A trip on the way (SPEC *On the way*): waiting for the train, on it, told to get off, and arrived.
 * A trip between well-known stations; the times are made up. No user data.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnTheWayScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val now: Instant = Instant.parse("2026-09-26T07:02:00Z")
    private fun at(minutes: Long): Instant = now.plus(Duration.ofMinutes(minutes))

    private val mildmay = TripLeg("overground", "mildmay", "Mildmay", "910GHGHI", "Highbury & Islington", "910GSTFD", "Stratford", at(4), at(20))
    private val walk = TripLeg(TripLeg.WALKING, "", "", "910GSTFD", "Stratford", "940GZZLUSTD", "Stratford", at(20), at(24))
    private val jubilee = TripLeg("tube", "jubilee", "Jubilee", "940GZZLUSTD", "Stratford", "940GZZLUCYF", "Canary Wharf", at(26), at(33))
    private val trip = ActiveTrip(TripRoute(listOf(mildmay, walk, jubilee)), "Canary Wharf", startedAt = now, vehicleId = "EXAMPLE")

    private fun show(trip: ActiveTrip?, progress: TripProgress?, failed: Boolean = false, onEnd: () -> Unit = {}, onBack: () -> Unit = {}) {
        composeRule.setContent {
            StopDashTheme(dynamicColor = false) {
                OnTheWayScreen(trip, progress, failed, now, onEnd, onBack)
            }
        }
    }

    @Test
    fun on_the_way_waiting_for_the_train() {
        show(trip, TripProgress.Waiting(mildmay, at(4)))
        composeRule.onNodeWithText("Board Mildmay at Highbury & Islington").assertIsDisplayed()
        composeRule.onNodeWithText("Due in 4 min").assertIsDisplayed()
        captureSnapshot("on-the-way-waiting.png")
    }

    @Test
    fun on_the_way_on_the_train() {
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Hackney Central", 4, at(16), getOffSoon = false))
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("4 stops · next Hackney Central").assertIsDisplayed()
        captureSnapshot("on-the-way-riding.png")
    }

    @Test
    fun on_the_way_get_off_soon() {
        show(trip.copy(boarded = true), TripProgress.Riding(mildmay, "Stratford", 1, at(1), getOffSoon = true))
        composeRule.onNodeWithText("Get off at Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Next stop").assertIsDisplayed()
        captureSnapshot("on-the-way-get-off.png")
    }

    @Test
    fun on_the_way_a_failed_update_says_so() {
        show(trip.copy(legIndex = 1), TripProgress.Walking(walk, at(3)), failed = true)
        composeRule.onNodeWithText("Walk to Stratford").assertIsDisplayed()
        composeRule.onNodeWithText("Couldn't update just now").assertIsDisplayed()
    }

    @Test
    fun on_the_way_end_trip_ends_it() {
        var ended = false
        show(trip, TripProgress.Waiting(mildmay, null), onEnd = { ended = true })
        composeRule.onNodeWithText("Finding your train…").assertIsDisplayed()
        composeRule.onNodeWithText("End trip").performClick()
        composeRule.runOnIdle { assertTrue(ended) }
    }

    @Test
    fun on_the_way_arrived() {
        var closed = false
        show(null, TripProgress.Arrived, onBack = { closed = true })
        composeRule.onNodeWithText("You've arrived").assertIsDisplayed()
        captureSnapshot("on-the-way-arrived.png")
        composeRule.onNodeWithText("Done").performClick()
        composeRule.runOnIdle { assertTrue(closed) }
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
