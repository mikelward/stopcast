package app.trackmo.domain

import java.time.Instant

/**
 * One row of the departures list (SPEC D8): a single **(stop, service, direction)**
 * group — a *service* being a line at a stop — with that group's next departures.
 * The flat list shows one row per direction, so a two-way service at a stop is two
 * rows and nothing is hidden behind a gesture.
 *
 * [direction] is TfL's `inbound`/`outbound` for display (or empty when TfL omits it) —
 * it is *not* a unique key, since a blank direction repeats across rows.
 * [directionKey] is the resolved grouping discriminator (TfL `direction` when present,
 * else the platform, else the destination), so **([stopId], [lineId], [directionKey])
 * uniquely identifies a row** — the stable identity a persisted star keys on, even when
 * TfL omits `direction` and two rows would otherwise share a blank one.
 * [destination] is the human-readable headline — the *soonest* upcoming departure's
 * destination; a branching line can run several destinations in one direction, so
 * later entries in [upcoming] may differ, but the headline names what leaves next.
 * [upcoming] holds that group's not-yet-departed departures, soonest-first. It is empty
 * only for a **status row** — a disrupted line with no predictions (a suspended line
 * often returns none), surfaced so it isn't silently dropped for want of a departure
 * (SPEC *Departures*). A status row is direction-independent ([direction] blank,
 * [directionKey] the [STATUS_DIRECTION_KEY] sentinel) and always carries a non-null
 * [status]; a prediction group with nothing upcoming still produces no row at all.
 * [mode] is the group's TfL mode (all its departures share the line, so the mode is
 * one value), carried so the row can be shown in its mode's identity — a tube line's
 * color, London-bus red — without the render layer re-deriving it.
 * [status] is the line's disruption, if any — set only when the line is disrupted, so a
 * non-null value marks the row (SPEC *Disruptions*: a delayed or suspended line's
 * countdowns are flagged rather than shown as if trustworthy). Null when the line has a
 * good service, or when its status was not looked up.
 * [stopDisruption] marks a **stop-level status row** — a whole-stop disruption (a closure)
 * rather than a line one (SPEC *Disruptions*). When set, the row is *about the stop*: it
 * carries no line (blank [lineId]/[lineName]/[mode]), no [upcoming] and no [status], and
 * the text is TfL's stop-disruption description. Null on every line and timed row.
 * [fetchedAt] is the age of the stop this row came from (the snapshot stamps each stop
 * independently), so the screen withholds *this row's* countdowns when *its* stop is stale
 * — a stop that failed to refresh goes to "—" while a fresh stop beside it still shows live
 * numbers (SPEC D4). Rows from the same stop share one value; it is not part of a row's
 * identity (that is `(stopId, lineId, directionKey)`).
 */
data class DepartureRow(
    val stopId: String,
    val stopName: String,
    val lineId: String,
    val lineName: String,
    val direction: String,
    val directionKey: String,
    val destination: String,
    val mode: String,
    val upcoming: List<Departure>,
    val fetchedAt: Instant,
    val status: LineStatus? = null,
    val stopDisruption: String? = null,
)

/**
 * The [DepartureRow.directionKey] a status row carries — a fixed sentinel, since a status
 * row has no direction. It keeps `(stopId, lineId, directionKey)` unique for a status row
 * (there is one per stop+line, and the line has no prediction rows to collide with).
 */
const val STATUS_DIRECTION_KEY: String = "status"

/**
 * The [DepartureRow.directionKey] a stop-level status row carries — a fixed sentinel, so
 * `(stopId, lineId, directionKey)` is unique for the one stop-status row per stop (its
 * [DepartureRow.lineId] is blank, so it can't collide with a line's rows).
 */
const val STOP_STATUS_DIRECTION_KEY: String = "stop-status"
