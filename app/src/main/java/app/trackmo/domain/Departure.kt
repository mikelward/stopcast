package app.trackmo.domain

import java.time.Instant

/**
 * A single predicted departure from a watched stop. TfL's feed calls these
 * "arrivals"; trackmo says departures throughout (SPEC *Departures*).
 *
 * Holds the **absolute** [expectedArrival], not the fetch-relative countdown TfL
 * returns, so the countdown recomputes client-side from the current clock as time
 * passes — "3 min" becomes "1 min" between fetches, and a service that has gone
 * drops off — without a new request (SPEC D4).
 *
 * [lineId] is retained (not just the display [lineName]) so a surface can mark a
 * departure whose line is disrupted from the watched-stop→line mapping, even when
 * the prediction itself looks normal (SPEC D3).
 *
 * [direction] is TfL's own `inbound`/`outbound` (empty when TfL gives none). It is
 * the stable key for grouping a stop's departures into per-direction rows (SPEC D8):
 * the human-readable [destination] can't substitute — TfL leaves it blank on some
 * services, and a branching line runs several destinations in one direction.
 *
 * [mode] is TfL's `modeName` (`tube`, `bus`, `dlr`, `overground`, `elizabeth-line`,
 * `tram`, …), kept so a surface can present a service in its mode's identity — a tube
 * line in its own color, a bus in London-bus red — without re-deriving the mode from
 * the line id. Empty when TfL omits it.
 */
data class Departure(
    val lineId: String,
    val lineName: String,
    val direction: String,
    val destination: String,
    val platform: String?,
    val expectedArrival: Instant,
    val mode: String,
)
