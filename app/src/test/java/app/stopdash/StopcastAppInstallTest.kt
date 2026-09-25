package app.stopdash

import com.mikelward.androidlog.DebugLog
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises the production diagnostic-log installation path — the real `LogcatSink` +
 * `DebugFileSink` construction, `start()`, and start-before-add ordering in
 * [installDiagnosticSinks] — and asserts the warning actually reaches the **persisted file**,
 * not just the in-memory buffer. So removing `addSink(fileSink, …)` (or the write) fails this
 * test rather than leaving production silently without persisted diagnostics.
 *
 * The suite otherwise runs [TestStopcastApp], which skips installation; this is the one test
 * that runs the real path. Robolectric, because the library `DebugFileSink` needs a `Context`
 * and its `cacheDir`. Deterministic via the library's own `awaitIdle` seam — no sleep.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class StopcastAppInstallTest {
    @Test
    fun `installing the diagnostic sinks persists a warning to the file sink`() {
        val context = RuntimeEnvironment.getApplication()
        // Start from an empty cache so the assertion can't match a stale file.
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }

        val log = DebugLog()
        // The real registration: LogcatSink + DebugFileSink to the app's cacheDir, start()
        // ahead of addSink. installCrashHandler = false so the test doesn't take over the JVM's
        // uncaught-exception handler.
        val fileSink = installDiagnosticSinks(log, context, installCrashHandler = false)

        log.warning("probe: %s", "value")
        // Flush the debounced mirror write deterministically (the seam the library exposes for
        // exactly a consuming app's tests), then read what the file sink actually wrote.
        fileSink.awaitIdle()

        val persisted = context.cacheDir.listFiles().orEmpty()
            .filter { it.isFile }
            .any { runCatching { it.readText() }.getOrDefault("").contains("probe: value") }
        assertTrue("expected the warning persisted to a file-sink file in cacheDir", persisted)
    }
}
