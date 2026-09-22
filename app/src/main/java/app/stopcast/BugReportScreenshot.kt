package app.stopcast

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import androidx.core.content.FileProvider
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Captures the current screen for a bug report and serves it through [FileProvider].
 *
 * The capture is deliberately of `activity.window` alone: the consent dialog is a *separate*
 * window, so it (and its dim scrim) are not in this surface — the screenshot is the departures
 * or gate screen the user is actually reporting, not the dialog over it. A missing capture is a
 * text-only report, never a dropped share (SPEC principle 2); every failure is logged sanitized
 * (a class name, never a coordinate — *Privacy*) rather than swallowed.
 */
internal object BugReportScreenshot {

    /** Cache subdirectory holding the captures attached to bug reports (see `@xml/file_paths`). */
    private const val DIR_NAME = "bug-reports"

    /**
     * How long a capture survives before a later capture's prune may delete it. Eviction is by
     * age, not count: a count-based "keep newest N" can delete a capture that is still in flight —
     * several reports started close together each capture before their up-to-10s collection
     * finishes, and the newest few would evict the oldest while its own `deliver` hasn't run yet.
     * Anything younger than this window is never pruned, so an in-flight capture is always safe.
     *
     * The window is a day, deliberately generous: the app can't know when a share target is done
     * reading a granted URI, so the window has to outlast any realistic hold (an open email/message
     * compose), which a day does — nobody keeps one open that long. Bounded growth without a
     * race; `cacheDir`'s own OS eviction under storage pressure is the backstop past that.
     */
    private const val RETAIN_MILLIS = 24 * 60 * 60 * 1000L

