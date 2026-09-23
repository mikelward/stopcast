package app.stopcast.domain

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

    /**
     * The snapshot last saved for the widget — read even where [load] declines to restore the stops
     * in-app, so the app keeps the widget's journey pins (and their origins) while it works the
     * journeys out again.
     */
    suspend fun loadForWidget(): DeparturesSnapshot? = load()

    /**
     * Drop from the stored snapshot the widget journeys not among the starred [keys], or whose
     * origin differs from the one [origins] now gives for their key (the journey flipped), with a
     * journey-only stop no remaining journey starts from — an unstar or a flip reaches the widget
     * even when no refresh has saved since. A no-op when nothing changes.
     */
    suspend fun retainWidgetJourneys(keys: Set<String>, origins: Map<String, String> = emptyMap()) {}

    /**
     * Replace the stored snapshot's widget journeys with [journeys] (those whose origin it holds),
     * leaving its stops as they are — for a journeys change that must land even though no fetch
     * will save after it (a relocation canceled the one it waited on).
     */
    suspend fun replaceWidgetJourneys(journeys: List<WidgetJourney>, origins: List<StopArrivals> = emptyList()) {}

    /**
     * Persist [snapshot] like [save], except that a stop stored with newer arrivals (the widget's
     * live refresh ran since [snapshot] was fetched) keeps them — for re-saving the app's last
     * snapshot with changed journeys without rolling back fresher data.
     */
    suspend fun saveKeepingFresher(snapshot: DeparturesSnapshot) = save(snapshot)

    /**
     * [saveKeepingFresher], but leaving the stored widget journeys (and the journey-only stops they
     * start from) as they are — for a save made while the caller couldn't read them back.
     */
    suspend fun saveKeepingJourneys(snapshot: DeparturesSnapshot) = saveKeepingFresher(snapshot)

    /** Persist [snapshot] as the new last-good, replacing any previous one. */
    suspend fun save(snapshot: DeparturesSnapshot)

    /**
     * Persist [snapshot] only if the stored snapshot's stop set still matches [expectedStopIds]
     * — the ids the caller loaded before it did its (possibly slow) work. Returns true if the
     * write was applied, false if a different stop set is now stored and the caller's now-stale
     * result was therefore discarded.
     *
     * This is the compare-and-set a background refresh needs: the widget worker loads a snapshot,
     * fetches for seconds, then saves — and in that window the app may persist a different set
     * (the user relocated). An unconditional [save] would let the slow worker win last and stamp
     * old-location departures as fresh over the new set. The compare must be atomic with the
     * write (no reload→save window), which the DataStore-backed store gets from its update
     * transform running under the write lock. The app's own refresh, which is the authority on
     * the current set, keeps using [save].
     *
     * The guard is deliberately on stop **identity**, not the exact snapshot or a revision: it
     * closes only the case that breaks the honesty floor — a *changed* set, where old-location
     * departures would be stamped fresh over the new one. A *same-set* concurrent write (the app
     * and the worker both refreshing the same stops seconds apart) still passes this predicate,
     * but both results are honestly ~fresh for the same stops and countdowns render from absolute
     * `expectedArrival`, so the marginally-older one winning is within the aging-stamp floor — the
     * broader concurrent-writer race the maintainer deferred (the widget-snapshot-scope work; a
     * full-prior/revision compare that would also close the same-set case is that redesign's shape,
     * a maintainer call, not folded in here).
     */
    suspend fun saveIfStopsMatch(
        snapshot: DeparturesSnapshot,
        expectedStopIds: List<String>,
    ): Boolean

    /**
     * Remove [departedStopIds] from the stored snapshot, keeping the rest at their existing ages,
     * and re-derive the whole-snapshot stamp from what remains — so a stop that has left the nearby
     * set stops being rendered (by the widget) at once, without waiting on the next full [save]. A
     * no-op when nothing is stored or none of the ids are present.
     *
     * This is a **targeted, save-independent** removal, on purpose. Pruning is otherwise coupled to
     * the app's next authoritative save, which a failed/canceled refresh — or a relocation that
     * discards the per-set ViewModel driving that refresh — can skip, leaving a departed stop on
     * disk for the widget worker to keep polling as current (SPEC D4 / principle 2). Doing the
     * removal here, atomically with the read (under the store's write lock, so no reload→save window
     * a concurrent writer could slip through) and off the ViewModel's lifecycle, closes that class
     * of holes rather than the instance. Best-effort like [save].
     */
    suspend fun pruneStops(departedStopIds: Collection<String>)

    companion object {
        /** A store that persists nothing — the default for tests and for a build with no
         *  wired DataStore, so the app runs identically minus the cross-session restore. */
        val NONE: SnapshotStore = object : SnapshotStore {
            override suspend fun load(): DeparturesSnapshot? = null
            override suspend fun save(snapshot: DeparturesSnapshot) {}
            // Persists nothing, so no conditional write is ever applied.
            override suspend fun saveIfStopsMatch(
                snapshot: DeparturesSnapshot,
                expectedStopIds: List<String>,
            ): Boolean = false

            // Nothing is stored, so there is nothing to prune.
            override suspend fun pruneStops(departedStopIds: Collection<String>) {}
        }
    }
}
