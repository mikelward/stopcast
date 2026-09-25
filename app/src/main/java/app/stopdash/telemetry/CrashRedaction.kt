package app.stopdash.telemetry

/**
 * Hands [next] (Crashlytics' own fatal-crash handler) only a redacted copy of an uncaught
 * exception — its types and frames without any message, as androidlog renders a throwable for an
 * off-device sink ([redact], `DebugLog.offDeviceThrowable`). An exception's message can carry what
 * must never leave the device: a failed TfL request's URL holds the query's coordinates and the
 * user's `app_key`. Non-fatals already arrive redacted through [CrashlyticsLogSink]; this is the
 * same boundary for the crash that ends the process.
 *
 * If redaction itself throws, [next] still gets a crash to record, with the exception's type and
 * frames only, so the crash is reported rather than lost and the process still dies the usual way.
 */
class RedactingCrashHandler(
    private val next: Thread.UncaughtExceptionHandler,
    private val redact: (Throwable) -> Throwable,
    /**
     * Runs first, before [next] closes the report: flushes the log lines still queued for
     * Crashlytics ([CrashlyticsLogSink.drain]), so the report carries the last line before the crash.
     */
    private val beforeReport: () -> Unit = {},
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, e: Throwable) {
        try {
            beforeReport()
        } catch (_: Exception) {
            // Deliberately not logged, as below: the crash is still reported, just maybe without
            // its last breadcrumb.
        }
        val safe = try {
            redact(e)
        } catch (_: Exception) {
            // Deliberately not logged: this is the crash path, the logger's file sink already has
            // the original, and a log call here could recurse into the dying process's handlers.
            RuntimeException(e::class.java.name).apply { stackTrace = e.stackTrace }
        }
        next.uncaughtException(thread, safe)
    }
}
