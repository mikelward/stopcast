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
 * [mode] is the group's TfL mode (all its departures share the line, so the mode is
 * one value), carried so the row can be shown in its mode's identity — a tube line's
 * color, London-bus red — without the render layer re-deriving it.
 * [status] is the line's disruption, if any — set only when the line is disrupted, so a
 * non-null value marks the row (SPEC *Disruptions*: a delayed or suspended line's
 * countdowns are flagged rather than shown as if trustworthy). Null when the line has a
 * good service, or when its status was not looked up.
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
    val status: LineStatus? = null,
)
