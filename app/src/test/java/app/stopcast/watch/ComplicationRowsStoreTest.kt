package app.stopcast.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.stopcast.domain.StarredRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The complication rows the phone keeps for its watches: synthetic rows and node ids only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComplicationRowsStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val a = StarredRow("940GA", "victoria", "inbound")
    private val b = StarredRow("940GB", "central", "outbound")

    @Test
    fun `each watch keeps its own rows, and every envelope gets them all`() {
        assertTrue(ComplicationRowsStore.save(context, "watch-1", setOf(a)))
        assertTrue(ComplicationRowsStore.save(context, "watch-2", setOf(b)))
        assertEquals(setOf(a, b), ComplicationRowsStore.load(context))
        // One watch clearing its picks leaves the other's.
        assertTrue(ComplicationRowsStore.save(context, "watch-1", emptySet()))
        assertEquals(setOf(b), ComplicationRowsStore.load(context))
    }

    @Test
    fun `an unchanged sync is no change`() {
        ComplicationRowsStore.save(context, "watch-1", setOf(a))
        assertFalse(ComplicationRowsStore.save(context, "watch-1", setOf(a)))
    }

    @Test
    fun `a lookup's older item never overwrites a change saved while it ran`() {
        ComplicationRowsStore.save(context, "watch-3", setOf(a))
        val since = ComplicationRowsStore.generations()
        // The watch clears its picks while the lookup is in flight; the lookup then reads the old item.
        ComplicationRowsStore.save(context, "watch-3", emptySet())
        assertFalse(ComplicationRowsStore.saveIfUnchanged(context, "watch-3", setOf(a), since))
        assertFalse(a in ComplicationRowsStore.load(context))
        // A watch with no change since still takes the lookup's item.
        assertTrue(ComplicationRowsStore.saveIfUnchanged(context, "watch-4", setOf(b), since))
        assertTrue(b in ComplicationRowsStore.load(context))
    }
}
