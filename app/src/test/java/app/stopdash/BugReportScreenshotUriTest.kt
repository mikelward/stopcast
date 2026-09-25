package app.stopdash

import com.mikelward.androidlog.DebugLog
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The bug-report screenshot's URI-minting guard. A `FileProvider` failure must degrade to a
 * text-only report (null), never a dropped share or a crash of the application-scoped share
 * coroutine (SPEC principle 2), and must not leave the unshareable PNG behind. The provider needs
 * a device, so the mint is injected; these run on a plain JVM.
 */
class BugReportScreenshotUriTest {

    private val dir: File = Files.createTempDirectory("stopdash-uri").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun log() = DebugLog(readMillis = { 0L })

    @Test
    fun `a failed mint yields null and deletes the orphaned file`() {
        val file = File(dir, "screenshot-1-r.png").apply { writeBytes(byteArrayOf(1)) }

        val result = bugReportScreenshotUri(file, log()) {
            throw IllegalArgumentException("outside the configured paths")
        }

        assertNull(result)
        assertFalse(file.exists())
    }

    @Test
    fun `cancellation propagates rather than becoming a null screenshot`() {
        val file = File(dir, "screenshot-2-r.png").apply { writeBytes(byteArrayOf(1)) }

        assertThrows(CancellationException::class.java) {
            bugReportScreenshotUri(file, log()) { throw CancellationException("cancelled") }
        }
    }
}
