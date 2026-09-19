package app.trackmo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure nearby-gate decision (the `LaunchedEffect` in `MainActivity` only supplies the three
 * booleans and carries out the result). The regression case is the coarse-only upgrade — an
 * install predating the FINE declaration keeps a live coarse grant that the manifest change does
 * not upgrade, so it must be offered precise exactly once rather than silently keeping the
 * inaccurate coarse fix.
 */
class NearbyPermissionActionTest {
    @Test
    fun `precise held locates`() {
        assertEquals(
            NearbyPermissionAction.LOCATE,
            nearbyPermissionAction(hasFine = true, hasAnyLocation = true, precisePrompted = false),
        )
    }

    @Test
    fun `coarse-only upgrade with precise never prompted requests precise once`() {
        // The reported bug: without this the coarse-only install locates immediately and never
        // sees the precise dialog, permanently keeping the ~1 km-off fix this change fixes.
        assertEquals(
            NearbyPermissionAction.REQUEST_PRECISE,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = true, precisePrompted = false),
        )
    }

    @Test
    fun `coarse held after the precise prompt was shown locates without re-prompting`() {
        // The user's completed approximate choice: locate rather than nag on every open.
        assertEquals(
            NearbyPermissionAction.LOCATE,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = true, precisePrompted = true),
        )
    }

    @Test
    fun `no location permission waits for the gate's Allow button`() {
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = false, precisePrompted = false),
        )
        // precisePrompted can't be true without a grant having been offered, but the decision is
        // still WAIT with no permission held — nothing auto-fires.
        assertEquals(
            NearbyPermissionAction.WAIT,
            nearbyPermissionAction(hasFine = false, hasAnyLocation = false, precisePrompted = true),
        )
    }
}
