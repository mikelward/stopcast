package app.stopcast.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-tier near-me cluster selection (SPEC *Finding stops → Near me now*): stops group into
 * clusters (a station's platforms, a junction's poles), the nearest [NearbySelection.CLUSTERS_PER_MODE]
 * clusters of each mode are eager (fetched at once), the rest wait behind a per-mode "More".
 * Coordinates are obviously-synthetic offsets from the origin (SPEC *Privacy*).
 */
class NearbyClustersTest {
    // A stop [meters] due north of the origin (~111,320 m per degree of latitude) serving one
    // line of [mode], optionally in [cluster] (blank = its own cluster).
    private fun stop(id: String, meters: Double, mode: String, cluster: String = "") = StopLocation(
        id = id,
        name = id,
        latitude = meters / 111_320.0,
        longitude = 0.0,
        lines = listOf(LineRef("$mode-$id", id, mode)),
        clusterId = cluster,
    )

    private fun select(stops: List<StopLocation>, clustersPerMode: Int = NearbySelection.CLUSTERS_PER_MODE) =
        NearbySelection.selectClusters(stops, latitude = 0.0, longitude = 0.0, clustersPerMode = clustersPerMode)

    // A tier as lists of the stop ids in each cluster, in cluster order.
    private fun List<NearbySelection.NearbyCluster>.ids() = map { c -> c.stops.map { it.id } }

    @Test
    fun `no stops yields empty tiers`() {
        val result = select(emptyList())
        assertEquals(emptyList<List<String>>(), result.eager.ids())
        assertEquals(emptyList<List<String>>(), result.more.ids())
    }

