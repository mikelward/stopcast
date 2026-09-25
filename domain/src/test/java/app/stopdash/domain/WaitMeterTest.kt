package app.stopdash.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitMeterTest {
    private var now = 0L
    private val meter = WaitMeter { now }

    @Test
    fun `overlapping waits count once`() {
        meter.enter() // t=0
        now = 500
        meter.enter()
        now = 1_500
        meter.exit()
        now = 2_000
        meter.exit()
        assertEquals(2_000, meter.totalMillis())
    }

    @Test
    fun `separate waits add up, and a wait in progress counts so far`() {
        meter.enter()
        now = 1_000
        meter.exit()
        now = 5_000
        meter.enter()
        now = 5_300
        assertEquals(1_300, meter.totalMillis())
    }

    @Test
    fun `a stray exit doesn't go negative`() {
        meter.exit()
        assertEquals(0, meter.totalMillis())
    }
}
