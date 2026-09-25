package app.stopdash.ui

import app.stopdash.R
import app.stopdash.domain.NearbySelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The "More" buttons are keyed by mode, so two revealable modes that fall through to the same
 * generic "More stops" label render as indistinguishable buttons that reveal different clusters
 * (Codex, PR #87). Every mode the nearby search can actually return — the modes served at
 * `StopFinder.DEFAULT_NEARBY_STOP_TYPES` stops — must therefore map to its own label.
 */
class MoreLabelTest {
    // The mode ids reachable from the nearby stop-type set (NaptanMetroStation / RailStation /
    // PublicBusCoachTram / FerryPort). national-rail, coach and river-bus are the three that used
    // to collide on the generic label.
    private val reachableModes = listOf(
        "bus", "tube", "dlr", "overground", "elizabeth-line", "tram",
        "national-rail", "coach", "river-bus",
    )

    @Test
    fun `every reachable mode has its own label, distinct from the generic one`() {
        val labels = reachableModes.associateWith { moreLabelRes(it) }
        for (mode in reachableModes) {
            assertNotEquals(
                "mode '$mode' falls through to the generic \"More stops\" label",
                R.string.more_stops,
                labels.getValue(mode),
            )
        }
        assertEquals(
            "two reachable modes share a label and become indistinguishable buttons",
            reachableModes.size,
            labels.values.toSet().size,
        )
    }

    @Test
    fun `the modeless bucket keeps the generic label`() {
        assertEquals(R.string.more_stops, moreLabelRes(NearbySelection.GENERIC_MORE))
    }
}
