package app.stopdash.domain

/**
 * Whether a precise fix that arrived after the list was shown from a coarse one should move the list
 * (SPEC *Finding stops*). A coarse (network) fix can be hundreds of meters out, so the stops nearest
 * the rider may be missing; once GPS answers, the set is re-picked around the precise fix — but only
 * when it is [MOVE_THRESHOLD_METERS] or more away, so a precise fix that agrees with the coarse one
 * just confirms the list rather than rebuilding it.
 */
object FixRefinement {
    /** How far the precise fix must be from the coarse one before the list is re-picked. */
    const val MOVE_THRESHOLD_METERS = 100.0

    fun shouldMove(shownFrom: Coordinates, precise: Coordinates): Boolean =
        NearestStops.distanceMeters(
            shownFrom.latitude,
            shownFrom.longitude,
            precise.latitude,
            precise.longitude,
        ) >= MOVE_THRESHOLD_METERS
}
