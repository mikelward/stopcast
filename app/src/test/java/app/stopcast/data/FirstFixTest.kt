package app.stopcast.data

import app.stopcast.domain.Coordinates
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accurate-first fresh-fix waterfall used by `AndroidLocationProvider` (the
 * `LocationManager` glue itself needs a device). Regression coverage for the field case where a
 * fused provider that accepts the request but never calls back must not starve the providers
 * behind it: the first provider timing out has to fall through to the next within its own bound,
 * so a device whose GPS would answer still gets a precise fix (Codex). Also pins that a
 * timed-out provider is reported via `onTimeout` so a hanging provider stays diagnosable even
 * when the overall result is a later provider's no-fix (Codex).
 */
class FirstFixTest {
    private val timeout = 4_000L
    private val fix = Coordinates(51.5, -0.12)

    @Test
    fun `a provider that never answers times out and the next is tried`() = runTest {
        val queried = mutableListOf<String>()
        val timedOut = mutableListOf<String>()
        val result = firstFix(listOf("fused", "gps"), timeout, onTimeout = { timedOut += it }) { provider ->
            queried += provider
            // Fused accepts the request but never calls back within its bound; GPS answers.
            if (provider == "fused") { delay(timeout * 2); fix } else fix
        }
        assertEquals(fix, result)
        assertEquals(listOf("fused", "gps"), queried)
        assertEquals("the hung provider is reported", listOf("fused"), timedOut)
    }

    @Test
    fun `a provider that returns no fix falls through without reporting a timeout`() = runTest {
        val timedOut = mutableListOf<String>()
        val result = firstFix(listOf("fused", "gps"), timeout, onTimeout = { timedOut += it }) { provider ->
            if (provider == "fused") null else fix
        }
        assertEquals(fix, result)
        assertTrue("a prompt no-fix is not a timeout", timedOut.isEmpty())
    }

    @Test
    fun `every provider timing out returns null and reports each`() = runTest {
        val timedOut = mutableListOf<String>()
        val result = firstFix(listOf("fused", "gps"), timeout, onTimeout = { timedOut += it }) {
            delay(timeout * 2)
            fix
        }
        assertNull(result)
        assertEquals(listOf("fused", "gps"), timedOut)
    }

    @Test
    fun `the first provider to yield a fix wins and later ones are not tried`() = runTest {
        val queried = mutableListOf<String>()
        val result = firstFix(listOf("fused", "gps"), timeout) { provider ->
            queried += provider
            fix
        }
        assertEquals(fix, result)
        assertEquals(listOf("fused"), queried)
    }

    @Test
    fun `no provider yields a fix returns null`() = runTest {
        val result = firstFix(listOf("fused", "gps"), timeout) { null }
        assertNull(result)
    }
}
