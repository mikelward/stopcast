package app.stopcast.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import app.stopcast.domain.AppSettings
import app.stopcast.domain.FontSizeSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The live-widget-refresh coordinator's single error policy (SPEC D5 / *Error handling*): apply
 * and startup-sync each persist/read the choice and apply the WorkManager schedule, returning
 * [LiveWidgetRefreshResult.APPLIED] on success and [LiveWidgetRefreshResult.FAILED] — never
 * throwing — when the settings store errors, so the UI can surface it. The scheduling itself runs
 * on an in-memory WorkManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LiveWidgetRefreshTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private class FakeSettings(
        private val flow: Flow<Boolean> = flowOf(false),
        private val onSet: suspend (Boolean) -> Unit = {},
    ) : AppSettings {
        override fun liveWidgetRefresh(): Flow<Boolean> = flow
        override suspend fun setLiveWidgetRefresh(enabled: Boolean) = onSet(enabled)
        override fun fontSize(): Flow<FontSizeSettings> = flowOf(FontSizeSettings())
        override suspend fun setFontScale(scale: Float) {}
        override suspend fun setPinchEnabled(enabled: Boolean) {}
    }

    private fun enqueuedCount() =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get()
            .count { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `apply enabled persists, schedules, and reports applied`() = runTest {
        var saved: Boolean? = null
        val settings = FakeSettings(onSet = { saved = it })
        val result = applyLiveWidgetRefresh(context, settings, enabled = true)
        assertEquals(LiveWidgetRefreshResult.APPLIED, result)
        assertEquals(true, saved)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `apply reports failed and keeps the user's choice when the write throws`() = runTest {
        val settings = FakeSettings(onSet = { throw IOException("disk full") })
        val result = applyLiveWidgetRefresh(context, settings, enabled = true)
        assertEquals(LiveWidgetRefreshResult.FAILED, result)
        // No rollback attempted, and no tick scheduled — the caller surfaces the failure.
        assertEquals(0, enqueuedCount())
    }

    @Test
    fun `sync applies the persisted value and reports applied`() = runTest {
        val settings = FakeSettings(flow = flowOf(true))
        val result = syncLiveWidgetRefreshSchedule(context, settings)
        assertEquals(LiveWidgetRefreshResult.APPLIED, result)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `sync reports failed when the read throws`() = runTest {
        val settings = FakeSettings(flow = flow { throw IOException("read error") })
        val result = syncLiveWidgetRefreshSchedule(context, settings)
        assertEquals(LiveWidgetRefreshResult.FAILED, result)
        assertEquals(0, enqueuedCount())
    }

    /**
     * A persistent storage error makes `liveWidgetRefresh()` retry forever (never emitting), so the
     * bounded startup read returns null and the sync reports FAILED rather than hanging and never
     * surfacing the error — the restore self-heals on the next startup/render (Codex P2 on #56).
     */
    @Test
    fun `sync reports failed when the read never emits`() = runTest {
        val settings = FakeSettings(flow = flow { awaitCancellation() })
        val result = syncLiveWidgetRefreshSchedule(context, settings)
        assertEquals(LiveWidgetRefreshResult.FAILED, result)
        assertEquals(0, enqueuedCount())
    }

    /**
     * Concurrent applications are serialized, so a rapid toggle can't interleave one apply's
     * persist+schedule with another's (which left the switch persisted on with no tick scheduled —
     * Codex P2 on #56). We gate the first apply inside its write and count how many applies are
     * ever inside the write at once: with the mutex it never exceeds one; without it the second
     * would enter while the first is gated.
     */
    @Test
    fun `concurrent applies do not interleave`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var gatedOnce = false
        val settings = object : AppSettings {
            override fun liveWidgetRefresh(): Flow<Boolean> = flowOf(false)
            override suspend fun setLiveWidgetRefresh(enabled: Boolean) {
                active++
                maxActive = maxOf(maxActive, active)
                if (!gatedOnce) {
                    gatedOnce = true
                    gate.await()
                }
                active--
            }
            override fun fontSize(): Flow<FontSizeSettings> = flowOf(FontSizeSettings())
            override suspend fun setFontScale(scale: Float) {}
            override suspend fun setPinchEnabled(enabled: Boolean) {}
        }
        launch { applyLiveWidgetRefresh(context, settings, enabled = true) }
        launch { applyLiveWidgetRefresh(context, settings, enabled = false) }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, maxActive)
    }
}
