package app.stopcast

import com.mikelward.androidlog.DebugLog

/**
 * StopCast's process-wide diagnostic log — the shared `mikelward/androidlog` buffer that
 * `docs/PRIVACY.md` describes. A named subclass rather than the base [DebugLog] directly, so
 * it reads as one log at every call site while a test can still build a fresh instance.
 *
 * Sinks are registered once in [StopcastApp]: a Logcat sink (the developer-facing log) and the
 * on-device file sink that persists the buffer to `cacheDir`, so a crash or a silent kill still
 * leaves a diagnosable record. **No off-device sink is registered — nothing here leaves the
 * device.** The library's `Destination.OFF_DEVICE` type boundary (a `String` argument is
 * withheld and rendered as a placeholder unless wrapped in `safe(...)`) is what will let a
 * Crashlytics breadcrumb sink be added later without auditing every call site; until that
 * lands there is no such sink.
 *
 * The *Privacy* rule governs every call regardless of sink: log a stop id, a line id, or an
 * HTTP status — never a coordinate or the TfL `app_key`. Pass a format **literal** with one
 * `%s` per argument (never interpolate a value into the format string), so an argument's type,
 * not its text, decides whether it could ever leave the device.
 */
internal object StopcastDebugLog : DebugLog()
