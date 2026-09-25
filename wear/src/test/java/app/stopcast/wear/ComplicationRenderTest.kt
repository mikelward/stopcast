package app.stopcast.wear

import androidx.test.core.app.ApplicationProvider
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import android.content.Context
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The complication's data as the watch face gets it: synthetic rows only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ComplicationRenderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val t0: Instant = Instant.parse("2026-09-24T08:00:00Z")
    private val departure = ComplicationContent.Departure("VIC", "Victoria", "Brixton", t0.plusSeconds(180), uncertain = false)

    @Test
    fun `a departure is the line's code over its countdown, short or long`() {
        val short = ComplicationRender.data(context, ComplicationType.SHORT_TEXT, departure) as ShortTextComplicationData
        assertEquals("VIC", short.title!!.getTextAt(context.resources, t0).toString())
        assertEquals("3m", short.text.getTextAt(context.resources, t0).toString())
        val long = ComplicationRender.data(context, ComplicationType.LONG_TEXT, departure) as LongTextComplicationData
        assertEquals("Victoria to Brixton 3m", long.text.getTextAt(context.resources, t0).toString())
    }

    @Test
    fun `a carried-forward departure marks its countdown`() {
        val short = ComplicationRender.data(context, ComplicationType.SHORT_TEXT, departure.copy(uncertain = true)) as ShortTextComplicationData
        assertEquals("~3m", short.text.getTextAt(context.resources, t0).toString())
    }

    @Test
    fun `the empty and stale forms carry no time`() {
        val empty = ComplicationRender.data(context, ComplicationType.SHORT_TEXT, ComplicationContent.Empty("VIC", "Victoria", false))
        assertEquals("None", (empty as ShortTextComplicationData).text.getTextAt(context.resources, t0).toString())
        val stale = ComplicationRender.data(context, ComplicationType.LONG_TEXT, ComplicationContent.Stale("VIC", "Victoria"))
        assertEquals("Victoria: out of date", (stale as LongTextComplicationData).text.getTextAt(context.resources, t0).toString())
    }

    @Test
    fun `nothing to show is no data, and an unoffered type is nothing`() {
        assertTrue(ComplicationRender.data(context, ComplicationType.SHORT_TEXT, ComplicationContent.NoData) is NoDataComplicationData)
        assertNull(ComplicationRender.data(context, ComplicationType.RANGED_VALUE, departure))
    }

    @Test
    fun `the timeline's open-ended last entry is its default`() {
        val entries = listOf(
            ComplicationEntry(t0, t0.plusSeconds(180), departure),
            ComplicationEntry(t0.plusSeconds(180), t0.plusSeconds(300), ComplicationContent.Empty("VIC", "Victoria", false)),
            ComplicationEntry(t0.plusSeconds(300), null, ComplicationContent.Stale("VIC", "Victoria")),
        )
        val timeline = ComplicationRender.timeline(context, ComplicationType.SHORT_TEXT, entries)!!
        assertEquals(2, timeline.timelineEntries.size)
        val default = timeline.defaultComplicationData as ShortTextComplicationData
        assertEquals("?", default.text.getTextAt(context.resources, t0).toString())
    }
}
