package app.stopcast.domain

/**
 * A line a stop serves, known independently of predictions: its TfL [id], display [name],
 * and [mode]. Retained so a **disrupted line with zero predictions** — a suspended line
 * often returns none — can still surface as a status row (SPEC *Departures* / *Disruptions*),
 * which a prediction-derived list alone could never show. Until Phase 2's watched stops
 * carry this, it's a seed per stop.
 */
data class LineRef(
    val id: String,
    val name: String,
    val mode: String,
)
