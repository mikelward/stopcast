package app.stopdash.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryGateTest {

    /** Crashlytics' flag ([collecting]) and Analytics' separately, as the real SDKs keep them. */
    private class FakeBackend(var crashlytics: Boolean, var analytics: Boolean = crashlytics) : TelemetryBackend {
        val calls = mutableListOf<String>()
        var waitingAtStartup: Boolean? = false
        private val pendingChecks = mutableListOf<(Boolean?) -> Unit>()
        override val collecting get() = crashlytics
        override fun switchCollection(enabled: Boolean) {
            calls += "collect=$enabled"
            crashlytics = enabled
            analytics = enabled
        }
        override fun checkUnsent(result: (Boolean?) -> Unit) {
            pendingChecks += result
        }
        override fun discardUnsent() {
            calls += "discard"
        }
        fun answerChecks() = pendingChecks.toList().also { pendingChecks.clear() }.forEach { it(waitingAtStartup) }
    }

    private class FakePending(var value: Boolean = false) : PendingMarker {
        override fun read() = value
        override fun save(pending: Boolean): Boolean {
            value = pending
            return true
        }
    }

    @Test
    fun `off always switches both SDKs off and discards what's cached, even if already off`() {
        val backend = FakeBackend(crashlytics = false)
        TelemetryGate(backend, FakePending()).apply(false)
        assertEquals(listOf("collect=false", "discard"), backend.calls)
    }

    @Test
    fun `off after a half-done switch turns the stray Analytics flag off too`() {
        val backend = FakeBackend(crashlytics = false, analytics = true)
        TelemetryGate(backend, FakePending()).apply(false)
        assertFalse(backend.analytics)
    }

    @Test
    fun `opting in with nothing waiting starts collecting`() {
        val backend = FakeBackend(crashlytics = false)
        val pending = FakePending()
        TelemetryGate(backend, pending).apply(true)
        assertFalse(backend.collecting)
        backend.answerChecks()
        assertTrue(backend.collecting)
        assertTrue(backend.analytics)
        assertFalse(pending.value)
    }

    @Test
    fun `opting in over a pre-consent crash discards it and waits for a later launch`() {
        val backend = FakeBackend(crashlytics = false).apply { waitingAtStartup = true }
        val pending = FakePending()
        TelemetryGate(backend, pending).apply(true)
        backend.answerChecks()
        assertFalse(backend.collecting)
        assertTrue("discard" in backend.calls)
        assertTrue(pending.value)
        // The next launch's startup check is clean: now it collects.
        backend.waitingAtStartup = false
        TelemetryGate(backend, pending).apply(true)
        backend.answerChecks()
        assertTrue(backend.collecting)
        assertFalse(pending.value)
    }

    @Test
    fun `when the crash SDK can't say, it stays off and retries next launch`() {
        val backend = FakeBackend(crashlytics = false).apply { waitingAtStartup = null }
        val pending = FakePending()
        TelemetryGate(backend, pending).apply(true)
        backend.answerChecks()
        assertFalse(backend.collecting)
        assertTrue(pending.value)
    }

    @Test
    fun `an opt-out before the check answers wins, and clears the pending opt-in`() {
        val backend = FakeBackend(crashlytics = false)
        val pending = FakePending(value = true)
        val gate = TelemetryGate(backend, pending)
        gate.apply(true)
        gate.apply(false)
        backend.answerChecks()
        assertFalse(backend.collecting)
        assertFalse(backend.analytics)
        assertFalse(pending.value)
    }

    @Test
    fun `an opted-in restart keeps the last run's crash to send`() {
        val backend = FakeBackend(crashlytics = true)
        TelemetryGate(backend, FakePending()).apply(true)
        assertEquals(listOf("collect=true"), backend.calls)
    }

    @Test
    fun `opting out stops collecting first, then discards what's unsent`() {
        val backend = FakeBackend(crashlytics = true)
        TelemetryGate(backend, FakePending()).apply(false)
        assertEquals(listOf("collect=false", "discard"), backend.calls)
    }

    @Test
    fun `off still clears pending and discards when switching the SDKs off throws`() {
        val calls = mutableListOf<String>()
        val backend = object : TelemetryBackend {
            override val collecting = true
            override fun switchCollection(enabled: Boolean) = throw IllegalStateException("sdk")
            override fun checkUnsent(result: (Boolean?) -> Unit) = result(false)
            override fun discardUnsent() {
                calls += "discard"
            }
        }
        val pending = FakePending(value = true)
        val thrown = runCatching { TelemetryGate(backend, pending).apply(false) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(pending.value)
        assertEquals(listOf("discard"), calls)
    }

    @Test
    fun `every switch-off step runs, and the first failure is reported`() {
        val ran = mutableListOf<Int>()
        val first = IllegalStateException("crashlytics")
        val second = IllegalArgumentException("analytics")
        val thrown = runCatching {
            attemptAll(
                { ran += 1; throw first },
                { ran += 2; throw second },
                { ran += 3 },
            )
        }.exceptionOrNull()
        assertEquals(listOf(1, 2, 3), ran)
        assertTrue(thrown === first)
        assertTrue(first.suppressed.single() === second)
    }

    @Test
    fun `off reports a pending opt-in it couldn't clear, and still switches off and discards`() {
        val backend = FakeBackend(crashlytics = false, analytics = true)
        val stuck = object : PendingMarker {
            override fun read() = true
            override fun save(pending: Boolean) = pending // clearing fails
        }
        val thrown = runCatching { TelemetryGate(backend, stuck).apply(false) }.exceptionOrNull()
        assertTrue(thrown is IllegalStateException)
        assertFalse(backend.analytics)
        assertEquals(listOf("collect=false", "discard"), backend.calls)
    }
}