    /** Matches the "applicationId + .fileprovider" authority declared in the manifest. */
    private const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Captures [activity]'s window, writes it to a private cache PNG, and returns a
     * [FileProvider] content URI the share sheet target can read — or null if there is nothing
     * worth capturing (a finished window) or a step failed. Safe to call from the
     * application-scoped share coroutine: it holds [activity] only until the capture returns.
     */
    suspend fun capture(activity: Activity): Uri? {
        // The share can outlive the screen that started it (it runs on the application scope). A
        // destroyed window has nothing worth capturing and PixelCopy against its stale token
        // fails anyway — go straight to a text-only report instead of spending a 10-30 MB buffer
        // finding out.
        if (activity.isFinishing || activity.isDestroyed) return null
        // Logged, not silently dropped: a throw before PixelCopy's own guard — allocating the
        // bitmap, reading the decor view — would otherwise become a text-only report with nothing
        // saying why. Cancellation still propagates.
        val bitmap = try {
            captureWindow(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            StopcastDebugLog.warning("bug report: window capture failed: %s", e::class.simpleName)
            null
        } ?: return null
        // Compressing a full-window PNG and pruning previous files would block the main thread
        // long enough to jank the share-sheet open, so persist on Dispatchers.IO.
        return try {
            withContext(Dispatchers.IO) {
                var file: File? = null
                // Only the hand-off returns the file to the caller; every other exit (a false
                // encode, a throw mid-encode or mid-mint, cancellation) leaves an unusable PNG, so
                // one `finally` deletes it rather than a per-branch cleanup that keeps missing a path.
                var handedOff = false
                try {
                    val dir = File(activity.cacheDir, DIR_NAME).apply { mkdirs() }
                    // Prune only captures older than the retain window: a recent one may be a
                    // concurrent report's in-flight capture, or a URI a share target still reads
                    // lazily (an unsent email draft), and deleting either breaks it. Age, not count.
                    prunePersistedScreenshots(dir, now = System.currentTimeMillis(), retainMillis = RETAIN_MILLIS)
                    // createTempFile appends random digits, so two reports persisting in the same
                    // millisecond can't collide on one path (and overwrite/interleave each other's
                    // encode). The capture millis stays in the prefix — `screenshot-<millis>-<rand>`
                    // — so the age prune still reads it, falling back to lastModified otherwise.
                    file = File.createTempFile("screenshot-${System.currentTimeMillis()}-", ".png", dir)
                    val compressed = FileOutputStream(file).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    if (!compressed) {
                        // A false return (no throw) leaves a truncated/empty PNG; a URI to it would
                        // attach a broken screenshot instead of the promised text-only fallback.
                        StopcastDebugLog.warning("bug report: screenshot PNG encode returned false")
                        null
                    } else {
                        FileProvider.getUriForFile(
                            activity,
                            activity.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX,
                            file,
                        ).also { handedOff = true }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    StopcastDebugLog.warning("bug report: screenshot persist failed: %s", e::class.simpleName)
                    null
                } finally {
                    if (!handedOff) file?.delete()
                }
            }
        } finally {
            // Only the PNG on disk outlives this call; free the full-window ARGB_8888 buffer
            // (10-30 MB on current phones) now instead of waiting for GC. Safe even on
            // cancellation: withContext waits for its block, so compress has finished with the
            // bitmap by the time we get here.
            bitmap.recycle()
        }
    }

    /**
     * Deletes `screenshot-*.png` captures in [dir] older than [retainMillis] before [now], age
     * judged by the millis embedded in the filename (falling back to `lastModified` for a name
     * that doesn't parse). Called before each new capture is written. Age rather than count so a
     * capture that is still in flight — a concurrent report's, or one whose URI a share target is
     * about to read — is never pruned: it is younger than the window. Bounded growth without that
     * race, since anything past the window has no live use. Visible for tests.
     */
    internal fun prunePersistedScreenshots(dir: File, now: Long, retainMillis: Long) {
        val captures = dir.listFiles { file ->
            file.isFile && file.name.startsWith("screenshot-") && file.name.endsWith(".png")
        } ?: return
        captures.forEach { file ->
            // Name is `screenshot-<millis>-<rand>.png`; read the millis prefix, falling back to
            // the file's mtime for any name that doesn't carry one.
            val capturedAt = file.name.removePrefix("screenshot-").substringBefore("-").toLongOrNull()
                ?: file.lastModified()
            if (now - capturedAt > retainMillis) file.delete()
        }
    }

    private suspend fun captureWindow(activity: Activity): Bitmap? {
        val window = activity.window ?: return null
        val view: View = window.decorView
        // View geometry is main-thread state, and capture() runs on Dispatchers.Main.immediate —
        // so read the size and location here, before moving the heavy allocation off the thread.
        if (view.width <= 0 || view.height <= 0) return null
        val width = view.width
        val height = view.height
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(location[0], location[1], location[0] + width, location[1] + height)
        // Allocating and zero-filling a full-window ARGB_8888 buffer is 10-30 MB of CPU work; on
        // the main thread that stalls the share flow — a dropped frame or two right as the consent
        // dialog dismisses. Do it on Default. requestPixelCopy below resumes on Main and delivers
        // its result on a main-looper Handler, so the copy itself is unaffected.
        val bitmap = withContext(Dispatchers.Default) { createBitmap(width, height) }
        return awaitPixelCopyInto(bitmap) { onResult -> requestPixelCopy(window, rect, bitmap, onResult) }
    }

    /**
     * Suspends until [request] reports whether the copy into [bitmap] landed, returning the
     * bitmap on success and null on failure. The bitmap is a full-window ARGB_8888 buffer (10-30
     * MB on current phones), so every path that does not hand it to the caller recycles it: a
     * failed copy, a synchronous throw from [request], and a caller cancelled before the result
     * arrived. In the cancelled case the recycle happens in the (now ignored) result callback
     * rather than eagerly at cancellation time, because PixelCopy may still be writing into the
     * buffer until then. Visible for tests.
     */
    internal suspend fun awaitPixelCopyInto(
        bitmap: Bitmap,
        request: (onResult: (Boolean) -> Unit) -> Unit,
    ): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            request { ok ->
                when {
                    // Cancelled while the copy was in flight: the result is unwanted, but
                    // PixelCopy has finished writing into the buffer by the time its callback
                    // fires, so free it here rather than eagerly at cancellation time.
                    !cont.isActive -> bitmap.recycle()
                    ok -> cont.resume(bitmap)
                    else -> {
                        bitmap.recycle()
                        cont.resume(null)
                    }
                }
            }
        } catch (e: CancellationException) {
            // A cancelled capture is not a failed one: free the buffer and let cancellation
            // propagate rather than resuming with null, which would let the caller carry on and
            // deliver a report the coroutine was told to stop building.
            bitmap.recycle()
            throw e
        } catch (e: Exception) {
            // Nonfatal only: a fatal Error (an allocation or native-linkage failure) must not be
            // turned into a null "text-only" capture and swallowed — let it propagate.
            StopcastDebugLog.warning("bug report: PixelCopy.request threw: %s", e::class.simpleName)
            bitmap.recycle()
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun requestPixelCopy(
        window: Window,
        rect: Rect,
        bitmap: Bitmap,
        onResult: (Boolean) -> Unit,
    ) {
        val handler = Handler(Looper.getMainLooper())
        PixelCopy.request(window, rect, bitmap, { result ->
            // Log the ordinary non-success code (e.g. ERROR_TIMEOUT, ERROR_SOURCE_NO_DATA) so a
            // report that silently arrives without its screenshot still says why in the log. The
            // code is a fixed PixelCopy constant, not user data.
            if (result != PixelCopy.SUCCESS) {
                StopcastDebugLog.warning("bug report: PixelCopy failed (code %s)", result.toString())
            }
            onResult(result == PixelCopy.SUCCESS)
        }, handler)
    }
}
