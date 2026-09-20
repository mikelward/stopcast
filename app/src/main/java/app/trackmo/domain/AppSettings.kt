package app.trackmo.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The user's app-level settings, behind a seam (interface) so a surface depends on the
 * capability, not on DataStore, and a JVM/Robolectric test can supply a fake. The concrete
 * DataStore-backed implementation lives in the `data` layer.
 *
 * Unlike the starred rows or (later) watched stops, a setting is not irreplaceable user work:
 * a lost or unreadable settings file resets to the documented defaults rather than being
 * preserved as [StarredRowSet.Unavailable]. Each accessor is a cold [Flow] that emits the
 * current value at once and again on every change, so a screen collecting it reflects a
 * toggle immediately and a later launch reads it back.
 */
interface AppSettings {
    /**
     * Whether the widget refreshes its data on its own roughly once a minute in the
     * background (SPEC D5's opt-in "live widget" cadence — deferrable and Doze-gated, not a
     * screen-on-only guarantee). Off by default — the widget otherwise follows the app's own
     * refresh (D6) and ages honestly to `?` when stale (D4).
     */
    fun liveWidgetRefresh(): Flow<Boolean>

    /** Set [liveWidgetRefresh]. Suspending, meant to run off the main thread; best-effort. */
    suspend fun setLiveWidgetRefresh(enabled: Boolean)

    companion object {
        /** A store that persists nothing and always reads the defaults — the default for tests
         *  and a build with no wired DataStore, so the app runs identically minus persistence. */
        val NONE: AppSettings = object : AppSettings {
            override fun liveWidgetRefresh(): Flow<Boolean> = flowOf(false)
            override suspend fun setLiveWidgetRefresh(enabled: Boolean) {}
        }
    }
}
