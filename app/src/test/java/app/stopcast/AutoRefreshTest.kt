package app.stopcast

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The periodic auto-refresh (SPEC D5): [autoRefresh] fetches once per interval while the
 * screen is RESUMED, never before the interval elapses, and pauses while backgrounded.
 * Driven by a [LifecycleRegistry] on a test dispatcher with virtual time, so the cadence is
 * asserted without a device or real elapsed time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoRefreshTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `refreshes once per interval while resumed, and not before the interval elapses`() = runTest(dispatcher) {
        val owner = FakeOwner()
        var refreshes = 0
        val job = launch { autoRefresh(owner.lifecycle, 60_000L) { refreshes++ } }
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()

        advanceTimeBy(59_000)
        runCurrent()
        assertEquals("no fetch before the interval elapses", 0, refreshes)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals("the first tick lands one interval after resume, not on resume", 1, refreshes)

        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("and once per interval after", 2, refreshes)

        job.cancel()
    }

    @Test
    fun `skips a tick while a refresh is still in flight, and ticks once it settles`() = runTest(dispatcher) {
        val owner = FakeOwner()
        var refreshes = 0
        var refreshing = true // a slow refresh is already running
        val job = launch {
            autoRefresh(owner.lifecycle, 60_000L, isRefreshing = { refreshing }) { refreshes++ }
        }
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()

        // Ticks while the refresh is in flight are skipped — never cancel it by starting another.
        advanceTimeBy(60_000)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no tick fires while a refresh is in flight", 0, refreshes)

        // Once the refresh settles, the next tick fetches.
        refreshing = false
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("ticks resume once the in-flight refresh has settled", 1, refreshes)

        job.cancel()
    }

    @Test
    fun `pauses while backgrounded and resumes on return`() = runTest(dispatcher) {
        val owner = FakeOwner()
        var refreshes = 0
        val job = launch { autoRefresh(owner.lifecycle, 60_000L) { refreshes++ } }
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, refreshes)

        // Dropping below RESUMED cancels the loop — no ticks while backgrounded.
        owner.registry.currentState = Lifecycle.State.CREATED
        runCurrent()
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals("no fetch while backgrounded", 1, refreshes)

        // Returning to RESUMED restarts the loop; the next tick is a fresh interval later.
        owner.registry.currentState = Lifecycle.State.RESUMED
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("resumes ticking on return to the foreground", 2, refreshes)

        job.cancel()
    }
}
