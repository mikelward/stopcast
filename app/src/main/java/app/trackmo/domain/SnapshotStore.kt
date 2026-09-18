package app.trackmo.domain

/**
 * Reads and writes the persisted last-good [DeparturesSnapshot]. A seam (interface) so the
 * ViewModel and the widget both depend on the capability, not on DataStore, and a JVM test
 * can supply a fake without Android. The concrete DataStore-backed implementation lives in
 * the `data` layer.
 *
 * Both calls are suspending and meant to run off the main thread. Neither throws for the
 * ordinary "nothing saved yet" or "the stored bytes were unreadable" cases — [load] returns
 * null and the caller shows its placeholder, and [save] is best-effort — so a persistence
 * hiccup degrades to "no last-good this launch" rather than a crash on the render path.
 */
interface SnapshotStore {
    /** The stored snapshot, or null when nothing is saved yet or it couldn't be read. */
    suspend fun load(): DeparturesSnapshot?

    /** Persist [snapshot] as the new last-good, replacing any previous one. */
    suspend fun save(snapshot: DeparturesSnapshot)

    companion object {
        /** A store that persists nothing — the default for tests and for a build with no
         *  wired DataStore, so the app runs identically minus the cross-session restore. */
        val NONE: SnapshotStore = object : SnapshotStore {
            override suspend fun load(): DeparturesSnapshot? = null
            override suspend fun save(snapshot: DeparturesSnapshot) {}
        }
    }
}
