package app.stopcast.data

import android.os.SystemClock
import app.stopcast.domain.TflRateLimiter
import app.stopcast.domain.TokenBucketRateLimiter
import app.stopcast.domain.WaitMeter
import kotlinx.coroutines.delay

/**
 * The process-wide shared TfL limiter (item 6). One limiter for every client in the process —
 * MainActivity's departures and discovery clients and WidgetRefreshWorker's — so they throttle
 * against a single budget rather than each consuming the full allowance.
 *
 * The budget **follows the active key** (SPEC D7): keyless requests throttle toward ~50 req/min,
 * and a user-pasted `app_key` toward the higher ~500/min ceiling it unlocks. The bucket is picked
 * per request by [rateLimiterFor] from the key snapshot the client already read, so a paste or
 * clear takes effect at once and the budget always matches the `app_key` the request carries.
 * Best-effort and in-memory; see [TokenBucketRateLimiter] for the reset-on-restart caveat and the
 * recorded persistence follow-up.
 */
object SharedTflRateLimiter {
    /** The keyless per-minute budget the keyless bucket keeps the app under (TfL's ~50 anonymous). */
    const val KEYLESS_PER_MINUTE = 50

    /**
     * The immediate burst: a whole typical cold near-me load — the nearby lookup plus ~15-18
     * departures requests once a junction's closure checks share one request — goes out unpaced,
     * in two quick waves through the 10-request [SharedTflRequestPool] (2026-09-23).
     */
    const val BURST = 20

    /**
     * The sustained rate once the burst is spent (maintainer, 2026-09-23: kept at 40, not lowered to
     * fit the bigger burst). A full bucket drained at once plus a minute's refill — the most any
     * 60-second window admits — is therefore 60, over [KEYLESS_PER_MINUTE]: accepted, since only
     * sustained heavy use (repeated reveals) reaches it and a 429 shows as rate-limited, with fewer
     * requests per refresh (caching) the planned way back under. A 60 s auto-refresh of a near-me
     * list (~10-14 requests, closures cached) sits well under it.
     */
    const val SUSTAINED_PER_MINUTE = 40

    /** The higher budget a user `app_key` unlocks (~500 req/min, SPEC D7) — 10x the keyless one. */
    const val KEYED_PER_MINUTE = 500

    /** The keyed burst. Same burst-plus-refill-within-budget shape as the keyless bucket, scaled up. */
    const val KEYED_BURST = 100

    /** The keyed sustained rate. [KEYED_BURST] + this stays within [KEYED_PER_MINUTE]. */
    const val KEYED_SUSTAINED_PER_MINUTE = 400

    /**
     * The longest a single request will defer waiting for a token before it surfaces the honest
     * rate-limited state. Bounded well under the auto-refresh interval so a stalled cycle gives
     * way to the next rather than piling up.
     */
    const val MAX_WAIT_MILLIS = 20_000L

    // The two shared token buckets, one per budget. Lazy so they exist once per process and every
    // request selecting through [rateLimiterFor] shares them, so total traffic (app + widget)
    // counts against one budget rather than each client draining the full allowance.
    private val keylessBucket: TflRateLimiter by lazy { bucket(BURST, SUSTAINED_PER_MINUTE) }
    private val keyedBucket: TflRateLimiter by lazy { bucket(KEYED_BURST, KEYED_SUSTAINED_PER_MINUTE) }

    // Wall-clock time any request spent waiting for a token, across both buckets — read before and
    // after a fetch for its debug-log line ([app.stopcast.domain.LoadStats]). Concurrent waits count
    // once; a widget refresh waiting at the same moment lands in the same total (a diagnostic).
    private val waits = WaitMeter(SystemClock::elapsedRealtime)

    /** Wall-clock milliseconds some request spent waiting on either bucket since the process started. */
    val waitedMillis: Long get() = waits.totalMillis()

    /**
     * The shared bucket a request with key snapshot [key] is charged to: the keyed budget when a
     * non-blank key is active, the keyless budget otherwise (SPEC D7). The caller ([KtorTflClient])
     * reads the key once per request and passes that one snapshot here and to the `app_key`, so a
     * runtime paste/clear can't charge a request to one budget while sending the other key state.
     */
    fun rateLimiterFor(key: String?): TflRateLimiter =
        if (key.isNullOrBlank()) keylessBucket else keyedBucket

    private fun bucket(burst: Int, sustainedPerMinute: Int): TflRateLimiter =
        TokenBucketRateLimiter(
            capacity = burst,
            refillPerMinute = sustainedPerMinute,
            maxWaitMillis = MAX_WAIT_MILLIS,
            // Monotonic: elapsedRealtime never jumps on an NTP/user clock correction, which a
            // wall clock would — a backward jump would otherwise wedge the limiter until real
            // time caught up, a forward jump would hand out a spurious full burst (Codex, #108).
            nowMillis = SystemClock::elapsedRealtime,
            sleep = { waits.measure { delay(it) } },
        )
}
