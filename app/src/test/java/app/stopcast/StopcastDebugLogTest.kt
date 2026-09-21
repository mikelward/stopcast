package app.stopcast

import com.mikelward.androidlog.DebugLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The privacy invariant that makes [StopcastDebugLog] safe to add a Crashlytics (off-device)
 * sink to later: a warning's message renders in full for an on-device sink, but its `String`
 * argument is withheld (rendered as the library's placeholder) for an off-device sink, because
 * the seams pass a format literal with `%s` per value rather than interpolating.
 *
 * Pure JVM (logging-core is Android-free); uses a fresh [DebugLog] rather than the
 * [StopcastDebugLog] singleton so nothing leaks between tests.
 */
class StopcastDebugLogTest {
    @Test
    fun `a warning shows on-device but is withheld off-device`() {
        val log = DebugLog()
        val onDevice = mutableListOf<String>()
        val offDevice = mutableListOf<String>()
        log.addSink({ line -> onDevice += line }, DebugLog.Destination.DEVICE)
        log.addSink({ line -> offDevice += line }, DebugLog.Destination.OFF_DEVICE)

        // The shape every warn seam uses: an area prefix in the format literal, the coarse
        // (already-sanitized) message as the `%s` argument.
        log.warning("location: %s", "no fix: permission not held")

        assertTrue(
            "on-device sink should carry the full message",
            onDevice.any { it.contains("location: no fix: permission not held") },
        )
        // The message is a String, so off-device it is the placeholder — a future Crashlytics
        // breadcrumb sink cannot leak it unless a call explicitly opts in with safe(...).
        assertTrue(
            "off-device sink should carry the prefix with the value withheld",
            offDevice.any { it.contains("location:") && it.contains("•••") },
        )
        assertFalse(
            "off-device sink must not carry the message text",
            offDevice.any { it.contains("permission not held") },
        )
    }
}
