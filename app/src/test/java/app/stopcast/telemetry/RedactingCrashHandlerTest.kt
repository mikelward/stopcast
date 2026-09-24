package app.stopcast.telemetry

import com.mikelward.androidlog.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactingCrashHandlerTest {

    private fun trace(e: Throwable): String = e.stackTraceToString()

    @Test
    fun `a fatal crash reaches Crashlytics without its messages`() {
        var received: Throwable? = null
        val handler = RedactingCrashHandler({ _, e -> received = e }, DebugLog()::offDeviceThrowable)
        val cause = IllegalStateException("GET https://api.tfl.gov.uk/StopPoint?lat=51.5&lon=-0.12&app_key=EXAMPLE")
        handler.uncaughtException(Thread.currentThread(), RuntimeException("refresh failed for 490000001", cause))
        val sent = trace(received!!)
        assertFalse(sent, sent.contains("51.5"))
        assertFalse(sent, sent.contains("EXAMPLE"))
        assertFalse(sent, sent.contains("490000001"))
        assertTrue(sent, sent.contains("IllegalStateException"))
        assertTrue(sent, sent.contains("RedactingCrashHandlerTest"))
    }

    @Test
    fun `a redaction that throws still reports the crash, by type only`() {
        var received: Throwable? = null
        val handler = RedactingCrashHandler({ _, e -> received = e }, redact = { throw IllegalArgumentException("boom") })
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("app_key=EXAMPLE"))
        val sent = trace(received!!)
        assertFalse(sent, sent.contains("EXAMPLE"))
        assertTrue(sent, sent.contains("IllegalStateException"))
    }

    @Test
    fun `queued breadcrumbs are flushed before the crash is reported, even if the flush throws`() {
        val order = mutableListOf<String>()
        RedactingCrashHandler({ _, _ -> order += "report" }, { it }, beforeReport = { order += "flush" })
            .uncaughtException(Thread.currentThread(), IllegalStateException())
        assertEquals(listOf("flush", "report"), order)

        order.clear()
        RedactingCrashHandler({ _, _ -> order += "report" }, { it }, beforeReport = { throw IllegalStateException() })
            .uncaughtException(Thread.currentThread(), IllegalStateException())
        assertEquals(listOf("report"), order)
    }
}
