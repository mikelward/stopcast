package app.stopcast.data

import app.stopcast.domain.AppSettings
import app.stopcast.domain.FontSizeSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The concurrency-critical behavior of the app_key cache (SPEC D7): FIFO write ordering, and the
 * `userHasSet` latch that stops a late store echo from restoring a key the user has since cleared —
 * a credential must never be sent after the user clears it. Driven on the test scheduler via the
 * injected [backgroundScope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UserApiKeyHolderTest {

    /** An [AppSettings] whose stored key is [flow]; each write appends to [written] in order. */
    private class FakeSettings(
        private val flow: Flow<String?>,
        val written: MutableList<String?> = mutableListOf(),
    ) : AppSettings {
        override fun userApiKey(): Flow<String?> = flow
        override suspend fun setUserApiKey(key: String?) {
            written += key
        }

        override fun liveWidgetRefresh(): Flow<Boolean> = flowOf(false)
        override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
        override fun fontSize(): Flow<FontSizeSettings> = flowOf(FontSizeSettings())
        override suspend fun setFontScale(scale: Float) {}
        override suspend fun setPinchEnabled(enabled: Boolean) {}
        override fun skipBugReportConsent(): Flow<Boolean> = flowOf(false)
        override suspend fun setSkipBugReportConsent(enabled: Boolean) {}
    }

    // A holder whose coroutines run eagerly on the test scheduler (so warm/set effects land without
    // manual advancing) and are cancelled with the test via backgroundScope's Job.
    private fun TestScope.eagerHolder() =
        UserApiKeyHolder(CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)))

    @Test
    fun `loaded waits for the stored value, and returns at once when never warmed`() = runTest {
        val stored = MutableSharedFlow<String?>()
        val holder = eagerHolder()
        assertNull("never warmed: the initial value, without waiting", holder.loaded())
        holder.warm(FakeSettings(stored))
        val read = async { holder.loaded() }
        // Run what's ready without advancing the clock, which would pass the load timeout.
        runCurrent()
        assertTrue("still waiting for the store", read.isActive)
        stored.emit("EXAMPLE")
        assertEquals("EXAMPLE", read.await())
    }

    @Test
    fun `isLoaded turns true when the store is read, or the user sets a value first`() = runTest {
        val stored = MutableSharedFlow<String?>()
        val holder = eagerHolder()
        holder.warm(FakeSettings(stored))
        runCurrent()
        assertFalse("nothing read yet", holder.isLoaded.value)
        stored.emit("EXAMPLE")
        assertTrue(holder.isLoaded.value)

        val early = eagerHolder()
        early.warm(FakeSettings(MutableSharedFlow()))
        early.set("EXAMPLE")
        assertTrue("the user's own value counts as loaded", early.isLoaded.value)
    }

    @Test
    fun `a failed write is reported until the screen has shown it`() = runTest {
        val holder = eagerHolder()
        val failing = object : AppSettings by FakeSettings(flowOf(null)) {
            override suspend fun setUserApiKey(key: String?) = throw java.io.IOException("disk full")
        }
        holder.warm(failing)
        holder.set("EXAMPLE")
        advanceUntilIdle()
        // Still applied in memory, but the screen is told it won't survive a restart.
        assertEquals("EXAMPLE", holder.current)
        assertTrue(holder.writeFailed.value)
        holder.writeFailureShown()
        assertEquals(false, holder.writeFailed.value)
    }

    @Test
    fun `a later successful write clears an earlier failure`() = runTest {
        val holder = eagerHolder()
        var fail = true
        val flaky = object : AppSettings by FakeSettings(flowOf(null)) {
            override suspend fun setUserApiKey(key: String?) {
                if (fail) throw java.io.IOException("disk full")
            }
        }
        holder.warm(flaky)
        holder.set("EXAMPLE")
        advanceUntilIdle()
        assertTrue(holder.writeFailed.value)
        fail = false
        holder.set("EXAMPLE2")
        advanceUntilIdle()
        assertFalse("the latest value is saved", holder.writeFailed.value)
    }

    @Test
    fun `warm reads the stored key into current`() = runTest {
        val holder = eagerHolder()
        holder.warm(FakeSettings(flowOf("EXAMPLE")))
        advanceUntilIdle()
        assertEquals("EXAMPLE", holder.current)
    }

    @Test
    fun `set applies the key to current at once and trims it`() = runTest {
        val holder = eagerHolder()
        holder.warm(FakeSettings(MutableSharedFlow()))
        holder.set("  EXAMPLE  ")
        // No scheduler advance: current is updated synchronously, before the background write.
        assertEquals("EXAMPLE", holder.current)
    }

    @Test
    fun `rapid edits persist in FIFO order`() = runTest {
        val settings = FakeSettings(MutableSharedFlow())
        val holder = eagerHolder()
        holder.warm(settings)

        holder.set("A")
        holder.set("B")
        holder.set(null) // a Clear
        advanceUntilIdle()

        // Normalized and written newest-last, so a restart reads the user's final choice.
        assertEquals(listOf("A", "B", null), settings.written)
    }

    @Test
    fun `a late store echo cannot restore a key the user has cleared`() = runTest {
        val stored = MutableSharedFlow<String?>(replay = 1)
        val holder = eagerHolder()
        holder.warm(FakeSettings(stored))

        stored.emit("EXAMPLE") // the stored key warms in
        advanceUntilIdle()
        assertEquals("EXAMPLE", holder.current)

        holder.set(null) // the user clears it
        assertNull(holder.current)

        stored.emit("EXAMPLE") // a delayed echo of the old write
        advanceUntilIdle()
        assertNull(holder.current) // the latch keeps the cleared value — never restored
    }
}
