package app.stopdash.domain

import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    @Test
    fun `under a minute reads just now`() {
        assertEquals("just now", RelativeTime.formatAge(0.seconds))
        assertEquals("just now", RelativeTime.formatAge(59.seconds))
    }

    @Test
    fun `minutes are truncated, not rounded`() {
        assertEquals("1 min ago", RelativeTime.formatAge(60.seconds))
        assertEquals("1 min ago", RelativeTime.formatAge(119.seconds))
        assertEquals("2 min ago", RelativeTime.formatAge(2.minutes))
    }

    @Test
    fun `an hour or more reads in hours`() {
        assertEquals("59 min ago", RelativeTime.formatAge(59.minutes))
        assertEquals("1 h ago", RelativeTime.formatAge(60.minutes))
        assertEquals("3 h ago", RelativeTime.formatAge(3.hours + 20.minutes))
    }
}
