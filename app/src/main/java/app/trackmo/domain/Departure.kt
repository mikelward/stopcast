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
 */
data class Departure(
    val lineId: String,
    val lineName: String,
    val destination: String,
    val platform: String?,
    val expectedArrival: Instant,
)
