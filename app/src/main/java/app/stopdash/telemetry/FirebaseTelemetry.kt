package app.stopdash.telemetry

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mikelward.androidlog.DebugLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Firebase-backed [TelemetryBackend], or null from [orNull] when this build carries no
 * Firebase config (no google-services.json, or a debug build — dev-docs/firebase.md): Firebase
 * never initializes then, and its accessors would throw.
 */
class FirebaseTelemetryBackend private constructor(
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics,
) : TelemetryBackend {

    override val collecting: Boolean get() = crashlytics.isCrashlyticsCollectionEnabled

    override fun switchCollection(enabled: Boolean) {
        // Crashlytics' flag is the commit marker (TelemetryBackend): last on, first off.
        if (enabled) {
            analytics.setAnalyticsCollectionEnabled(true)
            crashlytics.setCrashlyticsCollectionEnabled(true)
        } else {
            // Both attempted even if the first throws: one SDK failing must not leave the other on.
            attemptAll(
                { crashlytics.setCrashlyticsCollectionEnabled(false) },
                { analytics.setAnalyticsCollectionEnabled(false) },
            )
        }
    }

    override fun checkUnsent(result: (Boolean?) -> Unit) {
        crashlytics.checkForUnsentReports().addOnCompleteListener { check ->
            result(if (check.isSuccessful) check.result == true else null)
        }
    }

    override fun discardUnsent() {
        attemptAll(
            { crashlytics.deleteUnsentReports() },
            // A fresh Analytics app-instance ID after a withdrawal or a new opt-in, so the two
            // stretches of usage stats aren't linked. (Crashlytics keeps its own installation ID;
            // docs/PRIVACY.md says so.)
            { analytics.resetAnalyticsData() },
        )
    }

    companion object {
        fun orNull(context: Context): FirebaseTelemetryBackend? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            // If one SDK can't be reached, the other is switched off before the failure is
            // reported: the caller gets no backend, so nothing else could turn it off.
            val (analytics, crashlytics) = acquireBoth(
                first = { FirebaseAnalytics.getInstance(context) },
                switchFirstOff = { it.setAnalyticsCollectionEnabled(false) },
                second = { FirebaseCrashlytics.getInstance() },
                switchSecondOff = { it.setCrashlyticsCollectionEnabled(false) },
            )
            return FirebaseTelemetryBackend(analytics, crashlytics)
        }

        /**
         * Puts a [RedactingCrashHandler] directly in front of Crashlytics' fatal-crash handler, so a
         * crash reaches Crashlytics only as [redact] renders it. Call before anything else chains
         * onto the default handler (the diagnostic log's file sink), so that sink, which stays on
         * the device, still sees the original. True once installed, or when this build has no
         * Firebase to protect; false if Crashlytics' handler couldn't be found.
         */
        fun installCrashRedaction(
            context: Context,
            redact: (Throwable) -> Throwable,
            beforeReport: () -> Unit,
        ): Boolean {
            if (FirebaseApp.getApps(context).isEmpty()) return true
            FirebaseCrashlytics.getInstance() // its handler is installed when it starts
            val crashlytics = Thread.getDefaultUncaughtExceptionHandler() ?: return false
            Thread.setDefaultUncaughtExceptionHandler(RedactingCrashHandler(crashlytics, redact, beforeReport))
            return true
        }
    }
}

/**
 * Mirrors the diagnostic log's **off-device** lines into Crashlytics, so an uploaded crash report
 * carries the few lines that led up to it, and records each logged exception as a non-fatal.
 *
 * Registered as a [DebugLog.Destination.OFF_DEVICE] sink, so androidlog redacts before anything
 * reaches here: an argument not marked `safe(...)` (a stop ID, a line ID, a coordinate) arrives
 * as a placeholder, and an exception arrives as its types and frames without its message. This
 * sink adds no redaction of its own and must never be registered as a DEVICE sink.
 *
 * [optedIn] is read at submit and again at delivery, so an opt-out that lands in between wins.
 * Delivery runs on its own single worker, never on the logging thread: a log call is often on a
 * refresh or render path, which must not wait on the SDK. A delivery failure goes to Logcat —
 * not the shared log, which would feed it straight back here — once per process, so a broken SDK
 * leaves a trace without flooding it; the lines themselves are best-effort.
 */
class CrashlyticsLogSink internal constructor(
    private val optedIn: () -> Boolean,
    private val sendLine: (String) -> Unit,
    private val sendException: (Throwable) -> Unit,
    private val deliver: (Runnable) -> Unit,
    private val onFailure: (String) -> Unit = {},
) : DebugLog.Sink {

    constructor(optedIn: () -> Boolean) : this(
        optedIn,
        sendLine = { FirebaseCrashlytics.getInstance().log(it) },
        sendException = { FirebaseCrashlytics.getInstance().recordException(it) },
        deliver = worker()::execute,
        onFailure = { Log.w("StopDash", "telemetry: $it") },
    )

    override fun log(line: String) = log(line, ' ', null)

    override fun log(line: String, level: Char) = log(line, level, null)

    override fun log(line: String, level: Char, throwable: Throwable?) {
        if (!optedIn()) return
        runCatching {
            deliver(
                Runnable {
                    if (!optedIn()) return@Runnable
                    runCatching { sendLine(line) }.onFailure(::reportFailure)
                    if (throwable != null) runCatching { sendException(throwable) }.onFailure(::reportFailure)
                },
            )
        }.onFailure(::reportFailure)
    }

    /**
     * Waits up to [timeoutMillis] for everything already queued to reach the SDK. For the fatal-crash
     * path ([RedactingCrashHandler.beforeReport]): the last line before a crash is the one most worth
     * having, and Crashlytics may close the report and end the process before the worker gets to it.
     * Bounded, so a stuck SDK can't hang the crash. True if the queue drained in time.
     */
    fun drain(timeoutMillis: Long): Boolean {
        val done = CountDownLatch(1)
        return try {
            deliver(Runnable { done.countDown() })
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            reportFailure(e)
            false
        }
    }

    @Volatile
    private var failureReported = false

    private fun reportFailure(e: Throwable) {
        if (failureReported) return
        failureReported = true
        runCatching { onFailure("crashlytics delivery failed: ${e::class.simpleName}") }
    }

    private companion object {
        fun worker() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
            Thread(runnable, "stopdash-crashlytics-log").apply { isDaemon = true }
        }
    }
}
