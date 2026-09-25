package app.stopdash.domain

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Caps how many TfL requests are in flight at once, so a refresh can fan its per-stop requests out
 * in parallel without opening an unbounded number of connections. Callers launch freely; the pool
 * queues the excess. It promises a bound, not an order: waiters are admitted in the order they
 * reach it, but a caller's dispatcher may reorder them on the way, so nothing relies on it.
 *
 * Sits outside the [TflRateLimiter]: a request takes a pool slot, then a rate token, then goes on
 * the wire. The limiter bounds requests per minute; the pool bounds requests at once. They are
 * independent — a keyless cold-start fan-out bigger than the limiter's burst is still paced by the
 * limiter, however many slots are free.
 *
 * Pure of Android, so it's JVM-testable.
 */
class TflRequestPool(val maxConcurrent: Int) {
    init {
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
    }

    private val semaphore = Semaphore(maxConcurrent)

    /** Runs [block] once a slot is free, releasing it when [block] returns, throws, or is canceled. */
    suspend fun <T> run(block: suspend () -> T): T = semaphore.withPermit { block() }

    companion object {
        /** No cap — every request runs at once. The default for tests and an unwired client. */
        val UNBOUNDED: TflRequestPool = TflRequestPool(Int.MAX_VALUE)
    }
}
