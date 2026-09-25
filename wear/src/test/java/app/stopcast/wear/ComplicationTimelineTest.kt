package app.stopcast.wear

import app.stopcast.data.WatchEnvelope
import app.stopcast.data.WatchStarKey
import app.stopcast.data.toPersisted
import app.stopcast.domain.Departure
import app.stopcast.domain.LineRef
import app.stopcast.domain.RoutePattern
import app.stopcast.domain.RouteTopology
import app.stopcast.domain.Staleness
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import app.stopcast.domain.Terminating
import java.time.Instant
import kotlin.time.toJavaDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The complication's timeline over a fixed clock: synthetic stops only, no user data. */
class ComplicationTimelineTest {
    private val fetched: Instant = Instant.parse("2026-09-24T08:00:00Z")
    private val boundary: Instant = fetched.plus(Staleness.THRESHOLD.toJavaDuration())

    private fun departure(seconds: Long, line: String = "victoria", destination: String = "Brixton", mode: String = "tube") = Departure(
        lineId = line,
        lineName = line.replaceFirstChar { it.uppercase() },
        direction = "inbound",
        destination = destination,
        platform = null,
        expectedArrival = fetched.plusSeconds(seconds),
        mode = mode,
    )

    private fun stop(id: String, departures: List<Departure>, fresh: Boolean = true, line: String = "victoria") = StopArrivals(
        stopId = id,
        stopName = "Stop $id",
        departures = departures,
        fetchedAt = fetched,
        lines = listOf(LineRef(line, line.replaceFirstChar { it.uppercase() }, "tube")),
        arrivalsFresh = fresh,
    )

    private fun envelope(vararg stops: StopArrivals, starred: Set<StarredRow> = emptySet(), hidden: List<String> = emptyList()) =
        WatchEnvelope(stops = stops.map { it.toPersisted() }, starred = starred.map(WatchStarKey::of), hiddenModes = hidden)

    private fun List<ComplicationEntry>.at(t: Instant): ComplicationContent =
        single { it.start <= t && (it.end == null || t < it.end) }.content

    @Test
    fun `nothing to show is the no-data dash`() {
        val noData = listOf(ComplicationEntry(fetched, null, ComplicationContent.NoData))
        assertEquals("never synced", noData, ComplicationTimeline.entries(null, fetched))
        assertEquals("no stops", noData, ComplicationTimeline.entries(WatchEnvelope(), fetched))
        assertEquals("stops but no rows", noData, ComplicationTimeline.entries(envelope(stop("940GA", emptyList())), fetched))
    }

