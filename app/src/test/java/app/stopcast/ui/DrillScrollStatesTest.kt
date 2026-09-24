package app.stopcast.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class DrillScrollStatesTest {
    private val anyScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun `each view keeps its own position, so a station survives a platform opened from it`() {
        val states = DrillScrollStates()
        val station = states.stateFor("station")
        val platform = states.stateFor("platform")

        assertNotSame(station, platform)
        // Backing out of the platform hands the station its own state again, not a fresh one.
        assertSame(station, states.stateFor("station"))
    }

    @Test
    fun `positions survive a save and restore, and clearing starts views afresh`() {
        val states = DrillScrollStates(hashMapOf("station" to LazyListState(7, 30), "platform" to LazyListState(2, 0)))

        val restored = with(DrillScrollStates.Saver) { restore(anyScope.save(states)!!)!! }

        assertEquals(7, restored.stateFor("station").firstVisibleItemIndex)
        assertEquals(30, restored.stateFor("station").firstVisibleItemScrollOffset)
        assertEquals(2, restored.stateFor("platform").firstVisibleItemIndex)

        restored.clear()
        assertEquals(0, restored.stateFor("station").firstVisibleItemIndex)
    }
}