    @Test
    fun `a stop beyond the outer ring is dropped from both tiers`() {
        val result = select(listOf(stop("near", 100.0, "bus"), stop("far", 2000.0, "bus")))
        assertEquals(listOf(listOf("near")), result.eager.ids())
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun `poles sharing a cluster form one cluster, nearest member first`() {
        // A junction's two poles are distinct stop ids sharing a stationNaptan — one cluster,
        // its stops ordered nearest-first, ranked by the nearer pole.
        val result = select(
            listOf(
                stop("A", 100.0, "bus", "490G0TPL"),
                stop("B", 60.0, "bus", "490G0TPL"),
            ),
        )
        assertEquals(listOf(listOf("B", "A")), result.eager.ids())
        assertEquals(60.0, result.eager.single().distanceMeters, 0.5)
    }

    @Test
    fun `two same-named stops in different clusters stay separate clusters`() {
        // The whole point of keying on the cluster, not the name: adjacent stations TfL spells
        // alike but gives different stationNaptans are two places, not one.
        val result = select(
            listOf(
                stop("k1", 100.0, "tube", "940GZZLUKSX"),
                stop("s1", 120.0, "tube", "490G000760"),
            ),
        )
        assertEquals(listOf(listOf("k1"), listOf("s1")), result.eager.ids())
    }

    @Test
    fun `a blank cluster id keeps each stop its own cluster`() {
        val result = select(listOf(stop("x", 100.0, "bus"), stop("y", 120.0, "bus")))
        assertEquals(listOf(listOf("x"), listOf("y")), result.eager.ids())
    }

    @Test
    fun `only the nearest two clusters of a mode are eager, the rest go to more`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0C1"),
                stop("b2", 120.0, "bus", "490G0C2"),
                stop("b3", 300.0, "bus", "490G0C3"),
                stop("t1", 700.0, "tube", "940GZZLUKSX"),
            ),
        )
        // Nearest two bus clusters are eager; the far lone tube is eager as its mode's nearest.
        assertEquals(listOf(listOf("b1"), listOf("b2"), listOf("t1")), result.eager.ids())
        // The third bus cluster waits behind "More".
        assertEquals(listOf(listOf("b3")), result.more.ids())
    }

    @Test
    fun `the nearest station of a sparse mode is always eager, however far`() {
        // Highgate at 0.8 mi: the only tube, so rank 1 of its mode — never crowded out by nearer
        // buses (maintainer's worked example).
        val result = select(
            listOf(
                stop("bus1", 40.0, "bus", "490G0C1"),
                stop("bus2", 90.0, "bus", "490G0C2"),
                stop("bus3", 150.0, "bus", "490G0C3"),
                stop("highgate", 1287.0, "tube", "940GZZLUHGT"), // ~0.8 mi
            ),
        )
        assertTrue("the far lone tube is eager", result.eager.ids().contains(listOf("highgate")))
        assertEquals(listOf(listOf("bus3")), result.more.ids())
    }

    @Test
    fun `an interchange cluster counts toward every mode it serves`() {
        // A hub serving tube + bus is eager via tube (its only one) and also counts as a top-2
        // bus cluster, so it isn't double-listed and the tube is never pushed to "More".
        val hub = StopLocation(
            id = "hub", name = "hub", latitude = 40.0 / 111_320.0, longitude = 0.0,
            lines = listOf(LineRef("victoria", "Victoria", "tube"), LineRef("55", "55", "bus")),
            clusterId = "940GZZLUOXC",
        )
        val result = select(
            listOf(
                hub,
                stop("busA", 50.0, "bus", "490G0A"),
                stop("busB", 120.0, "bus", "490G0B"),
                stop("busC", 300.0, "bus", "490G0C"),
            ),
        )
        assertEquals(listOf(listOf("hub"), listOf("busA")), result.eager.ids())
        assertEquals(listOf(listOf("busB"), listOf("busC")), result.more.ids())
        // No tube left in "more" — the hub covered it.
        assertTrue(result.more.none { "tube" in it.modes })
    }

    @Test
    fun `more is globally ordered and pages per mode`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0B1"),
                stop("b2", 120.0, "bus", "490G0B2"),
                stop("b3", 400.0, "bus", "490G0B3"),
                stop("u1", 300.0, "tube", "940GZZLU1"),
                stop("u2", 600.0, "tube", "940GZZLU2"),
                stop("u3", 900.0, "tube", "940GZZLU3"),
            ),
        )
        // Eager: nearest two of each mode, kept in global distance order.
        assertEquals(listOf(listOf("b1"), listOf("b2"), listOf("u1"), listOf("u2")), result.eager.ids())
        // More stays globally ordered; the "More bus"/"More tube" buttons filter it by mode.
        assertEquals(listOf(listOf("b3"), listOf("u3")), result.more.ids())
        assertEquals(listOf(listOf("b3")), result.more.filter { "bus" in it.modes }.ids())
        assertEquals(listOf(listOf("u3")), result.more.filter { "tube" in it.modes }.ids())
    }

    @Test
    fun `a stop with no declared mode is still selected, not dropped`() {
        // A stop TfL lists no lines for has no mode; it must not fall out of every mode's top-N.
        val noLines = StopLocation("s", "S", latitude = 100.0 / 111_320.0, longitude = 0.0)
        val result = select(listOf(noLines))
        assertEquals(listOf(listOf("s")), result.eager.ids())
        assertTrue(result.more.isEmpty())
    }

    @Test
    fun `modeless clusters beyond the cap go to more with no mode, for a generic More`() {
        // Three stops with no declared mode: the sentinel bucket keeps the nearest two eager and
        // sends the third to "more" — where it carries an empty modes set, so the UI must surface
        // it under a generic "More" rather than dropping it (the caller buckets empty modes).
        val noLines = { id: String, meters: Double -> StopLocation(id, id, meters / 111_320.0, 0.0) }
        val result = select(listOf(noLines("a", 50.0), noLines("b", 120.0), noLines("c", 300.0)))
        assertEquals(listOf(listOf("a"), listOf("b")), result.eager.ids())
        assertEquals(listOf(listOf("c")), result.more.ids())
        assertTrue("the overflow cluster has no declared mode", result.more.single().modes.isEmpty())
    }

    @Test
    fun `a tighter per-mode cap pushes more clusters behind More`() {
        val result = select(
            listOf(
                stop("b1", 50.0, "bus", "490G0B1"),
                stop("b2", 120.0, "bus", "490G0B2"),
            ),
            clustersPerMode = 1,
        )
        assertEquals(listOf(listOf("b1")), result.eager.ids())
        assertEquals(listOf(listOf("b2")), result.more.ids())
    }

    // --- The "More" reveal paging (NearbySelection.nextReveal / revealableBuckets) ---

    // A single-stop cluster [meters] out serving [modes] (none = a modeless cluster).
    private fun cluster(key: String, meters: Double, vararg modes: String) =
        NearbySelection.NearbyCluster(
            key = key,
            stops = listOf(
                StopLocation(
                    id = key, name = key, latitude = meters / 111_320.0, longitude = 0.0,
                    lines = modes.map { LineRef("$it-$key", key, it) },
                ),
            ),
            distanceMeters = meters,
        )

    @Test
    fun `nextReveal pages a bucket's clusters nearest-first, skipping revealed`() {
        val more = listOf(cluster("C3", 300.0, "bus"), cluster("C4", 400.0, "bus"), cluster("C5", 500.0, "bus"))
        // One tap reveals the nearest two, the next tap the last, then nothing is left.
        assertEquals(listOf("C3", "C4"), NearbySelection.nextReveal(more, "bus", emptySet()))
        assertEquals(listOf("C5"), NearbySelection.nextReveal(more, "bus", setOf("C3", "C4")))
        assertEquals(emptyList<String>(), NearbySelection.nextReveal(more, "bus", setOf("C3", "C4", "C5")))
    }

    @Test
    fun `a two-mode cluster is revealable under either mode`() {
        val more = listOf(cluster("HUB", 500.0, "tube", "bus"), cluster("CX", 600.0, "bus"))
        assertEquals(listOf("HUB"), NearbySelection.nextReveal(more, "tube", emptySet()))
        assertEquals(listOf("HUB", "CX"), NearbySelection.nextReveal(more, "bus", emptySet()))
    }

    @Test
    fun `a modeless cluster reveals under the generic bucket, not a real mode`() {
        val more = listOf(cluster("M1", 300.0))
        assertEquals(setOf(NearbySelection.GENERIC_MORE), NearbySelection.revealableBuckets(more, emptySet()))
        assertEquals(listOf("M1"), NearbySelection.nextReveal(more, NearbySelection.GENERIC_MORE, emptySet()))
        assertEquals(emptyList<String>(), NearbySelection.nextReveal(more, "bus", emptySet()))
    }

    // A single-stop bus cluster [meters] out serving the given [lineIds] (a route can repeat across
    // clusters, unlike `cluster()` which ties the line id to the key), so redundancy is controllable.
    private fun busCluster(key: String, meters: Double, vararg lineIds: String) =
        NearbySelection.NearbyCluster(
            key = key,
            stops = listOf(
                StopLocation(
                    id = key, name = key, latitude = meters / 111_320.0, longitude = 0.0,
                    lines = lineIds.map { LineRef(it, it, "bus") },
                ),
            ),
            distanceMeters = meters,
        )

    @Test
    fun `a tap pages through a redundant run to the first cluster with a new route`() {
        // The reported bug: the nearer clusters only repeat routes already shown (the near-me list
        // collapses a route to its nearest stop), so paging the next two surfaces nothing. One tap
        // must reach through them to the first farther cluster carrying a new route.
        val more = listOf(
            busCluster("R1", 300.0, "L1"), // repeats a shown route
            busCluster("R2", 400.0, "L2"), // repeats a shown route
            busCluster("N", 500.0, "L9"), // the first genuinely new route
        )
        assertEquals(
            listOf("R1", "R2", "N"),
            NearbySelection.nextReveal(more, "bus", emptySet(), shownLineIds = setOf("L1", "L2")),
        )
    }

    @Test
    fun `when nothing remaining adds a new route, a tap pages a bounded few not the whole tail`() {
        val more = listOf(
            busCluster("R1", 300.0, "L1"),
            busCluster("R2", 400.0, "L2"),
            busCluster("R3", 500.0, "L1"),
        )
        // Every remaining cluster only repeats a shown route: fall back to the bounded page (2),
        // rather than revealing the entire redundant tail in one fetch burst.
        assertEquals(
            listOf("R1", "R2"),
            NearbySelection.nextReveal(more, "bus", emptySet(), shownLineIds = setOf("L1", "L2")),
        )
    }

    @Test
    fun `a tap reveals at most the per-tap cap even when the new route is farther`() {
        // A long redundant run before the new route must not fan out an unbounded fetch burst: the
        // tap stops at the cap and the next tap continues (Codex P1, PR #98).
        val redundant = (1..NearbySelection.MAX_REVEAL_PER_TAP + 2).map { busCluster("R$it", it * 10.0, "L1") }
        val more = redundant + busCluster("N", 5000.0, "L9")
        val revealed = NearbySelection.nextReveal(more, "bus", emptySet(), shownLineIds = setOf("L1"))
        assertEquals(NearbySelection.MAX_REVEAL_PER_TAP, revealed.size)
        assertEquals(redundant.take(NearbySelection.MAX_REVEAL_PER_TAP).map { it.key }, revealed)
    }

    @Test
    fun `a new route within the first page still reveals a whole page, not just one`() {
        val more = listOf(busCluster("N1", 300.0, "L8"), busCluster("N2", 400.0, "L9"))
        // The nearest cluster already adds a new route, so this is the ordinary page: reveal both,
        // matching the pre-fix page-at-a-time burst rather than shrinking to one cluster per tap.
        assertEquals(
            listOf("N1", "N2"),
            NearbySelection.nextReveal(more, "bus", emptySet(), shownLineIds = setOf("L1")),
        )
    }

    @Test
    fun `revealableBuckets lists only buckets with an unrevealed cluster`() {
        val more = listOf(cluster("C3", 300.0, "bus"), cluster("U3", 600.0, "tube"))
        assertEquals(setOf("bus", "tube"), NearbySelection.revealableBuckets(more, emptySet()))
        assertEquals(setOf("tube"), NearbySelection.revealableBuckets(more, setOf("C3")))
        assertEquals(emptySet<String>(), NearbySelection.revealableBuckets(more, setOf("C3", "U3")))
    }
}
