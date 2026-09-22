package app.stopcast

import android.graphics.Bitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Covers the bug-report screenshot cache pruning and the full-window buffer cleanup.
 *
 * Pruning: each new capture prunes the directory but must keep the most recent previous
 * captures — a FileProvider URI from an earlier share can still be held by its target (an unsent
 * email draft, a lazily-reading messaging app), so deleting every file retroactively breaks that
 * grant (the attachment fails with FileNotFoundException when the target finally reads it).
 *
 * Recycling: the capture allocates a full-window ARGB_8888 bitmap (10-30 MB on current phones),
 * so every path that does not hand it to the caller must recycle it — a failed copy, a
 * synchronous throw, and a cancel mid-capture — while the one path that does hands it over live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BugReportScreenshotCaptureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 10_000_000L
    private val retain = 5 * 60 * 1000L

    // Names carry the capture millis in the prefix, then a `-<rand>` collision-proof suffix that
    // createTempFile adds, so the prune reads the age from before the first dash.
    @Test
    fun `prune deletes captures older than the retain window and keeps recent ones`() {
        val dir = tmp.root
        val old = dir.resolve("screenshot-${now - retain - 1}-r.png").apply { writeBytes(byteArrayOf(1)) }
        val recent = dir.resolve("screenshot-${now - 5_000}-r.png").apply { writeBytes(byteArrayOf(2)) }

        BugReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain)

        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `an in-flight capture survives a concurrent prune`() {
        // Regression: several reports started close together each capture before their own
        // up-to-10s collection finishes. A just-written capture (age 0) is younger than the
        // window, so a concurrent report's prune must leave it — its own deliver still needs it.
        val dir = tmp.root
        val inFlight = dir.resolve("screenshot-$now-r.png").apply { writeBytes(byteArrayOf(1)) }

        BugReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain)

        assertTrue(inFlight.exists())
    }

    @Test
    fun `prune ignores unrelated files`() {
        val dir = tmp.root
        val unrelated = dir.resolve("notes.txt").apply { writeBytes(byteArrayOf(9)) }
        val old = dir.resolve("screenshot-${now - retain - 100}-r.png").apply { writeBytes(byteArrayOf(1)) }
        val recent = dir.resolve("screenshot-${now - 1_000}-r.png").apply { writeBytes(byteArrayOf(2)) }

        BugReportScreenshot.prunePersistedScreenshots(dir, now = now, retainMillis = retain)

        assertTrue(unrelated.exists())
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

    @Test
    fun `a successful copy returns the bitmap unrecycled`(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReportScreenshot.awaitPixelCopyInto(bitmap) { onResult -> onResult(true) }

        assertSame(bitmap, result)
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun `a failed copy recycles the bitmap`(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReportScreenshot.awaitPixelCopyInto(bitmap) { onResult -> onResult(false) }

        assertNull(result)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `a synchronous request failure recycles the bitmap`(): Unit = runBlocking {
        val bitmap = newBitmap()

        val result = BugReportScreenshot.awaitPixelCopyInto(bitmap) { throw IllegalStateException("boom") }

        assertNull(result)
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun `cancellation recycles the bitmap only once the copy lands`(): Unit = runBlocking {
        val bitmap = newBitmap()
        var deliverResult: ((Boolean) -> Unit)? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            BugReportScreenshot.awaitPixelCopyInto(bitmap) { onResult -> deliverResult = onResult }
        }
        job.cancelAndJoin()

        // PixelCopy may still be writing into the buffer until its callback fires, so cancellation
        // alone must not recycle...
        assertFalse(bitmap.isRecycled)

        // ...but once the (now unwanted) result lands, the buffer is freed.
        deliverResult!!(true)
        assertTrue(bitmap.isRecycled)
    }
}
