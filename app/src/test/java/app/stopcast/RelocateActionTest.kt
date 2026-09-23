package app.stopcast

import app.stopcast.domain.Coordinates
import app.stopcast.ui.NearbyStopsViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The re-locate action wiring (SPEC *Finding stops*): a refresh and a return to the foreground
 * both run [relocateAction], which must cancel the in-flight departures fetch, then force a fresh
 * fix that re-resolves the nearby set and reconciles it — not a departures-only refresh. This pins
 * that composition, so a regression back to a location-free refresh (the "reopen shows the old
 * stops" bug this branch fixes) is caught off a device.
 */
class RelocateActionTest {
    @Test
    fun `cancels the in-flight fetch, then re-locates and reconciles the fresh fix`() {
        val events = mutableListOf<String>()
        // An obviously-synthetic fix — a fixture, never a real device position (SPEC Privacy).
        val fresh = NearbyStopsViewModel.State.Ready(
            eager = emptyList(),
            more = emptyList(),
            distanceMeters = emptyMap(),
            location = Coordinates(51.5, -0.12),
        )
        val action = relocateAction(
            cancelFetch = { events.add("cancel") },
            relocate = { onSameSet -> events.add("relocate"); onSameSet(fresh) },
            reconcile = { events.add("reconcile") },
        )

        action()

        // Cancel precedes the fresh fix (the cancel-on-relocate invariant), and the fix result is
        // reconciled — a departures-only refresh would do neither.
        assertEquals(listOf("cancel", "relocate", "reconcile"), events)
    }
}