    @Test
    fun `each departure counts down until it leaves, then the next takes over`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(120), departure(240)))), fetched)
        val first = entries.at(fetched) as ComplicationContent.Departure
        assertEquals(fetched.plusSeconds(120), first.at)
        assertEquals("Brixton", first.destination)
        assertEquals(fetched.plusSeconds(240), (entries.at(fetched.plusSeconds(120)) as ComplicationContent.Departure).at)
    }

    @Test
    fun `a row that runs out shows its empty form until the boundary, then the stale form`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60)))), fetched)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = false), entries.at(fetched.plusSeconds(60)))
        assertEquals(ComplicationContent.Stale("VIC", "Victoria"), entries.at(boundary))
        assertEquals(null, entries.last().end)
        // No departure entry starts after the boundary, so a live countdown never overlaps the stale one.
        assertTrue(entries.filter { it.content is ComplicationContent.Departure }.all { it.end!! <= boundary })
    }

    @Test
    fun `a busy row counts down every departure in the window, never a false None`() {
        val busy = (1..50L).map { departure(it * 5) }
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", busy)), fetched)
        assertEquals(50, entries.count { it.content is ComplicationContent.Departure })
        // "None" only once the last of them has left.
        assertEquals(fetched.plusSeconds(250), entries.single { it.content is ComplicationContent.Empty }.start)
    }

    @Test
    fun `a departure past the boundary is never counted down`() {
        val late = boundary.epochSecond - fetched.epochSecond + 60
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60), departure(late)))), fetched)
        assertEquals(ComplicationContent.Stale("VIC", "Victoria"), entries.at(boundary.plusSeconds(1)))
        assertTrue(entries.none { (it.content as? ComplicationContent.Departure)?.at == fetched.plusSeconds(late) })
    }

    @Test
    fun `a carried-forward stop marks every time, and its empty form, uncertain`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(60)), fresh = false)), fetched)
        assertTrue((entries.at(fetched) as ComplicationContent.Departure).uncertain)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = true), entries.at(fetched.plusSeconds(60)))
    }

    @Test
    fun `an already stale stop shows only the stale form`() {
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(departure(3600)))), boundary.plusSeconds(5))
        assertEquals(listOf(ComplicationEntry(boundary.plusSeconds(5), null, ComplicationContent.Stale("VIC", "Victoria"))), entries)
    }

    @Test
    fun `the default row is the top starred row, else the widget's first`() {
        val a = stop("940GA", listOf(departure(60)))
        val b = stop("940GB", listOf(departure(300, line = "central", destination = "Ealing")), line = "central")
        assertEquals(StarredRow("940GA", "victoria", "inbound"), ComplicationTimeline.defaultRow(envelope(a, b), fetched))
        val star = StarredRow("940GB", "central", "inbound")
        assertEquals(star, ComplicationTimeline.defaultRow(envelope(a, b, starred = setOf(star)), fetched))
    }

    @Test
    fun `a hidden mode never feeds the complication`() {
        val bus = stop("940GA", listOf(departure(60, line = "73", destination = "Oxford Circus", mode = "bus")), line = "73")
        val tube = stop("940GB", listOf(departure(300, line = "central", destination = "Ealing")), line = "central")
        assertEquals("central", ComplicationTimeline.defaultRow(envelope(bus, tube, hidden = listOf("bus")), fetched)?.lineId)
    }

    @Test
    fun `a chosen row whose stop left the envelope falls back to the default row`() {
        val env = envelope(stop("940GA", listOf(departure(60))))
        val gone = StarredRow("940GGONE", "central", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, gone))
    }

    @Test
    fun `a star for a hidden mode or a line the stop no longer serves never becomes the row`() {
        val hiddenStar = StarredRow("940GA", "73", "inbound")
        val bus = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = emptyList(),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus")),
        )
        assertEquals(null, ComplicationTimeline.defaultRow(envelope(bus, starred = setOf(hiddenStar), hidden = listOf("bus")), fetched))
        val goneLine = StarredRow("940GA", "central", "inbound")
        assertEquals(null, ComplicationTimeline.defaultRow(envelope(stop("940GA", emptyList()), starred = setOf(goneLine)), fetched))
    }

    @Test
    fun `a departure with no destination reads as a dash, never blank`() {
        // No destination and no direction or platform to fall back on.
        val blank = departure(60, destination = "").copy(direction = "")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(blank))), fetched)
        assertEquals("—", (entries.at(fetched) as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a via-branch that's a choice ahead joins the destination, as on the tile`() {
        // Two trunks from this stop (940GA) to Morden: the branch is a choice ahead of it.
        val topology = RouteTopology(
            mapOf(
                "northern" to listOf(
                    RoutePattern("Bank", listOf("940GNORTH", "940GA", "940GBANK", "940GMORDEN"), "North", "Morden"),
                    RoutePattern("Charing X", listOf("940GNORTH", "940GA", "940GCHX", "940GMORDEN"), "North", "Morden"),
                ),
            ),
        )
        val via = departure(60, line = "northern", destination = "Morden").copy(branch = "Bank")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", listOf(via), line = "northern")), fetched, topology = topology)
        assertEquals("Morden/Bank", (entries.at(fetched) as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a starred row with no departures shows its empty form rather than no data`() {
        val star = StarredRow("940GA", "victoria", "inbound")
        val entries = ComplicationTimeline.entries(envelope(stop("940GA", emptyList()), starred = setOf(star)), fetched)
        assertEquals(ComplicationContent.Empty("VIC", "Victoria", uncertain = false), entries.at(fetched))
    }

    @Test
    fun `a pick whose mode is hidden gives way to the default row`() {
        val bus = departure(60, line = "73", destination = "Oxford Circus", mode = "bus")
        val tube = departure(120)
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(bus, tube),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
        )
        val env = envelope(stop, hidden = listOf("bus"))
        val pick = StarredRow("940GA", "73", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, pick))
        assertEquals("Victoria", (ComplicationTimeline.entries(env, fetched, pick).first().content as ComplicationContent.Departure).lineName)
        // Shown again once the mode is.
        val shown = ComplicationTimeline.entries(env.copy(hiddenModes = emptyList()), fetched, pick).first().content
        assertEquals("Oxford Circus", (shown as ComplicationContent.Departure).destination)
    }

    @Test
    fun `a pick the widget's terminating filter removes gives way to the default row`() {
        // Every 73 here terminates at a stop no farther from the rider: the widget drops them.
        val ending = departure(60, line = "73", destination = "Stop 940GNEAR", mode = "bus").copy(destinationId = "940GNEAR")
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(ending, departure(120)),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
            nearer = Terminating.Nearer(ids = setOf("940GNEAR")),
        )
        val env = envelope(stop)
        val pick = StarredRow("940GA", "73", "inbound")
        assertEquals(ComplicationTimeline.entries(env, fetched), ComplicationTimeline.entries(env, fetched, pick))
    }

    @Test
    fun `a pick is judged by its own direction's services, not the other direction's`() {
        // Outbound 73s all terminate nearer the rider; inbound ones don't. An outbound pick goes.
        val outbound = departure(60, line = "73", destination = "Stop 940GNEAR", mode = "bus")
            .copy(direction = "outbound", destinationId = "940GNEAR")
        val inbound = departure(90, line = "73", destination = "Oxford Circus", mode = "bus")
        val stop = StopArrivals(
            stopId = "940GA",
            stopName = "Stop 940GA",
            departures = listOf(outbound, inbound, departure(120)),
            fetchedAt = fetched,
            lines = listOf(LineRef("73", "73", "bus"), LineRef("victoria", "Victoria", "tube")),
            nearer = Terminating.Nearer(ids = setOf("940GNEAR")),
        )
        val env = envelope(stop)
        assertEquals(false, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "outbound"), fetched))
        assertEquals(true, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "inbound"), fetched))
        // Still filtered once its only (terminating) service has left, not resurrected as empty.
        assertEquals(false, ComplicationTimeline.shows(env, StarredRow("940GA", "73", "outbound"), fetched.plusSeconds(200)))
    }
}
