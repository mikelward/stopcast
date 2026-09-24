package app.stopcast.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The watched set as read from storage: either the current set, or **unavailable** because a
 * stored set exists that this build can't read (it was written by a newer schema version).
 * The two are kept distinct so a surface never shows a newer-version set as an empty one — an
 * empty [Loaded] is "the user has watched nothing", [Unavailable] is "there is a set, but not
 * one this build understands" (SPEC principle 2: never present the wrong thing as fact). The
 * store never overwrites an [Unavailable] file, so the set survives a downgrade or rollback.
 */
sealed interface WatchedStopSet {
    /** The current watched set — possibly empty because the user has added none. */
    data class Loaded(val stops: List<WatchedStop>) : WatchedStopSet

    /** A stored set exists but was written by a newer build; it is preserved untouched. */
    data object Unavailable : WatchedStopSet
}

/**
 * Reads and writes the persisted **watched-stop set** — the source of truth for what every
 * surface shows (SPEC *Watched stops* / D1). A seam (interface) so a ViewModel and the widget
 * depend on the capability, not on DataStore, and a JVM test can supply a fake without
 * Android. The concrete DataStore-backed implementation lives in the `data` layer.
 *
 * [watched] is a cold [Flow] the caller collects: it emits the current [WatchedStopSet] at once
 * and again on every change, so a surface re-renders when the user adds or removes a stop,
 * without polling. [add] and [remove] are suspending and meant to run off the main thread; both
 * are best-effort and idempotent by stop id (see [WatchedStops]). Neither ever loses the user's
 * work: against a set this build can't read ([WatchedStopSet.Unavailable]) they preserve the
 * stored file untouched rather than overwriting it with a downgraded one, so a persistence
 * hiccup or a version downgrade degrades to "the set didn't change this time", never to erased
 * stops (SPEC *never lose the user's work*).
 */
interface WatchedStopsStore {
    /** The current watched set, re-emitted on every change; [WatchedStopSet.Unavailable] when a
     *  stored set was written by a newer build. */
    fun watched(): Flow<WatchedStopSet>

    /** Watch [stop]. A no-op if a stop with the same id is already watched ([WatchedStops.add]),
     *  or if the stored set is [WatchedStopSet.Unavailable] (preserved, never overwritten). */
    suspend fun add(stop: WatchedStop)

    /** Stop watching [stopId], dropping all its rows. A no-op if it isn't watched, or if the
     *  stored set is [WatchedStopSet.Unavailable] (preserved, never overwritten). */
    suspend fun remove(stopId: String)

    companion object {
        /** A store that persists nothing and always reads an empty set — the default for tests
         *  and a build with no wired DataStore, so the app runs identically minus watched stops. */
        val NONE: WatchedStopsStore = object : WatchedStopsStore {
            override fun watched(): Flow<WatchedStopSet> = flowOf(WatchedStopSet.Loaded(emptyList()))
            override suspend fun add(stop: WatchedStop) {}
            override suspend fun remove(stopId: String) {}
        }
    }
}
