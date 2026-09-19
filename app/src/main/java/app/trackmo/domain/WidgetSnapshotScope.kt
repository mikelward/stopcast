package app.trackmo.domain

/**
 * Whether the persisted widget snapshot should be cleared because it no longer describes the
 * stop set the app has just resolved (SPEC principle 1). The widget shows no stop name, so a
 * previous location's departures left in the snapshot read as live for the newly-resolved
 * context until the stamp ages them stale; and because the snapshot is only rewritten on an
 * *authoritative* arrivals cycle, a new set whose fetch fails (offline / rate-limited), or a
 * location that resolves to no stops at all, never overwrites it. Clearing on the set change
 * closes both: the widget goes empty (honest) rather than showing another place's trains.
 *
 * Cleared only when the snapshot holds a stop that is **not** in the resolved set — i.e. it
 * carries another area's departures. A snapshot whose stops are all in the resolved set is kept:
 * that is either the same set, or a *subset* of it (a multi-stop resolution where only some
 * stops' arrivals came back the first time — `MainViewModel` persists just the fetched ones), and
 * a subset is still this area's last-good, so deleting it would blank the widget for stops it
 * genuinely has data for (Codex). An empty resolution clears any non-empty snapshot, since every
 * stop it holds is then outside the (empty) set. Membership, not order or distance, and not
 * strict equality: the same stops in a different nearest-first order, or a subset of them, are
 * not "another area".
 *
 * Interim: the nearby set is location-derived and changes as the user moves. Once Phase 2's
 * user-chosen watched stops are the source, the set changes only on an explicit edit and this
 * scoping is moot.
 */
fun shouldClearWidgetSnapshot(
    persistedStopIds: Set<String>,
    resolvedStopIds: Set<String>,
): Boolean = persistedStopIds.isNotEmpty() && !resolvedStopIds.containsAll(persistedStopIds)
