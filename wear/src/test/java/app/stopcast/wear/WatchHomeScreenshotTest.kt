package app.stopcast.wear

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The watch home on a round watch. Captures land beside the phone's (app/src/test/snapshots), so
 * CI's one screenshot pipeline records, diffs and commits them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w227dp-h227dp-round-watch-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatchHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun capture(name: String, home: WatchHome, notice: RefreshNotice.Kind? = null, refresh: Boolean = false) {
        compose.setContent { WatchHomeScreen(home, notice, onRefresh = if (refresh) ({}) else null) }
        compose.onRoot().captureRoboImage(filePath = "../app/src/test/snapshots/images/wear_$name.png")
    }

    @Test
    fun neverSynced() = capture("never_synced", WatchHome.NeverSynced)

    @Test
    fun neverSyncedOutOfReach() = capture(
        "never_synced_out_of_reach",
        WatchHome.NeverSynced,
        notice = RefreshNotice.Kind.PHONE_OUT_OF_REACH,
        refresh = true,
    )

    @Test
    fun noStops() = capture("no_stops", WatchHome.NoStops)

    @Test
    fun stops() = capture(
        "stops",
        WatchHome.Stops(listOf("Oxford Circus", "King's Cross St. Pancras", "Euston"), omitted = 2, partial = true),
    )

    @Test
    fun refreshFailed() = capture(
        "refresh_failed",
        WatchHome.Stops(listOf("Oxford Circus"), omitted = 0, partial = false),
        notice = RefreshNotice.Kind.RATE_LIMITED,
        refresh = true,
    )
}
