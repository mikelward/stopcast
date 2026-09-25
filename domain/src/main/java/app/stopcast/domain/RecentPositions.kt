package app.stopcast.domain

/**
 * The last few positions the app worked from — each fix it got (a rough one set aside for a
 * remembered precise one included: where the network placed the rider is the diagnosis) and each
 * nearby lookup — kept
 * **in memory only**, for at most [TTL_MILLIS] and at most [CAPACITY] entries (maintainer,
 * 2026-09-25). They exist so a wrong-station fix underground can be diagnosed from a bug report,
 * which is the one place they leave the device (consent-gated, SPEC *Privacy*). They are never
 * written to the diagnostic log, its file or Logcat: the point is a short window around a
 * problem, not a record of where someone has been.
 *
 * Times for expiry are from a monotonic clock (elapsed realtime); [stamp] is the caller's
 * wall-clock label, so an entry lines up with the diagnostic log's lines around it.
 */
class RecentPositions {
    private data class Entry(val atElapsedMillis: Long, val line: String)

    // Guarded by `this`: the location provider and the nearby lookup record from different threads.
    private val entries = ArrayDeque<Entry>()

    /** Records that [what] placed the rider at [at], at [nowElapsedMillis], labeled [stamp]. */
    @Synchronized
    fun record(what: String, at: Coordinates, nowElapsedMillis: Long, stamp: String) {
        expire(nowElapsedMillis)
        entries.addLast(Entry(nowElapsedMillis, "$stamp $what at ${FixDiagnostics.position(at)}"))
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    /** The entries still within [TTL_MILLIS] at [nowElapsedMillis], oldest first. */
    @Synchronized
    fun recent(nowElapsedMillis: Long): List<String> {
        expire(nowElapsedMillis)
        return entries.map { it.line }
    }

    /**
     * Deletes (not just hides) every entry past the TTL at [nowElapsedMillis], and says whether any
     * remain. The app also runs it on a short repeating tick while any do, so an idle app doesn't
     * keep a position past its 15 minutes (see [SWEEP_MILLIS]).
     */
    @Synchronized
    fun expire(nowElapsedMillis: Long): Boolean {
        entries.removeAll { nowElapsedMillis - it.atElapsedMillis >= TTL_MILLIS }
        return entries.isNotEmpty()
    }

    /**
     * How long until the oldest entry reaches the TTL at [nowElapsedMillis], or `null` when there
     * are none — for the app's sweep to fire at that deadline rather than on a fixed tick.
     */
    @Synchronized
    fun untilNextExpiry(nowElapsedMillis: Long): Long? =
        entries.firstOrNull()?.let { (it.atElapsedMillis + TTL_MILLIS - nowElapsedMillis).coerceAtLeast(0) }

    companion object {
        /** How long a position is kept. Reversible — one constant. */
        const val TTL_MILLIS = 15 * 60 * 1000L

        /** The most positions kept, however recent. */
        const val CAPACITY = 20

        /**
         * The longest the app's sweep waits between checks while it holds any position. The sweep
         * fires at the oldest entry's deadline ([untilNextExpiry]), capped at this: Android's
         * delayed messages count only awake time, so a long delay can run well after the deadline
         * if the phone slept, while a capped one catches up within this long of it waking. Waking
         * the phone to delete a position nothing can read while it sleeps would cost battery for
         * no reader (SPEC *Privacy*).
         */
        const val SWEEP_MILLIS = 60_000L
    }
}
