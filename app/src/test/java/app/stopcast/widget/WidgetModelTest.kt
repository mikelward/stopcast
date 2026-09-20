package app.stopcast.widget

import app.stopcast.domain.Departure
import app.stopcast.domain.DepartureRows
import app.stopcast.domain.DeparturesSnapshot
import app.stopcast.domain.StarredRow
import app.stopcast.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's render decision is derived purely from the snapshot and the clock, so it is
 * exercised here without a Glance host. Stops use `490…` example ids and canned line names
 * (SPEC *Privacy* — never a real watched-stop set).
 */
class WidgetModelTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(lineId: String, offsetSeconds: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private fun stop(id: String, departures: List<Departure>, fetchedAt: Instant) = StopArrivals(
        id,
        "Example Stop $id",
        departures,
        fetchedAt,
        disruptions = emptyList(),
    )

    @Test
    fun `a null snapshot is the empty state`() {
        val model = widgetModel(snapshot = null, now = now)
        assertFalse(model.hasData)
        assertFalse(model.stale)
        assertNull(model.stamp)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a snapshot with no stops is the empty state`() {
        val model = widgetModel(DeparturesSnapshot(stops = emptyList(), fetchedAt = now), now)
        assertFalse(model.hasData)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a fresh snapshot has data, a stamp, and is not stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertFalse(model.stale)
        assertEquals("Updated just now", model.stamp)
        assertEquals(listOf("victoria"), model.rows.map { it.row.lineId })
    }

    @Test
    fun `a snapshot older than the staleness threshold is marked stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertTrue(model.stale)
    }

    @Test
    fun `an empty list with an unrefreshed stop is uncertain, not a trustworthy none`() {
        // Fresh overall (recent snapshot stamp) but one stop carried arrivalsFresh=false from a
        // partial refresh and has no departures — the empty list can't be trusted as a real "no
        // departures", so the widget must say "may be out of date", not "No upcoming departures".
        val unrefreshed = StopArrivals(
            "490000001A",
            "Example Stop",
            emptyList(),
            now,
            disruptions = emptyList(),
            arrivalsFresh = false,
        )
        val model = widgetModel(DeparturesSnapshot(stops = listOf(unrefreshed), fetchedAt = now), now)
        assertTrue(model.hasData)
        assertFalse("the snapshot is fresh overall", model.stale)
        assertTrue("but an unrefreshed stop makes the empty list uncertain", model.uncertain)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `fresh rows survive the cap ahead of stale ones`() {
        // A partial refresh: six soon departures from a stale stop (its refresh failed, so its
        // rows will be `?`-withheld) plus one later, trustworthy departure from a fresh stop.
        // Soonest-first, the six stale rows precede the fresh one, so a plain take(6) would
        // drop the only live row and the widget would show six `?`. Fresh must be ranked first.
        val staleStop = stop(
            "490000001A",
            (1..6).map { departure("stale$it", it * 60L) },
            fetchedAt = now.minusSeconds(600),
        )
        val freshStop = stop(
            "490000002B",
            listOf(departure("freshline", 900L)),
            fetchedAt = now.minusSeconds(30),
        )
        val snapshot = DeparturesSnapshot(stops = listOf(staleStop, freshStop), fetchedAt = now.minusSeconds(30))
        val model = widgetModel(snapshot, now, maxLines = 6)
        assertEquals(6, model.rows.size)
        assertTrue("the fresh stop's row must survive the cap", model.rows.any { it.row.lineId == "freshline" })
    }

    @Test
    fun `a starred row is pinned to the top past the cap`() {
        // Six soon departures plus a later, starred one. A plain take(6) would drop the starred
        // row (it's the latest), but SPEC D8 requires a starred service at the top — so the
        // widget must load the stars and pin it before the cap, exactly as the in-app list does.
        val soon = stop("490000001A", (1..6).map { departure("line$it", it * 60L) }, now)
        val starredLater = stop("490000002B", listOf(departure("starredline", 900L)), now)
        val snapshot = DeparturesSnapshot(stops = listOf(soon, starredLater), fetchedAt = now)
        val starred = setOf(StarredRow("490000002B", "starredline", "inbound"))
        val model = widgetModel(snapshot, now, starred = starred, maxLines = 6)
        assertEquals(6, model.rows.size)
        assertEquals("the starred service is pinned to the top", "starredline", model.rows.first().row.lineId)
    }

    @Test
    fun `rows are capped so a long list can't overflow the cell`() {
        // Ten distinct lines at one stop; the cap keeps the widget within its cell. Each is a
        // single-destination row (one rendered line), so the line budget maps 1:1 to rows here.
        val departures = (1..10).map { departure("line$it", it * 60L) }
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", departures, now)),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now, maxLines = 6)
        assertEquals(6, model.rows.size)
    }

    @Test
    fun `a branching row's destination lines count toward the widget's line budget`() {
        // The Northern-style case: one (line, direction) row branches to two destinations, so it
        // renders two lines. With a plain row-count cap it would count as one and a multi-branch
        // service could push the widget past its height; the budget counts rendered lines, so the
        // branching row (2 lines) plus one single-line row fills a budget of 3 and the third row
        // is dropped — the widget stays bounded instead of overflowing (SPEC D4).
        fun toward(lineId: String, dest: String, offset: Long) = Departure(
            lineId = lineId,
            lineName = lineId,
            direction = "inbound",
            destination = dest,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "tube",
        )
        val branching = stop(
            "490000001A",
            listOf(toward("northern", "Morden", 60L), toward("northern", "Battersea Power Station", 120L)),
            now,
        )
        val single1 = stop("490000002B", listOf(departure("victoria", 180L)), now)
        val single2 = stop("490000003C", listOf(departure("central", 240L)), now)
        val snapshot = DeparturesSnapshot(stops = listOf(branching, single1, single2), fetchedAt = now)

        val model = widgetModel(snapshot, now, maxLines = 3)

        val renderedLines = model.rows.sumOf { it.groups.size }
        assertTrue("total rendered lines stay within the budget", renderedLines <= 3)
        assertTrue("the branching service is kept", model.rows.any { it.row.lineId == "northern" })
        assertFalse("the row past the budget is dropped", model.rows.any { it.row.lineId == "central" })
    }

    @Test
    fun `an oversized first branching row is itself bounded to the line budget`() {
        // A single (line, direction) that branches to more destinations than the budget: it must
        // show only as many lines as fit, not expand past the fixed-height cell (SPEC D4). This
        // guards the first-row case the whole-row budget would otherwise admit unbounded.
        fun toward(dest: String, offset: Long) = Departure(
            lineId = "northern",
            lineName = "northern",
            direction = "inbound",
            destination = dest,
            platform = null,
            expectedArrival = now.plusSeconds(offset),
            mode = "tube",
        )
        val many = (1..8).map { toward("Terminus $it", it * 60L) }
        val snapshot = DeparturesSnapshot(stops = listOf(stop("490000001A", many, now)), fetchedAt = now)

        val model = widgetModel(snapshot, now, maxLines = 6)

        assertEquals("only the one service, bounded", 1, model.rows.size)
        assertEquals("its lines are capped to the budget", 6, model.rows.first().groups.size)
    }
}
