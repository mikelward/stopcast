package app.trackmo.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The starred set as read from storage: either the current set, or **unavailable** because a
 * stored set exists that this build can't read (written by a newer schema version). Kept
 * distinct so a surface never treats a newer-version set as an empty one — an empty [Loaded]
 * is "nothing starred", [Unavailable] is "there is a set, but not one this build understands"
 * (SPEC principle 2). The store never overwrites an [Unavailable] file, so the stars survive a
 * downgrade or rollback (SPEC *never lose the user's work*). Mirrors [WatchedStopSet].
 */
sealed interface StarredRowSet {
    /** The current starred set — possibly empty because the user has starred nothing. */
    data class Loaded(val starred: Set<StarredRow>) : StarredRowSet

    /** A stored set exists but was written by a newer build; it is preserved untouched. */
    data object Unavailable : StarredRowSet
}

/**
 * Reads and writes the persisted **starred rows** — the ranking overlay that pins a service to
 * the top of the list (SPEC D8, [DepartureRows.pinStarred]). A seam (interface) so a ViewModel
 * depends on the capability, not on DataStore, and a JVM test can supply a fake without
 * Android. The concrete DataStore-backed implementation lives in the `data` layer.
 *
 * [starred] is a cold [Flow] the caller collects: it emits the current [StarredRowSet] at once
 * and again on every change, so the list re-orders the moment the user stars or unstars a row.
 * [toggle] is suspending and meant to run off the main thread; it is best-effort and, against a
 * set this build can't read ([StarredRowSet.Unavailable]), preserves the stored file untouched
 * rather than overwriting it with a downgraded one.
 */
interface StarredRowsStore {
    /** The current starred set, re-emitted on every change; [StarredRowSet.Unavailable] when a
     *  stored set was written by a newer build. */
    fun starred(): Flow<StarredRowSet>

    /** Flip [row]'s star ([Starred.toggle]). A no-op if the stored set is
     *  [StarredRowSet.Unavailable] (preserved, never overwritten). */
    suspend fun toggle(row: StarredRow)

    companion object {
        /** A store that persists nothing and always reads an empty set — the default for tests
         *  and a build with no wired DataStore, so the app runs identically minus starring. */
        val NONE: StarredRowsStore = object : StarredRowsStore {
            override fun starred(): Flow<StarredRowSet> = flowOf(StarredRowSet.Loaded(emptySet()))
            override suspend fun toggle(row: StarredRow) {}
        }
    }
}
