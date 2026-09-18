package app.trackmo.domain

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
 * [upcoming] holds that group's not-yet-departed departures, soonest-first, and is
 * never empty (a group with nothing upcoming produces no row).
 */
data class DepartureRow(
    val stopId: String,
    val stopName: String,
    val lineId: String,
    val lineName: String,
    val direction: String,
    val directionKey: String,
    val destination: String,
    val upcoming: List<Departure>,
)
