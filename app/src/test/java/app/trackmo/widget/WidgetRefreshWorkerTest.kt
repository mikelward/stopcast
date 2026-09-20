package app.trackmo.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import app.trackmo.data.DataStoreAppSettings
import app.trackmo.domain.AppSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scheduling side of the opt-in "live widget" refresh (SPEC D5): turning the setting on
 * enqueues exactly one unique refresh tick, turning it off cancels the pending one, and a second
 * schedule REPLACEs rather than stacking (so the self-rescheduling chain is never more than one
 * deep). The worker's own fetch-and-save cycle is covered by the pure `WidgetRefreshTest`; here we
 * only assert the WorkManager plumbing, on an in-memory WorkManager so no real work runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetRefreshWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun wm() = WorkManager.getInstance(context)

    private fun scheduledWork(): List<WorkInfo> =
        wm().getWorkInfosForUniqueWork(WIDGET_REFRESH_WORK).get()

    private fun enqueuedCount() = scheduledWork().count { it.state == WorkInfo.State.ENQUEUED }

    @Test
    fun `enabling the setting enqueues one refresh tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        val work = scheduledWork()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.first().state)
    }

    @Test
    fun `disabling the setting cancels the pending tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        assertEquals(1, enqueuedCount())
        applyWidgetRefreshSetting(context, enabled = false)
        assertEquals(0, enqueuedCount())
    }

    /**
     * Re-applying the enabled setting — as the startup `LaunchedEffect` does on every activity
     * recreation (a rotation) — keeps the existing tick rather than REPLACE-ing it, so repeated
     * config changes can't reset the pending delay or abort a running fetch (Codex P2 on #56). The
     * same **work id** across both calls is what proves it wasn't cancelled and re-enqueued.
     */
    @Test
    fun `re-applying the enabled setting keeps the existing tick`() = runTest {
        applyWidgetRefreshSetting(context, enabled = true)
        val firstId = scheduledWork().single { it.state == WorkInfo.State.ENQUEUED }.id
        applyWidgetRefreshSetting(context, enabled = true)
        val enqueued = scheduledWork().filter { it.state == WorkInfo.State.ENQUEUED }
        assertEquals(1, enqueued.size)
        assertEquals(firstId, enqueued.single().id)
    }

    @Test
    fun `scheduling twice keeps only one pending tick`() = runTest {
        scheduleWidgetRefresh(context)
        scheduleWidgetRefresh(context)
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `disabling when nothing is scheduled is a no-op`() = runTest {
        applyWidgetRefreshSetting(context, enabled = false)
        assertEquals(0, enqueuedCount())
    }

    /**
     * With the setting on but no widget installed (the Robolectric default — no host), the worker
     * retires the chain: it returns success without fetching or rescheduling, so it doesn't loop
     * every minute with no surface to update (Codex P1 on #56). The render path
     * (`resumeWidgetRefreshIfEnabled`) restarts it when a widget is added.
     */
    @Test
    fun `the worker retires without rescheduling when no widget is installed`() = runTest {
        DataStoreAppSettings.from(context).setLiveWidgetRefresh(true)
        val worker = TestListenableWorkerBuilder<WidgetRefreshWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, enqueuedCount())
    }

    private class FakeSettings(private val flow: Flow<Boolean>) : AppSettings {
        override fun liveWidgetRefresh(): Flow<Boolean> = flow
        override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
    }

    @Test
    fun `resume schedules a tick when the setting reads on`() = runTest {
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(true)))
        assertEquals(1, enqueuedCount())
    }

    @Test
    fun `resume does not schedule when the setting reads off`() = runTest {
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flowOf(false)))
        assertEquals(0, enqueuedCount())
    }

    /**
     * A persistent settings read failure makes `liveWidgetRefresh()` retry forever (never
     * emitting). On the widget render path the bounded read must give up and skip resume rather
     * than hang `provideContent` — and a timed-out read is never treated as "off", so it neither
     * schedules nor crashes; it simply does nothing this render (Codex P2 on #56).
     */
    @Test
    fun `resume gives up without scheduling when the settings read hangs`() = runTest {
        resumeWidgetRefreshIfEnabled(context, FakeSettings(flow { awaitCancellation() }))
        assertEquals(0, enqueuedCount())
    }
}
