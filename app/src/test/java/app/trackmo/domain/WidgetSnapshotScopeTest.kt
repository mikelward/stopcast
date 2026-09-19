package app.trackmo.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSnapshotScopeTest {

    @Test
    fun `clears when the snapshot holds a stop outside the resolved set`() {
        // The user moved: the snapshot holds another area's stops, none in the resolved set.
        // Clear, so the nameless widget doesn't show the old place's trains as live.
        assertTrue(shouldClearWidgetSnapshot(setOf("490A", "490B"), setOf("490C")))
        // Even one stop outside the current area is enough — a partly-overlapping older set.
        assertTrue(shouldClearWidgetSnapshot(setOf("490A", "490X"), setOf("490A", "490B")))
    }

    @Test
    fun `keeps a snapshot that is a subset of the resolved set`() {
        // A multi-stop resolution where only some stops' arrivals came back the first time
        // persists just the fetched ones — still this area's last-good, so don't blank it.
        assertFalse(shouldClearWidgetSnapshot(setOf("490A"), setOf("490A", "490B")))
    }

    @Test
    fun `clears when the location resolves to no stops`() {
        // An empty resolution never mounts the departures view, so nothing else clears it.
        assertTrue(shouldClearWidgetSnapshot(setOf("490A"), emptySet()))
    }

    @Test
    fun `does not clear when the set is unchanged`() {
        // Returning to the same set (a re-resolve, a refresh) must not blank the widget —
        // order and any distance differences don't matter, only the set of stop ids.
        assertFalse(shouldClearWidgetSnapshot(setOf("490A", "490B"), setOf("490B", "490A")))
    }

    @Test
    fun `does not clear when nothing is persisted`() {
        // No last-good to strand: a fresh install, or already cleared. The resolved set's own
        // fetch will populate it.
        assertFalse(shouldClearWidgetSnapshot(emptySet(), setOf("490A")))
        assertFalse(shouldClearWidgetSnapshot(emptySet(), emptySet()))
    }
}
