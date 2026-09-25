package app.stopdash.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TflRateLimiterTest {
    // A virtual clock the test controls, and a recorder of every wait. This fake sleep advances
    // the clock by the slept amount (as real time would), so sequential acquires that pace show
    // up as recorded waits and the bucket refills across them.
    private var now = 0L
    private val slept = mutableListOf<Long>()

    private fun limiter(
        capacity: Int = 3,
        refillPerMinute: Int = 60, // one token per 1000 ms
        maxWaitMillis: Long = 10_000,
    ) = TokenBucketRateLimiter(
        capacity = capacity,
        refillPerMinute = refillPerMinute,
        maxWaitMillis = maxWaitMillis,
        nowMillis = { now },
        sleep = { slept += it; now += it },
    )

    @Test
    fun `bursts up to capacity without waiting`() = runTest {
        val limiter = limiter(capacity = 3)
        repeat(3) { limiter.acquire() }
        assertEquals("the first capacity requests are immediate", emptyList<Long>(), slept)
    }

    @Test
    fun `paces requests beyond the burst at the refill rate`() = runTest {
        val limiter = limiter(capacity = 3, refillPerMinute = 60) // 1000 ms/token
        repeat(3) { limiter.acquire() } // spend the burst
        limiter.acquire() // 4th
        limiter.acquire() // 5th
        assertEquals("beyond the burst, each waits one refill interval", listOf(1000L, 1000L), slept)
    }

    @Test
    fun `throws RateLimited when the next token is past the bound`() = runTest {
        val limiter = limiter(capacity = 1, refillPerMinute = 60, maxWaitMillis = 500)
        limiter.acquire() // consumes the one token
        var rateLimited = false
        try {
            limiter.acquire() // next token is 1000 ms out > 500 bound
        } catch (e: TflException.RateLimited) {
            rateLimited = true
        }
        assertTrue("a token past the bound surfaces RateLimited", rateLimited)
        assertEquals("and it does not sleep first", emptyList<Long>(), slept)
    }

    @Test
    fun `waits within the bound then proceeds`() = runTest {
        val limiter = limiter(capacity = 1, refillPerMinute = 60, maxWaitMillis = 1500)
        limiter.acquire() // consumes the token
        limiter.acquire() // next token 1000 ms out <= 1500 bound → waits, then proceeds
        assertEquals(listOf(1000L), slept)
    }

    @Test
    fun `refills over idle time so a later burst is immediate again`() = runTest {
        val limiter = limiter(capacity = 2, refillPerMinute = 60)
        limiter.acquire()
        limiter.acquire() // burst spent (both immediate)
        assertEquals(emptyList<Long>(), slept)
        now = 60_000 // idle long enough to refill the whole bucket
        limiter.acquire()
        limiter.acquire()
        assertEquals("after idling, the refilled burst is immediate", emptyList<Long>(), slept)
    }

    @Test
    fun `a canceled wait reclaims its slot, not delaying the next request`() = runTest {
        // A token is consumed only when granted, so a wait canceled by a superseded refresh
        // (which MainViewModel does routinely) reserves nothing — the refilled token is still
        // there for the next request. A reserve-ahead limiter would have pushed it out.
        val limiter = TokenBucketRateLimiter(
            capacity = 1,
            refillPerMinute = 60, // one token per 1000 ms
            maxWaitMillis = 10_000,
            nowMillis = { testScheduler.currentTime },
            sleep = { delay(it) },
        )
        limiter.acquire() // t=0: consumes the only token

        val superseded = launch { limiter.acquire() } // waits for the next token
        runCurrent() // let it compute the wait and suspend
        superseded.cancel()
        superseded.join()

        var grantedAt = -1L
        val next = launch {
            limiter.acquire()
            grantedAt = testScheduler.currentTime
        }
        testScheduler.advanceTimeBy(1001)
        testScheduler.runCurrent()
        next.join()

        assertTrue(
            "granted at the first refill (~1000), not delayed by the canceled waiter; was $grantedAt",
            grantedAt in 1000..1001,
        )
    }

    @Test
    fun `UNLIMITED never waits`() = runTest {
        repeat(1000) { TflRateLimiter.UNLIMITED.acquire() }
        assertEquals(emptyList<Long>(), slept)
    }
}
