package app.stopdash.domain

/**
 * Wall-clock time during which at least one request was waiting on the rate limiter — the union of
 * the waits, not their sum, so several requests waiting side by side count once and a wait cut short
 * (canceled) counts only as long as it ran. Feeds the per-fetch log line ([LoadStats]); a diagnostic,
 * not an accounting. [nowMillis] is a monotonic clock.
 */
class WaitMeter(private val nowMillis: () -> Long) {
    private var waiting = 0
    private var since = 0L
    private var total = 0L

    @Synchronized
    fun enter() {
        if (waiting++ == 0) since = nowMillis()
    }

    @Synchronized
    fun exit() {
        if (waiting == 0) return
        if (--waiting == 0) total += nowMillis() - since
    }

    /** Total waited so far, including a wait still in progress. */
    @Synchronized
    fun totalMillis(): Long = total + if (waiting > 0) nowMillis() - since else 0L

    /** Runs [block] (a limiter's sleep) as one wait. */
    suspend fun <T> measure(block: suspend () -> T): T {
        enter()
        try {
            return block()
        } finally {
            exit()
        }
    }
}
