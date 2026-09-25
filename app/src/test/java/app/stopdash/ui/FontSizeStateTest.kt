package app.stopdash.ui

import app.stopdash.domain.FontSizeSettings
import app.stopdash.domain.MAX_FONT_SCALE
import app.stopdash.domain.MIN_FONT_SCALE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FontSizeState]'s move-it-live / persist-on-release rules and how a value arriving from the store
 * mid-gesture is held (SPEC *Display size*). Pure over Compose snapshot state — no host, no device.
 */
class FontSizeStateTest {

    private val settledScales = mutableListOf<Float>()
    private val pinchWrites = mutableListOf<Boolean>()

    private fun state(initial: FontSizeSettings = FontSizeSettings()) =
        FontSizeState(
            initial = initial,
            onScaleSettled = { settledScales += it },
            onPinchEnabledChange = { pinchWrites += it },
        )

    @Test
    fun `starts at the clamped initial value`() {
        val s = state(FontSizeSettings(scale = 9f, pinchEnabled = false))
        assertEquals(MAX_FONT_SCALE, s.scale, 0f)
        assertFalse(s.pinchEnabled)
    }

    @Test
    fun `preview shows the size without persisting it`() {
        val s = state()
        s.startGesture()
        s.preview(1.3f)
        assertEquals(1.3f, s.scale, 0f)
        assertTrue("preview must not write", settledScales.isEmpty())
    }

    @Test
    fun `preview clamps to the offered range`() {
        val s = state()
        s.startGesture()
        s.preview(9f)
        assertEquals(MAX_FONT_SCALE, s.scale, 0f)
        s.preview(0f)
        assertEquals(MIN_FONT_SCALE, s.scale, 0f)
    }

    @Test
    fun `commit after a preview persists the settled size once`() {
        val s = state()
        s.startGesture()
        s.preview(1.3f)
        s.commit(1.3f)
        assertEquals(1.3f, s.scale, 0f)
        assertEquals(listOf(1.3f), settledScales)
    }

    @Test
    fun `an empty gesture persists nothing and keeps the size`() {
        // Two fingers down and up with no spread past the slop: nothing moved, so nothing is written.
        val s = state(FontSizeSettings(scale = 1.1f))
        s.startGesture()
        s.commit(1.1f)
        assertEquals(1.1f, s.scale, 0f)
        assertTrue("an empty gesture must not write", settledScales.isEmpty())
    }

    @Test
    fun `a store value arriving mid-gesture is held, not applied under the fingers`() {
        val s = state(FontSizeSettings(scale = 1.0f, pinchEnabled = true))
        s.startGesture()
        s.preview(1.2f)
        // The warm read (or a prior commit's write) lands while the fingers are still down.
        s.onPersisted(FontSizeSettings(scale = 1.5f, pinchEnabled = false))
        // The text does not snap away from the fingers.
        assertEquals(1.2f, s.scale, 0f)
    }

    @Test
    fun `on release the gesture's size wins but a held pinch setting still lands`() {
        val s = state(FontSizeSettings(scale = 1.0f, pinchEnabled = true))
        s.startGesture()
        s.preview(1.2f)
        s.onPersisted(FontSizeSettings(scale = 1.5f, pinchEnabled = false))
        s.commit(1.2f)
        // The user's own gesture value wins over the deferred size…
        assertEquals(1.2f, s.scale, 0f)
        assertEquals(listOf(1.2f), settledScales)
        // …but the deferred pinch switch (not what the gesture was about) is applied.
        assertFalse(s.pinchEnabled)
    }

    @Test
    fun `a deferred value lands whole when the gesture moved nothing`() {
        val s = state(FontSizeSettings(scale = 1.0f, pinchEnabled = true))
        s.startGesture()
        s.onPersisted(FontSizeSettings(scale = 1.5f, pinchEnabled = false))
        s.commit(1.0f) // nothing was previewed
        assertEquals(1.5f, s.scale, 0f)
        assertFalse(s.pinchEnabled)
        assertTrue("an empty gesture must not write", settledScales.isEmpty())
    }

    @Test
    fun `onPersisted applies at once when no gesture is in flight`() {
        val s = state()
        s.onPersisted(FontSizeSettings(scale = 1.4f, pinchEnabled = false))
        assertEquals(1.4f, s.scale, 0f)
        assertFalse(s.pinchEnabled)
    }

    @Test
    fun `choosePinch flips the switch and persists it`() {
        val s = state(FontSizeSettings(pinchEnabled = true))
        s.choosePinch(false)
        assertFalse(s.pinchEnabled)
        assertEquals(listOf(false), pinchWrites)
    }

    @Test
    fun `chooseScale (reset) shows and persists a discrete size`() {
        val s = state(FontSizeSettings(scale = 1.4f))
        s.chooseScale(1.0f)
        assertEquals(1.0f, s.scale, 0f)
        assertEquals(listOf(1.0f), settledScales)
    }

    @Test
    fun `a second gesture after a committed one persists again`() {
        // `resized` must reset on commit, or a later real gesture would silently not write.
        val s = state()
        s.startGesture(); s.preview(1.3f); s.commit(1.3f)
        s.startGesture(); s.preview(1.1f); s.commit(1.1f)
        assertEquals(listOf(1.3f, 1.1f), settledScales)
    }

    @Test
    fun `nothing is left deferred after a commit`() {
        val s = state()
        s.startGesture()
        s.onPersisted(FontSizeSettings(scale = 1.5f))
        s.preview(1.2f)
        s.commit(1.2f)
        // A subsequent non-moving persist applies directly (the earlier deferred value was cleared,
        // not re-applied on the next commit).
        s.onPersisted(FontSizeSettings(scale = 1.0f, pinchEnabled = true))
        assertEquals(1.0f, s.scale, 0f)
        assertTrue(s.pinchEnabled)
    }
}
