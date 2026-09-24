package app.stopcast.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The synchronous [RouteTopologyStore.cached] peek, which is what lets a recreated activity
 * (rotation, its view models retained) seed its first frame with the merged grouping instead of
 * flickering through split rows while the async [RouteTopologyStore.load] re-runs. Needs a real
 * `Context` for the asset read, so it runs under Robolectric rather than as a plain JVM test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RouteTopologyStoreCacheTest {
    @Test
    fun `cached reflects a completed load, so a recreated surface sees the merged grouping`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        // Loading the bundled asset caches the parsed instance process-wide.
        val loaded = RouteTopologyStore.load(context)
        // Highgate → High Barnet merges (past the northern junction), so this is the real,
        // non-empty topology and not the safe EMPTY fallback.
        assertNull(loaded.grouping("northern", "940GZZLUHGT", "High Barnet", "Bank").label)

        // The synchronous peek now returns that same instance with no IO — the value a recreated
        // activity reads for its initial state, so the first frame is already merged.
        assertSame(loaded, RouteTopologyStore.cached())
    }
}
