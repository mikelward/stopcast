package app.trackmo.domain

/**
 * A stop the user has chosen to watch — the persisted membership that is the **source of
 * truth** for every surface (SPEC *Watched stops* / D1). Identity is the TfL stop [id]:
 * a stop is watched once, and removing it drops all of its rows. [name] and [lines] are
 * public facts about the stop carried alongside so a surface can label the stop and
 * surface a disrupted served line with no predictions as a status row (SPEC *Departures*),
 * without a second lookup — the same fields [app.trackmo.domain.StopLocation] resolves when
 * the stop is found.
 *
 * Coordinates are deliberately **not** part of a watched stop: the watched list is ordered
 * location-free so it works with location denied (D1), and a coordinate is the user's
 * business (SPEC *Privacy*). Per-stop line/direction filters (D2) are a later, separate
 * layer over this membership, not part of it yet.
 */
data class WatchedStop(
    val id: String,
    val name: String,
    val lines: List<LineRef> = emptyList(),
)
