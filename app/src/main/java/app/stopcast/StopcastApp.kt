package app.stopcast

import android.app.Application
import android.content.Context
import android.util.Log
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.LogcatSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

private const val LOGCAT_TAG = "StopCast"

/**
 * Registers the diagnostic log's DEVICE sinks on [log]: the developer-facing [LogcatSink] and
 * the on-device [DebugFileSink] that persists the buffer to `cacheDir`. [DebugFileSink.start]
 * rotates the previous run's file aside and (when [installCrashHandler]) chains an
 * uncaught-exception handler; it runs **before** the file sink is added so the rotation is
 * ordered ahead of this run's writes.
 *
 * Top-level, with [log] and [installCrashHandler] injected and the file sink returned, so a
 * test can drive the real registration against a fresh [DebugLog], without hijacking the JVM's
 * crash handler, and then flush the returned sink ([DebugFileSink.awaitIdle]) to assert the
 * warning actually reached the persisted file — the production installation path is covered,
 * not bypassed.
 */
internal fun installDiagnosticSinks(
    log: DebugLog,
    context: Context,
    installCrashHandler: Boolean = true,
): DebugFileSink {
    log.addSink(LogcatSink(LOGCAT_TAG), DebugLog.Destination.DEVICE)
    val fileSink = DebugFileSink(log, context)
    fileSink.start(installCrashHandler)
    log.addSink(fileSink, DebugLog.Destination.DEVICE)
    return fileSink
}

/**
 * The Application. Its one job today is to stand up the diagnostic log ([StopcastDebugLog]) at
 * process start — before any Activity, refresh, or widget update runs — so every warning after
 * startup is both visible in Logcat and persisted on-device.
 *
 * The persisted file is **on-device only** (`cacheDir`, excluded from backup) and is not shared
 * from here; a user-shareable export with travel data redacted is a later change
 * (`docs/PRIVACY.md`, `TODO.md`). No off-device sink is registered.
 */
open class StopcastApp : Application() {
    /**
     * The persisted diagnostic sink once it is stood up — the shared logger's on-device file
     * store. Handed to the consent-gated bug report so it can bundle (and, once shared, consume)
     * the earlier runs the sink kept. Null before [onCreate] runs, if setup failed, or in the
     * test Application that skips installation — a report then simply carries no earlier runs.
     */
    var diagnosticSink: DebugFileSink? = null
        private set

    /**
     * A process-lived scope for work that must outlive the Activity that started it — the bug
     * report's collect+share, which reads the persisted log (up to ~10 s) and opens the share
     * sheet, so a rotation mid-collect must not cancel it and drop the share silently (SPEC
     * principle 2; Codex P2 on #86). Main-dispatched because delivery touches the clipboard and
     * starts an activity; the collect step moves itself to IO. Never canceled — it lives for the
     * process, like the app.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        installDiagnosticLog()
    }

    /**
     * `open` so a test [Application] can skip standing up the persisted sink and the process-wide
     * crash handler (the unit-test suite does, via `robolectric.properties`).
     *
     * Guarded: the log exists to diagnose failures, so a failure to *stand it up* must never
     * crash the app. Per the error-handling rule it is logged (to Logcat, the sink always safe to
     * reach here) and then swallowed — a missing persisted log is a lost diagnostic, not a reason
     * to take down the process. There is nothing acquired to clean up on this path.
     */
    protected open fun installDiagnosticLog() {
        // Synchronous, on the main thread, deliberately: DebugFileSink.start() installs the
        // uncaught-exception handler on the *calling* thread, so it must run before onCreate
        // returns — otherwise a crash in an Activity, widget receiver, or worker launched right
        // after startup would go uncaptured, defeating the crash-survival guarantee (Codex, PR
        // #84). It does no first-frame I/O: the library rotates the prior run's file on its own
        // worker, never here. Guarded so a failure to stand up the log is logged and swallowed
        // rather than crashing the app it exists to diagnose (nothing acquired to clean up here).
        try {
            diagnosticSink = installDiagnosticSinks(StopcastDebugLog, this)
        } catch (e: Exception) {
            Log.w(LOGCAT_TAG, "diagnostic log setup failed: ${e::class.simpleName}")
        }
    }
}
