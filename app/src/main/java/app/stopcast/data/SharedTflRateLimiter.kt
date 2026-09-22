package app.stopcast.data

import android.os.SystemClock
import app.stopcast.domain.TflRateLimiter
import app.stopcast.domain.TokenBucketRateLimiter
import kotlinx.coroutines.delay

/**
 * The process-wide shared TfL limiter (item 6). One bucket for every client in the process —
 * MainActivity's departures and discovery clients and WidgetRefreshWorker's — so they throttle
 * against a single budget rather than each consuming the full allowance.
 *
 * Sized to the **keyless** budget (~50 req/min): stopcast ships no baked-in key, and a user
 * `app_key` (the higher ~500/min ceiling, SPEC D7) is not yet threaded to the clients — when it
 * is, this sizing should follow it. Best-effort and in-memory; see [TokenBucketRateLimiter] for
 * the reset-on-restart caveat and the recorded persistence follow-up.
 */
object SharedTflRateLimiter {
    /** The keyless per-minute budget this bucket keeps the app under. */
    const val KEYLESS_PER_MINUTE = 50

    /**
     * The immediate burst — a normal refresh's handful of requests goes at once. Kept small so the
     * burst plus a minute's [SUSTAINED_PER_MINUTE] refill stays within [KEYLESS_PER_MINUTE]: a full
     * bucket drained at once plus the refill is the most any 60-second window admits (Codex, #108).
     */
    const val BURST = 10

    /** The sustained rate once the burst is spent. [BURST] + this stays within the budget. */
    const val SUSTAINED_PER_MINUTE = 40

    /**
     * The longest a single request will defer waiting for a token before it surfaces the honest
     * rate-limited state. Bounded well under the auto-refresh interval so a stalled cycle gives
     * way to the next rather than piling up.
     */
    const val MAX_WAIT_MILLIS = 20_000L

    val instance: TflRateLimiter by lazy {
        TokenBucketRateLimiter(
            capacity = BURST,
            refillPerMinute = SUSTAINED_PER_MINUTE,
            maxWaitMillis = MAX_WAIT_MILLIS,
            // Monotonic: elapsedRealtime never jumps on an NTP/user clock correction, which a
            // wall clock would — a backward jump would otherwise wedge the limiter until real
            // time caught up, a forward jump would hand out a spurious full burst (Codex, #108).
            nowMillis = SystemClock::elapsedRealtime,
            sleep = { delay(it) },
        )
    }
}
